import React, { useState, useCallback } from 'react';
import { itab } from '../../utils/nav';
import { getCardClickCount, getHumanClickCount } from '../../services/businessEvents';
import usePolling from '../../hooks/usePolling';

/**
 * Zone D — 业务效果 3 卡（V23 M6 / 任务分解 B3）。
 *
 * 业务完成率 / 业务引导办理次数(mbank_card_click) / 转人工次数(mbank_human_click)。
 * 业务完成率读 props.metrics（RealtimeMetricsVO.completionRate）；
 * 两个埋点计数走 BusinessEventQueryService（前端 services/businessEvents 封装）。
 * 点击下钻到 AI 洞察「智能诊断」TAB（itab('diagnosis')）。
 */
function ZoneDBusiness({ metrics }) {
  const [cardClick, setCardClick] = useState(null);
  const [humanClick, setHumanClick] = useState(null);

  const loadCounts = useCallback(async () => {
    try {
      const [c, h] = await Promise.all([
        getCardClickCount(null, null),
        getHumanClickCount(null, null),
      ]);
      // 后端 /business-events/count 直接透出 Long 数值
      setCardClick(typeof c === 'number' ? c : (c && c.count != null ? c.count : null));
      setHumanClick(typeof h === 'number' ? h : (h && h.count != null ? h.count : null));
    } catch (err) {
      // 埋点计数获取失败不影响主指标展示
      console.warn('[ZoneD] counts fetch failed:', err.message);
    }
  }, []);

  // 10s 轮询业务埋点计数（埋点数据相对静态）
  usePolling(loadCounts, 10000, true);

  const completionRate = metrics && metrics.completionRate != null ? metrics.completionRate : null;

  const cards = [
    {
      title: '业务完成率',
      value: completionRate != null ? completionRate : '-',
      unit: '%',
      sub: '业务办理成功 / 总引导',
      color: '#722ed1',
    },
    {
      title: '业务引导办理次数',
      value: cardClick != null ? cardClick.toLocaleString() : '-',
      unit: '次',
      sub: 'mbank_card_click 埋点',
      color: '#d48806',
    },
    {
      title: '转人工次数',
      value: humanClick != null ? humanClick.toLocaleString() : '-',
      unit: '次',
      sub: 'mbank_human_click 埋点',
      color: '#cf1322',
    },
  ];

  return (
    <div>
      <div style={sectionHeaderStyle('#d48806', 'D')}>
        业务效果
        <span
          style={{ marginLeft: 'auto', fontSize: 11, color: '#d48806', cursor: 'pointer' }}
          onClick={() => itab('diagnosis')}
        >
          查看智能诊断 →
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

export default ZoneDBusiness;
