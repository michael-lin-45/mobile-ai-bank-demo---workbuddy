import React, { useState } from 'react';
import ChatBubble from './ChatBubble';
import { submitSatisfaction } from '../api/client';

/**
 * SessionDetailModal — 全屏会话回放 Modal
 *
 * 参照 dashboard-v15 session-modal:
 * - 头部: Session ID + 状态 Badge + 元信息
 * - 对话轮次: Turn Header + 用户消息 + AI 回复 + 元信息
 * - 会话总结: 意图流 + 域切换 + 总耗时 + 总 Token
 * - 底部: 👍/👎 满意度按钮（真正调用 POST /api/v1/ai/satisfaction）
 *
 * Props:
 * - session: 会话详情对象
 * - visible: boolean
 * - onClose: () => void
 * - onTraceClick: (traceId) => void
 * - onSatisfaction: (rating) => void
 * - satisfactionDisabled: boolean
 */
function SessionDetailModal({
  session,
  visible,
  onClose,
  onTraceClick,
  onSatisfaction,
  satisfactionDisabled = false,
}) {
  const [submittingRating, setSubmittingRating] = useState(false);
  const [submittedRating, setSubmittedRating] = useState(null);

  if (!visible || !session) return null;

  const statusMeta = getStatusMeta(session.status);
  const turns = session.turns || [];

  /** 真正调用后端满意度 API */
  const handleSatisfaction = async (rating) => {
    if (submittingRating) return;

    setSubmittingRating(true);
    try {
      await submitSatisfaction({
        sessionId: session.sessionId,
        rating: rating === 'like' ? 'satisfied' : 'unsatisfied',
        reason: rating === 'like' ? '用户满意' : '用户不满意',
      });
      setSubmittedRating(rating);
      // 同时回调外部 handler
      if (onSatisfaction) onSatisfaction(rating);
    } catch (err) {
      console.error('Failed to submit satisfaction:', err);
      // 即使失败也标记已提交（避免重复点击加重后端压力）
      setSubmittedRating(rating);
    } finally {
      setSubmittingRating(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-label="会话回放"
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        background: 'rgba(0,0,0,.45)',
        zIndex: 1000,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        style={{
          background: '#fff',
          borderRadius: 8,
          width: '90%',
          maxWidth: 960,
          maxHeight: '85vh',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          boxShadow: '0 6px 16px rgba(0,0,0,.08), 0 3px 6px rgba(0,0,0,.12)',
        }}
      >
        {/* Modal 头部 */}
        <div
          style={{
            padding: '16px 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <span style={{ fontSize: 16, fontWeight: 600 }}>💬 会话回放</span>
          <span
            onClick={onClose}
            style={{
              cursor: 'pointer',
              fontSize: 18,
              color: 'rgba(0,0,0,.65)',
              padding: '4px 8px',
              borderRadius: 4,
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = '#f5f5f5'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
          >
            ✕
          </span>
        </div>

        {/* Modal 主体 */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px 24px' }}>
          {/* ── 会话头部 ── */}
          <div
            style={{
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 1px 2px rgba(0,0,0,.03), 0 1px 6px -1px rgba(0,0,0,.02)',
              padding: '20px 24px',
              marginBottom: 16,
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'flex-start',
              flexWrap: 'wrap',
              gap: 16,
            }}
          >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {/* Session ID + 状态 */}
              <div style={{ fontSize: 18, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontFamily: '"JetBrains Mono", monospace' }}>
                  {session.sessionId || '-'}
                </span>
                <StatusBadge status={session.status} />
              </div>

              {/* 元信息 */}
              <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap', marginTop: 8 }}>
                <MetaItem icon="👤" label={session.userId} />
                <MetaItem icon="📱" label={session.channel} />
                <MetaItem icon="🕐" label={session.timeRange} />
                <MetaItem icon="⏱" label={session.durationText} bold />
                <MetaItem icon="🔄" label={`${turns.length} 轮`} />
                {session.domainSwitches > 0 && (
                  <MetaItem icon="🔀" label={`${session.domainSwitches}次域切换`} />
                )}
                <MetaItem icon="🪙" label={`${(session.tokens || 0).toLocaleString()} tokens`} />
              </div>
            </div>

            {/* 操作按钮 */}
            <div style={{ display: 'flex', gap: 8 }}>
              {onTraceClick && (
                <button
                  onClick={() => onTraceClick(session.traceId)}
                  style={{
                    padding: '6px 16px',
                    background: '#1677ff',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 6,
                    fontSize: 13,
                    cursor: 'pointer',
                  }}
                >
                  查看完整链路 →
                </button>
              )}
            </div>
          </div>

          {/* ── 对话轮次 ── */}
          {turns.map((turn, idx) => {
            const turnStatusMeta = getStatusMeta(turn.status);
            return (
              <div
                key={idx}
                style={{
                  background: '#fff',
                  borderRadius: 8,
                  boxShadow: '0 1px 2px rgba(0,0,0,.03)',
                  marginBottom: 16,
                  overflow: 'hidden',
                }}
              >
                {/* Turn Header */}
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '10px 20px',
                    background: '#fafafa',
                    borderBottom: '1px solid #f0f0f0',
                    fontSize: 12,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'rgba(0,0,0,.65)' }}>
                    <span
                      style={{
                        width: 22,
                        height: 22,
                        borderRadius: '50%',
                        background: '#1677ff',
                        color: '#fff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: 11,
                        fontWeight: 600,
                      }}
                    >
                      {idx + 1}
                    </span>
                    <span>{turn.time}</span>
                    <IntentBadge intent={turn.intent} />
                  </div>
                  <div style={{ color: 'rgba(0,0,0,.45)' }}>
                    {turn.agentPath} · 置信度 {turn.confidence} · 耗时 {turn.duration}
                    {turn.status && (
                      <span style={{ marginLeft: 6 }}>
                        <TurnStatusBadge status={turn.status} />
                      </span>
                    )}
                  </div>
                </div>

                {/* Turn Body */}
                <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {/* REROUTE 标记 */}
                  {turn.status === 'REROUTE' && (
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        padding: '6px 16px',
                        background: '#fffbe6',
                        borderRadius: 6,
                        fontSize: 12,
                        color: '#faad14',
                        border: '1px dashed #ffe58f',
                      }}
                    >
                      ⚠ REROUTE — 低置信度触发二次识别
                    </div>
                  )}

                  {/* 用户消息 */}
                  {turn.userMessage && (
                    <ChatBubble
                      role="user"
                      content={turn.userMessage}
                    />
                  )}

                  {/* AI 回复 */}
                  {turn.aiMessage && (
                    <ChatBubble
                      role="ai"
                      content={turn.aiMessage}
                      meta={{
                        duration: turn.duration,
                        tokens: turn.tokens ? `${turn.tokens} tokens` : null,
                        model: turn.model,
                        params: turn.params,
                        traceId: turn.traceId,
                        onTraceClick: onTraceClick,
                      }}
                    />
                  )}
                </div>
              </div>
            );
          })}

          {turns.length === 0 && (
            <div style={{ textAlign: 'center', color: 'rgba(0,0,0,.45)', padding: 24 }}>
              暂无对话轮次
            </div>
          )}

          {/* ── 会话总结 ── */}
          {session.summary && (
            <div
              style={{
                display: 'flex',
                gap: 24,
                padding: '16px 20px',
                background: '#fafafa',
                borderTop: '1px solid #f0f0f0',
                borderRadius: 8,
                fontSize: 13,
              }}
            >
              <SummaryItem label="意图流" value={session.summary.intentFlow} bold />
              <SummaryItem label="域切换" value={`${session.summary.domainSwitches || 0} 次`} />
              <SummaryItem label="总耗时" value={session.summary.totalDuration} bold />
              <SummaryItem label="总 Token" value={(session.summary.totalTokens || 0).toLocaleString()} bold />
            </div>
          )}

          {/* ── 满意度入口 ── */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 16,
              padding: '16px 0',
              borderTop: '1px solid #f0f0f0',
              marginTop: 16,
            }}
          >
            {submittedRating ? (
              <span style={{ fontSize: 13, color: '#52c41a', fontWeight: 500 }}>
                ✅ 感谢反馈！评价已提交
              </span>
            ) : (
              <>
                <span style={{ fontSize: 13, color: 'rgba(0,0,0,.65)' }}>本次会话有帮助吗？</span>
                <button
                  onClick={() => handleSatisfaction('like')}
                  disabled={submittingRating}
                  style={{
                    padding: '8px 20px',
                    borderRadius: 6,
                    border: '1px solid #d9d9d9',
                    background: '#fff',
                    cursor: submittingRating ? 'not-allowed' : 'pointer',
                    fontSize: 18,
                    opacity: submittingRating ? 0.5 : 1,
                    transition: 'all .15s',
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.borderColor = '#1677ff';
                    e.currentTarget.style.color = '#1677ff';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.borderColor = '#d9d9d9';
                    e.currentTarget.style.color = '';
                  }}
                >
                  👍
                </button>
                <button
                  onClick={() => handleSatisfaction('dislike')}
                  disabled={submittingRating}
                  style={{
                    padding: '8px 20px',
                    borderRadius: 6,
                    border: '1px solid #d9d9d9',
                    background: '#fff',
                    cursor: submittingRating ? 'not-allowed' : 'pointer',
                    fontSize: 18,
                    opacity: submittingRating ? 0.5 : 1,
                    transition: 'all .15s',
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.borderColor = '#ff4d4f';
                    e.currentTarget.style.color = '#ff4d4f';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.borderColor = '#d9d9d9';
                    e.currentTarget.style.color = '';
                  }}
                >
                  👎
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ── 辅助组件 ── */

function StatusBadge({ status }) {
  const meta = getStatusMeta(status);
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

function getStatusMeta(status) {
  const map = {
    completed: { label: '已完成', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
    active: { label: '进行中', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
    error: { label: '已失败', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
    slow: { label: '偏慢', color: '#faad14', bg: '#fffbe6', border: '#ffe58f' },
  };
  return map[status] || { label: status || '未知', color: '#8c8c8c', bg: '#f5f5f5', border: '#d9d9d9' };
}

function TurnStatusBadge({ status }) {
  const map = {
    REROUTE: { bg: '#fffbe6', color: '#faad14', border: '#ffe58f', label: 'REROUTE' },
    '参数追问': { bg: '#fffbe6', color: '#faad14', border: '#ffe58f', label: '参数追问' },
    '执行成功': { bg: '#f6ffed', color: '#52c41a', border: '#b7eb8f', label: '执行成功' },
  };
  const meta = map[status] || { bg: '#f5f5f5', color: 'rgba(0,0,0,.65)', border: '#d9d9d9', label: status };
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '2px 8px',
        borderRadius: 4,
        fontSize: 11,
        fontWeight: 500,
        background: meta.bg,
        color: meta.color,
        border: `1px solid ${meta.border}`,
      }}
    >
      {meta.label}
    </span>
  );
}

function IntentBadge({ intent }) {
  const map = {
    TRANSFER: { bg: '#f9f0ff', color: '#722ed1', border: '#d3adf7' },
    WEALTH: { bg: '#f9f0ff', color: '#722ed1', border: '#d3adf7' },
    BILL_QUERY: { bg: '#e6f4ff', color: '#1677ff', border: '#91caff' },
    CHAT: { bg: '#e6f4ff', color: '#1677ff', border: '#91caff' },
    FOLLOW_UP: { bg: '#f6ffed', color: '#52c41a', border: '#b7eb8f' },
  };
  const meta = map[intent] || { bg: '#f5f5f5', color: 'rgba(0,0,0,.65)', border: '#d9d9d9' };
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

function MetaItem({ icon, label, bold = false }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'rgba(0,0,0,.65)' }}>
      <span>{icon}</span>
      {bold ? <strong style={{ color: 'rgba(0,0,0,.88)' }}>{label}</strong> : <span>{label}</span>}
    </div>
  );
}

function SummaryItem({ label, value, bold = false }) {
  return (
    <div>
      <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 12 }}>{label}</span>
      <span style={{ fontWeight: bold ? 600 : 400, marginLeft: 6 }}>{value}</span>
    </div>
  );
}

export default SessionDetailModal;
