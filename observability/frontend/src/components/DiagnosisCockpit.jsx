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
import { formatBeijingTime } from '../utils/time';

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
        if (unsat.status !== 'fulfilled') setUnsatisfied(r.unsatisfied?.clusters || []);
        if (conv.status !== 'fulfilled') setConversion(r.conversionGaps?.[0] || null);
      } else {
        setReport(null);
      }
      if (bn.status === 'fulfilled') setBottlenecks(bn.value || []);
      if (act.status === 'fulfilled') setActions(act.value || []);
      if (unsat.status === 'fulfilled') setUnsatisfied(unsat.value?.clusters || []);
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
  const slowSessions = report?.slowSessions?.rows || [];
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
            报告时间 {formatBeijingTime(report.generatedAt)}
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
          style={{ borderRadius: 8, marginTop: 12 }}
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
          style={{ borderRadius: 8, marginTop: 12 }}
        >
          <SlowSessionTable
            sessions={slowSessions}
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
          style={{ borderRadius: 8, marginTop: 12 }}
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
          style={{ borderRadius: 8, marginTop: 12 }}
        >
          <PerfScatter perf={agentPerf} />
        </Card>
      </Spin>
    </div>
  );
}

/* ───────── ① 优先行动建议 ───────── */

export const SEVERITY_CONFIG = {
  HIGH: { color: '#ff4d4f', bg: '#fff2f0', text: '高' },
  MED: { color: '#fa8c16', bg: '#fff7e6', text: '中' },
  LOW: { color: '#52c41a', bg: '#f6ffed', text: '低' },
};

