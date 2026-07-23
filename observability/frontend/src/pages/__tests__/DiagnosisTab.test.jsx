import React from 'react';
import { render, screen } from '@testing-library/react';
import DiagnosisTab from '../DiagnosisTab';
import * as api from '../../api/client';

// 业务源 fetchInsightsReport 等统一在此 mock（相对路径与组件内 import 解析到同一模块）
vi.mock('../../api/client', () => ({
  fetchInsightsReport: vi.fn(),
  fetchBottlenecks: vi.fn(),
  fetchRootCause: vi.fn(),
  fetchUnsatisfied: vi.fn(),
  fetchInsightsConversion: vi.fn(),
  fetchInsightsActions: vi.fn(),
}));
vi.mock('../../components/ApiErrorAlert', () => ({ default: () => null }));
vi.mock('../../components/Top5Actions', () => ({ default: () => null }));

// 按设计/PRD 契约构造「智能诊断」报告块结构
const report = {
  generatedAt: '2026-07-23T10:00:00Z',
  boundary: 'L1',
  summary: '洞察摘要：3 类瓶颈、5 条待办建议、1 类不满意聚类。',
  slowSessions: {
    triggered: true,
    p90Seconds: 10.0,
    // 注：设计/PRD 要求阈值 3.0s；当前源码写死 60，前端读 thresholdSeconds 动态显示
    thresholdSeconds: 3.0,
    rows: [
      { sessionId: 'S-001', durationSeconds: 10, turnCount: 5, intentFlow: '转账 → 失败' },
      { sessionId: 'S-002', durationSeconds: 8, turnCount: 4, intentFlow: '查询 → 成功' },
    ],
  },
  unsatisfied: {
    triggered: true,
    rate: 12.0, // 百分比口径（与设计 rate>10% 一致）
    total: 1000,
    unsatisfied: 120,
    clusters: [
      { dimension: '转账', count: 80, examples: ['S-001'], commonPattern: '不满意会话多集中于「转账」意图' },
    ],
  },
};

beforeEach(() => {
  api.fetchInsightsReport.mockResolvedValue(report);
  api.fetchBottlenecks.mockResolvedValue([
    { category: 'PERFORMANCE', title: 'P95 端到端时延', value: 800, unit: 'ms', detail: '近 6h P95 时延 800ms' },
  ]);
  api.fetchUnsatisfied.mockResolvedValue(report.unsatisfied);
  api.fetchInsightsConversion.mockResolvedValue({
    totalSessions: 1000, completedSessions: 920, conversionRate: 92.0,
    stages: [
      { name: '访问会话', count: 1000 },
      { name: '意图识别成功', count: 920 },
      { name: '进入业务办理', count: 920 },
      { name: '业务成功完成', count: 920 },
    ],
  });
  api.fetchInsightsActions.mockResolvedValue([
    { id: 'act-general-health', title: '保持观测', description: 'd', category: 'GENERAL', priority: 10, severity: 'LOW' },
  ]);
  api.fetchRootCause.mockResolvedValue({ rootCauseCandidates: [] });
});

/**
 * 验收点：DiagnosisTab 按「块结构」读取
 *   slowSessions.p90Seconds / slowSessions.rows
 *   unsatisfied.rate / unsatisfied.total / unsatisfied.clusters
 * 不报错，且正确渲染。
 */
test('DiagnosisTab 按块结构读取 slowSessions / unsatisfied 不报错并正确渲染', async () => {
  const { container } = render(<DiagnosisTab />);

  // 慢会话块：P90 告警条 + 行明细
  expect(await screen.findByText(/慢会话 P90 时延/)).toBeInTheDocument();
  expect(screen.getByText('S-001')).toBeInTheDocument();
  expect(screen.getByText('S-002')).toBeInTheDocument();

  // 不满意块 / 转化块：rate 嵌进告警句「不满意率 12% 超过阈值 10%（120/1000）」，
  // 转化率经 antd Statistic 拆成 92 + % 两个节点；改用 container.textContent 宽松断言关键串
  expect(container.textContent).toContain('不满意率 12%');
  expect(container.textContent).toContain('转账');
  expect(container.textContent).toContain('120/1000');
  expect(container.textContent).toContain('92%');
});

test('DiagnosisTab 数据为 null 时优雅降级不报错', async () => {
  api.fetchInsightsReport.mockResolvedValue(null);
  api.fetchBottlenecks.mockResolvedValue([]);
  api.fetchUnsatisfied.mockResolvedValue(null);
  api.fetchInsightsConversion.mockResolvedValue(null);
  api.fetchInsightsActions.mockResolvedValue([]);
  render(<DiagnosisTab />);
  // 不抛错即视为通过；null 态下「智能诊断快照」标题与多个「暂无…」文案并存，
  // findByText 会命中多个而抛错，改用 findAllByText 等待并断言至少命中一处
  const degraded = await screen.findAllByText(/智能诊断快照|暂无/);
  expect(degraded.length).toBeGreaterThan(0);
});
