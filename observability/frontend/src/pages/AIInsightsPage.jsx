import React, { useState, useEffect, Suspense } from 'react';
import { Card, Tabs, Spin } from 'antd';
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
      children: isLoaded ? (
        <Suspense fallback={<TabLoading />}>
          <TabComponent />
        </Suspense>
      ) : (
        <TabLoading />
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

export default AIInsightsPage;
