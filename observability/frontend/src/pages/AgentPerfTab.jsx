import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin } from 'antd';
import ApiErrorAlert from '../components/ApiErrorAlert';
import BoxplotChart from '../components/charts/BoxplotChart';
import ScatterChart from '../components/charts/ScatterChart';
import { fetchAgentPerformance } from '../api/client';

/**
 * AgentPerfTab — Agent 性能 TAB
 *
 * 双维度子页签:
 * - Agent 维度: KPI×4 → 性能明细表 → TTFT箱线图 → TTFT vs TPOT散点图
 * - LLM 维度: 同样结构，按模型+Agent对分组
 *
 * 箱线图: Agent蓝 #1677ff / LLM紫 #722ed1
 * 散点图: 6个独立 series，六色映射
 */
function AgentPerfTab() {
  const [dimension, setDimension] = useState('agent');
  const [loading, setLoading] = useState(true);
  const [perfData, setPerfData] = useState(null);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchAgentPerformance({ dimension });
      setPerfData(data);
    } catch (err) {
      console.error('Failed to load agent performance:', err);
      setError(err.message || 'Agent 性能数据加载失败');
    } finally {
      setLoading(false);
    }
  }, [dimension]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const isAgent = dimension === 'agent';
  const kpis = perfData?.kpis || {};
  const tableData = perfData?.tables?.[isAgent ? 'agent' : 'llm'] || [];
  const boxplotData = perfData?.boxplots?.[isAgent ? 'agent' : 'llm'] || [];
  const scatterData = perfData?.scatters?.[isAgent ? 'agent' : 'llm'] || [];
  const categories = perfData?.categories?.[isAgent ? 'agent' : 'llm'] || [];
  const columns = isAgent ? AGENT_PERF_COLUMNS : LLM_PERF_COLUMNS;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <ApiErrorAlert error={error} onRetry={loadData} />
      {/* 子页签 */}
      <Card bodyStyle={{ padding: '0 0 12px' }}>
        <div style={{ display: 'flex', gap: 0, borderBottom: 'none' }}>
          <div
            onClick={() => setDimension('agent')}
            style={{
              padding: '8px 20px',
              cursor: 'pointer',
              fontSize: 12,
              color: isAgent ? '#1677ff' : 'rgba(0,0,0,.65)',
              borderBottom: isAgent ? '2px solid #1677ff' : '2px solid transparent',
              fontWeight: isAgent ? 500 : 400,
            }}
          >
            Agent 维度
          </div>
          <div
            onClick={() => setDimension('llm')}
            style={{
              padding: '8px 20px',
              cursor: 'pointer',
              fontSize: 12,
              color: !isAgent ? '#1677ff' : 'rgba(0,0,0,.65)',
              borderBottom: !isAgent ? '2px solid #1677ff' : '2px solid transparent',
              fontWeight: !isAgent ? 500 : 400,
            }}
          >
            LLM 维度
          </div>
        </div>
      </Card>

      <Spin spinning={loading}>
        {/* KPI 卡片 ×4 */}
        <div style={{ display: 'flex', gap: 16, marginBottom: 16 }}>
          <KpiCard label={isAgent ? '总调用' : 'LLM 调用'} value={kpis.totalCalls != null ? String(kpis.totalCalls) : '—'} bg="#f0f5ff" />
          <KpiCard label="平均耗时" value={kpis.avgLatency != null ? String(kpis.avgLatency) : '—'} unit="ms" bg="#f6ffed" />
          <KpiCard label="平均错误率" value={kpis.errorRate != null ? String(kpis.errorRate) : '—'} unit="%" bg="#fff2f0" color="#ff4d4f" />
          <KpiCard label="总 Token" value={kpis.totalTokens != null ? String(kpis.totalTokens) : '—'} bg="#f9f0ff" />
        </div>

        {/* 性能明细表 */}
        <Card style={{ marginBottom: 16 }}>
          <Table
            dataSource={tableData}
            columns={columns}
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty description="暂无性能数据" /> }}
          />
        </Card>

        {/* 箱线图 + 散点图 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Card
            title={<span style={{ fontSize: 12 }}>{isAgent ? 'TTFT 延迟分布' : 'LLM TTFT 分布'}</span>}
            bodyStyle={{ padding: 16 }}
          >
            <div style={{ fontSize: 10, color: 'rgba(0,0,0,.45)', marginTop: -8, marginBottom: 8 }}>
              盒子 = P25~P75 | 须线顶端 = P95（95% 请求在此时间内完成）
            </div>
            <BoxplotChart
              categories={categories}
              data={boxplotData}
              color={isAgent ? '#1677ff' : '#722ed1'}
              height={200}
            />
          </Card>

          <Card
            title={<span style={{ fontSize: 12 }}>{isAgent ? 'TTFT vs TPOT' : 'LLM TTFT vs TPOT'}</span>}
            bodyStyle={{ padding: 16 }}
          >
            <ScatterChart
              seriesData={scatterData}
              height={200}
              xAxisMax={isAgent ? 550 : 480}
              yAxisMax={isAgent ? 32 : 28}
              markAreaMax={isAgent ? [220, 20] : [200, 18]}
            />
          </Card>
        </div>
      </Spin>
    </div>
  );
}

/* ── KPI 卡片 ── */

function KpiCard({ label, value, unit, bg, color }) {
  return (
    <div style={{ flex: 1, textAlign: 'center', padding: 10, background: bg, borderRadius: 6 }}>
      <div style={{ fontSize: 11, color: 'rgba(0,0,0,.45)' }}>{label}</div>
      <div style={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, fontSize: 18, color: color || 'rgba(0,0,0,.88)' }}>
        {value}
        {unit && <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', fontWeight: 400 }}>{unit}</span>}
      </div>
    </div>
  );
}

/* ── 性能明细表 ── */

const AGENT_PERF_COLUMNS = [
  { title: 'Agent', dataIndex: 'agent', key: 'agent', render: (t) => <span style={{ fontWeight: 600, fontSize: 13 }}>{t}</span> },
  { title: '级别', dataIndex: 'level', key: 'level', render: (l) => <LevelBadge level={l} /> },
  { title: '调用', dataIndex: 'calls', key: 'calls', align: 'center', render: (v) => <Mono>{v?.toLocaleString()}</Mono> },
  { title: '总耗时', dataIndex: 'totalLatency', key: 'totalLatency', render: (v, r) => <BarCell value={v} max={1250} /> },
  { title: 'TTFT P50', dataIndex: 'ttftP50', key: 'ttftP50', align: 'center', render: (v) => <Mono>{v}ms</Mono> },
  { title: 'TTFT P95', dataIndex: 'ttftP95', key: 'ttftP95', align: 'center', render: warnP95 },
  { title: 'TPOT P50', dataIndex: 'tpotP50', key: 'tpotP50', align: 'center', render: (v) => <Mono>{v}ms</Mono> },
  { title: 'TPOT P95', dataIndex: 'tpotP95', key: 'tpotP95', align: 'center', render: warnP95 },
  { title: '总Token', dataIndex: 'totalTokens', key: 'totalTokens', align: 'center', render: (v) => <Mono>{v}</Mono> },
  {
    title: '错误%', dataIndex: 'errorRate', key: 'errorRate', align: 'center',
    render: (v) => <span style={{ color: v > 2 ? '#ff4d4f' : v > 1 ? '#faad14' : '#52c41a', fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}%</span>,
  },
];

const LLM_PERF_COLUMNS = [
  { title: 'Agent', dataIndex: 'agent', key: 'agent', render: (t) => <span style={{ fontWeight: 600, fontSize: 13 }}>{t}</span> },
  { title: '模型', dataIndex: 'model', key: 'model', render: (m) => <Mono style={{ color: '#722ed1' }}>{m}</Mono> },
  { title: '调用', dataIndex: 'calls', key: 'calls', align: 'center', render: (v) => <Mono>{v?.toLocaleString()}</Mono> },
  { title: '总耗时', dataIndex: 'totalLatency', key: 'totalLatency', render: (v, r) => <BarCell value={v} max={1250} /> },
  { title: 'TTFT P50', dataIndex: 'ttftP50', key: 'ttftP50', align: 'center', render: (v) => <Mono>{v}ms</Mono> },
  { title: 'TTFT P95', dataIndex: 'ttftP95', key: 'ttftP95', align: 'center', render: warnP95 },
  { title: 'TPOT P50', dataIndex: 'tpotP50', key: 'tpotP50', align: 'center', render: (v) => <Mono>{v}ms</Mono> },
  { title: 'TPOT P95', dataIndex: 'tpotP95', key: 'tpotP95', align: 'center', render: warnP95 },
  { title: 'Token(in/out)', dataIndex: 'tokenDesc', key: 'tokenDesc', align: 'center', render: (v) => <Mono>{v}</Mono> },
  {
    title: '错误%', dataIndex: 'errorRate', key: 'errorRate', align: 'center',
    render: (v) => <span style={{ color: v > 2 ? '#ff4d4f' : v > 1 ? '#faad14' : '#52c41a', fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}%</span>,
  },
];

function LevelBadge({ level }) {
  const map = {
    'L0': { bg: '#e6f4ff', color: '#1677ff', border: '#91caff' },
    'L1': { bg: '#f9f0ff', color: '#722ed1', border: '#d3adf7' },
    'L2': { bg: '#f6ffed', color: '#52c41a', border: '#b7eb8f' },
  };
  const m = map[level] || { bg: '#f5f5f5', color: 'rgba(0,0,0,.65)', border: '#d9d9d9' };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 500, background: m.bg, color: m.color, border: `1px solid ${m.border}` }}>
      {level}
    </span>
  );
}

function Mono({ children, style, ...rest }) {
  return <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12, ...style }} {...rest}>{children}</span>;
}

function BarCell({ value, max }) {
  const pct = Math.min(100, (value / max) * 100);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <div style={{ flex: 1, height: 6, background: '#f0f0f0', borderRadius: 3, overflow: 'hidden' }}>
        <div style={{ height: '100%', width: `${pct}%`, background: '#1677ff', borderRadius: 3 }} />
      </div>
      <Mono>{value}ms</Mono>
    </div>
  );
}

function warnP95(val) {
  const n = parseInt(val);
  const color = isNaN(n) ? 'inherit' : n > 1000 ? '#ff4d4f' : n > 600 ? '#faad14' : 'inherit';
  return <Mono style={{ color }}>{val}</Mono>;
}

export default AgentPerfTab;
