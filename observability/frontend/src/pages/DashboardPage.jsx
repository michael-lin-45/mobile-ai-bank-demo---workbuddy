import React, { useState, useCallback } from 'react';
import MetricCard from '../components/MetricCard';
import { fetchRealtimeMetrics } from '../api/client';
import usePolling from '../hooks/usePolling';
import DiagnosisSummary4Cards from '../components/dashboard/DiagnosisSummary4Cards';
import ZoneCSemantic from '../components/dashboard/ZoneCSemantic';
import ZoneDBusiness from '../components/dashboard/ZoneDBusiness';
import ZoneERag from '../components/dashboard/ZoneERag';

/**
 * 总览大屏（V23 M6 / 任务分解 B3 重构）。
 *
 * 结构：诊断摘要 4 卡 + Zone A-E，吃 B2 实时指标与业务埋点数据。
 * 删除冗余：数据健康 health-pill、请求 Token 趋势、Agent 分布、
 * 性能瓶颈详情、底部慢调用 TopN（M6④）。
 */
function DashboardPage() {
  const [metrics, setMetrics] = useState(null);
  const [error, setError] = useState(null);

  const loadMetrics = useCallback(async () => {
    try {
      const data = await fetchRealtimeMetrics();
      if (data) {
        setMetrics(data);
        setError(null);
      }
    } catch (err) {
      // 后端未就绪时显示错误，不注入假数据
      setError(err.message);
    }
  }, []);

  // 3s 轮询主指标
  usePolling(loadMetrics, 3000, true);

  // 诊断摘要 4 卡数据源（风险计数卡，对齐 DEMO V23）。
  // 优先从 insights 聚合取数（后端就绪后替换）；未就绪用 mock 形状兜底，
  // 不再从 RealtimeMetricsVO 取 KPI 渲染此卡。
  const risks = {
    perf: 2,
    accuracy: 3,
    conversion: 1,
    satisfaction: 4.1,
    subs: {
      perf: '理财咨询 P95 超标',
      accuracy: '低置信度意图',
      conversion: '参数提取阶段',
      satisfaction: '↑ 0.2 较昨日',
    },
  };

  // 首次加载中 — 显示加载状态
  if (metrics === null) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        {error && (
          <div style={{
            padding: 10,
            background: '#fff2f0',
            border: '1px solid #ffccc7',
            borderRadius: 6,
            color: '#ff4d4f',
            fontSize: 12,
          }}>
            ⚠ 后端数据加载失败，请检查后端服务 — {error}
          </div>
        )}
        <div style={{
          textAlign: 'center',
          padding: '80px 20px',
          color: 'rgba(0,0,0,.25)',
          fontSize: 14,
        }}>
          {error ? '数据加载失败，正在重试…' : '正在加载实时指标…'}
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* 错误提示 */}
      {error && (
        <div style={{
          padding: 10,
          background: '#fff2f0',
          border: '1px solid #ffccc7',
          borderRadius: 6,
          color: '#ff4d4f',
          fontSize: 12,
        }}>
          ⚠ 后端数据加载失败，请检查后端服务 — {error}
        </div>
      )}

      {/* 诊断摘要 4 卡（风险卡，下钻「智能诊断」/「满意度」） */}
      <DiagnosisSummary4Cards risks={risks} />

      {/* Zone A — 系统健康 */}
      <div>
        <div style={sectionHeaderStyle('#1677ff', 'A')}>
          系统健康
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
          <MetricCard
            label="访问用户量 (DAU)"
            icon="👥"
            value={metrics.dau != null ? metrics.dau.toLocaleString() : '-'}
            unit="人"
            delta={metrics.activeSessionsDelta || null}
            deltaUp={metrics.activeSessionsDeltaUp}
            deltaNote="较昨日"
            sub={`实时在线 ${metrics.realTimeOnline != null ? metrics.realTimeOnline + ' 人' : '-'}`}
            sparkColor="#1677ff"
          />
          <MetricCard
            label="访问次数 / QPS"
            icon="📈"
            value={metrics.requestCount != null ? (metrics.requestCount / 6 / 3600).toFixed(1) : '-'}
            unit="req/s"
            delta={metrics.requestCountDelta || null}
            deltaUp={metrics.requestCountDeltaUp}
            deltaNote="较昨日"
            sub={`近6小时累计 ${metrics.requestCount != null ? metrics.requestCount.toLocaleString() : '-'} 次`}
            sparkColor="#1677ff"
          />
          <MetricCard
            label="Agent 调用 L0/L1/L2"
            icon="🤖"
            value={metrics.agentCallCount != null ? metrics.agentCallCount.toLocaleString() : '-'}
            unit="次"
            delta={metrics.agentCallDelta || null}
            deltaUp={metrics.agentCallDeltaUp}
            sub={`L0: ${metrics.l0Calls != null ? metrics.l0Calls : '-'} · L1: ${metrics.l1Calls != null ? metrics.l1Calls : '-'} · L2: ${metrics.l2Calls != null ? metrics.l2Calls : '-'}`}
            sparkColor="#1677ff"
          />
        </div>
      </div>

      {/* Zone B — AI 性能 */}
      <div>
        <div style={sectionHeaderStyle('#722ed1', 'B')}>
          AI 性能
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
          <MetricCard
            label="Token 调用量"
            icon="🪙"
            value={formatBigNumber((metrics.tokenInput || 0) + (metrics.tokenOutput || 0))}
            unit=""
            delta={metrics.tokenDelta || null}
            deltaUp={metrics.tokenDeltaUp}
            deltaNote="较昨日"
            sub={`输入 ${formatBigNumber(metrics.tokenInput || 0)} · 输出 ${formatBigNumber(metrics.tokenOutput || 0)}`}
            sparkColor="#722ed1"
          />
          <MetricCard
            label="首 Token 时延 P95"
            icon="⏱"
            value={metrics.ttftP95 != null ? metrics.ttftP95 : '-'}
            unit="ms"
            delta={metrics.ttftDelta || null}
            deltaUp={metrics.ttftDeltaUp}
            deltaNote="较昨日"
            sub={`P50: ${metrics.ttftP50 != null ? metrics.ttftP50 + 'ms' : '-'} · P99: ${metrics.ttftP99 != null ? metrics.ttftP99 + 'ms' : '-'}`}
            sparkColor="#722ed1"
          />
          <MetricCard
            label="系统时延 P95"
            icon="⚡"
            value={metrics.p95Latency != null ? Math.round(metrics.p95Latency) : '-'}
            unit="ms"
            delta={metrics.latencyDelta || null}
            deltaUp={metrics.latencyDeltaUp}
            deltaNote="P95较昨日"
            sub={`P50: ${metrics.p50Latency != null ? Math.round(metrics.p50Latency) : '-'}ms · P99: ${metrics.p99Latency != null ? Math.round(metrics.p99Latency) : '-'}ms`}
            sparkColor="#722ed1"
          />
        </div>
      </div>

      {/* Zone C — 语义质量（B3 新卡：综合正确率 / 改写L1 / 重路由） */}
      <ZoneCSemantic metrics={metrics} />

      {/* Zone D — 业务效果（B3 新卡：业务完成率 / 引导办理 / 转人工） */}
      <ZoneDBusiness metrics={metrics} />

      {/* Zone E — 知识检索（RAG） */}
      <ZoneERag metrics={metrics} />
    </div>
  );
}

function sectionHeaderStyle(color, tag) {
  return {
    fontSize: 13,
    fontWeight: 600,
    color: 'rgba(0,0,0,.65)',
    marginBottom: 12,
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    paddingLeft: 10,
    borderLeft: `3px solid ${color}`,
  };
}

/** 格式化大数字 */
function formatBigNumber(n) {
  if (n == null || n === 0) return '0';
  if (n >= 1000000) return (n / 1000000).toFixed(2) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
  return String(n);
}

export default DashboardPage;
