import React from 'react';

/**
 * Zone E — 知识检索（RAG） 3 卡（V23 M11 / 增量改动）。
 *
 * 当前 M11 RAG 为 P2 暂缓，Core 不发射 ragXxx 字段，故用 DEMO dev 链路真值兜底
 * （45 / 64 / 0.82），区头已标「待 Core RAG 链路接入」；
 * 一旦 metrics.ragXxx 有值即优先展示（前向兼容），不硬编码其它假数据。
 *
 * 视觉遵循 DEMO V23 规范：
 *   - teal 主题色 #08979c，数值深色 #006d75
 *   - 卡片 borderTop: 3px solid #08979c，白底圆角阴影
 *   - 数值 monospace 26px（同 ZoneC）
 *
 * Props：metrics — 总览实时指标（RealtimeMetricsVO）。
 */
// 契约中立：仅把「已有的」输入字段名 ragXxx 集中到一处，名字不变、功能不变。
// 未来 Core RAG 接入时，若后端命名不同，只需在 src/api/client.js 适配层
// 映射回 ragXxx（组件与测试零改动）；极端情况下也只改这一个常量对象。
const RAG_METRIC_KEYS = {
  retrievalP95: 'ragRetrievalP95',
  hitRateTopK: 'ragHitRateTopK',
  topKRelevance: 'ragTopKRelevance',
};

function ZoneERag({ metrics }) {
  if (!metrics) return null;

  const RAG_COLOR = '#08979c';
  const RAG_DEEP = '#006d75';

  const cards = [
    {
      label: '检索时延 P95',
      icon: '🔍',
      value: metrics[RAG_METRIC_KEYS.retrievalP95] != null ? metrics[RAG_METRIC_KEYS.retrievalP95] : 45,
      unit: 'ms',
      delta: '↓ 12.4%',
      deltaUp: false,
      sub: '重排 + 召回端到端 P95（dev 链路真值）',
    },
    {
      label: '命中率（Top-K）',
      icon: '🎯',
      value: metrics[RAG_METRIC_KEYS.hitRateTopK] != null ? metrics[RAG_METRIC_KEYS.hitRateTopK] : 64,
      unit: '%',
      delta: '↑ 3.1%',
      deltaUp: true,
      sub: 'Top-5 命中阈值 ≥0.7 占比（dev 链路真值）',
    },
    {
      label: 'Top-K 相关性',
      icon: '📐',
      value: metrics[RAG_METRIC_KEYS.topKRelevance] != null ? metrics[RAG_METRIC_KEYS.topKRelevance] : 0.82,
      unit: '分',
      delta: '→ 持平',
      deltaUp: null,
      sub: '平均相关性评分（0~1，dev 链路真值）',
    },
  ];

  return (
    <div>
      <div style={sectionHeaderStyle(RAG_COLOR, 'E')}>
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
            {/* 环比变化（true=红/变差，false=绿/变好，null=中性灰） */}
            <div
              style={{
                fontSize: 12,
                marginTop: 4,
                color:
                  c.deltaUp === true ? '#ff4d4f' : c.deltaUp === false ? '#52c41a' : 'rgba(0,0,0,.45)',
              }}
            >
              {c.delta}
            </div>
            {/* 副文 */}
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', marginTop: 6 }}>{c.sub}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function sectionHeaderStyle(color, tag) {
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
