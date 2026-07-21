import React, { useEffect } from 'react';
import IOCards from './IOCards';
import SpanTree from './SpanTree';
import WaterfallChart from './charts/WaterfallChart';

/**
 * TraceDetailModal — 全屏 Trace 详情 Modal
 *
 * 参照 dashboard-v15 trace-detail-modal:
 * - (1) 输入/输出双色卡片（IOCards）
 * - (2) Agent 链路详情树（SpanTree）
 * - (3) Span 瀑布图（WaterfallChart）
 *
 * Props:
 * - trace: trace 详情对象
 * - visible: boolean
 * - onClose: () => void
 */
function TraceDetailModal({ trace, visible, onClose }) {
  // ESC 关闭弹窗（兼容遮罩/✕ 之外的键盘关闭）
  useEffect(() => {
    if (!visible) return;
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [visible, onClose]);

  if (!visible || !trace) return null;

  // IOCards: try multiple field name conventions from backend
  // Prefer ioInput/ioOutput objects, fallback to input/output, then inputText/outputText
  const rawInput = trace.ioInput || trace.input || { content: trace.inputText || '' };
  const rawOutput = trace.ioOutput || trace.output || { content: trace.outputText || '' };
  const input = {
    label: rawInput.label || '用户原始请求',
    content: rawInput.content || '-',
  };
  const output = {
    label: rawOutput.label || '最终输出',
    content: rawOutput.content || '-',
  };

  // Build waterfall spans from real data only
  const waterfallSpans = buildWaterfallSpans(trace);

  return (
    <div
      role="dialog"
      aria-label="Trace 详情"
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontSize: 16, fontWeight: 600 }}>🔗 Trace 详情</span>
            {trace.traceId && (
              <span style={{
                fontFamily: '"JetBrains Mono", monospace',
                fontSize: 12,
                color: '#1677ff',
                background: '#e6f4ff',
                padding: '2px 8px',
                borderRadius: 4,
              }} title={trace.traceId}>
                {trace.traceId}
              </span>
            )}
            {trace.status && (
              <StatusBadge status={trace.status} />
            )}
          </div>
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
          {/* (1) 输入/输出双色卡片 */}
          <div
            style={{
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 1px 2px rgba(0,0,0,.03)',
              padding: 20,
              marginBottom: 16,
            }}
          >
            <div style={{
              fontSize: 13,
              fontWeight: 600,
              marginBottom: 12,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}>
              <span>请求概要</span>
              <span style={{ fontSize: 11, color: 'rgba(0,0,0,.45)' }}>
                {trace.agentChain || '-'}
              </span>
            </div>
            <IOCards input={input} output={output} />
          </div>

          {/* (2) Agent 链路详情树 */}
          <div
            style={{
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 1px 2px rgba(0,0,0,.03)',
              padding: 20,
              marginBottom: 16,
            }}
          >
            <div style={{
              fontSize: 13,
              fontWeight: 600,
              marginBottom: 12,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}>
              <span>Agent 链路详情</span>
              <span style={{ fontSize: 11, color: 'rgba(0,0,0,.45)' }}>
                {trace.agentChain || '-'}
              </span>
            </div>
            <div style={{ padding: '12px 0' }}>
              {trace.spanTree && trace.spanTree.length > 0 ? (
                <SpanTree
                  nodes={trace.spanTree}
                  reRouted={trace.reRouted}
                  reRoutePath={trace.reRoutePath}
                />
              ) : (
                <div style={{
                  textAlign: 'center',
                  padding: '40px 0',
                  color: 'rgba(0,0,0,.25)',
                  fontSize: 13,
                }}>
                  暂无 Span 数据
                </div>
              )}
            </div>
          </div>

          {/* (3) Span 瀑布图 */}
          <div
            style={{
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 1px 2px rgba(0,0,0,.03)',
              padding: 20,
              marginBottom: 16,
            }}
          >
            <div style={{
              fontSize: 13,
              fontWeight: 600,
              marginBottom: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}>
              <span>Span 瀑布图</span>
              {trace.ttft != null && (
                <span style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  padding: '2px 8px',
                  borderRadius: 4,
                  fontSize: 10,
                  fontWeight: 500,
                  background: '#fffbe6',
                  color: '#faad14',
                  border: '1px solid #ffe58f',
                }}>
                  TTFT {trace.ttft}ms
                </span>
              )}
            </div>
            <WaterfallChart
              spans={waterfallSpans}
              ttftMs={trace.ttft}
              totalMs={trace.durationMs || trace.totalDurationMs}
            />
          </div>
        </div>
      </div>
    </div>
  );
}

function StatusBadge({ status }) {
  const map = {
    OK: { label: '正常', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
    ERR: { label: '异常', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
    SLOW: { label: '偏慢', color: '#faad14', bg: '#fffbe6', border: '#ffe58f' },
    ERROR: { label: '异常', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
  };
  const meta = map[status] || { label: status || '未知', color: '#8c8c8c', bg: '#f5f5f5', border: '#d9d9d9' };
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

/**
 * Build waterfall spans from trace data.
 * Only uses real data; returns empty array when no waterfall data is available.
 */
function buildWaterfallSpans(trace) {
  if (trace.waterfallSpans && trace.waterfallSpans.length > 0) {
    return trace.waterfallSpans;
  }
  // No real waterfall data — return empty; WaterfallChart should handle empty state
  return [];
}

export default TraceDetailModal;
