import React from 'react';

/**
 * IOCards — 输入/输出双色卡片
 *
 * 参照 dashboard-v15:
 * - 左侧：蓝底蓝左边框 = 输入
 * - 右侧：绿底绿右边框 = 输出
 * - 中间：32px 白底 + 圆形箭头
 *
 * Props:
 * - input: { label, content }
 * - output: { label, content }
 */
function IOCards({ input, output }) {
  return (
    <div
      style={{
        display: 'flex',
        gap: 0,
        alignItems: 'stretch',
        marginBottom: 12,
        borderRadius: 6,
        overflow: 'hidden',
        boxShadow: '0 1px 3px rgba(0,0,0,.06)',
      }}
    >
      {/* 输入卡片 */}
      <div
        style={{
          flex: 1,
          minWidth: 0,
          background: '#f6f8ff',
          borderLeft: '3px solid #1677ff',
          padding: '12px 16px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
          <span style={{ fontSize: 14 }}>📥</span>
          <span
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: '#1677ff',
              textTransform: 'uppercase',
              letterSpacing: '.4px',
            }}
          >
            {input?.label || '原始输入'}
          </span>
        </div>
        <div
          style={{
            fontSize: 13,
            color: '#1a1a1a',
            fontWeight: 500,
            wordBreak: 'break-all',
            lineHeight: 1.5,
          }}
        >
          {input?.content || '-'}
        </div>
      </div>

      {/* 中间箭头 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 32,
          background: '#fff',
          flexShrink: 0,
        }}
      >
        <div
          style={{
            width: 22,
            height: 22,
            border: '1px solid #e5e5e5',
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
            <path
              d="M3 8h10M10 5l3 3-3 3"
              stroke="#999"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </div>
      </div>

      {/* 输出卡片 */}
      <div
        style={{
          flex: 1,
          minWidth: 0,
          background: '#f6fff6',
          borderRight: '3px solid #52c41a',
          padding: '12px 16px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
          <span style={{ fontSize: 14 }}>📤</span>
          <span
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: '#52c41a',
              textTransform: 'uppercase',
              letterSpacing: '.4px',
            }}
          >
            {output?.label || '最终输出'}
          </span>
        </div>
        <div
          style={{
            fontSize: 13,
            color: '#1a1a1a',
            fontWeight: 500,
            wordBreak: 'break-all',
            lineHeight: 1.5,
          }}
        >
          {output?.content || '-'}
        </div>
      </div>
    </div>
  );
}

export default IOCards;
