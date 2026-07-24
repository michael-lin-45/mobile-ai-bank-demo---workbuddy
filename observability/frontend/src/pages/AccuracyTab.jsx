import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ApiErrorAlert from '../components/ApiErrorAlert';
import { DemoBadge } from '../components/InsightKpiCard';
import ReactEChartsCore from 'echarts-for-react';
import HeatmapChart from '../components/charts/HeatmapChart';
import { fetchAIAccuracyReport } from '../api/client';
import { isEmpty } from '../services/insightAdapters';
import { getAccuracyReportMock } from '../services/mockInsights';

/**
 * normalizeOverallStats — 归一化「总体统计」字段，兼容两种后端返回：
 *   1) 数组（mock / 标准报告）：原样返回
 *   2) 字符串（后端 buildOverallStats 返回整句摘要，如 "总体意图准确率 94.2%"）：
 *      包成单元素数组，label 取完整文案，value/unit 留空，交给 InsightKpiCard 渲染。
 * 其余（undefined / null / 空串 / 非预期类型）一律回退为空数组，保证 .map 可用。
 *
 * @param {*} raw - accuracyData.overallStats 的原始值
 * @returns {Array<{label: string, value: string, unit: string}>}
 */
function normalizeOverallStats(raw) {
  if (Array.isArray(raw)) return raw;
  if (typeof raw === 'string' && raw.trim().length > 0) {
    // 后端吐的是整句字符串，包成单卡片展示（InsightKpiCard 对空 value/unit 安全）
    return [{ label: raw.trim(), value: '', unit: '' }];
  }
  return [];
}

/**
 * buildSummaryItems — 将 overallStats 规整为「单行摘要条」展示项。
 * 口径与 V23 DEMO 对齐：整体意图准确率 / 改写准确率 L1 / 混淆率 / 样本量。
 *  - 标签「改写准确率」→ 显式「改写准确率 L1」；
 *  - 「样本量」数值做 k 缩写（12840 → 12.8k），去掉「条」单位。
 * @param {Array<{label,value,unit}>} stats
 * @returns {Array<{label:string,value:string,unit:string}>}
 */
function buildSummaryItems(stats) {
  return (stats || []).map((s) => {
    let label = s.label != null ? `${s.label}` : '';
    let value = s.value != null ? `${s.value}` : '-';
    let unit = s.unit != null ? `${s.unit}` : '';
    if (label.includes('改写')) {
      label = '改写准确率 L1';
    }
    if (label.includes('样本')) {
      const n = Number(value) || 0;
      value = n >= 1000 ? `${(n / 1000).toFixed(1)}k` : `${n}`;
      unit = '';
    }
    return { label, value, unit };
  });
}

/**
 * AccuracyTab — 准确率分析 TAB（改造版）。
 *
 * 内容:
 * - 顶部 KPI 汇总（共享 InsightKpiCard，来自 overallStats）
 * - 意图识别准确率趋势（ECharts 折线图，5条线）
 * - 改写准确率分析表（rewrite，接 mock 兜底）
 * - 改写失败根因 TOP3（rootCauses，接 mock 兜底）
 * - 意图混淆矩阵（ECharts 热力图）
 *
 * 真实优先、空则 Mock：fetch 失败/空 → getAccuracyReportMock()（docs/system_design.md §1.2）。
 */
function AccuracyTab() {
  const [loading, setLoading] = useState(true);
  const [accuracyData, setAccuracyData] = useState(null);
  const [demo, setDemo] = useState(false);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      setError(null);
      // 单端点消费统一准确率报告；失败/空 → 回退 mock
      const data = await fetchAIAccuracyReport().catch(() => null);
      const isRealEmpty = isEmpty(data);
      setAccuracyData(isRealEmpty ? getAccuracyReportMock() : data);
      setDemo(isRealEmpty);
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

  const overallStats = normalizeOverallStats(accuracyData?.overallStats);
  const confusionData = accuracyData?.confusion || null;

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <ApiErrorAlert error={error} onRetry={loadData} />

        {/* 顶部单行摘要条（4 指标一行展示，对齐 DEMO V23 E） */}
        {overallStats.length > 0 && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              flexWrap: 'wrap',
              padding: '10px 14px',
              background: '#fafafa',
              borderRadius: 8,
              border: '1px solid #f0f0f0',
              fontSize: 12,
              color: 'rgba(0,0,0,.65)',
            }}
          >
            <span style={{ fontWeight: 600, color: 'rgba(0,0,0,.45)', marginRight: 4 }}>准确率概览</span>
            {demo && <DemoBadge />}
            {buildSummaryItems(overallStats).map((it, i) => (
              <React.Fragment key={i}>
                {i > 0 && <span style={{ color: '#d9d9d9' }}>|</span>}
                <span style={{ whiteSpace: 'nowrap' }}>
                  {it.label}{' '}
                  <b style={{ color: 'rgba(0,0,0,.88)', fontWeight: 700 }}>{it.value}{it.unit}</b>
                </span>
              </React.Fragment>
            ))}
          </div>
        )}

        {/* ── 意图识别准确率趋势 ── */}
        <Card
          title="意图识别准确率趋势"
          extra={overallStats[0] ? <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{overallStats[0].label} {overallStats[0].value}{overallStats[0].unit}</span> : null}
        >
          <AccuracyTrendChart data={accuracyData?.trend} />
        </Card>

        {/* ── 改写准确率分析表 ── */}
        <Card
          title="改写准确率分析"
          extra={demo ? <DemoBadge /> : null}
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
          extra={demo ? <DemoBadge /> : null}
        >
          <RootCauseCards data={accuracyData?.rootCauses} />
        </Card>

        {/* ── 意图混淆矩阵 ── */}
        <Card
          title="意图混淆矩阵"
          extra={<span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>行=实际 · 列=预测</span>}
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
      data: allSeries.map((s, idx) => s.name || `系列${idx + 1}`),
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

/* ── 根因卡片（兼容 pct 数值 0-1 或百分比字符串）── */

function RootCauseCards({ data }) {
  if (!data || data.length === 0) {
    return <Empty description="暂无根因数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  return (
    <div style={{ display: 'flex', gap: 16 }}>
      {data.map((c) => {
        const pctStr = typeof c.pct === 'number' ? `${(c.pct * 100).toFixed(1)}%` : (c.pct || '0%');
        return (
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
              <div style={{ height: '100%', width: pctStr, background: c.color || '#ff4d4f', borderRadius: 4 }} />
            </div>
            <div style={{ textAlign: 'right', fontSize: 12, color: c.color || '#ff4d4f', marginTop: 4 }}>{pctStr}</div>
          </div>
        );
      })}
    </div>
  );
}

export default AccuracyTab;
