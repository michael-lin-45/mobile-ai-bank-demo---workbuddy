import React from 'react';
import { itab } from '../../utils/nav';

/**
 * Zone C — 语义质量 3 卡（V23 M6 / 任务分解 B3）。
 *
 * 意图识别综合正确率(含 L0/L1) / 意图改写正确率(L1) / 重路由比率。
 * 读取总览实时指标（props.metrics = RealtimeMetricsVO，来自 getRealtime）。
 */
function ZoneCSemantic({ metrics }) {
  if (!metrics) return null;

  const rerouteWarn = metrics.rerouteRate != null && metrics.rerouteRate > 20;

  const cards = [
    {
      title: '意图识别综合正确率',
      value: metrics.intentAccuracy != null ? metrics.intentAccuracy : '-',
      unit: '%',
      sub: `L0 ${metrics.intentAccuracyL0 != null ? metrics.intentAccuracyL0 : '-'}% · L1 ${metrics.intentAccuracyL1 != null ? metrics.intentAccuracyL1 : '-'}%`,
      color: '#52c41a',
      onClick: () => itab('diagnosis'),
    },
    {
      title: '意图改写正确率 (L1)',
      value: metrics.rewriteAccuracyL1 != null ? metrics.rewriteAccuracyL1 : '-',
      unit: '%',
      sub: `综合改写 ${metrics.rewriteAccuracy != null ? metrics.rewriteAccuracy + '%' : '-'}`,
      color: '#52c41a',
      onClick: () => itab('diagnosis'),
    },
    {
      title: '重路由比率 (Reroute)',
      value: metrics.rerouteRate != null ? metrics.rerouteRate : '-',
      unit: '%',
      sub: rerouteWarn ? '⚠ 超阈值 (>20%)' : 'Reroute · 低置信度触发 L1 二次识别',
      color: rerouteWarn ? '#ff4d4f' : '#faad14',
      onClick: () => itab('diagnosis'),
    },
  ];

  return (
    <div>
      <div style={sectionHeaderStyle('#52c41a', 'C')}>
        语义质量
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
        {cards.map((c) => (
          <div
            key={c.title}
            onClick={c.onClick}
            style={{
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 1px 2px rgba(0,0,0,.03)',
              padding: '16px 18px',
              cursor: 'pointer',
              borderLeft: `3px solid ${c.color}`,
            }}
            onMouseEnter={(e) => { e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,.08)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.boxShadow = '0 1px 2px rgba(0,0,0,.03)'; }}
          >
            <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginBottom: 8 }}>{c.title}</div>
            <div style={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, fontSize: 26, color: c.color }}>
              {c.value}
              <span style={{ fontSize: 13, fontWeight: 500, color: 'rgba(0,0,0,.45)', marginLeft: 2 }}>{c.unit}</span>
            </div>
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

export default ZoneCSemantic;
