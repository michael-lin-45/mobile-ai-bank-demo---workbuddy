import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';

/**
 * RagPanel 集成测试（RAG tab V24 三块结构，对齐 DEMO）。
 *
 * 策略：
 *  - mock echarts-for-react → 不真正渲染 ECharts（jsdom 下会崩）；
 *  - 使用 **真实** getRagMock() 作为数据源，端到端校验「数据 → 渲染」一致性；
 *  - 重点守卫设计文档红线：3 块结构、5 子指标 KPI、DemoBadge 每块 1 个、
 *    质量判定表 good→green / warn→gold（无红）、底部运行态/效果态说明。
 */

vi.mock('echarts-for-react', () => ({
  default: () => null,
}));

import RagPanel from '../RagPanel';

afterEach(() => {
  cleanup();
});

describe('RagPanel — 三块结构（对齐 DEMO，非两模块）', () => {
  it('① 仅渲染 3 个带 DEMO 标题的子 Card，无旧「基础调用统计」冗余模块', () => {
    render(<RagPanel />);
    expect(screen.getByText(/📊 RAG 运行指标（5 子指标）/)).toBeInTheDocument();
    expect(screen.getByText(/📈 趋势图 · 5 子指标（近 6h）/)).toBeInTheDocument();
    expect(screen.getByText(/🔬 RAG 检索质量子面板/)).toBeInTheDocument();

    // 旧模块 A（RagRealContent：调用次数/平均时延/P95/错误率统计 + 明细表）不应出现
    expect(screen.queryByText(/调用次数/)).not.toBeInTheDocument();
    expect(screen.queryByText(/平均时延/)).not.toBeInTheDocument();
  });

  it('② 块①副标题为「dev 链路真值 · 近 6h」', () => {
    render(<RagPanel />);
    expect(screen.getByText(/dev 链路真值 · 近 6h/)).toBeInTheDocument();
  });
});

describe('RagPanel — 运行态 5 子指标 KPI', () => {
  it('③ 5 个 KPI 标签全部渲染', () => {
    render(<RagPanel />);
    expect(screen.getByText('重排时延 P95')).toBeInTheDocument();
    expect(screen.getByText('平均召回文档数')).toBeInTheDocument();
    expect(screen.getByText('RAG 触发率')).toBeInTheDocument();
    expect(screen.getByText('检索错误率')).toBeInTheDocument();
    expect(screen.getByText('重排错误率')).toBeInTheDocument();
  });

  it('④ 5 个子指标数值与 DEMO 一致（45 / 8.2 / 64 / 0.4 / 0.1）', () => {
    render(<RagPanel />);
    expect(screen.getByText('45', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('8.2', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('64', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('0.4', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('0.1', { exact: true })).toBeInTheDocument();
  });

  it('⑤ KPI 单位正确（ms / 篇 / % / % / %）', () => {
    render(<RagPanel />);
    expect(screen.getByText('ms', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('篇', { exact: true })).toBeInTheDocument();
    expect(screen.getAllByText('%', { exact: true }).length).toBeGreaterThanOrEqual(2);
  });
});

describe('RagPanel — DemoBadge 标识', () => {
  it('⑥ 3 块各挂 1 个 DemoBadge（共 3 个「DEMO」角标）', () => {
    render(<RagPanel />);
    expect(screen.getAllByText('DEMO')).toHaveLength(3);
  });
});

describe('RagPanel — 检索质量子面板 质量判定表配色（无红）', () => {
  it('⑦ 判定表渲染 5 行，good→green 2 个 / warn→gold 3 个 / 无 red', () => {
    const { container } = render(<RagPanel />);
    // antd 预设色 Tag 会带 ant-tag-{color} 类
    const green = container.querySelectorAll('.ant-tag-green');
    const gold = container.querySelectorAll('.ant-tag-gold');
    const red = container.querySelectorAll('.ant-tag-red');
    expect(green).toHaveLength(2);
    expect(gold).toHaveLength(3);
    expect(red).toHaveLength(0);
  });

  it('⑧ 判定文案含「达标」与「偏低/临界」', () => {
    render(<RagPanel />);
    expect(screen.getAllByText('达标')).toHaveLength(2);
    expect(screen.getAllByText('偏低')).toHaveLength(2);
    expect(screen.getAllByText('临界')).toHaveLength(1);
  });

  it('⑨ 子面板右侧标题「质量判定表」与左侧「Top-K 相关性分布」', () => {
    render(<RagPanel />);
    expect(screen.getByText('质量判定表')).toBeInTheDocument();
    expect(screen.getByText('Top-K 相关性分布')).toBeInTheDocument();
  });
});

describe('RagPanel — 底部说明（运行态 vs 效果态）', () => {
  it('⑩ 底部 note 阐明运行态与效果态视角不同、不重叠', () => {
    render(<RagPanel />);
    expect(screen.getByText(/运行态监控/)).toBeInTheDocument();
    expect(screen.getByText(/效果态评估/)).toBeInTheDocument();
    expect(screen.getByText(/二者视角不同、不重叠/)).toBeInTheDocument();
  });
});
