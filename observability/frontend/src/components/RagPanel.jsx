import React from 'react';
import { Card, Table, Tag, Empty } from 'antd';
import ReactEChartsCore from 'echarts-for-react';
import { DemoBadge } from './InsightKpiCard';
import RagQualityChart from './charts/RagQualityChart';
import { getRagMock } from '../services/mockInsights';

/**
 * RagPanel — RAG 检索质量面板（V24 对齐 DEMO v24 · 整合设计 2026-07-25）。
 *
 * 最终呈现（对齐 DEMO RAG tab，仅 3 块，与「全部」视图统一调用记录表解耦）：
 *   ① 📊 RAG 运行指标（5 子指标）   — 运行态（时延 / 触发率 / 错误率，看「系统稳不稳」）
 *   ② 📈 趋势图 · 5 子指标（近 6h） — 运行态趋势（双 Y 轴：左 ms/篇，右 %）
 *   ③ 🔬 RAG 检索质量子面板         — 效果态（Top-K 相关性 + 质量判定表，看「检索准不准」）
 *
 * 数据来源：5 子指标 / 检索质量均来自设计真值 getRagMock()（后端未提供 5 子指标结构，见设计 Q7）。
 * 因此 3 块均挂 DemoBadge，与 DEMO「dev 链路真值 · 近 6h」副标题同义（设计 §7 共享约定）。
 *
 * 真实 fetchInvocations('rag') 不在此 tab 渲染（仅在「全部」视图统一调用记录表呈现，数据不丢）；
 * 后端接入真实 5 子指标 / 质量真值后，由 api/client.js 适配层映射 RagRunMetrics，RagPanel 零改（Q2）。
 */

const RAG = '#08979c';
const RAG_DEEP = '#006d75';
const RAG_SOFT = '#b5f5ec';
const TEXT_SECONDARY = 'rgba(0,0,0,.65)';
const TEXT_TERTIARY = 'rgba(0,0,0,.45)';

/**
 * 与 DEMO 断点严格对齐的响应式样式（桌面 5 列 / ≤980px 3 列 / ≤620px 2 列；
 * 检索质量分栏 ≤860px 上下堆叠）。类名加 ragv24- 前缀避免与全局样式冲突。
 */
const RAG_GRID_CSS = `
.ragv24-kpi{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}
@media(max-width:980px){.ragv24-kpi{grid-template-columns:repeat(3,1fr)}}
@media(max-width:620px){.ragv24-kpi{grid-template-columns:repeat(2,1fr)}}
.ragv24-qgrid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
@media(max-width:860px){.ragv24-qgrid{grid-template-columns:1fr}}
`;

const cardTitleStyle = { color: RAG_DEEP, fontSize: 14, fontWeight: 600 };

function RagPanel() {
  // RAG tab 恒为设计真值 3 块，无需真实 fetch（见顶部注释）。
  const rag = getRagMock();
  const run = rag.run;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <style dangerouslySetInnerHTML={{ __html: RAG_GRID_CSS }} />

      {/* ① RAG 运行指标（5 子指标）= 运行态 */}
      <Card
        size="small"
        style={{ background: '#fff' }}
        title={<CardTitle text="📊 RAG 运行指标（5 子指标）" sub="dev 链路真值 · 近 6h" />}
        extra={<DemoBadge />}
      >
        <RagRunKpiGrid run={run} />
      </Card>

      {/* ② 趋势图 · 5 子指标（近 6h）= 运行态趋势 */}
      <Card
        size="small"
        style={{ background: '#fff' }}
        title={<CardTitle text="📈 趋势图 · 5 子指标（近 6h）" />}
        extra={<DemoBadge />}
      >
        <RagTrendChart trend={run.trend} />
      </Card>

      {/* ③ RAG 检索质量子面板 = 效果态（rag-soft 边框，对齐 DEMO .rag-quality） */}
      <Card
        size="small"
        style={{ background: '#fff', borderColor: RAG_SOFT }}
        title={<CardTitle text="🔬 RAG 检索质量子面板" />}
        extra={<DemoBadge />}
      >
        <div className="ragv24-qgrid">
          <div>
            <div style={{ fontSize: 13, fontWeight: 600, color: TEXT_SECONDARY, marginBottom: 8 }}>
              Top-K 相关性分布
            </div>
            <RagQualityChart topK={rag.quality.topK} height={220} />
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 600, color: TEXT_SECONDARY, marginBottom: 8 }}>
              质量判定表
            </div>
            <RagQualityTable rows={rag.quality.table} />
          </div>
        </div>
      </Card>

      {/* 底部说明（DEMO L843）：运行态 vs 效果态 视角说明 */}
      <div style={{ fontSize: 12, color: TEXT_TERTIARY, lineHeight: 1.6 }}>
        🟡 5 子指标 = 运行态监控（时延/触发率/错误率）；检索质量子面板 = 效果态评估（命中率/相关性/重排增益）。二者视角不同、不重叠：前者看“系统稳不稳”，后者看“检索准不准”。
      </div>
    </div>
  );
}

/* ───────── 卡片标题（带可选副标题，对齐 DEMO card-header）───────── */

