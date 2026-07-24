import React from 'react';
import { KPI_COLOR, KPI_BG, KPI_VALUE_FONT_SIZE, DEMO_BADGE_STYLE } from '../theme/insightTokens';
import { formatThousands } from '../services/insightAdapters';

/**
 * InsightKpiCard — AI 洞察页共享彩色 KPI 卡片
 *
 * 提升自 AgentPerfTab 内联 KpiCard（docs/system_design.md §1.3）：
 *   flex:1 / min-width 200px / 彩色背景 / 居中 label + 大号等宽数字 + unit
 *
 * 全站 KPI 视觉统一（漏斗 / 准确率 / 外部调用 / 诊断 顶部均复用此组件）。
 *
 * Props:
 * - label:  指标名（顶部居中）
 * - value:  数值（number 自动千分位；string 原样）
 * - unit:   单位（如 ms / % / 类），可选
 * - color:  数字主色，缺省取 KPI_COLOR.blue
 * - bg:     卡片背景色，缺省取 KPI_BG.blue
 * - demo:   是否 mock 回退（true 时右上角显示 subtle DEMO 角标）
 */
function InsightKpiCard({ label, value, unit, color, bg, demo = false }) {
  const textColor = color || KPI_COLOR.blue;
  const background = bg || KPI_BG.blue;
  const displayValue = typeof value === 'number' ? formatThousands(value) : (value == null ? '—' : value);

  return (
    <div
      style={{
        position: 'relative',
        flex: 1,
        minWidth: 200,
        textAlign: 'center',
        padding: '12px 10px',
        background,
        borderRadius: 6,
        overflow: 'hidden',
      }}
    >
      {demo && (
        <span style={{ position: 'absolute', top: 4, right: 6, ...DEMO_BADGE_STYLE }}>DEMO</span>
      )}
      <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)' }}>{label}</div>
      <div
        style={{
          fontFamily: '"JetBrains Mono", monospace',
          fontWeight: 700,
          fontSize: KPI_VALUE_FONT_SIZE,
          color: textColor,
          lineHeight: 1.2,
          marginTop: 2,
        }}
      >
        {displayValue}
        {unit && (
          <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', fontWeight: 400, marginLeft: 2 }}>
            {unit}
          </span>
        )}
      </div>
    </div>
  );
}

/**
 * DemoBadge — 独立 subtle DEMO 角标
 * 供非 KPI 区块（表格 / 图表卡）在 mock 回退时右上角展示。
 */
export function DemoBadge({ style }) {
  return <span style={{ ...DEMO_BADGE_STYLE, ...style }}>DEMO</span>;
}

export default InsightKpiCard;
