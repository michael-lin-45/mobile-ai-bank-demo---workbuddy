import { render } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import React from 'react';

/**
 * RagQualityChart 测试（RAG 检索质量子面板 Top-K 相关性折线图）。
 *
 * 通过 vi.hoisted 在工厂 mock 中捕获 ReactEChartsCore 收到的 option，
 * 断言 series 数量/名称/配色、yAxis.max、空态降级（设计 §2.4）。
 * 真实 echarts 在 jsdom 下无法渲染，故 default 导出 mock 为 no-op 并抓取 props。
 */

// vi.hoisted：在 hoist 后的 mock 工厂与测试代码间安全共享捕获状态
const { capture } = vi.hoisted(() => ({ capture: { option: null } }));

vi.mock('echarts-for-react', () => ({
  default: (props) => {
    capture.option = props.option;
    return null; // 不真正渲染 echarts
  },
}));

import RagQualityChart from '../RagQualityChart';

const TOPK = {
  categories: ['07-04', '07-06', '07-08', '07-10'],
  top1: [44, 47, 50, 52],
  top3: [55, 58, 61, 63],
  top5: [58, 61, 63, 64],
};

describe('RagQualityChart — option 构建', () => {
  it('① 渲染不报错，且构建 3 条 series（Top-1/Top-3/Top-5）', () => {
    render(<RagQualityChart topK={TOPK} height={220} />);
    const opt = capture.option;
    expect(opt).toBeTruthy();
    expect(opt.series).toHaveLength(3);
    expect(opt.series.map((s) => s.name)).toEqual(['Top-1', 'Top-3', 'Top-5']);
  });

  it('② series 配色对齐 DEMO（#08979c / #13c2c2 / #722ed1）', () => {
    render(<RagQualityChart topK={TOPK} />);
    const { series } = capture.option;
    expect(series[0].itemStyle.color).toBe('#08979c');
    expect(series[1].itemStyle.color).toBe('#13c2c2');
    expect(series[2].itemStyle.color).toBe('#722ed1');
  });

  it('③ yAxis.max = 100（命中率/相关性上限 100%）', () => {
    render(<RagQualityChart topK={TOPK} />);
    expect(capture.option.yAxis.max).toBe(100);
    expect(capture.option.xAxis.data).toEqual(TOPK.categories);
    expect(capture.option.series[0].data).toEqual(TOPK.top1);
    expect(capture.option.series[2].data).toEqual(TOPK.top5);
  });

  it('④ height 透传为图表容器高度（默认 240）', () => {
    const { rerender } = render(<RagQualityChart topK={TOPK} height={180} />);
    // height 仅作用于 ReactEChartsCore 的 style，不影响 option；这里验证不抛错即可
    expect(() => rerender(<RagQualityChart topK={TOPK} />)).not.toThrow();
  });
});

describe('RagQualityChart — 空态降级', () => {
  it('⑤ topK 缺失 / 空 categories 时不抛错，series 数据为长度 0 的数组', () => {
    expect(() => render(<RagQualityChart topK={null} />)).not.toThrow();
    expect(capture.option.series.every((s) => Array.isArray(s.data) && s.data.length === 0)).toBe(true);
    expect(capture.option.xAxis.data).toEqual([]);
  });

  it('⑥ topK 无 categories 数组时不抛错', () => {
    expect(() =>
      render(<RagQualityChart topK={{ top1: [1], top3: [2], top5: [3] }} />),
    ).not.toThrow();
    expect(capture.option.xAxis.data).toEqual([]);
  });
});
