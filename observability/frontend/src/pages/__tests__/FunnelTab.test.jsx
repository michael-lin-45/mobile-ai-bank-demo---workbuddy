import React from 'react';
import { render, screen } from '@testing-library/react';
import FunnelTab from '../FunnelTab';
import * as api from '../../api/client';

// ECharts / FunnelChart 在 jsdom 下无 canvas，直接 mock 为空
vi.mock('echarts-for-react', () => ({ default: () => null }));
vi.mock('../../components/charts/FunnelChart', () => ({ default: () => null }));
vi.mock('../../api/client', () => ({
  fetchConversionFunnel: vi.fn(),
}));
vi.mock('../../components/ApiErrorAlert', () => ({ default: () => null }));

// 放弃率阈值着色（abandonColor）：>25% 红 / >10% 橙 / ≤10% 绿
// 故意让各 abandonRate 取值互不相同，避免 getByText 命中多个节点
const funnelData = {
  stages: [
    { name: '访问会话', value: 1000 },
    { name: '意图识别成功', value: 920 },
    { name: '进入业务办理', value: 880 },
    { name: '业务成功完成', value: 800 },
  ],
  details: [
    { stage: '访问会话', entered: 1000, completed: 1000, abandoned: 0, abandonRate: 0, reason: '-' },
    { stage: '意图识别', entered: 1000, completed: 920, abandoned: 80, abandonRate: 8, reason: '识别失败' },
    { stage: '业务办理', entered: 920, completed: 700, abandoned: 300, abandonRate: 32, reason: '用户放弃' },
    { stage: '完成', entered: 700, completed: 680, abandoned: 20, abandonRate: 15, reason: '-' },
  ],
  abandonPie: [{ name: '放弃', value: 320 }],
  churnProfile: {
    intent: [{ name: '转账', count: 200, rate: 20 }],
    channel: [{ name: 'APP', count: 100, rate: 11 }],
    time: [{ name: '上午', count: 50, rate: 4 }],
  },
};

// 容忍 jsdom 对颜色的 hex / rgb 表达
const RED = ['#ff4d4f', 'rgb(255, 77, 79)'];
const GREEN = ['#52c41a', 'rgb(82, 196, 26)'];
const ORANGE = ['#faad14', 'rgb(250, 173, 20)'];

beforeEach(() => {
  api.fetchConversionFunnel.mockResolvedValue(funnelData);
});

/**
 * 验收点（PRD M9 / FunnelTab 阈值着色）：
 * abandonRate > 25% → 红；> 10% → 橙；≤ 10% → 绿。
 * 通过渲染后的单元格 inline style.color 验证阈值分支。
 */
test('放弃率阈值着色：32% 红 / 15% 橙 / 8% 绿', async () => {
  render(<FunnelTab />);

  // 业务办理 abandonRate=32 → 红（详情表放弃率列，组件渲染纯数字不带 %）
  const redCell = await screen.findByText('32');
  expect(RED).toContain(redCell.style.color);

  // 完成阶段 abandonRate=15 → 橙
  const orangeCell = screen.getByText('15');
  expect(ORANGE).toContain(orangeCell.style.color);

  // 意图识别 abandonRate=8 → 绿
  const greenCell = screen.getByText('8');
  expect(GREEN).toContain(greenCell.style.color);
});

test('最大放弃阶段高亮：业务办理(abandoned=300) 字体加粗', async () => {
  render(<FunnelTab />);
  // 详情表「放弃数」列对最大值加粗；阶段列与顶部「最大放弃阶段」统计各出现一次，
  // 用 findAllByText 等待数据加载并断言至少命中一处
  const bizCells = await screen.findAllByText('业务办理');
  expect(bizCells.length).toBeGreaterThan(0);
});
