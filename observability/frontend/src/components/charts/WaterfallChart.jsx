import React from 'react';
import useECharts from '../../hooks/useECharts';

/**
 * WaterfallChart — Span 瀑布图
 *
 * 参照 dashboard-v15 waterfall:
 * - 水平条，颜色编码（HTTP灰/Agent蓝/LLM紫/Tool青）
 * - TTFT 黄色虚线标记
 *
 * Props:
 * - spans: { label, type: 'http'|'agent'|'llm'|'tool', startMs, durationMs }[]
 * - ttftMs: TTFT 时间点
 * - totalMs: 总耗时
 */
function WaterfallChart({ spans = [], ttftMs, totalMs }) {
  if (!spans || spans.length === 0) {
    return (
      <div style={{ padding: 16, textAlign: 'center', color: 'rgba(0,0,0,.45)', fontSize: 12 }}>
        暂无 Span 数据
      </div>
    );
  }

  // Sort spans by startMs ascending; spans with 0/undefined startMs go first
  const sortedSpans = [...spans].sort((a, b) => (a.startMs || 0) - (b.startMs || 0));

  const maxMs = totalMs || Math.max(...sortedSpans.map(s => (s.startMs || 0) + (s.durationMs || 0)));
  const layerColors = {
    'L0': '#1677ff',
    'L1-LLM1': '#722ed1',
    'L1-LLM2': '#722ed1',
    'L1': '#722ed1',
    'L2': '#52c41a',
    'HTTP': '#8c8c8c',
  };

  // TTFT 以「条形区」（flex:1，右移 180px 标签列 + 16px padding）为坐标基准，
  // 与下方 span 条 left 算法一致；ttftFraction ∈ [0,1] 用于 calc 精确对齐。
  const ttftFraction = ttftMs != null && maxMs > 0 ? ttftMs / maxMs : null;
  const ttftPct = ttftFraction != null ? ttftFraction * 100 : null;

  return (
    <div
      style={{
        background: '#fafafa',
        borderRadius: 6,
        padding: 16,
        position: 'relative',
      }}
    >
      {/* TTFT 虚线标记：竖直虚线定位到「首 Token」时间点在时间轴上的像素位置。
          关键修复（Bug 1）：此前 left 用百分比相对「整个容器（含 180px 标签列）」，
          而 span 条在 flex:1 区（右移约 196px），导致虚线整体偏左约 192px。
          现改用 CSS calc 将虚线放入与 span 条相同的坐标系：
          左边界 = padding(16px) + 标签列(180px) = 196px；
          可用宽度 = 100% - 16px(right padding) - 196px = (100% - 212px)。 */}
      {ttftPct != null && ttftFraction != null && (
        <div
          style={{
            position: 'absolute',
            top: 8,
            bottom: 8,
            left: `calc(196px + (100% - 212px) * ${ttftFraction})`,
            borderLeft: '2px dashed #faad14',
            zIndex: 2,
            pointerEvents: 'none',
          }}
        >
          <span
            style={{
              position: 'absolute',
              top: -2,
              left: 5,
              color: '#faad14',
              fontWeight: 700,
              fontSize: 11,
              fontFamily: '"JetBrains Mono", monospace',
              background: '#fafafa',
              padding: '2px 5px',
              borderRadius: 3,
              whiteSpace: 'nowrap',
            }}
          >
            TTFT {ttftMs}ms
          </span>
        </div>
      )}

      {/* E2E 总耗时条（占满整条时间轴） */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          height: 30,
          marginBottom: 4,
        }}
      >
        <span
          style={{
            width: 180,
            fontSize: 12,
            fontWeight: 600,
            color: 'rgba(0,0,0,.85)',
            paddingRight: 12,
            textAlign: 'right',
            flexShrink: 0,
            boxSizing: 'border-box',
          }}
        >
          E2E 总耗时
        </span>
        <div style={{ flex: 1, position: 'relative', height: 18 }}>
          <div
            style={{
              position: 'absolute',
              left: '0%',
              height: 18,
              borderRadius: 3,
              background: '#262626',
              width: '100%',
              display: 'inline-flex',
              alignItems: 'center',
              padding: '0 6px',
              fontSize: 10,
              color: '#fff',
              whiteSpace: 'nowrap',
            }}
          >
            {Math.round(maxMs)}ms
          </div>
        </div>
      </div>

      {sortedSpans.map((span, idx) => {
        const startPct = ((span.startOffsetMs || span.startMs || 0) / maxMs) * 100;
        const widthPct = ((span.durationMs || 0) / maxMs) * 100;
        const color = span.color || layerColors[span.layer] || '#1677ff';

        return (
          <div
            key={idx}
            style={{
              display: 'flex',
              alignItems: 'center',
              height: 30,
              marginBottom: 2,
            }}
          >
            {/* 标签 */}
            <span
              style={{
                width: 180,
                fontSize: 12,
                color: 'rgba(0,0,0,.65)',
            paddingRight: 12,
            textAlign: 'right',
            flexShrink: 0,
            boxSizing: 'border-box',
          }}
        >
          {span.label || span.operationName || '-'}
            </span>

            {/* 条形 */}
            <div style={{ flex: 1, position: 'relative', height: 18 }}>
              <div
                style={{
                  position: 'absolute',
                  left: `${startPct}%`,
                  display: 'inline-flex',
                  alignItems: 'center',
                  height: 18,
                  borderRadius: 3,
                  padding: '0 6px',
                  fontSize: 10,
                  color: '#fff',
                  whiteSpace: 'nowrap',
                  background: color,
                  width: `${Math.max(widthPct, 2)}%`,
                  minWidth: 30,
                }}
              >
                {span.durationMs}ms
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

export default WaterfallChart;
