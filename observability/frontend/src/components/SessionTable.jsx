import React from 'react';
import { formatBeijingTime } from '../utils/time';

/**
 * SessionTable — 会话列表表格
 *
 * 列: Session ID · User ID · 渠道 · 时间 · 时长 · 轮次 · 意图 · 执行智能体(→分隔) · Token · 会话状态 · 操作
 *
 * Props:
 * - sessions: 会话数组
 * - onViewSession: (session) => void 查看回放回调
 * - loading: boolean
 */
const STATUS_META = {
  completed: { label: '正常', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
  active: { label: '正常', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
  error: { label: '异常', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
  slow: { label: '偏慢', color: '#faad14', bg: '#fffbe6', border: '#ffe58f' },
};

const INTENT_BADGE = {
  TRANSFER: { bg: '#f9f0ff', color: '#722ed1', border: '#d3adf7' },
  WEALTH: { bg: '#f9f0ff', color: '#722ed1', border: '#d3adf7' },
  BILL_QUERY: { bg: '#e6f4ff', color: '#1677ff', border: '#91caff' },
  CHAT: { bg: '#e6f4ff', color: '#1677ff', border: '#91caff' },
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

function SessionTable({ sessions = [], onViewSession, loading }) {
  if (loading) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
        加载中...
      </div>
    );
  }

  if (sessions.length === 0) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
        暂无会话数据
      </div>
    );
  }

  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
        <thead>
          <tr>
            <th style={TH_STYLE}>Session ID</th>
            <th style={TH_STYLE}>User ID</th>
            <th style={TH_STYLE}>渠道</th>
            <th style={TH_STYLE}>时间</th>
            <th style={TH_STYLE}>时长</th>
            <th style={TH_STYLE}>轮次</th>
            <th style={TH_STYLE}>执行智能体</th>
            <th style={TH_STYLE}>Token</th>
            <th style={TH_STYLE}>会话状态</th>
            <th style={TH_STYLE}>操作</th>
          </tr>
        </thead>
        <tbody>
          {sessions.map((s) => (
            <tr
              key={s.sessionId}
              style={{ cursor: 'pointer' }}
              onMouseEnter={(e) => { e.currentTarget.style.background = '#fafafa'; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
            >
              <td style={TD_STYLE}>
                <span
                  style={{ ...MONO_STYLE, color: '#1677ff', cursor: 'pointer' }}
                  onClick={() => onViewSession(s)}
                >
                  {s.sessionId || '-'}
                </span>
              </td>
              <td style={TD_STYLE}>
                <span style={MONO_STYLE}>{s.userId || '-'}</span>
              </td>
              <td style={TD_STYLE}>{s.channel || '-'}</td>
              <td style={TD_STYLE}>{formatBeijingTime(s.time) || '-'}</td>
              <td style={TD_STYLE}>{formatDuration(s.duration)}</td>
              <td style={{ ...TD_STYLE, fontFamily: MONO_STYLE.fontFamily }}>{s.rounds ?? '-'}</td>
              <td style={TD_STYLE}>
                <span style={{ fontSize: 13 }}>
                  {(dedupAgents(s.agents) || []).join(' → ') || '-'}
                </span>
              </td>
              <td style={{ ...TD_STYLE, ...MONO_STYLE }}>
                {s.tokens != null ? Number(s.tokens).toLocaleString() : '-'}
              </td>
              <td style={TD_STYLE}>
                <StatusBadge status={s.status} />
              </td>
              <td style={TD_STYLE}>
                <span
                  onClick={(e) => { e.stopPropagation(); onViewSession(s); }}
                  style={{ color: '#1677ff', cursor: 'pointer', fontSize: 13 }}
                >
                  查看回放
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
      {intent || '-'}
    </span>
  );
}

function StatusBadge({ status }) {
  const meta = STATUS_META[status] || STATUS_META.completed;
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
      <span
        style={{
          width: 6,
          height: 6,
          borderRadius: '50%',
          display: 'inline-block',
          background: meta.color,
        }}
      />
      {meta.label}
    </span>
  );
}

function formatDuration(ms) {
  if (ms == null) return '-';
  if (typeof ms === 'string') return ms;
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  return `${m}m ${rs}s`;
}

function dedupAgents(agents) {
  if (!agents || agents.length === 0) return [];
  const result = [agents[0]];
  for (let i = 1; i < agents.length; i++) {
    if (agents[i] !== agents[i - 1]) {
      result.push(agents[i]);
    }
  }
  return result;
}


export default SessionTable;
