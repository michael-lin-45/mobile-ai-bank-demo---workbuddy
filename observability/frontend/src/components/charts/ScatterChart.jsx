import React from 'react';
import ReactEChartsCore from 'echarts-for-react';
import { Empty } from 'antd';

/**
 * ScatterChart — 六色散点图 (TTFT vs TPOT)
 *
 * 多 series + 固定颜色方案:
 *   每个 Agent/LLM 持有固定颜色，通过 legend/tooltip/label 识别。
 *   气泡大小 = Math.sqrt(调用量) / 4
 *   绿色半透明 markArea 标记优秀区
 *
 * 六色映射:
 *   L0: #1677ff | L1: #722ed1 | 转账服务: #52c41a
 *   账单查询: #13c2c2 | 理财咨询: #faad14 | 理财解读: #fa8c16
 *
 * Props:
 * - seriesData: Array<{ name, ttftP50, tpotP50, callCount, color? }>
 * - height: number (default 200)
 * - xAxisMax: number — X 轴最大值（留标签空间）
 * - yAxisMax: number — Y 轴最大值
 * - markAreaMax: [number, number] — 优秀区 [x, y]
 */
function ScatterChart({
  seriesData = [],
  height = 200,
  xAxisMax = 550,
  yAxisMax = 32,
  markAreaMax = [220, 20],
}) {
  // When no data is provided, show empty state
  if (!seriesData || seriesData.length === 0) {
    return (
      <div style={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无散点图数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const colorMap = {
    'L0': '#1677ff',
    'L1': '#722ed1',
    '转账服务': '#52c41a',
    '账单查询': '#13c2c2',
    '理财咨询': '#faad14',
    '理财解读': '#fa8c16',
  };

  const items = seriesData;

  const series = items.map((item, idx) => {
    const clr = item.color || colorMap[item.name] || '#1677ff';
    const pointData = [item.ttftP50, item.tpotP50, item.callCount, item.name];
    return {
      type: 'scatter',
      name: item.name,
      clip: false,
      data: [pointData],
      symbolSize: Math.sqrt(item.callCount) / 4,
      itemStyle: {
        color: clr,
        borderColor: '#fff',
        borderWidth: 1,
      },
      label: {
        show: true,
        position: 'top',
        fontSize: 9,
        fontWeight: 'bold',
        color: clr,
        formatter: function (p) {
          return p.seriesName;
        },
      },
    };
  });

  // 优秀区 markArea 放在第一个 series
  if (series.length > 0) {
    series[0].markArea = {
      silent: true,
      data: [
        [
          { xAxis: 0, yAxis: 0, itemStyle: { color: 'rgba(82,196,26,.06)' } },
          { xAxis: markAreaMax[0], yAxis: markAreaMax[1] },
        ],
      ],
    };
  }

  const option = {
    tooltip: {
      trigger: 'item',
      textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      formatter: function (p) {
        const score = p.value[0] * 0.7 + p.value[1] * 0.3;
        const tag = score < 250 ? '✅ 优秀' : score < 450 ? '⚠️ 中等' : '❌ 需优化';
        return (
          '<b>' + p.seriesName + '</b><br/>' +
          'TTFT P50: ' + p.value[0] + 'ms<br/>' +
          'TPOT P50: ' + p.value[1] + 'ms<br/>' +
          '调用量: ' + p.value[2] + '<br/>' +
          tag
        );
      },
    },
    grid: {
      left: 65,
      right: 15,
      top: 10,
      bottom: 38,
    },
    xAxis: {
      type: 'value',
      name: 'TTFT P50',
      nameLocation: 'middle',
      nameGap: 18,
      nameTextStyle: { fontSize: 9, color: 'rgba(0,0,0,.45)' },
      max: xAxisMax,
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}ms' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    yAxis: {
      type: 'value',
      name: 'TPOT P50',
      nameLocation: 'middle',
      nameGap: 35,
      nameTextStyle: { fontSize: 9, color: 'rgba(0,0,0,.45)' },
      max: yAxisMax,
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}ms' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    series: series,
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

export default ScatterChart;
