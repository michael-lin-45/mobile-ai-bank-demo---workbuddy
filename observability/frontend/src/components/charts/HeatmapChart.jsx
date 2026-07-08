import React from 'react';
import ReactEChartsCore from 'echarts-for-react';

/**
 * HeatmapChart — 混淆矩阵热力图
 *
 * visualMap: { show: false } — 隐藏底部色阶但保留颜色映射
 * 参照 dashboard-v15 chart-confusion 实现。
 *
 * Props:
 * - xLabels: string[] — X 轴标签（预测）
 * - yLabels: string[] — Y 轴标签（实际）
 * - matrix: number[][] — 矩阵数据 [row][col]
 * - height: number (default 280)
 */
function HeatmapChart({
  xLabels = [],
  yLabels = [],
  matrix = [],
  height = 280,
}) {
  // 将矩阵转为 ECharts heatmap 数据格式: [colIdx, rowIdx, value]
  const heatData = [];
  const hasData = matrix.length > 0 && (matrix[0]?.length || 0) > 0;

  if (hasData) {
    matrix.forEach((row, rowIdx) => {
      row.forEach((value, colIdx) => {
        heatData.push([colIdx, rowIdx, value]);
      });
    });
  }

  const labelsX = xLabels.length > 0 ? xLabels : ['转账', '账单', '理财咨询', '理财解读', '闲聊'];
  const labelsY = yLabels.length > 0 ? yLabels : ['转账', '账单', '理财咨询', '理财解读', '闲聊'];

  // 用默认混淆矩阵数据（与 dashboard-v15 一致）
  const defaultData = heatData.length > 0 ? heatData : buildDefaultData(labelsX);

  const option = {
    tooltip: {
      position: 'top',
      textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      formatter: function (p) {
        const val = p.data[2];
        const diag = p.data[0] === p.data[1];
        const label = diag ? '✓ 正确分类' : '✗ 误分类';
        return `<b>${labelsX[p.data[0]]}</b> → <b>${labelsY[p.data[1]]}</b><br/>${label}: ${val} 例`;
      },
    },
    grid: {
      left: 55,
      right: 15,
      top: 10,
      bottom: 25,
    },
    xAxis: {
      type: 'category',
      data: labelsX,
      position: 'top',
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'category',
      data: labelsY.reverse(),
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
        data: defaultData,
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
    <ReactEChartsCore
      option={option}
      style={{ height }}
      notMerge
      lazyUpdate
    />
  );
}

/** 构建默认 5×5 混淆矩阵 */
function buildDefaultData(labels) {
  const size = labels.length;
  const defaultMatrix = [
    [95, 2, 1, 0, 2],
    [1, 94, 2, 1, 2],
    [1, 1, 92, 3, 3],
    [0, 1, 2, 94, 3],
    [2, 2, 1, 1, 94],
  ];
  const data = [];
  for (let i = 0; i < size; i++) {
    for (let j = 0; j < size; j++) {
      const val = (defaultMatrix[i] && defaultMatrix[i][j]) ? defaultMatrix[i][j] : 0;
      // Y 轴反转: rowIdx = size - 1 - i
      data.push([j, size - 1 - i, val]);
    }
  }
  return data;
}

export default HeatmapChart;
