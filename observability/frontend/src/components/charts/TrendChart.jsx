import React, { useEffect, useCallback } from 'react';
import useECharts from '../../hooks/useECharts';

/**
 * TrendChart — 请求 & Token 趋势双轴图
 *
 * 参照 dashboard-v15 chart-overview:
 * - 柱状图：请求量
 * - 折线图：Token 消耗
 * - 6 小时 X 轴（12 个数据点，每 30min）
 * - 280px 高度
 *
 * Props:
 * - data: { times: string[], requests: number[], tokens: number[] }
 * - height: 图表高度（默认 280）
 */
function TrendChart({ data, height = 280 }) {
  const { chartRef, instanceRef, initChart, setOption } = useECharts();

  const hasData = data && data.times && data.times.length > 0;

  const getOption = useCallback(() => {
    if (!hasData) {
      return {
        title: {
          text: '暂无趋势数据',
          left: 'center',
          top: 'center',
          textStyle: { color: 'rgba(0,0,0,.25)', fontSize: 14, fontWeight: 400 },
        },
        series: [],
      };
    }

    return {
      tooltip: {
        trigger: 'axis',
        textStyle: { color: 'rgba(0,0,0,.65)' },
      },
      legend: {
        data: ['请求量', 'Token'],
        bottom: 2,
        textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      },
      grid: {
        top: 20,
        bottom: 44,
        left: 50,
        right: 50,
      },
      xAxis: {
        type: 'category',
        data: data.times,
        axisLabel: { color: 'rgba(0,0,0,.45)', fontSize: 10 },
        axisLine: { lineStyle: { color: '#f0f0f0' } },
      },
      yAxis: [
        {
          type: 'value',
          name: '请求量',
          nameTextStyle: { color: 'rgba(0,0,0,.45)', fontSize: 11 },
          axisLabel: { color: 'rgba(0,0,0,.45)', fontSize: 10 },
          splitLine: { lineStyle: { color: '#f0f0f0' } },
        },
        {
          type: 'value',
          name: 'Token',
          nameTextStyle: { color: 'rgba(0,0,0,.45)', fontSize: 11 },
          axisLabel: {
            color: 'rgba(0,0,0,.45)',
            fontSize: 10,
            formatter: (v) => v >= 1000 ? `${(v / 1000).toFixed(0)}k` : String(v),
          },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: '请求量',
          type: 'bar',
          data: data.requests,
          itemStyle: { color: '#1677ff', borderRadius: [3, 3, 0, 0] },
          barWidth: 16,
        },
        {
          name: 'Token',
          type: 'line',
          yAxisIndex: 1,
          data: data.tokens,
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { color: '#52c41a', width: 2 },
          itemStyle: { color: '#52c41a' },
        },
      ],
    };
  }, [data, hasData]);

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

export default TrendChart;
