import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card,
  Table,
  Tag,
  Empty,
  Spin,
  Button,
  Alert,
  Tooltip,
  message,
} from 'antd';
import {
  ThunderboltOutlined,
  WarningOutlined,
  AimOutlined,
  ApartmentOutlined,
  DotChartOutlined,
} from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import {
  fetchInsightsReport,
  fetchBottlenecks,
  fetchRootCause,
  fetchUnsatisfied,
  fetchInsightsConversion,
  fetchInsightsActions,
  fetchAgentPerformance,
  refreshInsights,
} from '../api/client';

/**
 * DiagnosisCockpit — 智能洞察诊断驾驶舱（T-H，设计 §12.2）
 *
 * 顶部 5 区块，全部对接真实 insights 引擎端点（不硬编码数值）：
 *   ① ⚡ 优先行动建议 TOP5   ← /ai/insights/actions
 *   ② 三类瓶颈卡            ← /ai/insights/bottlenecks（性能红/准确率黄/转化紫）
 *   ③ 慢会话根因表          ← insights-report.slowSessions + /ai/insights/root-cause/{id}
 *   ④ 不满意会话共性表      ← /ai/insights/unsatisfied
 *   ⑤ Agent P95 vs 错误率散点 ← /ai/agent-performance?dimension=agent
 *
 * 处理加载/错误/空数据态；数据缺失不注入假值。
 */
