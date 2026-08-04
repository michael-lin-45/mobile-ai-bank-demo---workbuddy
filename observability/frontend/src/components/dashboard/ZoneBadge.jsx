import React from 'react';

/**
 * ZoneBadge — 大屏 Zone 字母徽标（A/B/C/D/E）。
 *
 * 圆角胶囊 + 白字 + Zone 主题色底，配合各 Zone section header 渲染，
 * 解决「sectionHeaderStyle(color, tag) 忽略 tag」导致 Zone 字母不显示的问题
 * （差距分析 B.1 跨 Zone 通用结构差异）。
 *
 * 颜色消费 docs/system_design.md §7 Zone 配色 token：
 *   A #1677ff / B #722ed1 / C #52c41a / D #d48806 / E #08979c。
 *
 * Props:
 * - letter: 字母（'A'|'B'|'C'|'D'|'E'）
 * - color:  Zone 主题色（默认 '#1677ff'）
 * - size:   'sm' | 'lg'（默认 'sm'）
 */
function ZoneBadge({ letter, color = '#1677ff', size = 'sm' }) {
  const dim = size === 'lg'
    ? { height: 22, fontSize: 13, padding: '0 10px' }
    : { height: 18, fontSize: 11, padding: '0 8px' };

  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        height: dim.height,
        padding: dim.padding,
        borderRadius: 999,
        background: color,
        color: '#fff',
        fontSize: dim.fontSize,
        fontWeight: 700,
        lineHeight: 1,
        letterSpacing: '0.5px',
        flexShrink: 0,
        boxShadow: '0 1px 2px rgba(0,0,0,.12)',
      }}
    >
      {letter}
    </span>
  );
}

export default ZoneBadge;
