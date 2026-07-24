import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import ExternalCallTab from '../ExternalCallTab';
import * as api from '../../api/client';

vi.mock('../../api/client', () => ({
  fetchInvocations: vi.fn(),
}));
vi.mock('../../components/ApiErrorAlert', () => ({ default: () => null }));
vi.mock('../../components/RagPanel', () => ({ default: () => <div>RAG_STUB</div> }));
// 注意：ExternalCallTab 通过 './ToolCallTab'（即 src/pages/ToolCallTab）引入，
// 因此 mock 路径须为 '../ToolCallTab' 才能命中，否则会渲染真实组件。
vi.mock('../ToolCallTab', () => ({ default: () => <div>TOOLCALL_STUB</div> }));

// 覆盖四类（all）与 MCP 分段（mcp）的记录，字段对齐 InvocationRecord DTO
const allRecords = [
  { category: 'tool', name: 'crm.getCustomer', calls: 1820, success: 1790, failed: 30, avgLatencyMs: 120, p95LatencyMs: 320, errorRate: 1.6, lastError: '下游超时' },
  { category: 'tool', name: 'risk.evaluate', calls: 940, success: 935, failed: 5, avgLatencyMs: 240, p95LatencyMs: 610, errorRate: 0.5, lastError: null },
  { category: 'mcp', name: 'MCP:queryBalance', calls: 640, success: 638, failed: 2, avgLatencyMs: 210, p95LatencyMs: 480, errorRate: 0.3, lastError: null },
  { category: 'mcp', name: 'MCP:transfer', calls: 220, success: 218, failed: 2, avgLatencyMs: 340, p95LatencyMs: 700, errorRate: 0.9, lastError: '授权失败' },
  { category: 'mcp', name: 'MCP:fundDetail', calls: 150, success: 150, failed: 0, avgLatencyMs: 260, p95LatencyMs: 540, errorRate: 0, lastError: null },
  { category: 'rag', name: 'rag.retrieve', calls: 3120, success: 3100, failed: 20, avgLatencyMs: 85, p95LatencyMs: 260, errorRate: 0.6, lastError: null },
  { category: 'skill', name: 'skill.transfer', calls: 220, success: 218, failed: 2, avgLatencyMs: 340, p95LatencyMs: 700, errorRate: 0.9, lastError: null },
  { category: 'skill', name: 'skill.recommend', calls: 180, success: 180, failed: 0, avgLatencyMs: 200, p95LatencyMs: 460, errorRate: 0, lastError: null },
];
const mcpRecords = allRecords.filter((r) => r.category === 'mcp');

beforeEach(() => {
  api.fetchInvocations.mockImplementation((cat) =>
    Promise.resolve(cat === 'mcp' ? mcpRecords : allRecords),
  );
});

/**
 * 验收点（PRD M5 / M10）：外部调用 TAB 以 Segmented 在
 * 「全部 / RAG / 工具函数 / SKILL / MCP」间切换；
 * 切到 MCP 分段时正确渲染 MCP seed 分支（queryBalance / transfer / fundDetail）。
 */
test('「全部」聚合 4 类 + 切到「MCP」渲染 3 条 MCP 记录', async () => {
  render(<ExternalCallTab />);

  // 全部视图：总调用 = 1820+940+640+220+150+3120+220+180 = 7290
  // （antd Statistic 默认千分位 → 渲染为「7,290」）
  expect(await screen.findByText('7,290')).toBeInTheDocument();

  // 切到 MCP 分段
  fireEvent.click(screen.getByText('MCP'));

  // MCP 子表渲染 queryBalance / transfer / fundDetail
  expect(await screen.findByText('MCP:queryBalance')).toBeInTheDocument();
  expect(screen.getByText('MCP:transfer')).toBeInTheDocument();
  expect(screen.getByText('MCP:fundDetail')).toBeInTheDocument();
});

test('切到 RAG / 工具函数 / SKILL 分段不报错', async () => {
  render(<ExternalCallTab />);
  await screen.findByText('7,290'); // 等全部视图就绪

  fireEvent.click(screen.getByText('RAG'));
  expect(await screen.findByText('RAG_STUB')).toBeInTheDocument();

  fireEvent.click(screen.getByText('工具函数'));
  expect(await screen.findByText('TOOLCALL_STUB')).toBeInTheDocument();

  // SKILL 分段改为渲染 mock 表格（不再「暂未开放」空态），不报错
  fireEvent.click(screen.getByText('SKILL'));
  expect(await screen.findByText('skill.transfer')).toBeInTheDocument();
});
