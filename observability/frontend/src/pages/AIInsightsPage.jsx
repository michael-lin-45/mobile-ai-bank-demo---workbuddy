import React, { useState, useEffect, Suspense } from 'react';
import { Card, Tabs, Spin, Alert, Button } from 'antd';
import { on } from '../utils/nav';
import { TAB_ITEMS } from './aiInsights.tabs';

/**
 * AI 洞察页 — TAB 框架（整合改造版）。
 *
 * 智能诊断（统一视图）→ 准确率分析 → Agent 性能 → Token 成本 → 外部调用 → 业务转化漏斗 → 用户满意度
 *
 * 整合后移除独立「智能洞察诊断驾驶舱」TAB（docs/system_design.md §1.4 / Q3）：
 *   - `diagnosis` 键指向统一组件 DiagnosisUnified（含原驾驶舱 5 区块 + 诊断快照 + 转化漏斗）
 *   - 不再引用 DiagnosisCockpit / cockpit 键
 *
 * 每个 TAB 使用懒加载独立组件，TAB 切换不重渲染已加载 TAB。
 * 订阅总览大屏下钻（itab('diagnosis') 等）→ 切换对应 TAB。
 */
const AIInsightsPage = () => {
  const [activeKey, setActiveKey] = useState('diagnosis');
  // 重试计数器：点击错误边界「重试」时自增，使对应 TAB 错误边界以新 key 重新挂载并重新加载 lazy chunk
  const [retryTick, setRetryTick] = useState(0);
  const handleTabRetry = () => setRetryTick((t) => t + 1);

  // 订阅总览大屏下钻（itab('diagnosis') 等）→ 切换对应 TAB
  useEffect(() => {
    const off = on('ai-insights:tab', (e) => {
      const tabKey = e.detail && e.detail.tabKey;
      if (tabKey) {
        setActiveKey(tabKey);
        loadedTabs.add(tabKey);
      }
    });
    return off;
  }, []);

  // 记录已加载的 TAB
  if (!loadedTabs.has(activeKey)) {
    loadedTabs.add(activeKey);
  }

  /** 构建 Tab items，未加载的渲染空占位 */
  const tabItems = TAB_ITEMS.map((tab) => {
    const TabComponent = tab.component;
    const isLoaded = loadedTabs.has(tab.key);
    return {
      key: tab.key,
      label: (
        <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {tab.icon}
          <span>{tab.label}</span>
          <span style={{ fontSize: 10, color: 'rgba(0,0,0,.35)', fontWeight: 400, marginLeft: 2 }}>
            {tab.subtitle}
          </span>
        </span>
      ),
      children: (
        <TabErrorBoundary key={`${tab.key}-${retryTick}`} onReset={handleTabRetry}>
          {isLoaded ? (
            <Suspense fallback={<TabLoading />}>
              <TabComponent />
            </Suspense>
          ) : (
            <TabLoading />
          )}
        </TabErrorBoundary>
      ),
    };
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Topbar */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: '0 0 16px',
        borderBottom: '1px solid #f0f0f0',
      }}>
        <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', fontFamily: '"JetBrains Mono", monospace' }}>
          home / <span style={{ color: 'rgba(0,0,0,.88)' }}>AI 洞察</span>
        </span>
        <span style={{
          marginLeft: 'auto',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          fontSize: 11,
          color: '#52c41a',
          fontFamily: '"JetBrains Mono", monospace',
          fontWeight: 500,
        }}>
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#52c41a', display: 'inline-block' }} />
          LIVE
        </span>
      </div>

      {/* TAB 导航 + 内容 */}
      <Card bodyStyle={{ padding: '20px 20px 16px' }}>
        <Tabs
          activeKey={activeKey}
          onChange={(key) => {
            setActiveKey(key);
            if (!loadedTabs.has(key)) {
              loadedTabs.add(key);
            }
          }}
          items={tabItems}
          size="large"
          tabBarStyle={{ marginBottom: 16, borderBottom: '1px solid #f0f0f0' }}
        />
      </Card>
    </div>
  );
};

/** 已加载过的 TAB keys 缓存 */
const loadedTabs = new Set();

function TabLoading() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 300 }}>
      <Spin size="default" />
    </div>
  );
}

/**
 * TabErrorBoundary — TAB 级错误边界（任务3：止血「整页白屏」）。
 *
 * 背景：AIInsightsPage 各 TAB 用 React.lazy + <Suspense> 加载，但此前
 * 没有 ErrorBoundary。任一 TAB 在渲染期抛错、或其 lazy 动态 import 失败，
 * React18 会向上找最近边界；找不到则卸载整棵组件树 → 整页白屏（"系统空白"）。
 *
 * 该边界包住每个 TAB 的 Suspense，使单 TAB 崩溃被隔离：
 *   - 崩溃时仅该 TAB 内显示 antd <Alert type="error"> 错误卡 + 重试按钮
 *   - 不再拖垮整页的其他 TAB
 *   - componentDidCatch 记录错误（含组件栈），便于浏览器 console 排错
 *
 * key 用 `${tab.key}-${retryTick}` 隔离各 TAB 实例；点击重试通过 onReset 清
 * 错误态并自增 retryTick → 边界以新 key 重挂载，lazy chunk 重新 import。
 */
class TabErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    // 记录错误，便于排错（含组件栈信息）
    console.error('[TabErrorBoundary] TAB 渲染异常：', error, errorInfo);
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null });
    if (typeof this.props.onReset === 'function') {
      this.props.onReset();
    }
  };

  render() {
    if (this.state.hasError) {
      return (
        <Alert
          type="error"
          showIcon
          style={{ borderRadius: 8, marginTop: 8 }}
          message="该 TAB 加载失败"
          description={
            <span>
              {this.state.error?.message || '未知错误'}
              <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                错误已记录到浏览器控制台（console），可贴出具体报错以便最终定位根因。
              </div>
            </span>
          }
          action={
            <Button size="small" danger onClick={this.handleRetry}>
              重试
            </Button>
          }
        />
      );
    }
    return this.props.children;
  }
}

export default AIInsightsPage;
