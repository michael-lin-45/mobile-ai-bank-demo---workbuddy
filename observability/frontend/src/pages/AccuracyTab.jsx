import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ApiErrorAlert from '../components/ApiErrorAlert';
import ReactEChartsCore from 'echarts-for-react';
import HeatmapChart from '../components/charts/HeatmapChart';
import { fetchAIAccuracyReport } from '../api/client';

/**
 * AccuracyTab — 准确率分析 TAB
 *
 * 内容:
 * - 意图识别准确率趋势（ECharts 折线图，5条线）
 * - 改写准确率分析表
 * - 改写失败根因 TOP3（3 张彩色卡片）
 * - 意图混淆矩阵（ECharts 热力图，visualMap 隐藏）
 */
function AccuracyTab() {
  const [loading, setLoading] = useState(true);
  const [accuracyData, setAccuracyData] = useState(null);
  const [confusionData, setConfusionData] = useState(null);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      setError(null);
      // 单端点消费统一准确率报告（趋势+改写表+根因+混淆矩阵），修复契约错配
      const data = await fetchAIAccuracyReport();
      setAccuracyData(data);
      setConfusionData(data?.confusion || null);
    } catch (err) {
      console.error('Failed to load accuracy data:', err);
      setError(err.message || '准确率数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const overallStats = accuracyData?.overallStats;
  const rewriteSummary = accuracyData?.rewriteSummary;
  const rootCauseSummary = accuracyData?.rootCauseSummary;

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <ApiErrorAlert error={error} onRetry={loadData} />
        {/* ── 意图识别准确率趋势 ── */}
        <Card
          title="意图识别准确率趋势"
          extra={<span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{overallStats || '—'}</span>}
        >
          <AccuracyTrendChart data={accuracyData?.trend} />
        </Card>

        {/* ── 改写准确率分析表 ── */}
        <Card
          title="改写准确率分析"
          extra={<span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{rewriteSummary || '—'}</span>}
        >
          <Table
            dataSource={accuracyData?.rewrite || []}
            columns={REWRITE_COLUMNS}
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty description="暂无改写数据" /> }}
          />
        </Card>

        {/* ── 改写失败根因 TOP3 ── */}
        <Card
          title="改写失败根因 TOP3"
          extra={<span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{rootCauseSummary || '—'}</span>}
        >
          <RootCauseCards data={accuracyData?.rootCauses} />
        </Card>

        {/* ── 意图混淆矩阵 ── */}
        <Card
          title="意图混淆矩阵"
          extra={<span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>行=预测 · 列=实际</span>}
        >
          {confusionData ? (
            <HeatmapChart
              xLabels={confusionData.labels || ['转账', '账单', '理财咨询', '理财解读', '闲聊']}
              yLabels={confusionData.labels || ['转账', '账单', '理财咨询', '理财解读', '闲聊']}
              matrix={confusionData.matrix || []}
              height={280}
            />
          ) : (
            <HeatmapChart height={280} />
          )}
        </Card>
      </div>
    </Spin>
  );
}

/* ── 准确率趋势折线图 ── */

function AccuracyTrendChart({ data }) {
  if (!data) {
    return (
      <div style={{ height: 280, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="暂无准确率数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const allSeries = (data.series && data.series.length > 0) ? data.series : [];
  const allCategories = data.categories || [];
  const colorPalette = ['#1677ff', '#722ed1', '#52c41a', '#13c2c2', '#faad14'];
  const symbolPalette = ['circle', 'diamond', 'triangle', 'rect', 'roundRect'];

  const series = allSeries.map((s, idx) => ({
    name: s.name || '—',
    type: 'line',
    smooth: true,
    data: s.data || [],
    itemStyle: { color: s.color || colorPalette[idx % colorPalette.length] },
    symbol: s.symbol || symbolPalette[idx % symbolPalette.length],
    symbolSize: 4,
  }));

  const option = {
    tooltip: {
      trigger: 'axis',
      textStyle: { color: 'rgba(0,0,0,.65)' },
    },
    legend: {
      data: allSeries.map(s => s.name),
      top: 0,
      textStyle: { fontSize: 10, color: 'rgba(0,0,0,.65)' },
    },
    grid: { left: 40, right: 15, top: 30, bottom: 25 },
    xAxis: {
      type: 'category',
      data: allCategories.length > 0 ? allCategories : Array.from({ length: (allSeries[0]?.data || []).length }, (_, i) => String(i + 1)),
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)' },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    series: series,
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

/* ── 改写准确率列 ── */

const REWRITE_COLUMNS = [
  { title: '改写场景', dataIndex: 'scene', key: 'scene' },
  { title: '总次数', dataIndex: 'total', key: 'total', align: 'center' },
  { title: '正确次数', dataIndex: 'correct', key: 'correct', align: 'center' },
  {
    title: '准确率', dataIndex: 'rate', key: 'rate', align: 'center',
    render: (val) => <span style={{ color: val > 90 ? '#52c41a' : '#faad14', fontWeight: 600 }}>{val}%</span>,
  },
  {
    title: '趋势', dataIndex: 'trend', key: 'trend', align: 'center',
    render: (val) => <span style={{ color: val > 0 ? '#52c41a' : '#ff4d4f' }}>{val > 0 ? `↑ ${val}%` : `↓ ${Math.abs(val)}%`}</span>,
  },
  { title: '典型错误', dataIndex: 'errorExample', key: 'errorExample', render: (t) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{t}</span> },
];

/* ── 根因卡片 ── */

function RootCauseCards({ data }) {
  if (!data || data.length === 0) {
    return <Empty description="暂无根因数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  return (
    <div style={{ display: 'flex', gap: 16 }}>
      {data.map((c) => (
        <div
          key={c.rank || c.key}
          style={{
            flex: 1,
            background: c.bg || '#fff2f0',
            borderRadius: 6,
            padding: '16px 20px',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <span style={{ fontWeight: 600, fontSize: 14 }}>{c.rank}. {c.title}</span>
            <span style={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, fontSize: 20, color: c.color || '#ff4d4f' }}>
              {c.count}
            </span>
          </div>
          <div style={{ fontSize: 12, color: 'rgba(0,0,0,.65)', marginBottom: 8 }}>{c.desc}</div>
          <div style={{ height: 8, background: 'rgba(0,0,0,.06)', borderRadius: 4, overflow: 'hidden' }}>
            <div style={{ height: '100%', width: c.pct, background: c.color || '#ff4d4f', borderRadius: 4 }} />
          </div>
          <div style={{ textAlign: 'right', fontSize: 12, color: c.color || '#ff4d4f', marginTop: 4 }}>{c.pct}</div>
        </div>
      ))}
    </div>
  );
}

export default AccuracyTab;
