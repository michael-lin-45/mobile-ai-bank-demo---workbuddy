import React from 'react';
import { render, screen } from '@testing-library/react';
import TraceDetailModal from '../TraceDetailModal';

// V16 恢复：输入/输出双栏 + L0→L1→L2 改写轨迹 + Span 树 + 瀑布图
// 子组件在单测中 mock，聚焦「改写轨迹渲染」与「空态不渲染」
vi.mock('../IOCards', () => ({
  default: ({ input, output }) => (
    <div data-testid="iocards">
      {input && input.content}
      {output && output.content}
    </div>
  ),
}));
vi.mock('../SpanTree', () => ({ default: () => <div data-testid="spantree">SPAN</div> }));
vi.mock('../charts/WaterfallChart', () => ({ default: () => null }));

const trace = {
  traceId: 'trace-xyz',
  status: 'OK',
  agentChain: 'L0 → L1 → L2',
  ioInput: { label: '用户原始请求', content: '帮我转 100 元' },
  ioOutput: { label: '最终输出', content: '已为您转账' },
  // 改写轨迹：L0 原始请求 → L1 改写 → L2 落地（V16 恢复要点）
  rewriteChain: [
    { layer: 'L0 原始请求', text: '原始：转账' },
    { layer: 'L1 改写', text: '改写：调用 transfer' },
    { layer: 'L2 改写', text: '落地：MCP transfer' },
  ],
  spanTree: [{ id: '1', name: 'L0-LLM' }],
  waterfallSpans: [{ name: 'x', start: 0, end: 10 }],
  ttft: 120,
  durationMs: 500,
};

/**
 * 验收点（PRD M8 / V16 恢复）：TraceDetailModal 正确渲染
 *  - 输入/输出双栏；
 *  - 改写轨迹 L0 → L1 → L2 三段；
 *  - visible=false 或 无 trace 时不渲染（空态降级）。
 */
test('visible=false / 无 trace 时不渲染', () => {
  const { container } = render(
    <TraceDetailModal visible={false} trace={trace} onClose={() => {}} />,
  );
  expect(container.firstChild).toBeNull();

  const { container: c2 } = render(
    <TraceDetailModal visible trace={null} onClose={() => {}} />,
  );
  expect(c2.firstChild).toBeNull();
});

test('V16 改写轨迹（L0→L1→L2）与双栏正确渲染', () => {
  render(<TraceDetailModal visible trace={trace} onClose={() => {}} />);

  // 标题与改写轨迹区块
  expect(screen.getByText('改写轨迹 (L0 → L1 → L2)')).toBeInTheDocument();
  expect(screen.getByText('L0 原始请求')).toBeInTheDocument();
  expect(screen.getByText('L1 改写')).toBeInTheDocument();
  expect(screen.getByText('L2 改写')).toBeInTheDocument();
  expect(screen.getByText('原始：转账')).toBeInTheDocument();
  expect(screen.getByText('落地：MCP transfer')).toBeInTheDocument();

  // 输入/输出双栏（IOCards mock 把 input.content + output.content 拼进同一 div，
  // 精确 getByText 会命中拼接串而失败，改用正则子串匹配）
  expect(screen.getByText(/帮我转 100 元/)).toBeInTheDocument();
  expect(screen.getByText(/已为您转账/)).toBeInTheDocument();

  // Agent 链路详情树子组件已挂载
  expect(screen.getByTestId('spantree')).toBeInTheDocument();
});
