import { describe, it, expect } from 'vitest';
import { getRagMock } from '../mockInsights';

/**
 * getRagMock 数据真值完整性测试（RAG tab V24 对齐 DEMO）。
 *
 * 这是 RAG tab 的「黄金数据」守卫测试：设计文档 §2.4 / 附：字段映射速查
 * 明确要求 quality.table 必须是 **5 行、verdict ∈ {good|warn}**、NDCG@5 改相对增益
 * 「+0.11」、且不得出现「6 行全 good」的误导性旧数据（设计 §4.1 F2 P0 红线）。
 *
 * 若后端/设计后续改动 getRagMock，这些断言会第一时间拦截「全达标误导」与「维度缺失」。
 */

describe('getRagMock — 运行态 5 子指标 (run)', () => {
  const run = getRagMock().run;

  it('① run 含 5 个子指标且数值对齐 DEMO（45 / 8.2 / 64 / 0.4 / 0.1）', () => {
    expect(run.reorderP95Ms).toBe(45);
    expect(run.recallDocs).toBe(8.2);
    expect(run.triggerRate).toBe(64);
    expect(run.retrieveErrRate).toBe(0.4);
    expect(run.reorderErrRate).toBe(0.1);
  });

  it('② run 5 子指标均带 delta 副文案（KPI k-sub，设计 §2.2）', () => {
    expect(run.reorderP95Delta).toBe('↓ 12.4% 较昨日');
    expect(run.recallDocsDelta).toBe('↑ 0.6 篇');
    expect(run.triggerRateDelta).toBe('↑ 3.1% 会话含检索');
    expect(run.retrieveErrRateDelta).toBe('↓ 0.1% 网络/超时');
    expect(run.reorderErrRateDelta).toBe('→ 持平');
  });

  it('③ run.trend 为 7 点 x 轴 + 5 条 series，每条含 color 与 yAxisIndex(双轴)', () => {
    expect(run.trend.categories).toEqual([
      '10:00', '11:00', '12:00', '13:00', '14:00', '15:00', '16:00',
    ]);
    expect(run.trend.series).toHaveLength(5);
    for (const s of run.trend.series) {
      expect(s).toHaveProperty('name');
      expect(s).toHaveProperty('data');
      expect(s).toHaveProperty('color');
      expect(s).toHaveProperty('yAxisIndex');
      expect(typeof s.yAxisIndex).toBe('number');
    }
    // 双 Y 轴映射：时延/文档数 → 0（ms/篇），比率类 → 1（%）
    const byName = Object.fromEntries(run.trend.series.map((s) => [s.name, s.yAxisIndex]));
    expect(byName['重排时延P95']).toBe(0);
    expect(byName['召回文档数']).toBe(0);
    expect(byName['RAG触发率']).toBe(1);
    expect(byName['检索错误率']).toBe(1);
    expect(byName['重排错误率']).toBe(1);
  });
});

describe('getRagMock — 检索质量子面板 (quality)', () => {
  const quality = getRagMock().quality;

  it('① quality.topK 为 4 点 x 轴 + Top-1/3/5 三条数组等长', () => {
    expect(quality.topK.categories).toEqual(['07-04', '07-06', '07-08', '07-10']);
    const { top1, top3, top5 } = quality.topK;
    expect(top1).toHaveLength(4);
    expect(top3).toHaveLength(4);
    expect(top5).toHaveLength(4);
    expect(top1[top1.length - 1]).toBe(52); // Top-1 命中率末点 52%
    expect(top5[top5.length - 1]).toBe(64); // Top-5 命中率末点 64%
  });

  it('② quality.table 恰好 5 行（拦截旧「6 行全 good」误导回归，设计 F2 P0）', () => {
    expect(quality.table).toHaveLength(5);
  });

  it('③ 每行 verdict 仅允许 good|warn 枚举，不允许 red/bad/error 等', () => {
    const allowed = ['good', 'warn'];
    for (const row of quality.table) {
      expect(allowed).toContain(row.verdict);
    }
  });

  it('④ 判定分布：2 行 good（达标）+ 3 行 warn（偏低/临界），与 DEMO 一致', () => {
    const good = quality.table.filter((r) => r.verdict === 'good');
    const warn = quality.table.filter((r) => r.verdict === 'warn');
    expect(good).toHaveLength(2);
    expect(warn).toHaveLength(3);
    expect(good.map((r) => r.dim)).toEqual([
      '空召回率（无文档）',
      '重排提升度（NDCG@5）',
    ]);
    expect(warn.map((r) => r.dim)).toEqual([
      'Top-1 命中率',
      'Top-5 命中率',
      '平均相关性评分',
    ]);
  });

  it('⑤ NDCG@5 当前值为相对增益「+0.11」、目标「≥+0.08」、verdict good（设计 §2.4 红线）', () => {
    const ndcg = quality.table.find((r) => r.dim.includes('NDCG@5'));
    expect(ndcg).toBeDefined();
    expect(ndcg.current).toBe('+0.11');
    expect(ndcg.target).toBe('≥+0.08');
    expect(ndcg.verdict).toBe('good');
  });

  it('⑥ 各维度目标阈值对齐 DEMO（≥60% / ≥70% / ≥0.85 / ≤2% / ≥+0.08）', () => {
    const byDim = Object.fromEntries(quality.table.map((r) => [r.dim, r.target]));
    expect(byDim['Top-1 命中率']).toBe('≥60%');
    expect(byDim['Top-5 命中率']).toBe('≥70%');
    expect(byDim['平均相关性评分']).toBe('≥0.85');
    expect(byDim['空召回率（无文档）']).toBe('≤2%');
    expect(byDim['重排提升度（NDCG@5）']).toBe('≥+0.08');
  });
});

describe('getRagMock — 形状稳定性', () => {
  it('① 多次调用返回结构一致的独立对象（非共享可变引用）', () => {
    const a = getRagMock();
    const b = getRagMock();
    expect(a).toEqual(b);
    expect(a).not.toBe(b); // 每次应返回新对象，避免跨调用污染
  });

  it('② 顶层结构含 run 与 quality 两个键', () => {
    const rag = getRagMock();
    expect(Object.keys(rag).sort()).toEqual(['quality', 'run']);
  });
});