function DiagnosisCockpit() {
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [report, setReport] = useState(null);
  const [actions, setActions] = useState([]);
  const [bottlenecks, setBottlenecks] = useState([]);
  const [unsatisfied, setUnsatisfied] = useState([]);
  const [conversion, setConversion] = useState(null);
  const [agentPerf, setAgentPerf] = useState(null);

  // 慢会话根因缓存：sessionId -> { loading, data, error }
  const [rootCauseMap, setRootCauseMap] = useState({});
  const aliveRef = useRef(true);

  const loadAll = useCallback(async () => {
    if (!aliveRef.current) return;
    setLoading(true);
    setError(null);
    try {
      const [rep, bn, act, unsat, conv, perf] = await Promise.allSettled([
        fetchInsightsReport(),
        fetchBottlenecks(),
        fetchInsightsActions(),
        fetchUnsatisfied(),
        fetchInsightsConversion(),
        fetchAgentPerformance({ dimension: 'agent' }),
      ]);
      if (!aliveRef.current) return;

      if (rep.status === 'fulfilled') {
        setReport(rep.value || null);
        // 若子端点失败，回退到聚合报告内的子集，保证驾驶舱有数据
        const r = rep.value || {};
        if (bn.status !== 'fulfilled') setBottlenecks(r.bottlenecks || []);
        if (act.status !== 'fulfilled') setActions(r.actions || []);
        if (unsat.status !== 'fulfilled') setUnsatisfied(r.unsatisfied || []);
        if (conv.status !== 'fulfilled') setConversion(r.conversionGaps?.[0] || null);
      } else {
        setReport(null);
      }
      if (bn.status === 'fulfilled') setBottlenecks(bn.value || []);
      if (act.status === 'fulfilled') setActions(act.value || []);
      if (unsat.status === 'fulfilled') setUnsatisfied(unsat.value || []);
      if (conv.status === 'fulfilled') setConversion(conv.value || null);
      if (perf.status === 'fulfilled') setAgentPerf(perf.value || null);

      // 任一核心端点失败给出提示，但不阻断其余区块
      const failed = [rep, bn, act, unsat, conv, perf].filter((r) => r.status === 'rejected');
      if (failed.length > 0 && failed.length === 6) {
        setError('洞察引擎暂不可达，请确认后端 insights 服务已启动');
      }
    } catch (err) {
      if (aliveRef.current) setError(err.message || '加载驾驶舱数据失败');
    } finally {
      if (aliveRef.current) setLoading(false);
    }
  }, []);

  // 手动刷新：清缓存重算 + 重新拉取
  const handleRefresh = useCallback(async () => {
    try {
      await refreshInsights();
      message.success('已触发洞察重算');
    } catch (e) {
      // 即使 refresh 失败也尝试重新拉取最新缓存
    }
    loadAll();
  }, [loadAll]);

  // 监听全局刷新事件（顶栏刷新按钮）
  useEffect(() => {
    aliveRef.current = true;
    loadAll();
    const onRefresh = () => loadAll();
    window.addEventListener('global-refresh', onRefresh);
    return () => {
      aliveRef.current = false;
      window.removeEventListener('global-refresh', onRefresh);
    };
  }, [loadAll]);

  // 懒加载某慢会话根因
  const loadRootCause = useCallback(async (sessionId) => {
    if (!sessionId || rootCauseMap[sessionId]) return;
    setRootCauseMap((m) => ({ ...m, [sessionId]: { loading: true } }));
    try {
      const data = await fetchRootCause(sessionId);
      if (aliveRef.current) {
        setRootCauseMap((m) => ({ ...m, [sessionId]: { loading: false, data } }));
      }
    } catch (err) {
      if (aliveRef.current) {
        setRootCauseMap((m) => ({
          ...m,
          [sessionId]: { loading: false, error: err.message || '根因分析失败' },
        }));
      }
    }
  }, [rootCauseMap]);

  const slowSessions = report?.slowSessions || [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* 驾驶舱头 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: '12px 16px',
          background: 'linear-gradient(90deg,#f0f5ff 0%,#f9f0ff 100%)',
          borderRadius: 8,
          border: '1px solid #d6e4ff',
        }}
      >
        <ApartmentOutlined style={{ fontSize: 18, color: '#722ed1' }} />
        <span style={{ fontSize: 15, fontWeight: 600, color: 'rgba(0,0,0,.88)' }}>
          智能洞察诊断驾驶舱
        </span>
        {report?.generatedAt && (
          <span style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', fontFamily: 'monospace' }}>
            报告时间 {new Date(report.generatedAt).toLocaleString('zh-CN', { hour12: false })}
          </span>
        )}
        {report?.summary && (
          <span style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>{report.summary}</span>
        )}
        <Button
          size="small"
          style={{ marginLeft: 'auto' }}
          icon={<ThunderboltOutlined />}
          loading={loading}
          onClick={handleRefresh}
        >
          刷新洞察
        </Button>
      </div>

      {error && (
        <Alert type="error" showIcon message={error} style={{ borderRadius: 8 }} />
      )}

      <Spin spinning={loading && !report}>
        {/* ① 优先行动建议 TOP5 */}
        <Card
          size="small"
          title={
            <span>
              <ThunderboltOutlined style={{ color: '#fa8c16', marginRight: 6 }} />
              优先行动建议 TOP5
            </span>
          }
          style={{ borderRadius: 8 }}
        >
          <ActionList actions={actions} />
        </Card>

        {/* ② 三类瓶颈卡 */}
        <Card
          size="small"
          title={
            <span>
              <WarningOutlined style={{ color: '#ff4d4f', marginRight: 6 }} />
              三类瓶颈卡（性能 / 准确率 / 转化）
            </span>
          }
          style={{ borderRadius: 8, marginTop: 16 }}
        >
          <BottleneckCards bottlenecks={bottlenecks} />
        </Card>

        {/* ③ 慢会话根因表 */}
        <Card
          size="small"
          title={
            <span>
              <AimOutlined style={{ color: '#1677ff', marginRight: 6 }} />
              慢会话根因表
            </span>
          }
          style={{ borderRadius: 8, marginTop: 16 }}
        >
          <SlowSessionTable
            sessions={slowSessions}
            rootCauseMap={rootCauseMap}
            onExpand={loadRootCause}
            onJumpTrace={(sid) => navigate(`/trace?sessionId=${encodeURIComponent(sid)}`)}
          />
        </Card>

        {/* ④ 不满意会话共性表 */}
        <Card
          size="small"
          title={
            <span>
              <DotChartOutlined style={{ color: '#722ed1', marginRight: 6 }} />
              不满意会话共性表
            </span>
          }
          style={{ borderRadius: 8, marginTop: 16 }}
        >
          <UnsatisfiedTable unsatisfied={unsatisfied} />
        </Card>

        {/* ⑤ Agent P95 vs 错误率散点 */}
        <Card
          size="small"
          title={
            <span>
              <DotChartOutlined style={{ color: '#13c2c2', marginRight: 6 }} />
              Agent P95 时延 vs 错误率（红线 1500ms）
            </span>
          }
          style={{ borderRadius: 8, marginTop: 16 }}
        >
          <PerfScatter perf={agentPerf} />
        </Card>
      </Spin>
    </div>
  );
}

