import React from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import DiagnosisTab from '../DiagnosisTab';
import * as api from '../../api/client';

// 统一视图含散点图（echarts-for-react），jsdom 下无 canvas，直接 mock 为空
vi.mock('echarts-for-react', () => ({ default: () => null }));
vi.mock('../../api/client', () => ({
  fetchInsightsReport: vi.fn(),
  fetchBottlenecks: vi.fn(),
  fetchRootCause: vi.fn(),
  fetchUnsatisfied: vi.fn(),
  fetchInsightsConversion: vi.fn(),
  fetchInsightsActions: vi.fn(),
  fetchAgentPerformance: vi.fn(),
  refreshInsights: vi.fn(),
}));
vi.mock('../../components/ApiErrorAlert', () => ({ default: () => null }));
vi.mock('../../components/Top5Actions', () => ({ default: () => null }));

// 诊断快照 / 瓶颈 / 慢会话 / 不满意 / 转化 / 优先行动 的数据契约
const report = {
  generatedAt: '2026-07-23T10:00:00Z',
  boundary: 'L1',
  summary: '洞察摘要：3 类瓶颈、5 条待办建议、1 类不满意聚类。',
  slowSessions: {
    triggered: true,
    p90Seconds: 10.0,
    thresholdSeconds: 3.0,
    rows: [
      { sessionId: 'S-001', durationSeconds: 10, turnCount: 5, intentFlow: '转账 → 失败' },
      { sessionId: 'S-002', durationSeconds: 8, turnCount: 4, intentFlow: '查询 → 成功' },
    ],
  },
  unsatisfied: {
    triggered: true,
    rate: 12.0,
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
  api.fetchAgentPerformance.mockResolvedValue({ tables: { agent: [] } });
  api.refreshInsights.mockResolvedValue({});
  api.fetchRootCause.mockResolvedValue({ rootCauseCandidates: [] });
});

/**
 * 验收点（整合后 DiagnosisUnified，对齐 DEMO V23）：慢会话明细、不满意聚类、摘要条、
 * 三列彩色瓶颈卡（性能/准确率/转化）均能正确渲染（不报错）。
 * 注：转化漏斗卡与独立「智能诊断快照」卡已按 DEMO V23 有意移除，故不再断言 92% / 快照。
 */
test('DiagnosisTab（统一视图）渲染慢会话/不满意/三列瓶颈卡不报错', async () => {
  const { container } = render(
    <MemoryRouter>
      <DiagnosisTab />
    </MemoryRouter>,
  );

  // 慢会话根因表行明细（S-001 同时出现在慢会话表与不满意聚类样本中，用 findAllByText）
  expect((await screen.findAllByText('S-001')).length).toBeGreaterThan(0);
  expect(screen.getByText('S-002')).toBeInTheDocument();

  // 不满意聚类维度 + 摘要条标题
  expect(container.textContent).toContain('转账');
  expect(container.textContent).toContain('智能诊断摘要');

  // 三列彩色瓶颈卡标题（性能/准确率/转化）
  expect(container.textContent).toContain('性能瓶颈');
  expect(container.textContent).toContain('准确率问题');
  expect(container.textContent).toContain('转化瓶颈');
});

test('DiagnosisTab 各端点均 null 时优雅降级（mock 兜底）不报错', async () => {
  api.fetchInsightsReport.mockResolvedValue(null);
  api.fetchBottlenecks.mockResolvedValue([]);
  api.fetchUnsatisfied.mockResolvedValue(null);
  api.fetchInsightsConversion.mockResolvedValue(null);
  api.fetchInsightsActions.mockResolvedValue([]);
  api.fetchAgentPerformance.mockResolvedValue(null);
  render(
    <MemoryRouter>
      <DiagnosisTab />
    </MemoryRouter>,
  );
  // 不抛错即视为通过；降级态下「暂无…」兜底文案与「智能诊断摘要」摘要条并存
  const degraded = await screen.findAllByText(/暂无|智能诊断摘要/);
  expect(degraded.length).toBeGreaterThan(0);
});
