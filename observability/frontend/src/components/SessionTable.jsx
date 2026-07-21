import React, { useState, useRef } from 'react';
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

// 列宽拖拽手柄样式（绝对定位于 th 右边缘）
const RESIZE_HANDLE_STYLE = {
  position: 'absolute',
  top: 0,
  right: 0,
  width: 5,
  height: '100%',
  cursor: 'col-resize',
  userSelect: 'none',
  zIndex: 1,
};

function SessionTable({ sessions = [], onViewSession, loading }) {
  // ── 列宽可拖拽调整（宽度本地持久化）──
  const DEFAULT_WIDTHS = { 0: 200, 1: 130, 2: 100, 3: 170, 4: 90, 5: 80, 6: 180, 7: 110, 8: 100, 9: 90 };
  const [widths, setWidths] = useState(() => {
    const merged = { ...DEFAULT_WIDTHS };
    try {
      const saved = localStorage.getItem('colWidths:sessionTable');
      if (saved) Object.assign(merged, JSON.parse(saved));
    } catch (e) { /* ignore malformed storage */ }
    return merged;
  });
  const widthsRef = useRef(widths);
  widthsRef.current = widths;

  const startResize = (e, colIndex) => {
    e.preventDefault();
    e.stopPropagation();
    const startX = e.clientX;
    const startW = widthsRef.current[colIndex] || 120;
    const onMove = (ev) => {
      const newW = Math.max(60, startW + (ev.clientX - startX));
      setWidths((prev) => {
        const next = { ...prev, [colIndex]: newW };
        widthsRef.current = next;
        return next;
      });
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      try {
        localStorage.setItem('colWidths:sessionTable', JSON.stringify(widthsRef.current));
      } catch (e) { /* ignore quota errors */ }
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  const totalWidth = Object.keys(widths).reduce((sum, k) => sum + (widths[k] || 0), 0);

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
      <table style={{ width: '100%', tableLayout: 'fixed', minWidth: `${totalWidth}px`, borderCollapse: 'collapse', fontSize: 13 }}>
        <thead>
          <tr>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[0] }}>Session ID<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 0)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[1] }}>User ID<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 1)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[2] }}>渠道<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 2)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[3] }}>时间<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 3)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[4] }}>时长<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 4)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[5] }}>轮次<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 5)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[6] }}>执行智能体<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 6)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[7] }}>Token<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 7)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[8] }}>会话状态<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 8)} /></th>
            <th style={{ ...TH_STYLE, position: 'relative', width: widths[9] }}>操作<span style={RESIZE_HANDLE_STYLE} onMouseDown={(e) => startResize(e, 9)} /></th>
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
