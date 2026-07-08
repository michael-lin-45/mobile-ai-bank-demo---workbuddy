import React from 'react';
import ReactEChartsCore from 'echarts-for-react';
import { Empty } from 'antd';

/**
 * FunnelChart — ECharts 漏斗图
 *
 * 用于展示业务转化漏斗，标签在内部，白色粗体。
 * 参照 dashboard-v15 chart-funnel 实现。
 *
 * Props:
 * - data: [{ name, value, itemStyle? }]
 * - height: number (default 280)
 * - title: string (optional)
 */
function FunnelChart({ data = [], height = 280 }) {
  if (!data || data.length === 0) {
    return (
      <div style={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无漏斗数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const option = {
    tooltip: {
      trigger: 'item',
      textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 11 },
      formatter: '{b}: <b>{c}</b>',
    },
    series: [
      {
        type: 'funnel',
        left: '8%',
        right: '8%',
        top: 5,
        bottom: 5,
        sort: 'none',
        gap: 4,
        label: {
          show: true,
          position: 'inside',
          fontSize: 13,
          color: '#fff',
          fontWeight: 'bold',
          formatter: '{b}\n{c}',
        },
        labelLine: {
          show: false,
        },
        data: data,
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

export default FunnelChart;
