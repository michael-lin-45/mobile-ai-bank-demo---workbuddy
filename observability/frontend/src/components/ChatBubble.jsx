import React from 'react';

/**
 * ChatBubble — 对话气泡组件
 *
 * 参照 dashboard-v15:
 * - bubble-user: #e6f4ff (蓝色)
 * - bubble-ai:   #f6ffed (绿色)
 * - 用户名 + 头像
 * - 消息元信息（耗时/Token/模型/参数提取/Trace链接）
 *
 * Props:
 * - role: 'user' | 'ai'
 * - content: 消息内容
 * - meta: { duration, tokens, model, params, traceId, onTraceClick }
 */
function ChatBubble({ role, content, meta }) {
  const isUser = role === 'user';
  const bubbleBg = isUser ? '#e6f4ff' : '#f6ffed';
  const avatarBg = isUser ? '#e6f4ff' : '#f6ffed';
  const avatarColor = isUser ? '#1677ff' : '#52c41a';
  const avatarIcon = isUser ? '👤' : '🤖';

  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
      {/* 头像 */}
      <div
        style={{
          width: 32,
          height: 32,
          borderRadius: '50%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: 14,
          flexShrink: 0,
          background: avatarBg,
          color: avatarColor,
        }}
      >
        {avatarIcon}
      </div>

      {/* 消息内容 */}
      <div style={{ flex: 1 }}>
        {/* 角色标签 */}
        <div
          style={{
            fontSize: 12,
            color: 'rgba(0,0,0,.45)',
            marginBottom: 4,
          }}
        >
          {isUser ? '用户' : 'AI 助手'}
        </div>

        {/* 气泡 */}
        <div
          style={{
            padding: '10px 14px',
            borderRadius: '0 8px 8px 8px',
            fontSize: 13,
            lineHeight: 1.6,
            maxWidth: '80%',
            background: bubbleBg,
            color: 'rgba(0,0,0,.88)',
          }}
        >
          {content}
        </div>

        {/* 元信息 */}
        {meta && (
          <div
            style={{
              display: 'flex',
              gap: 16,
              flexWrap: 'wrap',
              marginTop: 4,
              fontSize: 11,
              color: 'rgba(0,0,0,.45)',
            }}
          >
            {meta.duration && (
              <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                ⏱ {meta.duration}
              </span>
            )}
            {meta.tokens && (
              <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                🪙 {meta.tokens}
              </span>
            )}
            {meta.model && (
              <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                🧠 {meta.model}
              </span>
            )}
            {meta.params && (
              <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                🎯 {meta.params}
              </span>
            )}
            {meta.traceId && (
              <span
                onClick={() => meta.onTraceClick && meta.onTraceClick(meta.traceId)}
                style={{
                  color: '#1677ff',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 4,
                }}
              >
                🔗 trace: {meta.traceId.substring(0, 8)}…
              </span>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export default ChatBubble;
