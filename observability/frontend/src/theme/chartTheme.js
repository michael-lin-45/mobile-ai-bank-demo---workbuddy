/**
 * ECharts 亮色主题配置
 *
 * 对应于 dashboard-v15.html 中定义的:
 *   var tc = 'rgba(0,0,0,.65)';           // 图表文字色
 *   var as = { color: 'rgba(0,0,0,.45)' }; // 轴标签
 *   var sl = { lineStyle: { color: '#f0f0f0' } }; // 分割线
 */

/** 图表主文字颜色 */
export const chartTextColor = 'rgba(0,0,0,.65)';

/** X/Y 轴标签样式 */
export const chartAxisLabel = { color: 'rgba(0,0,0,.45)' };

/** 网格分割线样式 */
export const chartSplitLine = { lineStyle: { color: '#f0f0f0' } };

/** Tooltip 通用样式 */
export const chartTooltip = {
  trigger: 'axis',
  textStyle: { color: 'rgba(0,0,0,.65)' },
};

/**
 * 获取 ECharts 通用亮色文本配置
 * 用于在图表 option 中展开
 */
export function getLightChartTextStyle() {
  return {
    textStyle: { color: chartTextColor },
    axisLabel: chartAxisLabel,
    splitLine: chartSplitLine,
  };
}

/**
 * ECharts 通用颜色调色板（与 dashboard-v15 一致）
 */
export const CHART_COLORS = {
  primary: '#1677ff',
  purple: '#722ed1',
  green: '#52c41a',
  cyan: '#13c2c2',
  orange: '#fa8c16',
  yellow: '#fadb14',
  red: '#ff4d4f',
};
