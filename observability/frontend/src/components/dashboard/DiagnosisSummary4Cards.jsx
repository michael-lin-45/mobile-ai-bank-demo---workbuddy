import React from 'react';
import { itab } from '../../utils/nav';

/**
 * 诊断摘要 4 卡（V23 M6 / 任务分解 B3）。
 *
 * 综合正确率(含 L0/L1) / 改写正确率(L1) / 重路由比率 / 业务完成率。
 * 点击任意卡下钻到 AI 洞察「智能诊断」TAB（itab('diagnosis')）。
 *
 * 数据来自总览实时指标（props.metrics = RealtimeMetricsVO）。
 */
function DiagnosisSummary4Cards({ metrics }) {
  if (!metrics) return null;

  const cards = [
    {
      title: '意图识别综合正确率',
      value: metrics.intentAccuracy != null ? metrics.intentAccuracy : '-',
      unit: '%',
      sub: `L0 ${metrics.intentAccuracyL0 != null ? metrics.intentAccuracyL0 : '-'}% · L1 ${metrics.intentAccuracyL1 != null ? metrics.intentAccuracyL1 : '-'}%`,
      color: '#1677ff',
    },
    {
      title: '意图改写正确率 (L1)',
      value: metrics.rewriteAccuracyL1 != null ? metrics.rewriteAccuracyL1 : '-',
      unit: '%',
      sub: `综合改写 ${metrics.rewriteAccuracy != null ? metrics.rewriteAccuracy + '%' : '-'}`,
      color: '#52c41a',
    },
    {
      title: '重路由比率',
      value: metrics.rerouteRate != null ? metrics.rerouteRate : '-',
      unit: '%',
      sub: metrics.rerouteRate != null && metrics.rerouteRate > 20
        ? '⚠ 超阈值 (>20%)'
        : '低置信度触发 L1 二次识别',
      color: metrics.rerouteRate != null && metrics.rerouteRate > 20 ? '#ff4d4f' : '#faad14',
    },
    {
      title: '业务完成率',
      value: metrics.completionRate != null ? metrics.completionRate : '-',
      unit: '%',
      sub: '业务办理成功 / 总数',
      color: '#722ed1',
    },
  ];

  return (
    <div>
      <div style={sectionHeaderStyle('#1677ff', 'D')}>
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
            key={c.title}
            onClick={() => itab('diagnosis')}
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

export default DiagnosisSummary4Cards;
