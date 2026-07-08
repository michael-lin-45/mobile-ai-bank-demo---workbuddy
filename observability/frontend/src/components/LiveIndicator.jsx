import React from 'react';

/**
 * LIVE 脉冲指示灯 — CSS animation 圆点
 *
 * dashboard-v7 风格：绿色圆点 + 脉冲动画
 */
function LiveIndicator() {
  return (
    <span
      style={{
        display: 'inline-block',
        width: 7,
        height: 7,
        borderRadius: '50%',
        background: '#10b981',
        boxShadow: '0 0 6px #10b981',
        animation: 'livePulse 2s infinite',
        flexShrink: 0,
      }}
    />
  );
}

// 注入 keyframes（一次性）
if (typeof document !== 'undefined' && !document.getElementById('live-pulse-style')) {
  const style = document.createElement('style');
  style.id = 'live-pulse-style';
  style.textContent = `
    @keyframes livePulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }
  `;
  document.head.appendChild(style);
}

export default LiveIndicator;
