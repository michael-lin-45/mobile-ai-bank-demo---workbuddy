import React from 'react';
import { Input, Select, Space } from 'antd';

/**
 * 全局筛选栏 — 意图 / 时间 / TraceID 搜索
 *
 * P0 仅 UI 框架，筛选逻辑推迟到 P1
 *
 * Props:
 * - onIntentChange: 意图变更回调
 * - onSearch: 搜索回调
 */
function GlobalFilter({ onIntentChange, onSearch }) {
  const intents = ['全部', 'TRANSFER', 'BILL_QUERY', 'WEALTH_CONSULT', 'WEALTH_INTERPRET', 'CHAT', 'ERROR'];

  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '8px 24px',
      background: '#0f141e',
      borderBottom: '1px solid #1e293b',
      overflowX: 'auto',
      flexShrink: 0,
    }}>
      <span style={{ fontSize: 11, color: '#57637c', fontWeight: 600, marginRight: 4 }}>
        筛选:
      </span>
      {intents.map((intent, i) => (
        <div
          key={intent}
          onClick={() => onIntentChange?.(intent === '全部' ? null : intent)}
          style={{
            padding: '6px 12px',
            borderRadius: 20,
            fontSize: 12,
            fontWeight: 500,
            background: i === 0 ? 'rgba(59,108,246,0.18)' : '#161c2a',
            color: i === 0 ? '#3b6cf6' : '#8896b0',
            border: `1px solid ${i === 0 ? 'rgba(59,108,246,0.3)' : '#1e293b'}`,
            cursor: 'pointer',
            whiteSpace: 'nowrap',
            transition: 'all 180ms',
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.borderColor = '#57637c';
            e.currentTarget.style.color = '#e8edf4';
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.borderColor = i === 0 ? 'rgba(59,108,246,0.3)' : '#1e293b';
            e.currentTarget.style.color = i === 0 ? '#3b6cf6' : '#8896b0';
          }}
        >
          {intent}
        </div>
      ))}
      <span style={{ width: 1, height: 20, background: '#1e293b', margin: '0 4px' }} />
      <div style={{
        marginLeft: 'auto',
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        padding: '6px 12px',
        borderRadius: 20,
        background: '#161c2a',
        border: '1px solid #1e293b',
      }}>
        <span style={{ fontSize: 12, color: '#57637c' }}>🔎</span>
        <Input
          placeholder="搜索 TraceID / 会话 / 用户…"
          bordered={false}
          style={{
            width: 160,
            fontSize: 12,
            fontFamily: "'JetBrains Mono', monospace",
            color: '#e8edf4',
            padding: 0,
          }}
          onChange={(e) => onSearch?.(e.target.value)}
        />
      </div>
    </div>
  );
}

export default GlobalFilter;
