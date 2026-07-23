import React, { useState, useEffect, useCallback } from 'react';
import { fetchSessionDetail } from '../api/client';
import { on, openTraceDetail } from '../utils/nav';
import { formatBeijingTime } from '../utils/time';

/**
 * 会话回放组件（V23 B6 / 任务分解 M8）。
 *
 * 订阅 nav 总线 `session:replay` 事件，弹出会话回放面板；每个对话轮次的 trace 以超链呈现，
 * 点击调用 openTraceDetail(traceId) 打开链路详情（最多展示 6 处 trace 超链）。
 * 在 AppLayout 全局挂载一次即可响应 openSessionReplay 导航。
 */
function SessionReplay() {
  const [visible, setVisible] = useState(false);
  const [loading, setLoading] = useState(false);
  const [session, setSession] = useState(null);

  useEffect(() => {
    const off = on('session:replay', (e) => {
      const sessionId = e.detail && e.detail.sessionId;
      if (sessionId) openReplay(sessionId);
    });
    return off;
  }, []);

  const openReplay = useCallback(async (sessionId) => {
    setVisible(true);
    setLoading(true);
    setSession({ sessionId });
    try {
      const detail = await fetchSessionDetail(sessionId);
      setSession(detail || { sessionId });
    } catch (err) {
      setSession({ sessionId, error: err.message });
    } finally {
      setLoading(false);
    }
  }, []);

  const close = useCallback(() => {
    setVisible(false);
    setSession(null);
  }, []);

  // ESC 关闭
  useEffect(() => {
    if (!visible) return;
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [visible, close]);

  if (!visible) return null;

  const turns = session?.turns || [];
  // 最多 6 处 trace 超链
  const traceLinks = [];
  if (session?.traceId) traceLinks.push({ label: '完整链路', traceId: session.traceId });
  for (const t of turns) {
    if (t.traceId && traceLinks.length < 6) {
      traceLinks.push({ label: `第 ${t.turnIndex || '?'} 轮`, traceId: t.traceId });
    }
  }

  return (
    <div
      role="dialog"
      aria-label="会话回放"
      style={{
        position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
        background: 'rgba(0,0,0,.45)', zIndex: 1001,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
      onClick={(e) => { if (e.target === e.currentTarget) close(); }}
    >
      <div style={{
        background: '#fff', borderRadius: 8, width: '90%', maxWidth: 880, maxHeight: '85vh',
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
        boxShadow: '0 6px 16px rgba(0,0,0,.08), 0 3px 6px rgba(0,0,0,.12)',
      }}>
        <div style={{
          padding: '16px 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          borderBottom: '1px solid #f0f0f0',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ fontSize: 16, fontWeight: 600 }}>🔁 会话回放</span>
            <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, color: '#1677ff', background: '#e6f4ff', padding: '2px 8px', borderRadius: 4 }}>
              {session.sessionId}
            </span>
          </div>
          <span onClick={close} style={{ cursor: 'pointer', fontSize: 18, color: 'rgba(0,0,0,.65)', padding: '4px 8px', borderRadius: 4 }}>✕</span>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '20px 24px' }}>
          {loading && <div style={{ textAlign: 'center', padding: 40, color: 'rgba(0,0,0,.45)' }}>加载中…</div>}
          {session?.error && (
            <div style={{ padding: 10, background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 6, color: '#ff4d4f', fontSize: 12, marginBottom: 12 }}>
              会话详情加载失败：{session.error}
            </div>
          )}

          {/* 6 处 trace 超链 */}
          {traceLinks.length > 0 && (
            <div style={{ background: '#fff', borderRadius: 8, boxShadow: '0 1px 2px rgba(0,0,0,.03)', padding: 16, marginBottom: 16 }}>
              <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>链路入口（点击下钻 Trace 详情）</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {traceLinks.map((l, i) => (
                  <span
                    key={i}
                    onClick={() => openTraceDetail(l.traceId)}
                    style={{
                      fontFamily: '"JetBrains Mono", monospace', fontSize: 12,
                      padding: '4px 12px', borderRadius: 4, cursor: 'pointer',
                      background: '#e6f4ff', color: '#1677ff', border: '1px solid #91caff',
                    }}
                  >
                    🔗 {l.label}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* 对话轮次 */}
          {turns.map((t, idx) => (
            <div key={idx} style={{ background: '#fff', borderRadius: 8, boxShadow: '0 1px 2px rgba(0,0,0,.03)', padding: '14px 18px', marginBottom: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'rgba(0,0,0,.45)', marginBottom: 8 }}>
                <span>第 {t.turnIndex || idx + 1} 轮 · {t.time ? formatBeijingTime(t.time) : '-'}</span>
                <span>{t.intent || t.agentPath || ''}</span>
              </div>
              {t.userMessage && (
                <div style={{ fontSize: 13, marginBottom: 6 }}><b>用户：</b>{t.userMessage}</div>
              )}
              {t.aiMessage && (
                <div style={{ fontSize: 13 }}><b>AI：</b>{t.aiMessage}</div>
              )}
            </div>
          ))}

          {!loading && turns.length === 0 && !session?.error && (
            <div style={{ textAlign: 'center', color: 'rgba(0,0,0,.45)', padding: 24 }}>暂无对话轮次</div>
          )}
        </div>
      </div>
    </div>
  );
}

export default SessionReplay;
