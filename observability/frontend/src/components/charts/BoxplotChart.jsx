import React from 'react';
import ReactEChartsCore from 'echarts-for-react';
import { Empty } from 'antd';

/**
 * BoxplotChart — ECharts 箱线图
 *
 * 数据格式: [min, P25, P50, P75, P95] — 5 值标准
 * Agent 维度颜色: 蓝 #1677ff
 * LLM 维度颜色: 紫 #722ed1
 *
 * ⚠ ECharts 陷阱: category xAxis 下 boxplot tooltip 的 p.data[0] 是类别标签,
 *   数据从索引 1 开始。tooltip formatter 使用 p.data[2]~[5] 对应 P25~P95。
 *
 * Props:
 * - categories: string[] — X 轴类别
 * - data: number[][] — 每个类别的 [min, P25, P50, P75, P95]
 * - color: string — 箱线颜色 (default '#1677ff')
 * - height: number (default 200)
 * - yAxisName: string (default 'ms')
 */
function BoxplotChart({
  categories = [],
  data = [],
  color = '#1677ff',
  height = 200,
  yAxisName = 'ms',
}) {
  // When no data or categories are provided, show empty state
  if (!data || data.length === 0 || !categories || categories.length === 0) {
    return (
      <div style={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无箱线图数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const boxData = data;

  const option = {
    tooltip: {
      trigger: 'item',
      textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      formatter: function (p) {
        const d = p.data;
        // category xAxis 下: d[0] = 类别标签, d[1] = min, d[2] = P25, d[3] = P50, d[4] = P75, d[5] = P95
        return (
          '<b>' + d[0] + '</b> TTFT<br/><br/>' +
          'P25: ' + d[2] + 'ms<br/>' +
          '<b>P50: ' + d[3] + 'ms</b><br/>' +
          'P75: ' + d[4] + 'ms<br/>' +
          '<b style="color:#ff4d4f">P95: ' + d[5] + 'ms</b>'
        );
      },
    },
    grid: {
      left: 60,
      right: 15,
      top: 5,
      bottom: 25,
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { fontSize: 9, color: 'rgba(0,0,0,.45)' },
      axisLine: { lineStyle: { color: '#f0f0f0' } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      name: yAxisName,
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}ms' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    series: [
      {
        type: 'boxplot',
        data: boxData,
        itemStyle: {
          color: color,
          borderColor: color,
        },
        boxWidth: Array(categories.length).fill(12),
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

export default BoxplotChart;
