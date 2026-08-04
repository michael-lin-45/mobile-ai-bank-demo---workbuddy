import React from 'react';
import { itab } from '../../utils/nav';
import MiniSpark from '../charts/MiniSpark';
import ZoneBadge from './ZoneBadge';

/**
 * Zone C — 语义质量 3 卡（V24 二次对齐 · 任务 T01/T05）。
 *
 * 对齐差距分析 B.1：
 *   - (b) Zone 字母徽标渲染 <ZoneBadge letter="C" />
 *   - (a) 每卡补 MiniSpark（7 点 sparkData，颜色=Zone 主题色 #52c41a）
 *   - (T05) 区头「部分待接入」状态徽标
 * 读取总览实时指标（props.metrics = RealtimeMetricsVO）。
 */
const ZONE_COLOR = '#52c41a';

function ZoneCSemantic({ metrics }) {
  if (!metrics) return null;

  const rerouteWarn = metrics.rerouteRate != null && metrics.rerouteRate > 20;

  const cards = [
    {
      title: '意图识别综合正确率',
      value: metrics.intentAccuracy != null ? metrics.intentAccuracy : '-',
      unit: '%',
      sub: `L0 ${metrics.intentAccuracyL0 != null ? metrics.intentAccuracyL0 : '-'}% · L1 ${metrics.intentAccuracyL1 != null ? metrics.intentAccuracyL1 : '-'}%`,
      color: ZONE_COLOR,
      sparkData: [92.5, 93.0, 93.4, 94.0, 93.7, 94.1, 94.2],
      onClick: () => itab('diagnosis'),
    },
    {
      title: '意图改写正确率 (L1)',
      value: metrics.rewriteAccuracyL1 != null ? metrics.rewriteAccuracyL1 : '-',
      unit: '%',
      sub: `综合改写 ${metrics.rewriteAccuracy != null ? metrics.rewriteAccuracy + '%' : '-'}`,
      color: ZONE_COLOR,
      sparkData: [90.0, 90.6, 91.0, 91.2, 91.0, 91.4, 91.5],
      onClick: () => itab('diagnosis'),
    },
    {
      title: '重路由比率 (Reroute)',
      value: metrics.rerouteRate != null ? metrics.rerouteRate : '-',
      unit: '%',
      sub: rerouteWarn ? '⚠ 超阈值 (>20%)' : 'Reroute · 低置信度触发 L1 二次识别',
      color: rerouteWarn ? '#ff4d4f' : '#faad14',
      sparkData: [16.5, 15.8, 15.2, 14.9, 14.6, 14.3, 14.2],
      onClick: () => itab('diagnosis'),
    },
  ];

  return (
    <div>
      <div style={sectionHeaderStyle(ZONE_COLOR)}>
        <ZoneBadge letter="C" color={ZONE_COLOR} />
        语义质量
        <span
          style={{
            marginLeft: 'auto',
            fontSize: 11,
            color: '#d48806',
            background: '#fffbe6',
            border: '1px solid #ffe7ba',
            borderRadius: 4,
            padding: '1px 8px',
          }}
        >
          部分待接入
        </span>
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
              transition: 'box-shadow .2s',
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
            <div style={{ height: 32, marginTop: 8 }}>
              <MiniSpark data={c.sparkData} color={c.color} />
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

export default ZoneCSemantic;
