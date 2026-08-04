import React from 'react';
import ReactEChartsCore from 'echarts-for-react';

/**
 * RagQualityChart — RAG 检索质量子面板：Top-K 相关性 / 命中率分布折线图。
 *
 * 对齐 DEMO v24 RAG TAB「检索质量子面板」（差距分析 B.2.3 P1）。
 * 复用 echarts（与 MiniSpark / HeatmapChart 同技术栈），无新依赖。
 *
 * Props:
 * - topK:   { categories: string[], top1: number[], top3: number[], top5: number[] }
 * - height: number (default 240)
 */
function RagQualityChart({ topK, height = 240 }) {
  const hasData = !!topK && Array.isArray(topK.categories) && topK.categories.length > 0;

  const option = {
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => `${v == null ? '-' : v}%`,
      textStyle: { fontSize: 12, color: 'rgba(0,0,0,.65)' },
    },
    legend: {
      data: ['Top-1', 'Top-3', 'Top-5'],
      top: 0,
      textStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)' },
    },
    grid: { left: 40, right: 16, top: 30, bottom: 24, containLabel: true },
    xAxis: {
      type: 'category',
      data: hasData ? topK.categories : [],
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      axisLine: { lineStyle: { color: '#e8e8e8' } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      max: 100,
      name: '相关性 / 命中率 %',
      nameTextStyle: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#f5f5f5' } },
    },
    series: [
      {
        name: 'Top-1',
        type: 'line',
        smooth: true,
        data: hasData ? topK.top1 : [],
        itemStyle: { color: '#08979c' },
        lineStyle: { width: 2, color: '#08979c' },
        symbolSize: 5,
      },
      {
        name: 'Top-3',
        type: 'line',
        smooth: true,
        data: hasData ? topK.top3 : [],
        itemStyle: { color: '#13c2c2' },
        lineStyle: { width: 2, color: '#13c2c2' },
        symbolSize: 5,
      },
      {
        name: 'Top-5',
        type: 'line',
        smooth: true,
        data: hasData ? topK.top5 : [],
        itemStyle: { color: '#722ed1' },
        lineStyle: { width: 2, color: '#722ed1' },
        symbolSize: 5,
      },
    ],
  };

  return (
    <ReactEChartsCore option={option} style={{ height }} notMerge lazyUpdate />
  );
}

export default RagQualityChart;
