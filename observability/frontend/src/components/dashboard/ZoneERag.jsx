import React from 'react';
import MiniSpark from '../charts/MiniSpark';
import ZoneBadge from './ZoneBadge';

/**
 * Zone E — 知识检索（RAG） 3 卡（V24 二次对齐 · 任务 T01(g)/T05）。
 *
 * 对齐差距分析 B.1：
 *   - (b) Zone 字母徽标渲染 <ZoneBadge letter="E" />
 *   - (a) 每卡补 MiniSpark（7 点 sparkData，颜色=Zone 主题色 #08979c）
 *   - (g) 环比配色语义修正：每卡 goodWhenUp，颜色 = deltaUp===null ? 灰 : (deltaUp===goodWhenUp ? 绿 : 红)
 *   - (T05) 保留区头「待 Core RAG 链路接入」状态徽标
 * 当前 M11 RAG 为 P2 暂缓，Core 不发射 ragXxx 字段，故用 DEMO dev 链路真值兜底；
 * 一旦 metrics.ragXxx 有值即优先展示（前向兼容）。
 */
const RAG_COLOR = '#08979c';
const RAG_DEEP = '#006d75';

// 契约中立：仅把「已有的」输入字段名 ragXxx 集中到一处，名字不变、功能不变。
const RAG_METRIC_KEYS = {
  retrievalP95: 'ragRetrievalP95',
  hitRateTopK: 'ragHitRateTopK',
  topKRelevance: 'ragTopKRelevance',
};

function ZoneERag({ metrics }) {
  if (!metrics) return null;

  const cards = [
    {
      label: '检索时延 P95',
      icon: '🔍',
      value: metrics[RAG_METRIC_KEYS.retrievalP95] != null ? metrics[RAG_METRIC_KEYS.retrievalP95] : 45,
      unit: 'ms',
      delta: '↓ 12.4%',
      deltaUp: false,
      goodWhenUp: false, // 时延↓为好 → 绿
      sub: '重排 + 召回端到端 P95（dev 链路真值）',
      sparkData: [52, 49, 47, 46, 45, 44, 45],
    },
    {
      label: '命中率（Top-K）',
      icon: '🎯',
      value: metrics[RAG_METRIC_KEYS.hitRateTopK] != null ? metrics[RAG_METRIC_KEYS.hitRateTopK] : 64,
      unit: '%',
      delta: '↑ 3.1%',
      deltaUp: true,
      goodWhenUp: true, // 命中率↑为好 → 绿
      sub: 'Top-5 命中阈值 ≥0.7 占比（dev 链路真值）',
      sparkData: [58, 60, 61, 62, 63, 63, 64],
    },
    {
      label: 'Top-K 相关性',
      icon: '📐',
      value: metrics[RAG_METRIC_KEYS.topKRelevance] != null ? metrics[RAG_METRIC_KEYS.topKRelevance] : 0.82,
      unit: '分',
      delta: '→ 持平',
      deltaUp: null,
      goodWhenUp: true, // 相关性↑为好，但持平 → 灰
      sub: '平均相关性评分（0~1，dev 链路真值）',
      sparkData: [0.78, 0.79, 0.80, 0.81, 0.81, 0.82, 0.82],
    },
  ];

  /**
   * 环比配色统一语义（docs/system_design.md §7.4）：
   *   deltaUp===null → 灰（持平）
   *   deltaUp===goodWhenUp → 绿（变好）
   *   否则 → 红（变差）
   */
  const deltaColor = (c) => {
    if (c.deltaUp === null) return 'rgba(0,0,0,.45)';
    return c.deltaUp === c.goodWhenUp ? '#52c41a' : '#ff4d4f';
  };

  return (
    <div>
      <div style={sectionHeaderStyle(RAG_COLOR)}>
        <ZoneBadge letter="E" color={RAG_COLOR} />
        知识检索（RAG）
        <span
          style={{
            marginLeft: 'auto',
            fontSize: 11,
            color: '#d48806',
            border: '1px solid rgba(212,136,6,.3)',
            borderRadius: 4,
            padding: '1px 6px',
          }}
        >
          待 Core RAG 链路接入
        </span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
        {cards.map((c) => (
          <div
            key={c.label}
            style={{
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 1px 2px rgba(0,0,0,.03)',
              padding: '16px 18px',
              cursor: 'default',
              borderTop: `3px solid ${RAG_COLOR}`,
            }}
          >
            {/* 指标名 + 图标 */}
            <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginBottom: 8 }}>
              {c.label}
              <span style={{ marginLeft: 4 }}>{c.icon}</span>
            </div>
            {/* 主数值（monospace 26px，teal 深） */}
            <div
              style={{
                fontFamily: '"JetBrains Mono", monospace',
                fontWeight: 700,
                fontSize: 26,
                color: RAG_DEEP,
              }}
            >
              {c.value}
              <span style={{ fontSize: 13, fontWeight: 500, color: 'rgba(0,0,0,.45)', marginLeft: 2 }}>
                {c.unit}
              </span>
            </div>
            {/* 环比变化（goodWhenUp 语义：↑绿/↓绿视指标而定） */}
            <div style={{ fontSize: 12, marginTop: 4, color: deltaColor(c) }}>
              {c.delta}
            </div>
            {/* 副文 */}
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', marginTop: 6 }}>{c.sub}</div>
            {/* Mini Spark（T01 a） */}
            <div style={{ height: 32, marginTop: 8 }}>
              <MiniSpark data={c.sparkData} color={RAG_COLOR} />
            </div>
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

export default ZoneERag;
