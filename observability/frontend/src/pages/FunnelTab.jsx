import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ApiErrorAlert from '../components/ApiErrorAlert';
import FunnelChart from '../components/charts/FunnelChart';
import ReactEChartsCore from 'echarts-for-react';
import { fetchConversionFunnel } from '../api/client';

/**
 * FunnelTab — 业务转化漏斗 TAB
 *
 * - 业务转化漏斗（ECharts funnel，标签在内部，白色粗体）
 * - 分阶段放弃率（环形饼图）
 * - 漏斗明细表
 */
function FunnelTab() {
  const [loading, setLoading] = useState(true);
  const [funnelData, setFunnelData] = useState(null);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchConversionFunnel();
      setFunnelData(data);
    } catch (err) {
      console.error('Failed to load funnel data:', err);
      setError(err.message || '转化漏斗数据加载失败');
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
        <ApiErrorAlert error={error} onRetry={loadData} />
        {/* 业务转化漏斗 + 分阶段放弃率 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Card title="业务转化漏斗">
            <FunnelChart data={funnelData?.stages || []} height={280} />
          </Card>
          <Card title="分阶段放弃率">
            <AbandonPieChart data={funnelData?.abandonPie} />
          </Card>
        </div>

        {/* 漏斗明细表 */}
        <Card title="漏斗明细">
          <Table
            dataSource={funnelData?.details || []}
            columns={FUNNEL_DETAIL_COLUMNS}
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty description="暂无漏斗数据" /> }}
          />
        </Card>
      </div>
    </Spin>
  );
}

/* ── 放弃率环形饼图 ── */

function AbandonPieChart({ data }) {
  if (!data || data.length === 0) {
    return (
      <div style={{ height: 280, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无放弃率数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const option = {
    tooltip: {
      trigger: 'item',
      textStyle: { fontSize: 11, color: 'rgba(0,0,0,.65)' },
      formatter: '{b}: {c} 次 ({d}%)',
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
          formatter: '{b}\n{d}%',
        },
      },
    ],
  };

  return (
    <ReactEChartsCore
      option={option}
      style={{ height: 280 }}
      notMerge
      lazyUpdate
    />
  );
}

/* ── 漏斗明细列 ── */

const FUNNEL_DETAIL_COLUMNS = [
  { title: '阶段', dataIndex: 'stage', key: 'stage', render: (t) => <span style={{ fontWeight: 500 }}>{t}</span> },
  { title: '进入数', dataIndex: 'entered', key: 'entered', align: 'right', render: (v) => <Mono>{v?.toLocaleString()}</Mono> },
  { title: '完成数', dataIndex: 'completed', key: 'completed', align: 'right', render: (v) => <Mono style={{ color: '#52c41a' }}>{v?.toLocaleString()}</Mono> },
  { title: '放弃数', dataIndex: 'abandoned', key: 'abandoned', align: 'right', render: (v) => <Mono style={{ color: v > 0 ? '#ff4d4f' : 'inherit' }}>{v > 0 ? v?.toLocaleString() : '—'}</Mono> },
  {
    title: '转化率', dataIndex: 'conversionRate', key: 'conversionRate', align: 'right',
    render: (v) => <span style={{ color: '#52c41a', fontWeight: 600, fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span>,
  },
  {
    title: '放弃率', dataIndex: 'abandonRate', key: 'abandonRate', align: 'right',
    render: (v) => <span style={{ color: v === '—' ? 'inherit' : '#ff4d4f', fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span>,
  },
  { title: '主要放弃原因', dataIndex: 'reason', key: 'reason', render: (t) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{t || '—'}</span> },
];

function Mono({ children, style, ...rest }) {
  return <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, ...style }} {...rest}>{children}</span>;
}

export default FunnelTab;
