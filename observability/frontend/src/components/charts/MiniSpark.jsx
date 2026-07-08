import React, { useEffect, useCallback } from 'react';
import * as echarts from 'echarts';
import useECharts from '../../hooks/useECharts';

/**
 * MiniSpark — ECharts 迷你折线图
 *
 * 参照 dashboard-v15 miniSpark():
 * - 10 数据点平滑折线
 * - 渐变面积填充
 * - 32px 高度
 * - 无坐标轴/无交互
 *
 * Props:
 * - data: number[] 数据点数组
 * - color: 线条颜色（默认 '#1677ff'）
 * - height: 图表高度（默认 32）
 */
function MiniSpark({ data, color = '#1677ff', height = 32 }) {
  const { chartRef, instanceRef, initChart, setOption } = useECharts();

  const hasData = data && data.length > 0;
  const seriesData = hasData ? data : [];

  const getOption = useCallback(() => {
    if (!hasData) {
      return { series: [] };
    }

    return {
      grid: {
        top: 2,
        bottom: 2,
        left: 0,
        right: 0,
      },
      xAxis: {
        type: 'category',
        show: false,
        data: seriesData.map((_, i) => i),
      },
      yAxis: {
        type: 'value',
        show: false,
        min: (val) => Math.floor(val.min * 0.8),
        max: (val) => Math.ceil(val.max * 1.2),
      },
      series: [
        {
          type: 'line',
          data: seriesData,
          smooth: true,
          symbol: 'none',
          lineStyle: { color, width: 1.5 },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: hexToRgba(color, 0.15) },
              { offset: 1, color: hexToRgba(color, 0.01) },
            ]),
          },
        },
      ],
    };
  }, [seriesData, color, hasData]);

  useEffect(() => {
    initChart();
    setOption(getOption());
  }, [initChart, setOption, getOption]);

  return (
    <div
      ref={chartRef}
      style={{ width: '100%', height, minHeight: height }}
    />
  );
}

/** hex → rgba 转换（用于渐变） */
function hexToRgba(hex, alpha) {
  const h = hex.replace('#', '');
  const r = parseInt(h.substring(0, 2), 16);
  const g = parseInt(h.substring(2, 4), 16);
  const b = parseInt(h.substring(4, 6), 16);
  return `rgba(${r},${g},${b},${alpha})`;
}

export default MiniSpark;
