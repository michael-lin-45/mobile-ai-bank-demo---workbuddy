import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ApiErrorAlert from '../components/ApiErrorAlert';
import ReactEChartsCore from 'echarts-for-react';
import { fetchSatisfaction } from '../api/client';

/**
 * SatisfactionTab — 用户满意度 TAB
 *
 * - 满意度分布（三栏: 满意68%/一般22%/不满意10%，带进度条）
 * - 满意度趋势（7天折线图）
 * - 不满意原因分布（饼图）
 * - 低满意度会话列表（表格）
 * - 转人工率 Empty 占位
 */
function SatisfactionTab() {
  const [loading, setLoading] = useState(true);
  const [satData, setSatData] = useState(null);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchSatisfaction();
      setSatData(data);
    } catch (err) {
      console.error('Failed to load satisfaction data:', err);
      setError(err.message || '满意度数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const distribution = satData?.distribution || {};
  const npsInfo = satData?.npsInfo;

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <ApiErrorAlert error={error} onRetry={loadData} />
        {/* 满意度分布 */}
        <Card
          title="用户满意度分布"
          extra={<span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{npsInfo || '—'}</span>}
        >
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
            <SatisfactionStat value={distribution.satisfied || '—'} label="👍 满意" color="#52c41a" />
            <SatisfactionStat value={distribution.neutral || '—'} label="😐 一般" color="#faad14" />
            <SatisfactionStat value={distribution.unsatisfied || '—'} label="👎 不满意" color="#ff4d4f" />
          </div>
        </Card>

        {/* 转人工率 Empty 占位 */}
        <Card title="转人工率">
          <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </Card>

        {/* 满意度趋势 + 不满意原因 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Card title="满意度趋势（7天）">
            <SatTrendChart data={satData?.trend} />
          </Card>
          <Card title="不满意原因分布">
            <SatReasonPieChart data={satData?.reasons} />
          </Card>
        </div>

        {/* 低满意度会话列表 */}
        <Card title="低满意度会话（需关注）">
          <Table
            dataSource={satData?.lowScoreSessions || []}
            columns={LOW_SAT_COLUMNS}
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty description="暂无低满意度会话" /> }}
          />
        </Card>
      </div>
    </Spin>
  );
}

/* ── 满意度分布统计 ── */

function SatisfactionStat({ value, label, color }) {
  const pctNum = parseInt(value);
  return (
    <div style={{ textAlign: 'center', padding: 12 }}>
      <div style={{ fontSize: 32, fontWeight: 700, color, marginBottom: 4 }}>{value}</div>
      <div style={{ fontSize: 12, color: 'rgba(0,0,0,.65)', marginBottom: 8 }}>{label}</div>
      <div style={{ height: 8, background: '#f0f0f0', borderRadius: 4, overflow: 'hidden' }}>
        <div style={{ height: '100%', width: `${isNaN(pctNum) ? 0 : pctNum}%`, background: color, borderRadius: 4 }} />
      </div>
    </div>
  );
}

/* ── 满意度趋势折线图 ── */

function SatTrendChart({ data }) {
  if (!data) {
    return (
      <div style={{ height: 200, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无趋势数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const option = {
    tooltip: {
      trigger: 'axis',
      textStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)' },
    },
    grid: { left: 40, right: 15, top: 15, bottom: 25 },
    xAxis: {
      type: 'category',
      data: data.categories || ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
    },
    yAxis: {
      type: 'value',
      min: data.yAxisMin != null ? data.yAxisMin : 60,
      max: data.yAxisMax != null ? data.yAxisMax : 100,
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    series: [
      {
        type: 'line',
        smooth: true,
        data: data.values || [],
        itemStyle: { color: '#52c41a' },
        areaStyle: { color: '#52c41a', opacity: 0.1 },
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

/* ── 不满意原因饼图 ── */

function SatReasonPieChart({ data }) {
  if (!data || data.length === 0) {
    return (
      <div style={{ height: 200, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无原因数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

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
        radius: ['35%', '60%'],
        center: ['50%', '42%'],
        data: data,
        label: {
          fontSize: 11,
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

/* ── 低满意度会话列 ── */

const LOW_SAT_COLUMNS = [
  { title: 'Session ID', dataIndex: 'sessionId', key: 'sessionId', render: (v) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, color: '#1677ff', cursor: 'pointer' }}>{v}</span> },
  { title: 'User ID', dataIndex: 'userId', key: 'userId', render: (v) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span> },
  { title: '域', dataIndex: 'domain', key: 'domain' },
  {
    title: '满意度', dataIndex: 'rating', key: 'rating', align: 'center',
    render: (v) => (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 500, background: '#fff2f0', color: '#ff4d4f', border: '1px solid #ffccc7' }}>
        👎 {v} 分
      </span>
    ),
  },
  { title: '原因', dataIndex: 'reason', key: 'reason' },
  { title: '会话时长', dataIndex: 'duration', key: 'duration', render: (v) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span> },
  {
    title: '操作', dataIndex: 'action', key: 'action', align: 'center',
    render: () => <span style={{ color: '#1677ff', cursor: 'pointer', fontSize: 13 }}>查看回放</span>,
  },
];

export default SatisfactionTab;