export function ActionList({ actions }) {
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

export const CATEGORY_CONFIG = {
  PERFORMANCE: { color: '#ff4d4f', bg: '#fff2f0', label: '性能' },
  ACCURACY: { color: '#faad14', bg: '#fffbe6', label: '准确率' },
  CONVERSION: { color: '#722ed1', bg: '#f9f0ff', label: '转化' },
};

export function BottleneckCards({ bottlenecks }) {
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

/* ───────── ③ 慢会话根因表（按根因类别聚合 + 条件触发）───────── */

// 根因类别 → 建议文案映射（对齐 DEMO V23 L634-637）
const ROOT_CAUSE_SUGGESTION = {
  'LLM 调用慢': '换用 Flash-Lite',
  '工具调用慢': '检查核心接口',
  '追问轮次多': '减少必填字段',
  其他: '个案分析',
};

// 根因类别 → Tag 配色（对齐 DEMO V23 badage-purple/blue/warning/info）
const ROOT_CAUSE_TAG_COLOR = {
  'LLM 调用慢': 'purple',
  '工具调用慢': 'blue',
  '追问轮次多': 'warning',
  其他: 'default',
};

/**
 * 推断单条慢会话的「根因类别」：
 *  1) 优先用显式 rootCauseCategory；
 *  2) 否则用 rootCause 文案；
 *  3) 再退化为 intentFlow 的首段（如「转账 → 失败」→「转账」）；
 *  4) 都无 → 「未分类」。
 */
function resolveRootCauseCategory(s) {
  if (!s || typeof s !== 'object') return '未分类';
  const rc = s.rootCauseCategory;
  if (rc != null && `${rc}`.trim()) return `${rc}`;
  if (s.rootCause && `${s.rootCause}`.trim()) return `${s.rootCause}`;
  const flow = s.intentFlow;
  if (flow && `${flow}`.trim()) {
    const head = `${flow}`.split(/[→\-]/)[0]?.trim();
    if (head) return head;
  }
  return '未分类';
}

/** 众数：返回出现次数最多的元素（无则返回 '—'） */
function modeOf(arr) {
  if (!Array.isArray(arr) || arr.length === 0) return '—';
  const counter = {};
  let best = null;
  let bestCount = 0;
  arr.forEach((v) => {
    if (v == null || v === '') return;
    const k = `${v}`;
    counter[k] = (counter[k] || 0) + 1;
    if (counter[k] > bestCount) {
      bestCount = counter[k];
      best = v;
    }
  });
  return best == null ? '—' : best;
}

/** 安全均值（保留 1 位小数） */
function safeAvg(nums) {
  const valid = (nums || []).filter((n) => typeof n === 'number' && !isNaN(n));
  if (valid.length === 0) return null;
  return +(valid.reduce((a, b) => a + b, 0) / valid.length).toFixed(1);
}

/**
 * SlowSessionTable — 由「逐会话列表」改为「按根因类别聚合表」（对齐 DEMO V23 L631-640）。
 *
 * 列：根因类别 / 会话数 / 占比 / 平均耗时 / 主要 Agent / 建议。
 * 条件触发：有慢会话且 P90 > 阈值(默认 3s) 才展示表格；
 *           否则显示绿色状态「✅ 近6h 无慢会话（P90 < 3s）」。
 * 聚合：对 sessions 按 rootCauseCategory 分组 → 计数、占比(组/总数)、
 *       平均耗时(组 durationSeconds 均值)、主要 Agent(众数)、建议(类别→文案映射)。
 * 增强：根因类别行可点击展开，查看该类别下具体会话（复用 onJumpTrace 链路跳转）。
 *
 * @param {Array} sessions       慢会话列表（需含 durationSeconds；可选 rootCauseCategory/mainAgent）
 * @param {Function} onJumpTrace 点击会话 ID 跳转到链路详情
 * @param {number} [p90Seconds]   调用方可显式传入 P90；缺省时由 durations 推算
 * @param {number} [thresholdSeconds=3] 触发阈值（秒）
 */
export function SlowSessionTable({ sessions, onJumpTrace, p90Seconds, thresholdSeconds = 3 }) {
  const rows = Array.isArray(sessions) ? sessions : [];
  const durations = rows
    .map((s) => Number(s?.durationSeconds))
    .filter((n) => !isNaN(n))
    .sort((a, b) => a - b);
  const computedP90 = durations.length
    ? durations[Math.min(durations.length - 1, Math.max(0, Math.ceil(durations.length * 0.9) - 1))]
    : 0;
  const p90 = typeof p90Seconds === 'number' && !isNaN(p90Seconds) ? p90Seconds : computedP90;
  const triggered = rows.length > 0 && p90 > thresholdSeconds;

  // 条件未触发 → 绿色状态，隐去表格
  if (!triggered) {
    return (
      <div style={GREEN_STATE_STYLE}>
        ✅ 近6h 无慢会话（P90 &lt; {thresholdSeconds}s）
      </div>
    );
  }

  // 按根因类别聚合
  const groups = new Map();
  rows.forEach((s) => {
    const cat = resolveRootCauseCategory(s);
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat).push(s);
  });
  const total = rows.length;
  const dataSource = Array.from(groups.entries()).map(([category, members]) => {
    const avg = safeAvg(members.map((m) => Number(m.durationSeconds)));
    const agents = members.map((m) => m.mainAgent).filter((v) => v != null && v !== '');
    return {
      key: category,
      category,
      count: members.length,
      ratio: total ? +((members.length / total) * 100).toFixed(1) : 0,
      avgDur: avg,
      mainAgent: modeOf(agents),
      suggestion: ROOT_CAUSE_SUGGESTION[category] || '优化相关链路',
      members,
    };
  });

  const columns = [
    {
      title: '根因类别',
      dataIndex: 'category',
      key: 'category',
      render: (v) => <Tag color={ROOT_CAUSE_TAG_COLOR[v] || 'default'}>{v}</Tag>,
    },
    {
      title: '会话数',
      dataIndex: 'count',
      key: 'count',
      align: 'right',
      render: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12, fontWeight: 600 }}>{v}</span>,
    },
    {
      title: '占比',
      dataIndex: 'ratio',
      key: 'ratio',
      align: 'right',
      render: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}%</span>,
    },
    {
      title: '平均耗时',
      dataIndex: 'avgDur',
      key: 'avgDur',
      align: 'right',
      render: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v != null ? `${v}s` : '—'}</span>,
    },
    {
      title: '主要 Agent',
      dataIndex: 'mainAgent',
      key: 'mainAgent',
      render: (v) =>
        v === '—' ? (
          <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>—</span>
        ) : (
          <span style={{ fontSize: 12 }}>{v}</span>
        ),
    },
    {
      title: '建议',
      dataIndex: 'suggestion',
      key: 'suggestion',
      render: (v) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>{v}</span>,
    },
  ];

  return (
    <Table
      dataSource={dataSource}
      columns={columns}
      rowKey="key"
      pagination={false}
      size="small"
      expandable={{
        expandedRowRender: (record) => (
          <div style={{ padding: '8px 12px', background: '#fafafa', borderRadius: 6 }}>
            <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>
              该类别下会话（点击会话 ID 查看链路）
            </div>
            <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
              <tbody>
                {record.members.map((m) => (
                  <tr key={m.sessionId}>
                    <td style={{ padding: '4px 8px' }}>
                      <span
                        style={{ fontFamily: 'monospace', fontSize: 12, color: '#1677ff', cursor: 'pointer' }}
                        onClick={() => onJumpTrace && onJumpTrace(m.sessionId)}
                      >
                        {m.sessionId}
                      </span>
                    </td>
                    <td style={{ padding: '4px 8px', fontFamily: 'monospace' }}>
                      {m.durationSeconds != null ? `${m.durationSeconds}s` : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ),
      }}
    />
  );
}

/* ───────── ④ 不满意会话共性表（加「全局均值 / 偏差」列 + 条件触发）───────── */

/**
 * UnsatisfiedTable — 列由 [维度/会话数/共性模式/示例会话] 改为
 *   [共性维度 / 分类 / 不满意数 / 不满意率 / 全局均值 / 偏差]（对齐 DEMO V23 L643-651）。
 *
 * 条件触发：整体不满意率(overallRate) < 10% 时展示绿色状态并隐去表格；
 *           否则展示聚合表。整体不满意率由调用方从报告级指标传入。
 * 偏差 = 该维度不满意率 − 全局均值（各维度不满意率均值），红(↑)/绿(↓) 带箭头。
 * 保留增强：行可展开查看共性模式 + 示例会话。
 *
 * @param {Array} unsatisfied  聚类列表 [{dimension,category,count,total,rate,commonPattern,examples}]
 * @param {number} [overallRate] 整体不满意率（%），<10 时隐藏表格
 */
export function UnsatisfiedTable({ unsatisfied, overallRate }) {
  const rows = Array.isArray(unsatisfied) ? unsatisfied : [];

  if (rows.length === 0) {
    return <Empty description="暂无不满意聚类（近期无负面满意度会话）" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  // 条件未触发（整体不满意率低）→ 绿色状态
  if (overallRate != null && !isNaN(Number(overallRate)) && Number(overallRate) < 10) {
    return <div style={GREEN_STATE_STYLE}>✅ 不满意率低于阈值，无需关注</div>;
  }

  // 各维度不满意率（优先 rate，否则 count/total）
  const rateOf = (r) => {
    if (r.rate != null && !isNaN(Number(r.rate))) return Number(r.rate);
    if (r.total && r.count != null) return +((r.count / r.total) * 100).toFixed(1);
    return null;
  };
  const rates = rows.map(rateOf).filter((v) => v != null);
  const globalMean = rates.length ? +(rates.reduce((a, b) => a + b, 0) / rates.length).toFixed(1) : null;

  const dataSource = rows.map((r, i) => {
    const rate = rateOf(r);
    const dev = rate != null && globalMean != null ? +(rate - globalMean).toFixed(1) : null;
    return { ...r, key: r.dimension || i, _rate: rate, _dev: dev };
  });

  const columns = [
    {
      title: '共性维度',
      dataIndex: 'dimension',
      key: 'dimension',
      render: (v) => <span style={{ fontWeight: 600, fontSize: 13 }}>{v || '—'}</span>,
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      render: (v) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>{v || '—'}</span>,
    },
    {
      title: '不满意数',
      dataIndex: 'count',
      key: 'count',
      align: 'right',
      render: (v) => <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#ff4d4f', fontWeight: 600 }}>{v}</span>,
    },
    {
      title: '不满意率',
      dataIndex: '_rate',
      key: 'rate',
      align: 'right',
      render: (v) =>
        v != null ? (
          <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#ff4d4f', fontWeight: 600 }}>{v}%</span>
        ) : (
          <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'rgba(0,0,0,.45)' }}>—</span>
        ),
    },
    {
      title: '全局均值',
      dataIndex: '_mean',
      key: 'mean',
      align: 'right',
      render: () =>
        globalMean != null ? (
          <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{globalMean}%</span>
        ) : (
          <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'rgba(0,0,0,.45)' }}>—</span>
        ),
    },
    {
      title: '偏差',
      dataIndex: '_dev',
      key: 'dev',
      align: 'right',
      render: (v) => renderDeviation(v),
    },
  ];

  return (
    <Table
      dataSource={dataSource}
      columns={columns}
      rowKey="key"
      pagination={false}
      size="small"
      expandable={{
        expandedRowRender: (record) => (
          <div style={{ padding: '8px 12px', background: '#fafafa', borderRadius: 6, fontSize: 12 }}>
            {record.commonPattern && (
              <div style={{ marginBottom: 6 }}>
                <b>共性模式：</b>
                {record.commonPattern}
              </div>
            )}
            {Array.isArray(record.examples) && record.examples.length > 0 && (
              <div>
                <b>示例会话：</b>
                {record.examples.join('、')}
              </div>
            )}
            {!record.commonPattern && (!record.examples || record.examples.length === 0) && (
              <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>
            )}
          </div>
        ),
      }}
    />
  );
}

/** 渲染偏差：正(高于均值)→红 ↑ +X%；负(低于均值)→绿 ↓ −X% */
function renderDeviation(dev) {
  if (dev == null) return <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'rgba(0,0,0,.45)' }}>—</span>;
  const up = dev > 0;
  const sign = up ? '+' : '';
  const color = up ? '#ff4d4f' : '#52c41a';
  const arrow = up ? '↑' : '↓';
  return (
    <span style={{ fontFamily: 'monospace', fontSize: 12, fontWeight: 600, color }}>
      {arrow} {sign}
      {dev}%
    </span>
  );
}

/* ───────── ⑤ Agent P95 vs 错误率散点 ───────── */

export function PerfScatter({ perf }) {
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

/* ───────── 共享样式 ───────── */

const GREEN_STATE_STYLE = {
  padding: '12px 16px',
  background: '#f6ffed',
  border: '1px solid #b7eb8f',
  borderRadius: 8,
  color: '#52c41a',
  fontSize: 13,
};

export default DiagnosisCockpit;
