import React, { useEffect, useCallback } from 'react';
import { Empty } from 'antd';
import useECharts from '../../hooks/useECharts';

/**
 * PieChart — Agent 分布环形饼图
 *
 * 参照 dashboard-v15 chart-agent-dist:
 * - L0 / L1 / L2 分布
 * - 标注格式 {b}\n{c}次 ({d}%)
 * - 280px 高度
 *
 * Props:
 * - data: { name: string, value: number }[]
 * - colors: string[] 颜色数组
 * - height: 图表高度（默认 280）
 */
function PieChart({ data, colors, height = 280 }) {
  const { chartRef, instanceRef, initChart, setOption } = useECharts();

  const hasData = data && data.length > 0 && data.some(d => d.value > 0);
  const chartData = hasData ? data : [];
  const chartColors = colors || ['#1677ff', '#722ed1', '#52c41a'];

  const getOption = useCallback(() => {
    if (!hasData) {
      return {
        title: {
          text: '暂无数据',
          left: 'center',
          top: 'center',
          textStyle: { color: 'rgba(0,0,0,.25)', fontSize: 14, fontWeight: 400 },
        },
        series: [],
      };
    }

    return {
      tooltip: {
        trigger: 'item',
        textStyle: { color: 'rgba(0,0,0,.65)' },
        formatter: '{b}: {c} 次 ({d}%)',
      },
      legend: {
        bottom: 0,
        textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      },
      series: [
        {
          type: 'pie',
          radius: ['50%', '72%'],
          center: ['50%', '45%'],
          avoidLabelOverlap: false,
          itemStyle: {
            borderRadius: 4,
            borderColor: '#fff',
            borderWidth: 2,
          },
          label: {
            show: true,
            position: 'outside',
            formatter: '{b}\n{c}次 ({d}%)',
            color: 'rgba(0,0,0,.65)',
            fontSize: 12,
          },
          labelLine: {
            lineStyle: { color: '#d9d9d9' },
          },
          data: chartData.map((d, i) => ({
            ...d,
            itemStyle: { color: chartColors[i % chartColors.length] },
          })),
        },
      ],
    };
  }, [chartData, chartColors, hasData]);

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

export default PieChart;
