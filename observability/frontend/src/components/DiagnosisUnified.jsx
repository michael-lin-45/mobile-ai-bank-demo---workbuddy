import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Spin, Button, Alert, Empty,
} from 'antd';
import {
  ThunderboltOutlined, AimOutlined, DotChartOutlined,
} from '@ant-design/icons';
import {
  fetchInsightsReport, fetchBottlenecks, fetchRootCause, fetchUnsatisfied,
  fetchInsightsConversion, fetchInsightsActions, fetchAgentPerformance, refreshInsights,
} from '../api/client';
import { isEmpty } from '../services/insightAdapters';
import { getDiagnosisMocks } from '../services/mockInsights';
import { DemoBadge } from './InsightKpiCard';
import { emit } from '../utils/nav';
// 复用驾驶舱的 4 个已验证子区块（M6：DiagnosisCockpit 降级为被复用子区块）
// 注：三类瓶颈卡不再复用 BottleneckCards（单卡网格），改为本文件三列彩色卡对齐 DEMO V23
import {
  ActionList, SlowSessionTable, UnsatisfiedTable, PerfScatter,
} from './DiagnosisCockpit';

/**
 * DiagnosisUnified — 整合后的「智能诊断」统一视图（对齐 DEMO V23 智能诊断 TAB）。
 *
 * 区块顺序（对齐 DEMO V23 L582-677）：
 *   摘要条（紫色渐变，承担快照角色；无独立「诊断快照」卡）
 *   ② 优先行动 TOP5  ③ 三列彩色瓶颈卡（性能 / 准确率 / 转化）
 *   ⑦ 瓶颈可视化（Agent P95 vs 错误率散点）  ④ 慢会话根因表（可展开）
 *   ⑤ 不满意共性表  「更多诊断信号」（可折叠，3 条）
 *
 * 注意：DEMO 智能诊断 TAB 不含「独立快照卡」与「转化漏斗卡」（漏斗有独立 TAB），
 * 故二者已从本 TAB 移除，以对齐 DEMO。各区块独立 mock 兜底：对应 fetch 失败/空
 * → getDiagnosisMocks() 对应子块，并加 subtle DEMO 角标。保留 refresh 按钮 + global-refresh 监听。
 */
