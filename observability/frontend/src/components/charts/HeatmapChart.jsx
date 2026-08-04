import React, { useState, useEffect } from 'react';
import ReactEChartsCore from 'echarts-for-react';
import {
  computeCramersV,
  computeTopConfusionPairs,
  cramersHealth,
  toIntentLabel,
} from '../../services/confusionStats';

/**
 * HeatmapChart — 意图混淆矩阵热力图（V24 二次对齐 · 任务 T03 / 设计 A.3 推荐方案）。
 *
 * ── 数据契约（权威：services/mockInsights.js confusion）──
 *   confusion = { labels: string[5], matrix: number[5][5] }
 *   matrix[row][col]：row = 实际意图(actual) → y 轴；col = 预测意图(predicted) → x 轴
 *   对角线 row==col = 正确分类计数（高值 760~980）。
 *
 * ── 重构点（对照差距分析 A 节「乱」的根因）──
 *   1) 对角线深绿铺底（#237804），一眼可辨主干是否厚实；
 *   2) 单元格只标百分比，原始 count 移入 tooltip；
 *   3) 非对角用发散色板（白→橙→红，按占行比例分档），越红越混淆；
 *   4) 轴标签显式写「实际意图(行) / 预测意图(列)」并标注「占该行比例」；
 *   5) 右上角 Cramér's V 摘要卡（健康灯：<0.15 绿 / 0.15–0.30 黄 / >0.30 红）；
 *   6) 矩阵下方 Top-3 混淆对（最该修的混淆，作为大屏结论钩子）；
 *   7) 窄屏（<480）降级：隐藏完整矩阵，仅渲染 Top-3 混淆对列表。
 *
 * 归一化口径：NORMALIZE_AXIS='row'（占实际意图比例），严禁列归一或原始计数展示。
 *
 * Props:
 * - xLabels: string[]  预测意图（列）
 * - yLabels: string[]  实际意图（行）
 * - matrix:  number[][] 原始计数 matrix[row=actual][col=predicted]
 * - height:  number (default 280)
 * - showCramers:  boolean (default true) 是否挂 Cramér's V 摘要卡
 * - showTopPairs: boolean (default true) 是否渲染 Top-3 混淆对
 * - narrow:  boolean | null (default null) 显式窄屏；null 时按 480px 自动判定
 */
const DEFAULT_LABELS = ['转账', '账单', '理财咨询', '理财解读', '闲聊'];

// 默认混淆矩阵（行=实际、列=预测），与 DEFAULT_LABELS 对齐；无数据兜底用
const DEFAULT_MATRIX = [
  [920, 30, 12, 8, 30],
  [25, 880, 18, 10, 67],
  [14, 20, 760, 140, 66],
  [9, 12, 130, 770, 79],
  [22, 18, 14, 11, 980],
];

// 对角线深绿铺底
const DIAG_COLOR = '#237804';

/**
 * 发散色板（非对角）：<1% 白 / 1–5% 浅橙 / 5–10% 橙 / 10–15% 橙红 / ≥15% 红
 * 越红代表占该实际意图行的比例越高（越混淆）。
 */
function confuseColor(pct) {
  if (pct < 1) return '#ffffff';
  if (pct < 5) return '#fff1e6';
  if (pct < 10) return '#ffbb96';
  if (pct < 15) return '#ff7a45';
  return '#ff4d4f';
}

/** 窄屏自动判定（< breakpoint px） */
function useIsNarrow(breakpoint = 480) {
  const get = () =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia(`(max-width:${breakpoint}px)`).matches
      : false;
  const [narrow, setNarrow] = useState(get);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const mql = window.matchMedia(`(max-width:${breakpoint}px)`);
    const handler = (e) => setNarrow(e.matches);
    setNarrow(mql.matches);
    if (mql.addEventListener) mql.addEventListener('change', handler);
    else mql.addListener(handler);
    return () => {
      if (mql.removeEventListener) mql.removeEventListener('change', handler);
      else mql.removeListener(handler);
    };
  }, [breakpoint]);
  return narrow;
}

