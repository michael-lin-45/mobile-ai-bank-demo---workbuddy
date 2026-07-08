import React from 'react';
import MiniSpark from './charts/MiniSpark';

/**
 * MetricCard — KPI 指标卡片
 *
 * 参照 dashboard-v15 metric-card:
 * - 指标名 + 图标
 * - 主数值 28px 700 字重
 * - 环比变化（绿↓好 / 红↑差）
 * - 副指标 12px 灰色
 * - Mini Spark 折线图（32px 高）
 *
 * Props:
 * - label: 指标名称
 * - icon: 图标（emoji 字符串）
 * - value: 主数值
 * - unit: 单位
 * - delta: 环比变化文本（如 '↑ 12.3%'）
 * - deltaUp: true=红色箭头（变差）, false=绿色箭头（变好）, null=无箭头
 * - deltaNote: 变化说明（如 '较昨日'）
 * - sub: 副指标文本
 * - sparkData: mini spark 数据 number[]
 * - sparkColor: mini spark 颜色
 * - empty: true 时显示 Empty 占位
 * - emptyText: 空状态文本
 */
function MetricCard({
  label,
  icon,
  value,
  unit,
  delta,
  deltaUp,
  deltaNote,
  sub,
  sparkData,
  sparkColor,
  empty = false,
  emptyText = '暂无',
}) {
  return (
    <div
      style={{
        background: '#fff',
        borderRadius: 8,
        boxShadow: '0 1px 2px rgba(0,0,0,.03), 0 1px 6px -1px rgba(0,0,0,.02), 0 2px 4px rgba(0,0,0,.02)',
        padding: 20,
        cursor: 'default',
        transition: 'all .2s',
        border: '1px solid transparent',
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = '#1677ff';
        e.currentTarget.style.boxShadow = '0 2px 12px rgba(22,119,255,.12)';
        e.currentTarget.style.transform = 'translateY(-1px)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = 'transparent';
        e.currentTarget.style.boxShadow = '0 1px 2px rgba(0,0,0,.03), 0 1px 6px -1px rgba(0,0,0,.02)';
        e.currentTarget.style.transform = 'translateY(0)';
      }}
    >
      {/* 指标名 + 图标 */}
      <div
        style={{
          fontSize: 13,
          color: 'rgba(0,0,0,.65)',
          marginBottom: 4,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        <span>{label}</span>
        {icon && <span style={{ fontSize: 16 }}>{icon}</span>}
      </div>

      {/* 主数值 */}
      {empty ? (
        <div
          style={{
            fontSize: 28,
            fontWeight: 700,
            lineHeight: 1.2,
            color: 'rgba(0,0,0,.25)',
          }}
        >
          {emptyText}
        </div>
      ) : (
        <div style={{ fontSize: 28, fontWeight: 700, lineHeight: 1.2, color: 'rgba(0,0,0,.88)' }}>
          {value}
          {unit && (
            <span style={{ fontSize: 13, fontWeight: 400, color: 'rgba(0,0,0,.45)', marginLeft: 4 }}>
              {unit}
            </span>
          )}
        </div>
      )}

      {/* 环比变化 */}
      {delta && (
        <div
          style={{
            fontSize: 12,
            marginTop: 4,
            display: 'flex',
            alignItems: 'center',
            gap: 4,
            color: deltaUp === true ? '#ff4d4f'
              : deltaUp === false ? '#52c41a'
              : 'rgba(0,0,0,.45)',
          }}
        >
          {delta}
          {deltaNote && (
            <span style={{ color: 'rgba(0,0,0,.45)' }}>{deltaNote}</span>
          )}
        </div>
      )}

      {/* 副指标 */}
      {sub && (
        <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginTop: 2 }}>
          {sub}
        </div>
      )}

      {/* Mini Spark 折线图 */}
      {!empty && (
        <div style={{ height: 32, marginTop: 4 }}>
          <MiniSpark data={sparkData} color={sparkColor} />
        </div>
      )}
    </div>
  );
}

export default MetricCard;
