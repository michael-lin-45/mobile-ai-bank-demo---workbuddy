import React, { useState, useCallback } from 'react';
import { Tag } from 'antd';
import MetricCard from '../components/MetricCard';
import ZoneBadge from '../components/dashboard/ZoneBadge';
import { fetchRealtimeMetrics } from '../api/client';
import usePolling from '../hooks/usePolling';
import DiagnosisSummary4Cards from '../components/dashboard/DiagnosisSummary4Cards';
import ZoneCSemantic from '../components/dashboard/ZoneCSemantic';
import ZoneDBusiness from '../components/dashboard/ZoneDBusiness';
import ZoneERag from '../components/dashboard/ZoneERag';

/**
 * 总览大屏（V24 二次对齐 · 任务 T01/T05）。
 *
 * 本次对齐项（docs/specs/可观测V24-对齐差距分析）：
 *   - (b) Zone 字母徽标 A/B/C/D/E 统一渲染（sectionHeaderStyle 现消费 <ZoneBadge>）
 *   - (T05) 各 Zone 接入状态徽标 + Zone A DAU「待接入」标注
 *   - (反馈1) Zone B「P95 系统时延」移除错误率 badge（后端不发射该字段，原 0.12 为占位兜底）
 * 删除冗余：数据健康 health-pill、请求 Token 趋势、Agent 分布、性能瓶颈详情、底部慢调用 TopN。
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

      {/* Zone A — 系统健康（已接通） */}
      <div>
        <div style={sectionHeaderStyle('#1677ff')}>
          <ZoneBadge letter="A" color="#1677ff" />
          系统健康
          <ZoneStatusBadge text="已接通" tone="success" />
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
            sub={(
              <span>
                实时在线 {metrics.realTimeOnline != null ? metrics.realTimeOnline + ' 人' : '-'}
                <Tag color="default" style={{ marginLeft: 6, fontSize: 10, lineHeight: '16px' }}>DAU 待接入</Tag>
              </span>
            )}
            sparkColor="#1677ff"
            sparkData={[1180, 1205, 1220, 1240, 1260, 1275, 1284]}
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
            sparkData={[38, 40, 42, 43, 44, 45, 45]}
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
            sparkData={[320, 340, 360, 380, 410, 430, 448]}
          />
        </div>
      </div>

      {/* Zone B — AI 性能（已接通） */}
      <div>
        <div style={sectionHeaderStyle('#722ed1')}>
          <ZoneBadge letter="B" color="#722ed1" />
          AI 性能
          <ZoneStatusBadge text="已接通" tone="success" />
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
            sparkData={[1.8, 2.0, 2.1, 2.2, 2.3, 2.34, 2.4]}
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
            sparkData={[410, 440, 425, 470, 455, 480, 465]}
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
            sparkData={[280, 310, 295, 330, 320, 350, 340]}
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

function sectionHeaderStyle(color) {
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

/** Zone 接入状态徽标（T05：A/B 已接通 / C/D 部分待接入 / E 待 Core RAG 链路接入） */
function ZoneStatusBadge({ text, tone }) {
  const toneMap = {
    success: { color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
    warning: { color: '#d48806', bg: '#fffbe6', border: '#ffe7ba' },
  };
  const t = toneMap[tone] || toneMap.warning;
  return (
    <span
      style={{
        marginLeft: 'auto',
        fontSize: 11,
        color: t.color,
        background: t.bg,
        border: `1px solid ${t.border}`,
        borderRadius: 4,
        padding: '1px 8px',
      }}
    >
      {text}
    </span>
  );
}

/** 格式化大数字 */
function formatBigNumber(n) {
  if (n == null || n === 0) return '0';
  if (n >= 1000000) return (n / 1000000).toFixed(2) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
  return String(n);
}

export default DashboardPage;
