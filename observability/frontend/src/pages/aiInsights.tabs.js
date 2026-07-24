import React, { lazy } from 'react';
import {
  SafetyCertificateOutlined,
  CheckCircleOutlined,
  ThunderboltOutlined,
  DollarOutlined,
  ApiOutlined,
  FunnelPlotOutlined,
  SmileOutlined,
} from '@ant-design/icons';

const h = React.createElement;

/**
 * aiInsights.tabs — AI 洞察页 TAB 清单（集中管理键名与顺序）。
 *
 * 整合后（docs/system_design.md §1.4 / Q3）：移除独立 `cockpit` 键，
 * `diagnosis` 键指向统一视图 DiagnosisUnified，显示名「智能诊断」。
 *
 * 各 TAB 仍采用 React.lazy 懒加载（保持原 AIInsightsPage 的懒加载语义，
 * TAB 切换不重渲染已加载 TAB，未访问 TAB 不提前加载 chunk）。
 *
 * 注：本文件为 .js（非 .jsx），图标节点用 React.createElement 构建，
 * 避免在 .js 文件中书写 JSX 导致构建期解析失败。
 */
export const TAB_ITEMS = [
  {
    key: 'diagnosis',
    label: '智能诊断',
    icon: h(SafetyCertificateOutlined),
    subtitle: '诊断看',
    component: lazy(() => import('../components/DiagnosisUnified')),
  },
  {
    key: 'accuracy',
    label: '准确率分析',
    icon: h(CheckCircleOutlined),
    subtitle: '算法看',
    component: lazy(() => import('./AccuracyTab')),
  },
  {
    key: 'perf',
    label: 'Agent 性能',
    icon: h(ThunderboltOutlined),
    subtitle: '开发看',
    component: lazy(() => import('./AgentPerfTab')),
  },
  {
    key: 'token',
    label: 'Token 成本',
    icon: h(DollarOutlined),
    subtitle: '成本看',
    component: lazy(() => import('./TokenCostTab')),
  },
  {
    key: 'external',
    label: '外部调用',
    icon: h(ApiOutlined),
    subtitle: '调用链看',
    component: lazy(() => import('./ExternalCallTab')),
  },
  {
    key: 'funnel',
    label: '业务转化漏斗',
    icon: h(FunnelPlotOutlined),
    subtitle: '产品看',
    component: lazy(() => import('./FunnelTab')),
  },
  {
    key: 'satisfaction',
    label: '用户满意度',
    icon: h(SmileOutlined),
    subtitle: '体验看',
    component: lazy(() => import('./SatisfactionTab')),
  },
];

export default TAB_ITEMS;
