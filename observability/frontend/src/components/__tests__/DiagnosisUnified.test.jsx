import { render, screen, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// ── 桩掉后端 API（mock 路径相对本测试文件 src/components/__tests__/，需回退两级）──
// 全部 reject → loadAll 走 mock 兜底分支（与真实「洞察引擎不可达」场景一致）。
vi.mock('../../api/client', () => ({
  fetchInsightsReport: vi.fn(() => Promise.reject(new Error('stub'))),
  fetchBottlenecks: vi.fn(() => Promise.reject(new Error('stub'))),
  fetchRootCause: vi.fn(() => Promise.reject(new Error('stub'))),
  fetchUnsatisfied: vi.fn(() => Promise.reject(new Error('stub'))),
  fetchInsightsConversion: vi.fn(() => Promise.reject(new Error('stub'))),
  fetchInsightsActions: vi.fn(() => Promise.reject(new Error('stub'))),
  fetchAgentPerformance: vi.fn(() => Promise.reject(new Error('stub'))),
  refreshInsights: vi.fn(() => Promise.reject(new Error('stub'))),
}));

vi.mock('../../services/insightAdapters', () => ({
  isEmpty: () => false,
}));

// 控制 mock 兜底数据形状，确保 5 个复用区块可正常渲染；
// agentScatter 置空 → PerfScatter 走 <Empty> 分支，避免 jsdom 下 echarts 渲染崩溃。
vi.mock('../../services/mockInsights', () => ({
  getDiagnosisMocks: () => ({
    snapshot: { boundary: 'L1', generatedAt: '2024-01-01T00:00:00', summary: 'mock snapshot' },
    topActions: [], // 空 → 触发「severity 角标占位 3/2/1」与 ActionList Empty 分支
    bottlenecks: [{ type: 'perf', title: 't', value: 1, unit: 'u', desc: 'd' }],
    unsatisfied: [{ theme: 'x', count: 1, rate: 1, sample: 's' }],
    conversionProgress: [{ stage: 's', count: 0 }],
    agentScatter: [],
    slowSessions: [{ sessionId: 's1', durationSec: 10, bottleneck: 'b' }],
  }),
}));

import DiagnosisUnified from '../DiagnosisUnified';

const renderInRouter = () =>
  render(
    <MemoryRouter>
      <DiagnosisUnified />
    </MemoryRouter>,
  );

describe('DiagnosisUnified (V23 DEMO 重绘)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('① 摘要条含「智能诊断摘要」与「自动扫描 6 个 TAB 指标」', async () => {
    renderInRouter();
    expect(await screen.findByText('智能诊断摘要')).toBeInTheDocument();
    expect(await screen.findByText(/自动扫描 6 个 TAB 指标/)).toBeInTheDocument();
  });

  it('② 存在 HIGH / MEDIUM / LOW 严重度角标文本（actions 空 → 占位 3/2/1）', async () => {
    renderInRouter();
    expect(await screen.findByText(/HIGH/)).toBeInTheDocument();
    expect(await screen.findByText(/MEDIUM/)).toBeInTheDocument();
    expect(await screen.findByText(/LOW/)).toBeInTheDocument();
  });

  it('③ TOP5 卡标题含「优先行动建议（TOP 5）」', async () => {
    renderInRouter();
    expect(await screen.findByText(/优先行动建议（TOP 5）/)).toBeInTheDocument();
  });
});
