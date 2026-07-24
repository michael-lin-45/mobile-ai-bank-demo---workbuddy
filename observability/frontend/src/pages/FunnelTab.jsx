import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ApiErrorAlert from '../components/ApiErrorAlert';
import FunnelChart from '../components/charts/FunnelChart';
import InsightKpiCard, { DemoBadge } from '../components/InsightKpiCard';
import ReactEChartsCore from 'echarts-for-react';
import { fetchConversionFunnel } from '../api/client';
import { toFunnelChartData, isEmpty } from '../services/insightAdapters';
import { getConversionFunnelMock } from '../services/mockInsights';

/**
 * FunnelTab — 业务转化漏斗 TAB（改造版）。
 *
 * - 顶部流失气泡概览 + 共享彩色 KPI 卡（InsightKpiCard）
 * - 业务转化漏斗（ECharts funnel，stages 经 toFunnelChartData 归一 {name,value}，修复 undefined）
 * - 分阶段放弃率（环形饼图）
 * - 漏斗明细表：放弃率阈值着色 + 最大放弃阶段高亮
 * - 流失画像 3 表（按意图 / 渠道 / 时段，接 mock 兜底修复空画像）
 *
 * 真实优先、空则 Mock：fetch 失败/空 → getConversionFunnelMock()（docs/system_design.md §1.2）。
 */
function FunnelTab() {
  const [loading, setLoading] = useState(true);
  const [funnelData, setFunnelData] = useState(null);
  const [demo, setDemo] = useState(false);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 真实优先：失败/空 → 回退 mock
      const data = await fetchConversionFunnel().catch(() => null);
      const isRealEmpty = isEmpty(data);
      setFunnelData(isRealEmpty ? getConversionFunnelMock() : data);
      setDemo(isRealEmpty);
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

  const details = funnelData?.details || [];
  const maxAbandoned = details.reduce((m, d) => Math.max(m, d.abandoned || 0), 0);
  const totalEntered = details.reduce((s, d) => s + (d.entered || 0), 0);
  const totalAbandoned = details.reduce((s, d) => s + (d.abandoned || 0), 0);
  const overallAbandonRate = totalEntered > 0 ? ((totalAbandoned / totalEntered) * 100).toFixed(1) : '0.0';

  const churn = funnelData?.churnProfile || {};

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <ApiErrorAlert error={error} onRetry={loadData} />

        {/* 顶部流失气泡概览 + 共享 KPI 卡 */}
        <Card size="small">
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{
                width: 44, height: 44, borderRadius: '50%',
                background: abandonColorBg(overallAbandonRate),
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 11, fontWeight: 700, color: abandonColor(overallAbandonRate),
                border: `2px solid ${abandonColor(overallAbandonRate)}`,
              }}>
                流失
              </span>
              <div>
                <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>整体放弃率</div>
                <div style={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, fontSize: 20, color: abandonColor(overallAbandonRate) }}>
                  {overallAbandonRate}%
                </div>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 12, flex: 1, minWidth: 0 }}>
              <InsightKpiCard label="总进入" value={totalEntered} color="#1677ff" bg="#f0f5ff" demo={demo} />
              <InsightKpiCard label="总放弃" value={totalAbandoned} color="#ff4d4f" bg="#fff2f0" demo={demo} />
              <InsightKpiCard
                label="最大放弃阶段"
                value={maxAbandoned > 0 ? maxAbandonStage(details, maxAbandoned) : '—'}
                color="#fa8c16"
                bg="#fff7e6"
                demo={demo}
              />
            </div>
          </div>
        </Card>

        {/* 业务转化漏斗 + 分阶段放弃率 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Card
            title="业务转化漏斗"
            extra={demo ? <DemoBadge /> : null}
          >
            <FunnelChart data={toFunnelChartData(funnelData?.stages || [])} height={280} />
          </Card>
          <Card
            title="分阶段放弃率"
            extra={demo ? <DemoBadge /> : null}
          >
            <AbandonPieChart data={funnelData?.abandonPie} />
          </Card>
        </div>

        {/* 漏斗明细表 */}
        <Card
          title="漏斗明细"
          extra={demo ? <DemoBadge /> : null}
        >
          <Table
            dataSource={details}
            columns={FUNNEL_DETAIL_COLUMNS(maxAbandoned)}
            pagination={false}
            size="small"
            rowKey="stage"
            locale={{ emptyText: <Empty description="暂无漏斗数据" /> }}
          />
        </Card>

        {/* 流失画像 3 表 */}
        <Card
          title="流失画像"
          extra={demo ? <DemoBadge /> : null}
        >
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
            <ChurnTable title="按意图" rows={churn.intent} />
            <ChurnTable title="按渠道" rows={churn.channel} />
            <ChurnTable title="按时段" rows={churn.time} />
          </div>
        </Card>
      </div>
    </Spin>
  );
}

