import React from 'react';
import ReactEChartsCore from 'echarts-for-react';

/**
 * HeatmapChart — 意图混淆矩阵热力图（融合设计：V16 美观 + 数据正确 + 突出混淆）
 *
 * ── 数据契约（权威：services/mockInsights.js confusion）──
 *   confusion = { labels: string[5], matrix: number[5][5] }
 *   matrix[row][col]：
 *     row = 实际意图(actual)  → y 轴(labelsY)
 *     col = 预测意图(predicted) → x 轴(labelsX)
 *   对角线 row==col = 正确分类计数（高值 760~980）。
 *
 * ── 修复点（对照 spec BUG-1~5，含架构师写反的契约修正）──
 *  1) 量程错配：组件内「按行归一化」pct = count / rowSum * 100（0~100），
 *     使 visualMap min:0/max:100 量程正确生效（对角线~88-98% 深蓝、错误格浅色）。
 *  2) Y 轴翻转未补偿：删除 labelsY.reverse()，改用 yAxis.inverse=true，
 *     数据用原始 row/col 索引（不翻转），与 V16/V23 朝向一致。
 *  3) 两条路径不一致：真实路径与默认路径统一为单条 buildHeatData
 *     （row=actual, col=predicted，不翻转、不补偿）。
 *  4) Tooltip 方向：保持「预测 → 实际」（labelsX[col]=预测、labelsY[row]=实际），
 *     对角标「✓ 正确分类」、非对角标「✗ 混淆」，附 count + pct。
 *  5) 对角线无差异化：对 top-N / ≥阈值(5%) 的非对角高值格叠加暖色描边/加粗
 *     （#fa8c16 普通混淆、#ff4d4f 最严重），突出「混淆」重点（不照搬 V23 红金满铺）。
 *
 * Props:
 * - xLabels: string[] — X 轴标签（预测意图）
 * - yLabels: string[] — Y 轴标签（实际意图）
 * - matrix: number[][] — 原始计数矩阵，matrix[row=actual][col=predicted]
 * - height: number (default 280)
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

// 非对角混淆格「重点」阈值（占行比例 ≥ 5% 视为需突出）
const CONFUSE_PCT_THRESHOLD = 5;

function HeatmapChart({
  xLabels = [],
  yLabels = [],
  matrix = [],
  height = 280,
}) {
  const labelsX = xLabels.length > 0 ? xLabels : DEFAULT_LABELS; // x = 预测
  const labelsY = yLabels.length > 0 ? yLabels : DEFAULT_LABELS; // y = 实际

  const hasData = Array.isArray(matrix) && matrix.length > 0
    && Array.isArray(matrix[0]) && matrix[0].length > 0;
  const sourceMatrix = hasData ? matrix : DEFAULT_MATRIX;

  // ── 单条数据构建：行归一化 + 暖色强调（row=actual=y, col=predicted=x）──
  const heatData = [];
  sourceMatrix.forEach((row, rowIdx) => {
    const rowSum = row.reduce((a, b) => a + (Number(b) || 0), 0) || 1;
    row.forEach((value, colIdx) => {
      const count = Number(value) || 0;
      const pct = +((count / rowSum) * 100).toFixed(1); // 0~100，用于上色
      const isDiag = rowIdx === colIdx;
      const isConfuse = !isDiag && pct >= CONFUSE_PCT_THRESHOLD;
      heatData.push({
        value: [colIdx, rowIdx, pct], // x=col(预测), y=row(实际)
        count,
        pct,
        isDiag,
        isConfuse,
        itemStyle: isConfuse
          ? { borderColor: pct >= 10 ? '#ff4d4f' : '#fa8c16', borderWidth: 2 }
          : undefined,
        label: {
          show: true,
          fontSize: 10,
          color: isDiag ? 'rgba(0,0,0,.65)' : (isConfuse ? '#d4380d' : 'rgba(0,0,0,.45)'),
          fontWeight: isConfuse ? 700 : 400,
          formatter: () => `${count}\n${pct}%`,
        },
      });
    });
  });

  // ── 计算 top-2 混淆对（非对角 pct 最高），用于 caption ──
  const confusePairs = [];
  sourceMatrix.forEach((row, rowIdx) => {
    const rowSum = row.reduce((a, b) => a + (Number(b) || 0), 0) || 1;
    row.forEach((value, colIdx) => {
      if (rowIdx === colIdx) return;
      const count = Number(value) || 0;
      const pct = +((count / rowSum) * 100).toFixed(1);
      confusePairs.push({ actual: labelsY[rowIdx], pred: labelsX[colIdx], count, pct });
    });
  });
  confusePairs.sort((a, b) => b.pct - a.pct);
  const topConfuse = confusePairs.slice(0, 2);

  const option = {
    tooltip: {
      position: 'top',
      textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      formatter: function (p) {
        const d = p.data;
        const pred = labelsX[d.value[0]]; // x = 预测
        const act = labelsY[d.value[1]]; // y = 实际
        if (d.isDiag) {
          return `✓ 正确分类<br/>预测 <b>${pred}</b> → 实际 <b>${act}</b><br/>${d.count} 例 (${d.pct}%)`;
        }
        return `✗ 混淆<br/>预测 <b>${pred}</b> → 实际 <b>${act}</b><br/>${d.count} 例 (占实际为 ${act} 的 ${d.pct}%)`;
      },
    },
    grid: {
      left: 64,
      right: 15,
      top: 36,
      bottom: 20,
    },
    xAxis: {
      type: 'category',
      data: labelsX,
      position: 'top',
      name: '预测',
      nameLocation: 'middle',
      nameGap: 22,
      nameTextStyle: { fontSize: 11, color: 'rgba(0,0,0,.45)' },
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'category',
      data: labelsY,
      inverse: true, // 行 0（实际=转账）置顶，与标准混淆矩阵朝向一致
      name: '实际',
      nameLocation: 'end',
      nameGap: 12,
      nameTextStyle: { fontSize: 11, color: 'rgba(0,0,0,.45)' },
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    visualMap: {
      min: 0,
      max: 100,
      show: false,
      inRange: {
        color: ['#f5f5f5', '#1677ff'],
      },
    },
    series: [
      {
        type: 'heatmap',
        data: heatData,
        label: {
          show: true,
          fontSize: 10,
          color: 'rgba(0,0,0,.65)',
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowColor: 'rgba(0,0,0,.15)',
          },
        },
      },
    ],
  };

  return (
    <div>
      <ReactEChartsCore
        option={option}
        style={{ height }}
        notMerge
        lazyUpdate
      />
      <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', marginTop: 6, lineHeight: 1.6 }}>
        单元格颜色 = 该预测意图下、实际为各意图的占比（按行归一化，0–100%）。
        {topConfuse.length > 0 && (
          <span style={{ color: '#d4380d' }}>
            {' '}⚠ 主要混淆：{topConfuse.map((c) => `${c.actual}↔${c.pred} ${c.pct}%`).join('、')}
          </span>
        )}
      </div>
    </div>
  );
}

export default HeatmapChart;
