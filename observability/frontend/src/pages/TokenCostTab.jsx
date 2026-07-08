import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ReactEChartsCore from 'echarts-for-react';
import { fetchTokenCost } from '../api/client';

/**
 * TokenCostTab — Token 成本 TAB
 *
 * - Token 消耗趋势（堆叠柱状图，按模型）
 * - Token 成本拆解（ECharts 饼图，按意图，标注 {b}\n{c}次 ({d}%)）
 * - Token 明细表（意图×模型交叉表）
 * 全文使用 `tokens`（非 `tok` 缩写）
 */
function TokenCostTab() {
  const [loading, setLoading] = useState(true);
  const [trendData, setTrendData] = useState(null);
  const [breakdownData, setBreakdownData] = useState(null);
  const [detailData, setDetailData] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [trendRes, breakdownRes, detailRes] = await Promise.allSettled([
        fetchTokenCost({ type: 'trend' }),
        fetchTokenCost({ type: 'breakdown' }),
        fetchTokenCost({ type: 'detail' }),
      ]);
      if (trendRes.status === 'fulfilled') setTrendData(trendRes.value);
      if (breakdownRes.status === 'fulfilled') setBreakdownData(breakdownRes.value);
      if (detailRes.status === 'fulfilled') setDetailData(detailRes.value?.content || detailRes.value || []);
    } catch (err) {
      console.error('Failed to load token cost data:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* Token 消耗趋势 + 成本拆解 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Card title="Token 消耗趋势（按模型）">
            <TokenTrendChart data={trendData} />
          </Card>
          <Card title="Token 成本拆解（按意图）">
            <TokenPieChart data={breakdownData} />
          </Card>
        </div>

        {/* Token 明细表 */}
        <Card title="Token 明细（按意图 × 模型）">
          <Table
            dataSource={detailData || []}
            columns={TOKEN_DETAIL_COLUMNS}
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty description="暂无 Token 数据" /> }}
          />
        </Card>
      </div>
    </Spin>
  );
}

/* ── Token 趋势堆叠柱状图 ── */

function TokenTrendChart({ data }) {
  // data shape: { totalInput, totalOutput, totalCost, breakdown: [{key, inputTokens, outputTokens, costEstimate}] }
  if (!data || !data.breakdown || data.breakdown.length === 0) {
    return (
      <div style={{ height: 200, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无趋势数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const breakdown = data.breakdown || [];
  const categories = breakdown.map(d => d.key || 'unknown');
  const colorPalette = ['#722ed1', '#d3adf7', '#1677ff', '#52c41a'];

  const option = {
    tooltip: {
      trigger: 'axis',
      textStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)' },
    },
    legend: {
      data: ['输入 Token', '输出 Token'],
      top: 0,
      textStyle: { fontSize: 11 },
    },
    grid: { left: 40, right: 15, top: 30, bottom: 25 },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    series: [
      {
        name: '输入 Token',
        type: 'bar',
        stack: 't',
        data: breakdown.map(d => d.inputTokens || 0),
        itemStyle: { color: colorPalette[0] },
      },
      {
        name: '输出 Token',
        type: 'bar',
        stack: 't',
        data: breakdown.map(d => d.outputTokens || 0),
        itemStyle: { color: colorPalette[1] },
      },
    ],
  };

  return (
    <ReactEChartsCore
      option={option}
      style={{ height: 200 }}
      notMerge
      lazyUpdate
    />
  );
}

/* ── Token 饼图 ── */

function TokenPieChart({ data }) {
  // data shape: { totalInput, totalOutput, totalCost, breakdown: [{key, inputTokens, outputTokens, costEstimate}] }
  const breakdown = data?.breakdown;
  if (!breakdown || breakdown.length === 0) {
    return (
      <div style={{ height: 200, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无成本数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const pieData = breakdown.map(d => ({
    name: d.key || 'unknown',
    value: (d.inputTokens || 0) + (d.outputTokens || 0),
  }));

  const option = {
    tooltip: {
      trigger: 'item',
      textStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)' },
    },
    legend: {
      bottom: 0,
      textStyle: { fontSize: 11 },
    },
    series: [
      {
        type: 'pie',
        radius: ['40%', '65%'],
        center: ['50%', '45%'],
        data: pieData,
        label: {
          fontSize: 11,
          formatter: '{b}\n{c}次 ({d}%)',
        },
      },
    ],
  };

  return (
    <ReactEChartsCore
      option={option}
      style={{ height: 200 }}
      notMerge
      lazyUpdate
    />
  );
}

/* ── Token 明细列 ── */

const TOKEN_DETAIL_COLUMNS = [
  { title: '意图', dataIndex: 'intent', key: 'intent' },
  { title: '模型', dataIndex: 'model', key: 'model', render: (m) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, color: '#722ed1' }}>{m}</span> },
  { title: '调用次数', dataIndex: 'calls', key: 'calls', align: 'right', render: (v) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v?.toLocaleString()}</span> },
  { title: '输入 Token', dataIndex: 'inputTokens', key: 'inputTokens', align: 'right', render: (v) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v?.toLocaleString()}</span> },
  { title: '输出 Token', dataIndex: 'outputTokens', key: 'outputTokens', align: 'right', render: (v) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v?.toLocaleString()}</span> },
  { title: '总 Token', dataIndex: 'totalTokens', key: 'totalTokens', align: 'right', render: (v) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, fontWeight: 600 }}>{v?.toLocaleString()}</span> },
  {
    title: '占比', dataIndex: 'percentage', key: 'percentage', align: 'right',
    render: (v) => {
      const pct = parseFloat(v);
      return <span style={{ color: pct > 20 ? '#1677ff' : 'rgba(0,0,0,.65)', fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span>;
    },
  },
];

export default TokenCostTab;
