import React, { useState, useCallback } from 'react';
import { itab } from '../../utils/nav';
import MiniSpark from '../charts/MiniSpark';
import ZoneBadge from './ZoneBadge';
import { getCardClickCount, getHumanClickCount } from '../../services/businessEvents';
import usePolling from '../../hooks/usePolling';

/**
 * Zone D — 业务效果 3 卡（V24 二次对齐 · 任务 T01/T05）。
 *
 * 对齐差距分析 B.1：
 *   - (b) Zone 字母徽标渲染 <ZoneBadge letter="D" />
 *   - (a) 每卡补 MiniSpark（7 点 sparkData，颜色=Zone 主题色 #d48806）
 *   - (T05) 区头「部分待接入」状态徽标 + 两个埋点卡「待埋点接入」标注
 * 业务完成率读 props.metrics；两个埋点计数走 BusinessEventQueryService。
 */
const ZONE_COLOR = '#d48806';

function ZoneDBusiness({ metrics }) {
  const [cardClick, setCardClick] = useState(null);
  const [humanClick, setHumanClick] = useState(null);

  const loadCounts = useCallback(async () => {
    try {
      const [c, h] = await Promise.all([
        getCardClickCount(null, null),
        getHumanClickCount(null, null),
      ]);
      setCardClick(typeof c === 'number' ? c : (c && c.count != null ? c.count : null));
      setHumanClick(typeof h === 'number' ? h : (h && h.count != null ? h.count : null));
    } catch (err) {
      console.warn('[ZoneD] counts fetch failed:', err.message);
    }
  }, []);

  // 10s 轮询业务埋点计数
  usePolling(loadCounts, 10000, true);

  const completionRate = metrics && metrics.completionRate != null ? metrics.completionRate : null;

  const cards = [
    {
      title: '业务完成率',
      value: completionRate != null ? completionRate : '-',
      unit: '%',
      sub: '业务办理成功 / 总引导',
      color: '#722ed1',
      sparkData: [84, 85, 86, 86.5, 87, 87.3, 87.6],
    },
    {
      title: '业务引导办理次数',
      value: cardClick != null ? cardClick.toLocaleString() : '-',
      unit: '次',
      sub: 'mbank_card_click 埋点（待埋点接入）',
      color: ZONE_COLOR,
      sparkData: [1100, 1150, 1180, 1200, 1230, 1260, 1284],
    },
    {
      title: '转人工次数',
      value: humanClick != null ? humanClick.toLocaleString() : '-',
      unit: '次',
      sub: 'mbank_human_click 埋点（待埋点接入）',
      color: '#cf1322',
      sparkData: [110, 105, 100, 98, 97, 96, 96],
    },
  ];

  return (
    <div>
      <div style={sectionHeaderStyle(ZONE_COLOR)}>
        <ZoneBadge letter="D" color={ZONE_COLOR} />
        业务效果
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

export default ZoneDBusiness;
