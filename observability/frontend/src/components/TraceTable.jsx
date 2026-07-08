import React from 'react';

/**
 * TraceTable — 链路追踪列表
 *
 * 列: Trace ID · Session ID · User ID · 意图(Badge) · Agents路径 · 耗时 · TTFT · Token · 状态 · 查看详情
 *
 * Props:
 * - traces: 链路数组
 * - onViewDetail: (trace) => void
 * - loading: boolean
 */
const INTENT_BADGE = {
  TRANSFER: { bg: '#f9f0ff', color: '#722ed1', border: '#d3adf7' },
  WEALTH: { bg: '#fffbe6', color: '#faad14', border: '#ffe58f' },
  BILL_QUERY: { bg: '#e6f4ff', color: '#1677ff', border: '#91caff' },
  CHAT: { bg: '#e6f4ff', color: '#1677ff', border: '#91caff' },
};

const STATUS_META = {
  OK: { label: '正常', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
  ERR: { label: '异常', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
  SLOW: { label: '偏慢', color: '#faad14', bg: '#fffbe6', border: '#ffe58f' },
  ERROR: { label: '异常', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
};

const TH_STYLE = {
  textAlign: 'left',
  padding: '10px 12px',
  background: '#fafafa',
  borderBottom: '1px solid #f0f0f0',
  fontWeight: 600,
  color: 'rgba(0,0,0,.65)',
  whiteSpace: 'nowrap',
  fontSize: 13,
};

const TD_STYLE = {
  padding: '10px 12px',
  borderBottom: '1px solid #f0f0f0',
  fontSize: 13,
};

const MONO_STYLE = {
  fontFamily: '"JetBrains Mono", "SF Mono", Consolas, monospace',
  fontSize: 12,
};

function TraceTable({ traces = [], onViewDetail, loading }) {
  if (loading) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
        加载中...
      </div>
    );
  }

  if (traces.length === 0) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
        暂无 Trace 数据
      </div>
    );
  }

  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
        <thead>
          <tr>
            <th style={TH_STYLE}>Trace</th>
            <th style={TH_STYLE}>Session</th>
            <th style={TH_STYLE}>User</th>
            <th style={TH_STYLE}>Agents</th>
            <th style={TH_STYLE}>耗时</th>
            <th style={TH_STYLE}>TTFT</th>
            <th style={TH_STYLE}>Token</th>
            <th style={TH_STYLE}>状态</th>
            <th style={TH_STYLE}></th>
          </tr>
        </thead>
        <tbody>
          {traces.map((t, idx) => (
            <tr
              key={t.traceId || idx}
              style={{ cursor: 'pointer' }}
              onClick={() => onViewDetail(t)}
              onMouseEnter={(e) => { e.currentTarget.style.background = '#fafafa'; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
            >
              <td style={TD_STYLE}>
                <span style={{ ...MONO_STYLE, color: '#1677ff', fontSize: 11 }}>
                  {shortId(t.traceId)}
                </span>
              </td>
              <td style={TD_STYLE}>
                <span style={{ ...MONO_STYLE, fontSize: 11 }}>{shortId(t.sessionId)}</span>
              </td>
              <td style={TD_STYLE}>
                <span style={{ ...MONO_STYLE, fontSize: 11 }}>{t.userId || '-'}</span>
              </td>
              <td style={TD_STYLE}>
                <span style={{ ...MONO_STYLE, fontSize: 11 }}>{dedupAgentChain(t.agentChain)}</span>
              </td>
              <td style={{ ...TD_STYLE, ...MONO_STYLE, fontSize: 11 }}>
                {formatMs(t.durationMs)}
              </td>
              <td style={{ ...TD_STYLE, ...MONO_STYLE, fontSize: 11, color: '#722ed1' }}>
                {formatMs(t.ttftMs)}
              </td>
              <td style={{ ...TD_STYLE, ...MONO_STYLE, fontSize: 11 }}>
                {t.tokenTotal != null ? formatK(t.tokenTotal) : '-'}
              </td>
              <td style={TD_STYLE}>
                <StatusBadge status={t.statusCode} />
              </td>
              <td style={TD_STYLE}>
                <span
                  onClick={(e) => { e.stopPropagation(); onViewDetail(t); }}
                  style={{
                    display: 'inline-block',
                    padding: '3px 10px',
                    borderRadius: 4,
                    fontSize: 11,
                    fontWeight: 500,
                    border: '1px solid #1677ff',
                    color: '#1677ff',
                    background: '#fff',
                    cursor: 'pointer',
                    whiteSpace: 'nowrap',
                    transition: '.15s',
                  }}
                  onMouseEnter={(e) => { e.currentTarget.style.background = '#1677ff'; e.currentTarget.style.color = '#fff'; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = '#fff'; e.currentTarget.style.color = '#1677ff'; }}
                >
                  查看详情
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function IntentBadge({ intent }) {
  const meta = INTENT_BADGE[intent] || { bg: '#f5f5f5', color: 'rgba(0,0,0,.65)', border: '#d9d9d9' };
  return (
    <span
      style={{
        display: 'inline-block',
        padding: '2px 8px',
        borderRadius: 4,
        fontSize: 10,
        fontWeight: 600,
        background: meta.bg,
        color: meta.color,
        border: `1px solid ${meta.border}`,
        minWidth: 82,
        textAlign: 'center',
      }}
    >
      {intent || '-'}
    </span>
  );
}

function StatusBadge({ status }) {
  const meta = STATUS_META[status] || STATUS_META.OK;
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '2px 8px',
        borderRadius: 4,
        fontSize: 12,
        fontWeight: 500,
        background: meta.bg,
        color: meta.color,
        border: `1px solid ${meta.border}`,
      }}
    >
      <span style={{ width: 6, height: 6, borderRadius: '50%', display: 'inline-block', background: meta.color }} />
      {meta.label}
    </span>
  );
}

function shortId(id) {
  if (!id) return '-';
  return id.length > 8 ? id.substring(0, 8) + '…' : id;
}

function formatMs(ms) {
  if (ms == null) return '-';
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's';
  return ms + 'ms';
}

function formatK(n) {
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
  return String(n);
}

function dedupAgentChain(chain) {
  if (!chain || chain === 'N/A') return '-';
  const parts = chain.split(/\s*→\s*/);
  if (parts.length <= 1) return chain;
  const result = [parts[0]];
  for (let i = 1; i < parts.length; i++) {
    if (parts[i] !== parts[i - 1]) {
      result.push(parts[i]);
    }
  }
  return result.join(' → ');
}


export default TraceTable;