function HeatmapChart({
  xLabels = [],
  yLabels = [],
  matrix = [],
  height = 280,
  showCramers = true,
  showTopPairs = true,
  narrow = null,
}) {
  const autoNarrow = useIsNarrow(480);
  const isNarrow = narrow != null ? narrow : autoNarrow;

  // 英文意图码 → 中文标签（QA 缺陷3），默认中文标签原样透传
  const labelsX = (xLabels.length > 0 ? xLabels : DEFAULT_LABELS).map(toIntentLabel); // x = 预测
  const labelsY = (yLabels.length > 0 ? yLabels : DEFAULT_LABELS).map(toIntentLabel); // y = 实际

  const hasData = Array.isArray(matrix) && matrix.length > 0
    && Array.isArray(matrix[0]) && matrix[0].length > 0;
  const sourceMatrix = hasData ? matrix : DEFAULT_MATRIX;

  // 派生统计（纯函数，DEMO 内联同算法）
  const cramersV = computeCramersV(sourceMatrix);
  const topPairs = computeTopConfusionPairs(labelsY, sourceMatrix, 3);

  // 单条数据构建：对角线绿 / 非对角发散色 / 只标百分比
  const heatData = [];
  sourceMatrix.forEach((row, rowIdx) => {
    const rowSum = row.reduce((a, b) => a + (Number(b) || 0), 0) || 1;
    row.forEach((value, colIdx) => {
      const count = Number(value) || 0;
      const pct = +((count / rowSum) * 100).toFixed(1);
      const isDiag = rowIdx === colIdx;
      const cellColor = isDiag ? DIAG_COLOR : confuseColor(pct);
      const labelColor = isDiag ? '#fff' : (pct >= 10 ? '#fff' : 'rgba(0,0,0,.65)');
      const labelWeight = isDiag || pct >= 10 ? 700 : 400;
      heatData.push({
        value: [colIdx, rowIdx, pct], // x=col(预测), y=row(实际)
        count,
        pct,
        isDiag,
        itemStyle: { color: cellColor, borderRadius: 2 },
        label: {
          show: true,
          fontSize: 10,
          fontWeight: labelWeight,
          color: labelColor,
          formatter: () => `${pct}`,
        },
      });
    });
  });

  const option = {
    tooltip: {
      position: 'top',
      textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      formatter: function (p) {
        const d = p.data;
        const pred = labelsX[d.value[0]]; // x = 预测
        const act = labelsY[d.value[1]]; // y = 实际
        if (d.isDiag) {
          return `✓ 正确分类<br/>预测 <b>${pred}</b> → 实际 <b>${act}</b><br/>${d.count} 例 (占该行 ${d.pct}%)`;
        }
        return `✗ 混淆<br/>预测 <b>${pred}</b> → 实际 <b>${act}</b><br/>${d.count} 例 (占实际为 ${act} 的 ${d.pct}%)`;
      },
    },
    grid: {
      left: 64,
      right: 16,
      top: 36,
      bottom: 20,
    },
    xAxis: {
      type: 'category',
      data: labelsX,
      position: 'top',
      name: '预测意图 (列)',
      nameLocation: 'middle',
      nameGap: 26,
      nameTextStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)', fontWeight: 600 },
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'category',
      data: labelsY,
      inverse: true, // 行 0（实际=转账）置顶，与标准混淆矩阵朝向一致
      name: '实际意图 (行)',
      nameLocation: 'end',
      nameGap: 12,
      nameTextStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)', fontWeight: 600 },
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    visualMap: {
      min: 0,
      max: 100,
      show: false,
      inRange: { color: ['#f5f5f5', '#1677ff'] },
    },
    series: [
      {
        type: 'heatmap',
        data: heatData,
        emphasis: {
          itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,.15)' },
        },
      },
    ],
  };

  return (
    <div>
      {/* 完整矩阵 + Cramér's V 摘要卡（窄屏降级隐藏） */}
      {!isNarrow && (
        <div style={{ display: 'flex', gap: 16, alignItems: 'stretch' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <ReactEChartsCore option={option} style={{ height }} notMerge lazyUpdate />
          </div>
          {showCramers && <CramersCard v={cramersV} />}
        </div>
      )}

      {/* Top-3 混淆对（窄屏降级时也渲染，作为核心结论） */}
      {showTopPairs && <TopPairsList pairs={topPairs} />}

      {/* 口径说明 */}
      <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', marginTop: 8, lineHeight: 1.6 }}>
        单元格数值 = 占该行（实际意图）比例，矩阵已按行归一化 0–100%（对角为正确分类占比）。
        {isNarrow && ' 窄屏已隐藏完整矩阵，仅展示 Top-3 混淆对。'}
      </div>
    </div>
  );
}

/* ── Cramér's V 摘要卡（右上角，健康灯）── */

function CramersCard({ v }) {
  const h = cramersHealth(v);
  return (
    <div
      style={{
        width: 196,
        flexShrink: 0,
        border: '1px solid #f0f0f0',
        borderRadius: 8,
        padding: '14px 16px',
        background: '#fff',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
      }}
    >
      <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginBottom: 4 }}>Cramér's V</div>
      <div style={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, fontSize: 28, color: 'rgba(0,0,0,.88)' }}>
        {v.toFixed(2)}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 8 }}>
        <span style={{ width: 8, height: 8, borderRadius: '50%', background: h.color, display: 'inline-block' }} />
        <span style={{ fontSize: 12, color: h.color, fontWeight: 600 }}>{h.text}</span>
      </div>
      <div style={{ fontSize: 11, color: 'rgba(0,0,0,.35)', marginTop: 8, lineHeight: 1.5 }}>
        越大越混淆：健康 &lt;0.15 / 关注 0.15–0.30 / 严重 &gt;0.30
      </div>
    </div>
  );
}

/* ── Top-3 混淆对列表 ── */

function TopPairsList({ pairs }) {
  if (!pairs || pairs.length === 0) return null;
  return (
    <div style={{ marginTop: 12 }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: 'rgba(0,0,0,.65)', marginBottom: 6 }}>Top-3 混淆对</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {pairs.map((p, i) => {
          const color = p.pct >= 15 ? '#ff4d4f' : p.pct >= 10 ? '#ff7a45' : '#fa8c16';
          return (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
              <span style={{ color: 'rgba(0,0,0,.4)', width: 14, textAlign: 'right' }}>{i + 1}.</span>
              <span style={{ fontWeight: 600, color: 'rgba(0,0,0,.85)' }}>{p.actual}</span>
              <span style={{ color: 'rgba(0,0,0,.45)' }}>↔</span>
              <span style={{ fontWeight: 600, color: 'rgba(0,0,0,.85)' }}>{p.predicted}</span>
              <span style={{ marginLeft: 'auto', fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, color }}>
                {p.pct}%
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default HeatmapChart;
