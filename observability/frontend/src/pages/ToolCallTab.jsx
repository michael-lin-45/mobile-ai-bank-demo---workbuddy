import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ReactEChartsCore from 'echarts-for-react';
import { fetchToolStats } from '../api/client';

/**
 * ToolCallTab — 工具调用统计 TAB
 *
 * 上半: 工具调用统计（ECharts 堆叠柱状图）+ KPI摘要
 * 下半: 工具调用明细表
 * P0 隐藏 Skill 区域: <Empty description="Skill 统计暂未开放" />
 */
function ToolCallTab() {
  const [loading, setLoading] = useState(true);
  const [toolData, setToolData] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchToolStats();
      if (data) setToolData(data);
    } catch (err) {
      console.error('Failed to load tool stats:', err);
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
        {/* 工具调用统计 + KPI 摘要 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Card title="MCP 工具调用统计">
            <ToolBarChart data={toolData?.chart} />
          </Card>
          <Card title="KPI 摘要">
            <ToolKpiSummary data={toolData?.kpis} />
          </Card>
        </div>

        {/* 工具调用明细表 */}
        <Card title="MCP 工具调用明细">
          <Table
            dataSource={toolData?.details || []}
            columns={TOOL_DETAIL_COLUMNS}
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty description="暂无工具调用数据" /> }}
          />
        </Card>

        {/* Skill 统计 — P0 隐藏 */}
        <Card title="Skill 调用统计（业务效果）">
          <Empty description="Skill 统计暂未开放" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </Card>
      </div>
    </Spin>
  );
}

/* ── 工具调用堆叠柱状图 ── */

function ToolBarChart({ data }) {
  if (!data) {
    return (
      <div style={{ height: 200, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无工具调用数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const categories = data.categories || [];
  const allSeries = data.series || [];
  const colorMap = { '成功': '#52c41a', '失败': '#ff4d4f', '取消': '#faad14' };

  const option = {
    tooltip: {
      trigger: 'axis',
      textStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)' },
    },
    legend: {
      data: allSeries.map(s => s.name),
      top: 0,
      textStyle: { fontSize: 10 },
    },
    grid: { left: 80, right: 15, top: 30, bottom: 25 },
    xAxis: {
      type: 'value',
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    yAxis: {
      type: 'category',
      data: categories,
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
    },
    series: allSeries.map(s => ({
      name: s.name,
      type: 'bar',
      stack: 't',
      data: s.data || [],
      itemStyle: { color: s.color || colorMap[s.name] || '#1677ff' },
    })),
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

/* ── KPI 摘要 ── */

function ToolKpiSummary({ data }) {
  if (!data) {
    return <Empty description="暂无 KPI 数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  const items = [
    { label: '总调用次数', value: data.totalCalls != null ? String(data.totalCalls) : '—', color: '#1677ff' },
    { label: '成功率', value: data.successRate != null ? String(data.successRate) : '—', color: '#52c41a' },
    { label: 'P95 耗时', value: data.p95Latency != null ? String(data.p95Latency) : '—', color: '#faad14' },
    { label: '错误率', value: data.errorRate != null ? String(data.errorRate) : '—', color: '#ff4d4f' },
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, padding: 12 }}>
      {items.map((item) => (
        <div key={item.label} style={{ textAlign: 'center' }}>
          <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', marginBottom: 4 }}>{item.label}</div>
          <div style={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, fontSize: 18, color: item.color }}>
            {item.value}
          </div>
        </div>
      ))}
    </div>
  );
}

/* ── 工具明细列 ── */

const TOOL_DETAIL_COLUMNS = [
  { title: '工具名称', dataIndex: 'tool', key: 'tool', render: (t) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{t}</span> },
  { title: '调用次数', dataIndex: 'calls', key: 'calls', align: 'center', render: (v) => <Mono>{v}</Mono> },
  { title: '成功', dataIndex: 'success', key: 'success', align: 'center', render: (v) => <Mono style={{ color: '#52c41a' }}>{v}</Mono> },
  { title: '失败', dataIndex: 'failed', key: 'failed', align: 'center', render: (v) => <Mono style={{ color: v > 0 ? '#ff4d4f' : 'inherit' }}>{v}</Mono> },
  { title: '平均耗时', dataIndex: 'avgLatency', key: 'avgLatency', align: 'center', render: (v) => <Mono>{v}</Mono> },
  { title: 'P95 耗时', dataIndex: 'p95Latency', key: 'p95Latency', align: 'center', render: (v) => <Mono style={{ color: '#faad14' }}>{v}</Mono> },
  {
    title: '错误率', dataIndex: 'errorRate', key: 'errorRate', align: 'center',
    render: (v) => <span style={{ color: v > 5 ? '#ff4d4f' : v > 0 ? '#faad14' : '#52c41a', fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span>,
  },
  { title: '典型错误', dataIndex: 'typicalError', key: 'typicalError', render: (t) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{t || '—'}</span> },
];

function Mono({ children, style, ...rest }) {
  return <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, ...style }} {...rest}>{children}</span>;
}

export default ToolCallTab;
