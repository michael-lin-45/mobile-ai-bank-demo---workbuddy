import React from 'react';

/**
 * LLM IO 面板 — Prompt / Response / Token 分布条
 *
 * Props:
 * - prompt: Input prompt 文本
 * - response: LLM 响应文本
 * - tokenBreakdown: { systemTokens, contextTokens, outputTokens }
 */
function IOPanel({ prompt, response, tokenBreakdown }) {
  const totalTokens = tokenBreakdown
    ? tokenBreakdown.systemTokens + tokenBreakdown.contextTokens + tokenBreakdown.outputTokens
    : 0;

  const systemPct = tokenBreakdown && totalTokens > 0
    ? (tokenBreakdown.systemTokens / totalTokens) * 100 : 0;
  const contextPct = tokenBreakdown && totalTokens > 0
    ? (tokenBreakdown.contextTokens / totalTokens) * 100 : 0;
  const outputPct = tokenBreakdown && totalTokens > 0
    ? (tokenBreakdown.outputTokens / totalTokens) * 100 : 0;

  return (
    <div style={{
      background: '#0b1018',
      borderRadius: 6,
      margin: '0 0 12px',
      padding: '14px 16px',
      border: '1px solid rgba(6,182,212,0.25)',
      borderLeft: '3px solid #06b6d4',
    }}>
      {/* Token 分布条 */}
      {tokenBreakdown && totalTokens > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div style={{ display: 'flex', gap: 14, fontSize: 10, color: '#57637c', marginBottom: 8, alignItems: 'center' }}>
            <span>
              <span style={{ display: 'inline-block', width: 6, height: 6, borderRadius: '50%', background: '#a78bfa' }} />
              {' '}System {tokenBreakdown.systemTokens}
            </span>
            <span>
              <span style={{ display: 'inline-block', width: 6, height: 6, borderRadius: '50%', background: '#06b6d4' }} />
              {' '}Context {tokenBreakdown.contextTokens}
            </span>
            <span>
              <span style={{ display: 'inline-block', width: 6, height: 6, borderRadius: '50%', background: '#10b981' }} />
              {' '}Output {tokenBreakdown.outputTokens}
            </span>
            <span style={{ marginLeft: 'auto', fontFamily: "'JetBrains Mono', monospace" }}>
              total {totalTokens}
            </span>
          </div>
          <div style={{ display: 'flex', height: 4, borderRadius: 2, overflow: 'hidden', background: '#1c2435' }}>
            <div style={{ width: `${systemPct}%`, background: '#a78bfa' }} />
            <div style={{ width: `${contextPct}%`, background: '#06b6d4' }} />
            <div style={{ width: `${outputPct}%`, background: '#10b981' }} />
          </div>
        </div>
      )}

      {/* Prompt */}
      {prompt && (
        <div style={{ marginBottom: 14 }}>
          <div style={{
            fontSize: 10,
            fontWeight: 600,
            textTransform: 'uppercase',
            letterSpacing: '0.06em',
            color: '#06b6d4',
            marginBottom: 6,
          }}>
            INPUT — Prompt
          </div>
          <div style={{
            background: '#060a10',
            border: '1px solid rgba(6,182,212,0.15)',
            borderRadius: 4,
            padding: '10px 12px',
            fontFamily: "'JetBrains Mono', monospace",
            fontSize: 11,
            lineHeight: 1.65,
            color: '#c0ccd8',
            maxHeight: 260,
            overflowY: 'auto',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
          }}>
            {truncate(prompt, 3000)}
          </div>
        </div>
      )}

      {/* Response */}
      {response && (
        <div>
          <div style={{
            fontSize: 10,
            fontWeight: 600,
            textTransform: 'uppercase',
            letterSpacing: '0.06em',
            color: '#06b6d4',
            marginBottom: 6,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}>
            OUTPUT — Response
            {tokenBreakdown?.outputTokens > 0 && (
              <span style={{
                fontSize: 9,
                padding: '1px 6px',
                borderRadius: 3,
                fontFamily: "'JetBrains Mono', monospace",
                background: 'rgba(6,182,212,0.12)',
                color: '#06b6d4',
              }}>
                {tokenBreakdown.outputTokens} tokens
              </span>
            )}
          </div>
          <div style={{
            background: '#060a10',
            border: '1px solid rgba(6,182,212,0.15)',
            borderRadius: 4,
            padding: '10px 12px',
            fontFamily: "'JetBrains Mono', monospace",
            fontSize: 11,
            lineHeight: 1.65,
            color: '#c0ccd8',
            maxHeight: 260,
            overflowY: 'auto',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
          }}>
            {truncate(response, 3000)}
          </div>
        </div>
      )}

      {!prompt && !response && (
        <div style={{ fontSize: 12, color: '#57637c' }}>
          No IO data available for this span.
        </div>
      )}
    </div>
  );
}

function truncate(text, maxLen) {
  if (!text || text.length <= maxLen) return text;
  return text.substring(0, maxLen) + '\n\n[...truncated]';
}

export default IOPanel;
