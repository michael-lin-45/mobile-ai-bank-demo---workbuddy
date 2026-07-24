/**
 * insightTokens — AI 洞察页共享视觉 token
 *
 * 跨文件共享的 KPI 配色 / 尺寸 / 间距约定，避免各 TAB 内联重复。
 * 色板对齐 AgentPerfTab 原 KpiCard 风格（蓝/绿/橙/红/青）。
 *
 * 设计来源：docs/system_design.md §8 共享知识。
 */

/** KPI 文字主色（用于数字 / 强调） */
export const KPI_COLOR = {
  blue: '#1677ff', // 总览 / 进入
  green: '#52c41a', // 成功 / 完成
  orange: '#fa8c16', // 警告 / 放弃
  red: '#ff4d4f', // 错误 / 失败
  cyan: '#13c2c2', // 中性 / 错误率
  purple: '#722ed1', // 转化 / L2
};

/** KPI 卡片背景（浅色调，与文字主色呼应） */
export const KPI_BG = {
  blue: '#f0f5ff',
  green: '#f6ffed',
  orange: '#fff7e6',
  red: '#fff2f0',
  cyan: '#e6fffb',
  purple: '#f9f0ff',
};

/** 数字字号（大号等宽） */
export const KPI_VALUE_FONT_SIZE = 26;

/** 区块间距（各 TAB gap 统一） */
export const SECTION_GAP = 16;

/** DEMO 角标样式（subtle，不喧宾夺主） */
export const DEMO_BADGE_STYLE = {
  fontSize: 10,
  lineHeight: '16px',
  color: 'rgba(0,0,0,.30)',
  background: 'rgba(0,0,0,.04)',
  border: '1px solid rgba(0,0,0,.06)',
  borderRadius: 4,
  padding: '0 6px',
  fontFamily: '"JetBrains Mono", monospace',
  letterSpacing: 0.5,
};

export default { KPI_COLOR, KPI_BG, KPI_VALUE_FONT_SIZE, SECTION_GAP, DEMO_BADGE_STYLE };