/* ───────── ① 优先行动建议 ───────── */

const SEVERITY_CONFIG = {
  HIGH: { color: '#ff4d4f', bg: '#fff2f0', text: '高' },
  MED: { color: '#fa8c16', bg: '#fff7e6', text: '中' },
  LOW: { color: '#52c41a', bg: '#f6ffed', text: '低' },
};

function ActionList({ actions }) {
  if (!actions || actions.length === 0) {
    return <Empty description="暂无优化建议" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  const top5 = actions.slice(0, 5);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {top5.map((a, idx) => {
        const sev = SEVERITY_CONFIG[a.severity] || SEVERITY_CONFIG.LOW;
        return (
          <div
            key={a.id || idx}
            style={{
              display: 'flex',
              gap: 12,
              padding: '10px 14px',
              borderRadius: 6,
              border: `1px solid ${sev.bg === '#fff2f0' ? '#ffccc7' : '#f0f0f0'}`,
              background: '#fafafa',
            }}
          >
            <div
              style={{
                fontFamily: 'monospace',
                fontWeight: 700,
                fontSize: 14,
                color: 'rgba(0,0,0,.4)',
                width: 20,
                textAlign: 'center',
              }}
            >
              {idx + 1}
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
                <span style={{ fontWeight: 600, fontSize: 13 }}>{a.title}</span>
                <Tag color={sev.color} style={{ margin: 0, lineHeight: '18px' }}>
                  {sev.text}优先级
                </Tag>
                {a.boundary === 'L2' && (
                  <Tooltip title="AI 模糊推断，需人工确认">
                    <Tag style={{ margin: 0, lineHeight: '18px', background: '#f9f0ff', color: '#722ed1', borderColor: '#d3adf7' }}>
                      L2
                    </Tag>
                  </Tooltip>
                )}
              </div>
              <div style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>{a.description}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

/* ───────── ② 三类瓶颈卡 ───────── */

const CATEGORY_CONFIG = {
  PERFORMANCE: { color: '#ff4d4f', bg: '#fff2f0', label: '性能' },
  ACCURACY: { color: '#faad14', bg: '#fffbe6', label: '准确率' },
  CONVERSION: { color: '#722ed1', bg: '#f9f0ff', label: '转化' },
};

function BottleneckCards({ bottlenecks }) {
  if (!bottlenecks || bottlenecks.length === 0) {
    return <Empty description="暂无瓶颈数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
      {bottlenecks.map((b, idx) => {
        const cfg = CATEGORY_CONFIG[b.category] || CATEGORY_CONFIG.ACCURACY;
        return (
          <div
            key={b.category || idx}
            style={{
              padding: '14px 16px',
              borderRadius: 6,
              borderLeft: `4px solid ${cfg.color}`,
              background: cfg.bg,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>{cfg.label}</span>
              <span style={{ fontFamily: 'monospace', fontWeight: 700, fontSize: 20, color: cfg.color }}>
                {b.value != null ? b.value : '—'}
                {b.unit && <span style={{ fontSize: 12, fontWeight: 400 }}> {b.unit}</span>}
              </span>
            </div>
            <div style={{ fontSize: 13, fontWeight: 600, margin: '6px 0 2px' }}>{b.title}</div>
            <div style={{ fontSize: 12, color: 'rgba(0,0,0,.55)' }}>{b.detail}</div>
          </div>
        );
      })}
    </div>
  );
}

/* ───────── ③ 慢会话根因表 ───────── */

function SlowSessionTable({ sessions, rootCauseMap, onExpand, onJumpTrace }) {
  if (!sessions || sessions.length === 0) {
    return <Empty description="暂无慢会话数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  const columns = [
    {
      title: '会话 ID',
      dataIndex: 'sessionId',
      key: 'sessionId',
      render: (v) => (
        <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#1677ff', cursor: 'pointer' }} onClick={() => onJumpTrace(v)}>
          {v}
        </span>
      ),
    },
    {
      title: '时长',
      dataIndex: 'durationSeconds',
      key: 'durationSeconds',
      align: 'right',
      render: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v != null ? `${v}s` : '—'}</span>,
    },
    { title: '轮次', dataIndex: 'turnCount', key: 'turnCount', align: 'right', render: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v ?? '—'}</span> },
    {
      title: '意图流',
      dataIndex: 'intentFlow',
      key: 'intentFlow',
      render: (v) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.55)' }}>{v || '—'}</span>,
    },
    {
      title: '操作',
      key: 'action',
      align: 'center',
      render: (_, r) => (
        <span style={{ color: '#1677ff', cursor: 'pointer', fontSize: 13 }} onClick={() => onJumpTrace(r.sessionId)}>
          查看链路
        </span>
      ),
    },
  ];

  return (
    <Table
      dataSource={sessions}
      columns={columns}
      rowKey="sessionId"
      pagination={false}
      size="small"
      expandable={{
        onExpand: (expanded, record) => {
          if (expanded) onExpand(record.sessionId);
        },
        expandedRowRender: (record) => <RootCauseDetail sessionId={record.sessionId} entry={rootCauseMap[record.sessionId]} />,
      }}
    />
  );
}

function RootCauseDetail({ sessionId, entry }) {
  if (!entry) {
    return <div style={{ padding: 8, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>展开以查看根因分析…</div>;
  }
  if (entry.loading) {
    return (
      <div style={{ padding: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
        <Spin size="small" /> <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>正在分析根因…</span>
      </div>
    );
  }
  if (entry.error) {
    return <div style={{ padding: 8, fontSize: 12, color: '#ff4d4f' }}>根因分析失败：{entry.error}</div>;
  }
  const candidates = entry.data?.rootCauseCandidates || [];
  if (candidates.length === 0) {
    return <div style={{ padding: 8, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>无可用根因数据</div>;
  }
  return (
    <div style={{ padding: '8px 12px', background: '#fafafa', borderRadius: 6 }}>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>根因候选（按独占时间 Top3）</div>
      {candidates.map((c, i) => (
        <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '4px 0', borderTop: i > 0 ? '1px solid #f0f0f0' : 'none' }}>
          <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#722ed1', fontWeight: 600, minWidth: 80 }}>
            {c.exclusiveTimeMs != null ? `${c.exclusiveTimeMs}ms` : '—'}
          </span>
          <span style={{ fontSize: 12, fontWeight: 500 }}>{c.spanName}</span>
          <span style={{ fontSize: 12, color: 'rgba(0,0,0,.55)', flex: 1 }}>{c.hypothesis}</span>
        </div>
      ))}
    </div>
  );
}

/* ───────── ④ 不满意会话共性表 ───────── */

function UnsatisfiedTable({ unsatisfied }) {
  if (!unsatisfied || unsatisfied.length === 0) {
    return <Empty description="暂无不满意聚类（近期无负面满意度会话）" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  const columns = [
    {
      title: '维度',
      dataIndex: 'dimension',
      key: 'dimension',
      render: (v) => <span style={{ fontWeight: 600, fontSize: 13 }}>{v || '—'}</span>,
    },
    {
      title: '会话数',
      dataIndex: 'count',
      key: 'count',
      align: 'right',
      render: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#ff4d4f', fontWeight: 600 }}>{v}</span>,
    },
    {
      title: '共性模式',
      dataIndex: 'commonPattern',
      key: 'commonPattern',
      render: (v) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>{v || '—'}</span>,
    },
    {
      title: '示例会话',
      dataIndex: 'examples',
      key: 'examples',
      render: (v) =>
        Array.isArray(v) && v.length > 0 ? (
          <span style={{ fontFamily: 'monospace', fontSize: 11, color: 'rgba(0,0,0,.45)' }}>{v.slice(0, 3).join('、')}</span>
        ) : (
          '—'
        ),
    },
  ];
  return (
    <Table
      dataSource={unsatisfied}
      columns={columns}
      rowKey={(r) => r.dimension || Math.random()}
      pagination={false}
      size="small"
    />
  );
}

/* ───────── ⑤ Agent P95 vs 错误率散点 ───────── */

function PerfScatter({ perf }) {
  const rows = perf?.tables?.agent || [];
  if (rows.length === 0) {
    return <Empty description="暂无 Agent 性能数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  const points = rows
    .map((r) => {
      const x = Number(r.ttftP95);
      const y = Number(r.errorRate);
      if (isNaN(x) || isNaN(y)) return null;
      return { name: r.agent, value: [x, y], calls: r.calls };
    })
    .filter(Boolean);

  if (points.length === 0) {
    return <Empty description="暂无散点数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  const option = {
    tooltip: {
      trigger: 'item',
      textStyle: { color: 'rgba(0,0,0,.65)', fontSize: 12 },
      formatter: (p) =>
        `<b>${p.data.name}</b><br/>P95 时延: ${p.data.value[0]}ms<br/>错误率: ${p.data.value[1]}%<br/>调用: ${p.data.calls}`,
    },
    grid: { left: 55, right: 20, top: 20, bottom: 45 },
    xAxis: {
      type: 'value',
      name: 'TTFT P95 (ms)',
      nameLocation: 'middle',
      nameGap: 28,
      nameTextStyle: { fontSize: 11, color: 'rgba(0,0,0,.45)' },
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    yAxis: {
      type: 'value',
      name: '错误率 (%)',
      nameLocation: 'middle',
      nameGap: 35,
      nameTextStyle: { fontSize: 11, color: 'rgba(0,0,0,.45)' },
      axisLabel: { fontSize: 10, color: 'rgba(0,0,0,.45)', formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#f0f0f0' } },
    },
    series: [
      {
        type: 'scatter',
        data: points,
        symbolSize: (val, params) => Math.max(10, Math.min(40, Math.sqrt(params.data.calls || 1) / 2)),
        itemStyle: {
          color: (p) => (p.data.value[0] > 1500 || p.data.value[1] > 2 ? '#ff4d4f' : '#1677ff'),
          opacity: 0.8,
          borderColor: '#fff',
          borderWidth: 1,
        },
        label: {
          show: true,
          position: 'top',
          fontSize: 10,
          color: 'rgba(0,0,0,.65)',
          formatter: (p) => p.data.name,
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#ff4d4f', type: 'dashed', width: 1.5 },
          label: { formatter: 'P95 红线 1500ms', color: '#ff4d4f', fontSize: 10 },
          data: [{ xAxis: 1500 }],
        },
      },
    ],
  };

  return <ReactEChartsCore option={option} style={{ height: 280 }} notMerge lazyUpdate />;
}

export default DiagnosisCockpit;
