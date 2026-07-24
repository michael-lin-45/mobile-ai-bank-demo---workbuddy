import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Tag, Spin, Button, Alert, Space, Typography, Progress,
} from 'antd';
import {
  ThunderboltOutlined, WarningOutlined, AimOutlined, DotChartOutlined,
  ApartmentOutlined,
} from '@ant-design/icons';
import {
  fetchInsightsReport, fetchBottlenecks, fetchRootCause, fetchUnsatisfied,
  fetchInsightsConversion, fetchInsightsActions, fetchAgentPerformance, refreshInsights,
} from '../api/client';
import { formatBeijingTime } from '../utils/time';
import { isEmpty } from '../services/insightAdapters';
import { getDiagnosisMocks } from '../services/mockInsights';
import { DemoBadge } from './InsightKpiCard';
// 复用驾驶舱的 5 个已验证子区块（M6：DiagnosisCockpit 降级为被复用子区块）
import {
  ActionList, BottleneckCards, SlowSessionTable, UnsatisfiedTable, PerfScatter,
} from './DiagnosisCockpit';

const { Text } = Typography;

/**
 * DiagnosisUnified — 整合后的「智能诊断」统一视图（合并 cockpit 5 区块 + 诊断快照 + 转化漏斗 Progress + 散点）。
 *
 * 区块顺序（docs/system_design.md §4.2）：
 *   ① 诊断快照  ② 优先行动 TOP5  ③ 三类瓶颈卡  ④ 慢会话根因表（可展开）
 *   ⑤ 不满意共性表  ⑥ 转化漏斗 Progress 条  ⑦ Agent P95 vs 错误率散点
 *
 * 各区块独立 mock 兜底：对应 fetch 失败/空 → getDiagnosisMocks() 对应子块，并加 subtle DEMO 角标。
 * 保留 cockpit 的 refresh 按钮 + global-refresh 监听。
 */
function DiagnosisUnified() {
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [report, setReport] = useState(null);
  const [actions, setActions] = useState([]);
  const [bottlenecks, setBottlenecks] = useState([]);
  const [unsatisfied, setUnsatisfied] = useState([]);
  const [conversion, setConversion] = useState(null);
  const [agentPerf, setAgentPerf] = useState(null);

  const [reportDemo, setReportDemo] = useState(false);
  const [actionsDemo, setActionsDemo] = useState(false);
  const [bottlenecksDemo, setBottlenecksDemo] = useState(false);
  const [unsatDemo, setUnsatDemo] = useState(false);
  const [convDemo, setConvDemo] = useState(false);
  const [perfDemo, setPerfDemo] = useState(false);

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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* 统一视图头（保留 cockpit refresh 逻辑） */}
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
          智能诊断
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
        <Alert type="warning" showIcon message={error} style={{ borderRadius: 8 }} />
      )}

      <Spin spinning={loading && !report}>
        {/* ① 诊断快照 */}
        <Card size="small" extra={reportDemo ? <DemoBadge /> : null}>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <div>
              <Text strong style={{ fontSize: 15 }}>智能诊断快照</Text>
              <Tag color="blue" style={{ marginLeft: 8 }}>{report ? (report.boundary || 'L1') : 'L1'}</Tag>
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {report ? report.generatedAt : ''}
            </Text>
          </Space>
          <div style={{ marginTop: 8 }}>
            <Text>{report ? report.summary : '加载中…'}</Text>
          </div>
        </Card>

        {/* ② 优先行动建议 TOP5 */}
        <Card
          size="small"
          title={
            <span>
              <ThunderboltOutlined style={{ color: '#fa8c16', marginRight: 6 }} />
              优先行动建议 TOP5
            </span>
          }
          extra={actionsDemo ? <DemoBadge /> : null}
          style={{ borderRadius: 8 }}
        >
          <ActionList actions={actions} />
        </Card>

        {/* ③ 三类瓶颈卡 */}
        <Card
          size="small"
          title={
            <span>
              <WarningOutlined style={{ color: '#ff4d4f', marginRight: 6 }} />
              三类瓶颈卡（性能 / 准确率 / 转化）
            </span>
          }
          extra={bottlenecksDemo ? <DemoBadge /> : null}
          style={{ borderRadius: 8 }}
        >
          <BottleneckCards bottlenecks={bottlenecks} />
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
          <UnsatisfiedTable unsatisfied={unsatisfied} />
        </Card>

        {/* ⑥ 转化漏斗 Progress 条 */}
        <Card
          size="small"
          title="业务转化漏斗"
          extra={convDemo ? <DemoBadge /> : null}
          style={{ borderRadius: 8 }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {conversionProgress.map((p) => (
              <div key={p.stage}>
                <div style={{ fontSize: 12, color: 'rgba(0,0,0,.65)', marginBottom: 2 }}>
                  {p.stage} · {p.rate}%
                </div>
                <Progress percent={p.rate} showInfo={false} size="small" strokeColor="#1677ff" />
              </div>
            ))}
          </div>
        </Card>

        {/* ⑦ Agent P95 vs 错误率散点 */}
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
      </Spin>
    </div>
  );
}

export default DiagnosisUnified;
