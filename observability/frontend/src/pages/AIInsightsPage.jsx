import React, { useState, Suspense, lazy } from 'react';
import { Card, Tabs, Spin } from 'antd';
import {
  ApartmentOutlined,
  CheckCircleOutlined,
  ThunderboltOutlined,
  DollarOutlined,
  ToolOutlined,
  FunnelPlotOutlined,
  SmileOutlined,
} from '@ant-design/icons';
import DiagnosisCockpit from '../components/DiagnosisCockpit';

/**
 * AI 洞察页 — 6 TAB 框架
 *
 * 准确率分析 → Agent 性能 → Token 成本 → 工具调用 → 业务转化漏斗 → 用户满意度
 *   算法看        开发看        成本看      调用链看      产品看          体验看
 *
 * 每个 TAB 使用懒加载独立组件，TAB 切换不重渲染已加载 TAB。
 */
const AccuracyTab = lazy(() => import('./AccuracyTab'));
const AgentPerfTab = lazy(() => import('./AgentPerfTab'));
const TokenCostTab = lazy(() => import('./TokenCostTab'));
const ToolCallTab = lazy(() => import('./ToolCallTab'));
const FunnelTab = lazy(() => import('./FunnelTab'));
const SatisfactionTab = lazy(() => import('./SatisfactionTab'));

const TAB_ITEMS = [
  {
    key: 'cockpit',
    label: '智能洞察诊断驾驶舱',
    icon: <ApartmentOutlined />,
    subtitle: '诊断看',
    component: CockpitTab,
  },
  {
    key: 'accuracy',
    label: '准确率分析',
    icon: <CheckCircleOutlined />,
    subtitle: '算法看',
    component: AccuracyTab,
  },
  {
    key: 'perf',
    label: 'Agent 性能',
    icon: <ThunderboltOutlined />,
    subtitle: '开发看',
    component: AgentPerfTab,
  },
  {
    key: 'token',
    label: 'Token 成本',
    icon: <DollarOutlined />,
    subtitle: '成本看',
    component: TokenCostTab,
  },
  {
    key: 'tool',
    label: '工具调用',
    icon: <ToolOutlined />,
    subtitle: '调用链看',
    component: ToolCallTab,
  },
  {
    key: 'funnel',
    label: '业务转化漏斗',
    icon: <FunnelPlotOutlined />,
    subtitle: '产品看',
    component: FunnelTab,
  },
  {
    key: 'satisfaction',
    label: '用户满意度',
    icon: <SmileOutlined />,
    subtitle: '体验看',
    component: SatisfactionTab,
  },
];

/** 已加载过的 TAB keys 缓存 */
const loadedTabs = new Set();

function AIInsightsPage() {
  const [activeKey, setActiveKey] = useState('cockpit');

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
}

/**
 * 诊断驾驶舱 TAB 内容：仅封装 DiagnosisCockpit 组件，使驾驶舱成为独立可切换视图。
 */
function CockpitTab() {
  return <DiagnosisCockpit />;
}

function TabLoading() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 300 }}>
      <Spin size="default" />
    </div>
  );
}

export default AIInsightsPage;