function CardTitle({ text, sub }) {
  return (
    <span style={cardTitleStyle}>
      {text}
      {sub && (
        <span style={{ fontSize: 12, fontWeight: 400, color: TEXT_TERTIARY, marginLeft: 8 }}>{sub}</span>
      )}
    </span>
  );
}

/* ───────── ① 运行指标 5 子指标 KPI 网格（对齐 DEMO .rag-kpi）───────── */

function RagRunKpiGrid({ run }) {
  const kpis = [
    { label: '重排时延 P95', value: run.reorderP95Ms, unit: 'ms', delta: run.reorderP95Delta },
    { label: '平均召回文档数', value: run.recallDocs, unit: '篇', delta: run.recallDocsDelta },
    { label: 'RAG 触发率', value: run.triggerRate, unit: '%', delta: run.triggerRateDelta },
    { label: '检索错误率', value: run.retrieveErrRate, unit: '%', delta: run.retrieveErrRateDelta },
    { label: '重排错误率', value: run.reorderErrRate, unit: '%', delta: run.reorderErrRateDelta },
  ];
  return (
    <div className="ragv24-kpi">
      {kpis.map((k) => (
        <RagRunKpiCard key={k.label} {...k} />
      ))}
    </div>
  );
}

/** 单个运行态 KPI 卡（白底 + 1px 边 + 顶部 3px 主色边；数值统一主色深） */
function RagRunKpiCard({ label, value, unit, delta }) {
  const display =
    typeof value === 'number'
      ? Number.isInteger(value)
        ? String(value)
        : value.toFixed(1)
      : value == null
        ? '—'
        : String(value);

  return (
    <div
      style={{
        background: '#fff',
        border: '1px solid #f0f0f0',
        borderRadius: 8,
        borderTop: `3px solid ${RAG}`,
        padding: '14px 12px',
        textAlign: 'center',
      }}
    >
      <div style={{ fontSize: 11, color: TEXT_SECONDARY, marginBottom: 6, lineHeight: 1.3 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 800, color: RAG_DEEP, lineHeight: 1 }}>
        {display}
        {unit && <span style={{ fontSize: 12, fontWeight: 400, color: TEXT_TERTIARY, marginLeft: 2 }}>{unit}</span>}
      </div>
      {delta && <div style={{ fontSize: 10, color: TEXT_TERTIARY, marginTop: 5 }}>{delta}</div>}
    </div>
  );
}

/* ───────── ② 趋势图 5 子指标（双 Y 轴，对齐 DEMO L1110）───────── */

function RagTrendChart({ trend }) {
  if (!trend || !Array.isArray(trend.series) || trend.series.length === 0) {
    return <Empty description="暂无趋势数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  const option = {
    tooltip: { trigger: 'axis' },
    legend: { data: trend.series.map((s) => s.name), top: 0, textStyle: { fontSize: 11, color: TEXT_SECONDARY } },
    grid: { left: 50, right: 50, top: 30, bottom: 30, containLabel: true },
    xAxis: {
      type: 'category',
      data: trend.categories,
      axisLabel: { fontSize: 10, color: TEXT_TERTIARY },
      axisLine: { lineStyle: { color: '#e8e8e8' } },
      axisTick: { show: false },
    },
    yAxis: [
      {
        type: 'value',
        name: 'ms / 篇',
        nameTextStyle: { fontSize: 10, color: TEXT_TERTIARY },
        axisLabel: { fontSize: 10, color: TEXT_TERTIARY },
        splitLine: { lineStyle: { color: '#f5f5f5' } },
      },
      {
        type: 'value',
        name: '%',
        nameTextStyle: { fontSize: 10, color: TEXT_TERTIARY },
        axisLabel: { fontSize: 10, color: TEXT_TERTIARY },
        splitLine: { show: false },
      },
    ],
    series: trend.series.map((s) => ({
      name: s.name,
      type: 'line',
      yAxisIndex: s.yAxisIndex ?? 0,
      data: s.data,
      smooth: true,
      symbolSize: 4,
      itemStyle: { color: s.color },
      lineStyle: { width: 2, color: s.color },
    })),
  };
  return <ReactEChartsCore option={option} style={{ height: 240 }} notMerge lazyUpdate />;
}

/* ───────── ③ 质量判定表（good→green / warn→gold，无红，对齐 DEMO）───────── */

function RagQualityTable({ rows }) {
  return (
    <Table
      dataSource={rows}
      rowKey="dim"
      pagination={false}
      size="small"
      columns={[
        { title: '质量维度', dataIndex: 'dim', key: 'dim' },
        { title: '当前值', dataIndex: 'current', key: 'current', align: 'center' },
        { title: '目标', dataIndex: 'target', key: 'target', align: 'center' },
        {
          title: '判定',
          dataIndex: 'verdict',
          key: 'verdict',
          align: 'center',
          render: (verdict, row) => (
            <Tag color={verdict === 'good' ? 'green' : 'gold'}>
              {row.label || (verdict === 'good' ? '达标' : '未达标')}
            </Tag>
          ),
        },
      ]}
    />
  );
}

export default RagPanel;
