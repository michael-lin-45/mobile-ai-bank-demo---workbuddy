import React from 'react';
import { itab } from '../../utils/nav';

/**
 * 诊断摘要 4 卡（V23 M6 / 任务分解 B3，对齐 DEMO V23）。
 *
 * 由「4 张 KPI 卡」改为「4 张风险计数卡」：
 *   性能瓶颈(红) / 准确率风险(黄) / 转化流失(紫) / 满意度(绿)。
 * 每张卡：维度名(11px 彩色) + 大字数值(24px 800) + 副文(具体问题)。
 * 点击下钻：性能/准确率 → 智能诊断 TAB；满意度 → 满意度 TAB。
 *
 * 数据来自新增的 `risks` prop（后端就绪后由 insights 聚合取数；
 * 未就绪时由调用方注入 mock 形状，不要再从 RealtimeMetricsVO 取 KPI）。
 *
 * @typedef {Object} Risks
 * @property {number} perf        性能瓶颈计数
 * @property {number} accuracy    准确率风险计数
 * @property {number} conversion  转化流失计数
 * @property {number} satisfaction 满意度评分
 * @property {Object} subs       { perf, accuracy, conversion, satisfaction } 各卡副文
 */

// mock 兜底形状（与 DEMO V23 L261-286 hex 完全一致）
export const DEFAULT_RISKS = {
  perf: 2,
  accuracy: 3,
  conversion: 1,
  satisfaction: 4.1,
  subs: {
    perf: '理财咨询 P95 超标',
    accuracy: '低置信度意图',
    conversion: '参数提取阶段',
    satisfaction: '↑ 0.2 较昨日',
  },
};

// 四卡静态样式（维度名色 / 背景 / 边框 / 下钻 TAB）
const CARD_CONFIG = [
  { key: 'perf', name: '性能瓶颈', color: '#ff4d4f', bg: '#fff7f7', border: '#ffccc7', tab: 'diagnosis' },
  { key: 'accuracy', name: '准确率风险', color: '#faad14', bg: '#fffbe6', border: '#ffe58f', tab: 'diagnosis' },
  { key: 'conversion', name: '转化流失', color: '#722ed1', bg: '#faf5ff', border: '#d3adf7', tab: 'diagnosis' },
  { key: 'satisfaction', name: '满意度', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f', tab: 'satisfaction' },
];

function DiagnosisSummary4Cards({ risks }) {
  const r = risks || DEFAULT_RISKS;
  const subs = r.subs || DEFAULT_RISKS.subs;

  const cards = CARD_CONFIG.map((c) => ({
    ...c,
    value: r[c.key] != null ? r[c.key] : '-',
    sub: subs[c.key] || '—',
  }));

  return (
    <div>
      <div style={sectionHeaderStyle('#1677ff')}>
        诊断摘要
        <span
          style={{ marginLeft: 'auto', fontSize: 11, color: '#1677ff', cursor: 'pointer' }}
          onClick={() => itab('diagnosis')}
        >
          查看智能诊断 →
        </span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
        {cards.map((c) => (
          <div
            key={c.key}
            onClick={() => itab(c.tab)}
            style={{
              background: c.bg,
              borderRadius: 8,
              boxShadow: '0 1px 2px rgba(0,0,0,.03)',
              padding: '16px 18px',
              cursor: 'pointer',
              borderLeft: `3px solid ${c.border}`,
              transition: 'box-shadow .2s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,.08)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.boxShadow = '0 1px 2px rgba(0,0,0,.03)'; }}
          >
            <div style={{ fontSize: 11, color: c.color, fontWeight: 600, marginBottom: 8 }}>{c.name}</div>
            <div style={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 800, fontSize: 24, color: c.color }}>
              {c.value}
            </div>
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', marginTop: 6 }}>{c.sub}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function sectionHeaderStyle(color) {
  return {
    fontSize: 13,
    fontWeight: 600,
    color: 'rgba(0,0,0,.65)',
    marginBottom: 12,
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    paddingLeft: 10,
    borderLeft: `3px solid ${color}`,
  };
}

export default DiagnosisSummary4Cards;