/* ── 流失画像子表 ── */

function ChurnTable({ title, rows }) {
  const data = rows || [];
  return (
    <div>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 8, color: 'rgba(0,0,0,.65)' }}>{title}</div>
      {data.length === 0 ? (
        <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <Table
          dataSource={data}
          pagination={false}
          size="small"
          rowKey={(r, i) => r.name || i}
          columns={[
            { title: '维度', dataIndex: 'name', render: (t) => <span style={{ fontSize: 12 }}>{t}</span> },
            { title: '流失', dataIndex: 'count', align: 'right', render: (v) => <span style={{ color: '#ff4d4f' }}>{v}</span> },
            {
              title: '流失率', dataIndex: 'rate', align: 'right',
              render: (v) => <span style={{ color: abandonColor(v), fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}%</span>,
            },
          ]}
        />
      )}
    </div>
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

/* ── 漏斗明细列（含阈值着色 + 最大高亮）── */

function FUNNEL_DETAIL_COLUMNS(maxAbandoned) {
  return [
    { title: '阶段', dataIndex: 'stage', key: 'stage', render: (t) => <span style={{ fontWeight: 500 }}>{t}</span> },
    { title: '进入数', dataIndex: 'entered', key: 'entered', align: 'right', render: (v) => <Mono>{v?.toLocaleString()}</Mono> },
    { title: '完成数', dataIndex: 'completed', key: 'completed', align: 'right', render: (v) => <Mono style={{ color: '#52c41a' }}>{v?.toLocaleString()}</Mono> },
    {
      title: '放弃数', dataIndex: 'abandoned', key: 'abandoned', align: 'right',
      render: (v, r) => {
        const isMax = maxAbandoned > 0 && v === maxAbandoned;
        return (
          <span style={{
            color: v > 0 ? '#ff4d4f' : 'inherit',
            fontWeight: isMax ? 700 : 400,
            fontFamily: '"JetBrains Mono", monospace', fontSize: 12,
            background: isMax ? '#fff1f0' : 'transparent',
            padding: isMax ? '2px 6px' : '0',
            borderRadius: isMax ? 4 : 0,
          }}>{v > 0 ? v?.toLocaleString() : '—'}</span>
        );
      },
    },
    {
      title: '转化率', dataIndex: 'conversionRate', key: 'conversionRate', align: 'right',
      render: (v) => <span style={{ color: '#52c41a', fontWeight: 600, fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span>,
    },
    {
      title: '放弃率', dataIndex: 'abandonRate', key: 'abandonRate', align: 'right',
      render: (v) => <span style={{ color: abandonColor(v), fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}</span>,
    },
    { title: '主要放弃原因', dataIndex: 'reason', key: 'reason', render: (t) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{t || '—'}</span> },
  ];
}

/* ── 辅助 ── */

/** 放弃率阈值着色：>25% 红，>10% 橙，否则绿 */
function abandonColor(v) {
  if (v == null || v === '—') return 'inherit';
  const n = typeof v === 'number' ? v : parseFloat(String(v).replace('%', ''));
  if (isNaN(n)) return 'inherit';
  if (n > 25) return '#ff4d4f';
  if (n > 10) return '#faad14';
  return '#52c41a';
}

function abandonColorBg(v) {
  const c = abandonColor(v);
  if (c === '#ff4d4f') return '#fff2f0';
  if (c === '#faad14') return '#fffbe6';
  return '#f6ffed';
}

function maxAbandonStage(details, max) {
  const hit = details.find((d) => (d.abandoned || 0) === max);
  return hit ? hit.stage : '—';
}

function Mono({ children, style, ...rest }) {
  return <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, ...style }} {...rest}>{children}</span>;
}

export default FunnelTab;