function DiagnosisUnified() {
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [report, setReport] = useState(null);
  const [actions, setActions] = useState([]);
  const [bottlenecks, setBottlenecks] = useState([]);
  const [unsatisfied, setUnsatisfied] = useState([]);
  const [unsatOverallRate, setUnsatOverallRate] = useState(null);
  const [conversion, setConversion] = useState(null);
  const [agentPerf, setAgentPerf] = useState(null);

  const [reportDemo, setReportDemo] = useState(false);
  const [actionsDemo, setActionsDemo] = useState(false);
  const [bottlenecksDemo, setBottlenecksDemo] = useState(false);
  const [unsatDemo, setUnsatDemo] = useState(false);
  const [convDemo, setConvDemo] = useState(false);
  const [perfDemo, setPerfDemo] = useState(false);

  // 「更多诊断信号（可折叠）」展开态
  const [moreOpen, setMoreOpen] = useState(false);

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

      const mock = getDiagnosisMocks();

      // ① 诊断快照
      if (rep.status === 'fulfilled' && !isEmpty(rep.value)) {
        setReport(rep.value || null);
        setReportDemo(false);
      } else {
        setReport(mock.snapshot);
        setReportDemo(true);
      }

      // ② 优先行动 TOP5
      if (act.status === 'fulfilled' && !isEmpty(act.value)) {
        setActions(act.value || []);
        setActionsDemo(false);
      } else {
        setActions(mock.topActions.map((a) => ({ id: `mock-${a.rank}`, title: a.title, description: a.impact, severity: 'MED', boundary: 'L1' })));
        setActionsDemo(true);
      }

      // ③ 三类瓶颈卡
      if (bn.status === 'fulfilled' && !isEmpty(bn.value)) {
        setBottlenecks(bn.value || []);
        setBottlenecksDemo(false);
      } else {
        setBottlenecks(mock.bottlenecks.map((b) => ({ category: b.type.toUpperCase(), title: b.title, value: b.value, unit: b.unit, detail: b.desc })));
        setBottlenecksDemo(true);
      }

      // ⑤ 不满意共性表
      const unsatClusters = unsat.status === 'fulfilled' && !isEmpty(unsat.value)
        ? (unsat.value?.clusters || [])
        : mock.unsatisfied.map((u) => ({ dimension: u.theme, count: u.count, commonPattern: `流失率 ${u.rate}%`, examples: [u.sample] }));
      setUnsatisfied(unsatClusters);
      // 整体不满意率（报告级），用于「不满意共性表」条件触发（<10% 隐去表格）
      const unsatOverall = unsat.status === 'fulfilled' && !isEmpty(unsat.value)
        ? (unsat.value?.rate ?? null)
        : null;
      setUnsatOverallRate(unsatOverall);
      setUnsatDemo(!(unsat.status === 'fulfilled' && !isEmpty(unsat.value)));

      // ⑥ 转化漏斗 Progress
      if (conv.status === 'fulfilled' && !isEmpty(conv.value)) {
        setConversion(conv.value || null);
        setConvDemo(false);
      } else {
        setConversion({ stages: mock.conversionProgress.map((p) => ({ name: p.stage, count: 0 })), mockProgress: mock.conversionProgress });
        setConvDemo(true);
      }

      // ⑦ Agent P95 vs 错误率散点
      if (perf.status === 'fulfilled' && !isEmpty(perf.value)) {
        setAgentPerf(perf.value || null);
        setPerfDemo(false);
      } else {
        setAgentPerf({
          tables: { agent: mock.agentScatter.map((a) => ({ agent: a.agent, ttftP95: a.p95Ms, errorRate: a.errorRate, calls: 100 })) },
        });
        setPerfDemo(true);
      }

      const failed = [rep, bn, act, unsat, conv, perf].filter((r) => r.status === 'rejected');
      if (failed.length === 6) {
        setError('洞察引擎暂不可达，已展示演示数据');
      }
    } catch (err) {
      if (aliveRef.current) setError(err.message || '加载诊断数据失败');
    } finally {
      if (aliveRef.current) setLoading(false);
    }
  }, []);

  const handleRefresh = useCallback(async () => {
    try {
      await refreshInsights();
    } catch (e) {
      // 即使 refresh 失败也尝试重新拉取最新缓存
    }
    loadAll();
  }, [loadAll]);

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

  // 慢会话行：真实 report.slowSessions.rows 优先；否则 mock 适配
  const slowSessions = report?.slowSessions?.rows
    || getDiagnosisMocks().slowSessions.map((s) => ({ sessionId: s.sessionId, durationSeconds: s.durationSec, turnCount: '-', intentFlow: s.bottleneck }));

  // 转化漏斗 Progress 列表
  const conversionProgress = (() => {
    if (conversion?.mockProgress) return conversion.mockProgress;
    const stages = conversion?.stages || [];
    const total = conversion?.totalSessions || 0;
    return stages.map((s) => ({
      stage: s.name,
      rate: total > 0 ? Math.round(((s.count || 0) / total) * 100) : 0,
    }));
  })();

  // 智能诊断摘要条 — 严重度角标统计（actions 为空 → DEMO 默认占位 3 HIGH / 2 MEDIUM / 1 LOW）
  const isActionsEmpty = !actions || actions.length === 0;
  const severityCounts = (actions || []).reduce(
    (acc, a) => {
      const s = (a.severity || '').toUpperCase();
      if (s === 'HIGH') acc.high += 1;
      else if (s === 'MEDIUM' || s === 'MED') acc.med += 1;
      else if (s === 'LOW') acc.low += 1;
      return acc;
    },
    { high: 0, med: 0, low: 0 },
  );
  const highCount = isActionsEmpty ? 3 : severityCounts.high;
  const medCount = isActionsEmpty ? 2 : severityCounts.med;
  const lowCount = isActionsEmpty ? 1 : severityCounts.low;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* 智能诊断摘要条 — DEMO V23 .diagnosis-summary 紫色渐变样式 */}
      <div
        style={{
          display: 'flex',
          gap: 12,
          alignItems: 'center',
          padding: '14px 18px',
          background: 'linear-gradient(135deg,#f9f0ff,#e6f7ff)',
          borderRadius: 8,
          border: '1px solid #d3adf7',
          marginBottom: 16,
        }}
      >
        <span style={{ fontSize: 20 }}>🧠</span>
        <div style={{ flex: 1 }}>
          <span style={{ fontWeight: 700, fontSize: 14 }}>智能诊断摘要</span>
          <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginLeft: 10 }}>
            更新于 2 分钟前 · 基于近 6h 数据 · 自动扫描 6 个 TAB 指标
          </span>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <span style={{ padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600, background: '#fff2f0', color: '#ff4d4f', border: '1px solid #ffccc7' }}>{highCount} HIGH</span>
          <span style={{ padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600, background: '#fff7e6', color: '#fa8c16', border: '1px solid #ffe7ba' }}>{medCount} MEDIUM</span>
          <span style={{ padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600, background: '#e6f7ff', color: '#1677ff', border: '1px solid #91d5ff' }}>{lowCount} LOW</span>
        </div>
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
        <Alert type="warning" showIcon message={error} style={{ borderRadius: 8 }} />
      )}

      <Spin spinning={loading && !report}>
        {/* ② 优先行动建议 TOP5 — DEMO V23 紫色边框 + 紫色渐变底（保持不变） */}
        <Card
          size="small"
          title={<span style={{ color: '#722ed1' }}>⚡ 优先行动建议（TOP 5）</span>}
          extra={actionsDemo ? <DemoBadge /> : null}
          style={{ border: '2px solid #722ed1', background: 'linear-gradient(135deg,#faf5ff 0%,#f9f0ff 100%)', borderRadius: 8 }}
        >
          <ActionList actions={actions} />
        </Card>

        {/* ③ 三类瓶颈卡 — 三列彩色卡（对齐 DEMO V23 L618-622：🔴性能#ff4d4f / 🟡准确率#faad14 / 🟣转化#722ed1） */}
        <BottleneckColumns bottlenecks={bottlenecks} demo={bottlenecksDemo} />

        {/* ⑦ 瓶颈可视化 — Agent P95 vs 错误率散点（对齐 DEMO 顺序：置于三类诊断卡之后） */}
        <Card
          size="small"
          title={
            <span>
              <DotChartOutlined style={{ color: '#13c2c2', marginRight: 6 }} />
              Agent P95 时延 vs 错误率（红线 1500ms）
            </span>
          }
          extra={perfDemo ? <DemoBadge /> : null}
          style={{ borderRadius: 8 }}
        >
          <PerfScatter perf={agentPerf} />
        </Card>

        {/* ④ 慢会话根因表 */}
        <Card
          size="small"
          title={
            <span>
              <AimOutlined style={{ color: '#1677ff', marginRight: 6 }} />
              慢会话根因表
            </span>
          }
          extra={reportDemo ? <DemoBadge /> : null}
          style={{ borderRadius: 8 }}
        >
          <SlowSessionTable
            sessions={slowSessions}
            rootCauseMap={rootCauseMap}
            onExpand={loadRootCause}
            onJumpTrace={(sid) => navigate(`/trace?sessionId=${encodeURIComponent(sid)}`)}
          />
        </Card>

        {/* ⑤ 不满意会话共性表 */}
        <Card
          size="small"
          title={
            <span>
              <DotChartOutlined style={{ color: '#722ed1', marginRight: 6 }} />
              不满意会话共性表
            </span>
          }
          extra={unsatDemo ? <DemoBadge /> : null}
          style={{ borderRadius: 8 }}
        >
          <UnsatisfiedTable unsatisfied={unsatisfied} overallRate={unsatOverallRate} />
        </Card>

        {/* 更多诊断信号（可折叠）— 新增，对齐 DEMO V23 L654-677 */}
        <Card size="small" style={{ borderRadius: 8, border: '1px dashed #d9d9d9' }}>
          <div
            role="button"
            aria-expanded={moreOpen}
            onClick={() => setMoreOpen((o) => !o)}
            style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6 }}
          >
            <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 13, fontWeight: 500 }}>
              {moreOpen ? '▼' : '▶'} 更多诊断信号（{MORE_DIAGNOSIS_SIGNALS.length} 条 · 未进 TOP5 但有趋势价值）
            </span>
          </div>
          {moreOpen && (
            <div style={{ marginTop: 12, display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
              {MORE_DIAGNOSIS_SIGNALS.map((s) => (
                <div
                  key={s.title}
                  role="button"
                  onClick={() => emit('ai-insights:tab', { tabKey: s.tab })}
                  style={{
                    cursor: 'pointer',
                    padding: '10px 12px',
                    borderLeft: `3px solid ${s.color}`,
                    background: '#fafafa',
                    borderRadius: 6,
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                    <span style={{ fontSize: 13, fontWeight: 600 }}>{s.title}</span>
                    <span style={{ fontSize: 11, color: '#fa8c16', border: '1px solid #ffe7ba', background: '#fff7e6', borderRadius: 4, padding: '0 6px' }}>{s.sev}</span>
                  </div>
                  <div style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>{s.sub}</div>
                  <div style={{ fontSize: 12, fontWeight: 600, color: 'rgba(0,0,0,.85)', marginTop: 4 }}>{s.metric}</div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </Spin>
    </div>
  );
}

/* ───────── ③ 三类瓶颈卡（三列彩色卡，对齐 DEMO V23 L618-622）───────── */

// 列定义：性能 / 准确率 / 转化，emoji + 主色 + 行项浅色背景（与 DEMO 一致）
const BOTTLENECK_COLUMNS = [
  { key: 'perf', emoji: '🔴', title: '性能瓶颈', color: '#ff4d4f', rowBgs: ['#fff2f0', '#fffbe6', '#f6ffed'] },
  { key: 'accuracy', emoji: '🟡', title: '准确率问题', color: '#faad14', rowBgs: ['#fffbe6', '#fff2f0', '#f6ffed'] },
  { key: 'conversion', emoji: '🟣', title: '转化瓶颈', color: '#722ed1', rowBgs: ['#fff2f0', '#fffbe6', '#f6ffed'] },
];

/**
 * 将瓶颈 category 归一为三列 key。
 * 兼容真实数据 'PERFORMANCE' 与 mock 映射后的 'PERF'（均含 PERF），
 * 以及 'ACCURACY' / 'CONVERSION'。
 */
function bucketOfCategory(category) {
  const c = (category || '').toUpperCase();
  if (c.includes('PERF')) return 'perf';
  if (c.includes('ACC')) return 'accuracy';
  if (c.includes('CONV')) return 'conversion';
  return 'accuracy';
}

/**
 * BottleneckColumns — 三列彩色瓶颈卡（替代原单卡 BottleneckCards，对齐 DEMO V23）。
 *
 * 数据仍来自 diagnosis TAB 的 bottlenecks state（{category,title,value,unit,detail}）。
 * 按 category 分到 性能 / 准确率 / 转化 三列，每列套 DEMO 配色与 emoji 图标，
 * 行项使用浅色背景（#fff2f0 / #fffbe6 / #f6ffed 循环）。
 */
export function BottleneckColumns({ bottlenecks, demo }) {
  const list = bottlenecks || [];
  const grouped = { perf: [], accuracy: [], conversion: [] };
  list.forEach((b) => {
    const key = bucketOfCategory(b.category);
    grouped[key].push(b);
  });

  if (list.length === 0) {
    return <Empty description="暂无瓶颈数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
      {BOTTLENECK_COLUMNS.map((col) => {
        const rows = grouped[col.key] || [];
        return (
          <Card key={col.key} size="small" style={{ borderRadius: 8 }}>
            <div style={{ marginBottom: 12 }}>
              <span style={{ fontSize: 13, fontWeight: 600, color: col.color }}>
                {col.emoji} {col.title}
              </span>
              {demo && col.key === 'perf' && (
                <span style={{ float: 'right' }}><DemoBadge /></span>
              )}
            </div>
            <div style={{ fontSize: 12, lineHeight: 1.8 }}>
              {rows.length > 0 ? (
                rows.map((b, i) => (
                  <div
                    key={b.category || i}
                    style={{
                      padding: '8px 10px',
                      background: col.rowBgs[i % col.rowBgs.length],
                      borderRadius: 4,
                      marginBottom: 6,
                    }}
                  >
                    <strong>
                      {b.title}
                      {b.value != null ? ` ${b.value}${b.unit ? ' ' + b.unit : ''}` : ''}
                    </strong>
                    {b.detail && (
                      <>
                        <br />
                        <span style={{ color: 'rgba(0,0,0,.45)' }}>{b.detail}</span>
                      </>
                    )}
                  </div>
                ))
              ) : (
                <Empty description="暂无" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </div>
          </Card>
        );
      })}
    </div>
  );
}

/* ───────── 更多诊断信号（可折叠，对齐 DEMO V23 L654-677）───────── */

// 3 条 mini 诊断信号（未进 TOP5 但有趋势价值）；点击跳转到对应 TAB
const MORE_DIAGNOSIS_SIGNALS = [
  {
    title: 'LLM 失败重试率',
    sev: '中',
    sub: '近 6h 错误率 1.3%，SenseNova 端点波动',
    metric: '重试 0.9%',
    tab: 'perf',
    color: '#ff4d4f',
  },
  {
    title: '重路由比率（Reroute） 14.2%',
    sev: '中',
    sub: '转账↔理财 易混淆 · 待 Core 度量',
    metric: '14.2%',
    tab: 'accuracy',
    color: '#faad14',
  },
  {
    title: '转账转化率低于账单',
    sev: '中',
    sub: '转账 87.6% vs 账单 95%',
    metric: '差 7.4%',
    tab: 'funnel',
    color: '#722ed1',
  },
];

export default DiagnosisUnified;
