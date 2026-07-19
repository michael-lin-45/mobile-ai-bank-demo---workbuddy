import React, { useState, useCallback } from 'react';
import MetricCard from '../components/MetricCard';
import TrendChart from '../components/charts/TrendChart';
import PieChart from '../components/charts/PieChart';
import HealthBadge from '../components/HealthBadge';
import { fetchRealtimeMetrics, fetchMetricsTrend } from '../api/client';
import usePolling from '../hooks/usePolling';

/**
 * 总览大屏 — 4区11卡 + 趋势图 + 饼图 + 3s轮询
 *
 * Zone A — 系统健康（3卡）
 * Zone B — AI 性能（3卡）
 * Zone C — 语义质量（3卡）
 * Zone D — 业务效果（2卡）
 */
function DashboardPage() {
  const [metrics, setMetrics] = useState(null);
  const [trend, setTrend] = useState(null);
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

  // 趋势数据（6h，按 30min 分桶）：相对静态，30s 轮询即可
  const loadTrend = useCallback(async () => {
    try {
      const data = await fetchMetricsTrend(6);
      if (data && data.times) setTrend(data);
    } catch (err) {
      // 趋势获取失败不阻断主指标展示
      console.warn('[Dashboard] trend fetch failed:', err.message);
    }
  }, []);

  // 3s 轮询主指标
  usePolling(loadMetrics, 3000, true);
  // 30s 轮询趋势
  usePolling(loadTrend, 30000, true);

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

      {/* Zone A — 系统健康 */}
      <div>
        <div style={{
          fontSize: 13,
          fontWeight: 600,
          color: 'rgba(0,0,0,.65)',
          marginBottom: 12,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          paddingLeft: 10,
          borderLeft: '3px solid #1677ff',
        }}>
          <span style={{
            fontSize: 11,
            padding: '1px 6px',
            borderRadius: 3,
            fontWeight: 500,
            background: '#e6f4ff',
            color: '#1677ff',
          }}>
            A
          </span>
          系统健康
          <span style={{ marginLeft: 'auto' }}>
            <HealthBadge />
          </span>
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
        <div style={{
          fontSize: 13,
          fontWeight: 600,
          color: 'rgba(0,0,0,.65)',
          marginBottom: 12,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          paddingLeft: 10,
          borderLeft: '3px solid #722ed1',
        }}>
          <span style={{
            fontSize: 11,
            padding: '1px 6px',
            borderRadius: 3,
            fontWeight: 500,
            background: '#f9f0ff',
            color: '#722ed1',
          }}>
            B
          </span>
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
            label="P95 系统时延 / 错误率"
            icon="⚡"
            value={metrics.p95Latency != null ? Math.round(metrics.p95Latency) : '-'}
            unit="ms"
            delta={metrics.latencyDelta || null}
            deltaUp={metrics.latencyDeltaUp}
            deltaNote="P95较昨日"
            sub={metrics.errorRate != null ? (
              <span style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '2px 8px',
                borderRadius: 4,
                fontSize: 11,
                fontWeight: 500,
                background: '#f6ffed',
                color: '#52c41a',
                border: '1px solid #b7eb8f',
              }}>
                错误率 {(metrics.errorRate * 100).toFixed(2)}%
              </span>
            ) : '-'}
            sparkColor="#722ed1"
          />
        </div>
      </div>

      {/* Zone C — 语义质量 */}
      <div>
        <div style={{
          fontSize: 13,
          fontWeight: 600,
          color: 'rgba(0,0,0,.65)',
          marginBottom: 12,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          paddingLeft: 10,
          borderLeft: '3px solid #52c41a',
        }}>
          <span style={{
            fontSize: 11,
            padding: '1px 6px',
            borderRadius: 3,
            fontWeight: 500,
            background: '#f6ffed',
            color: '#52c41a',
          }}>
            C
          </span>
          智能体语义质量
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
          <MetricCard
            label="意图 / 改写准确率"
            icon="🎯"
            value={metrics.intentAccuracy != null ? metrics.intentAccuracy : '-'}
            unit="%"
            delta={metrics.intentAccuracyDelta || null}
            deltaUp={metrics.intentAccuracyDeltaUp}
            sub={`改写 ${metrics.rewriteAccuracy != null ? metrics.rewriteAccuracy + '%' : '-'}${metrics.rewriteAccuracyDelta ? ` · ${metrics.rewriteAccuracyDelta}` : ''}`}
            sparkColor="#52c41a"
          />
          <MetricCard
            label="Reroute 率"
            icon="🔀"
            value={metrics.rerouteRate != null ? metrics.rerouteRate : '-'}
            unit="%"
            delta={metrics.rerouteRateDelta || null}
            deltaUp={metrics.rerouteRateDeltaUp}
            sub="低置信度触发 L1 二次识别"
            sparkColor="#52c41a"
          />
          <MetricCard
            label="业务完成率"
            icon="✅"
            value={metrics.completionRate != null ? metrics.completionRate : '-'}
            unit="%"
            delta={metrics.completionRateDelta || null}
            deltaUp={metrics.completionRateDeltaUp}
            sub={metrics.completionDetail || '-'}
            sparkColor="#52c41a"
          />
        </div>
      </div>

      {/* Zone D — 业务效果 */}
      <div>
        <div style={{
          fontSize: 13,
          fontWeight: 600,
          color: 'rgba(0,0,0,.65)',
          marginBottom: 12,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          paddingLeft: 10,
          borderLeft: '3px solid #d48806',
        }}>
          <span style={{
            fontSize: 11,
            padding: '1px 6px',
            borderRadius: 3,
            fontWeight: 500,
            background: '#fff7e6',
            color: '#d48806',
          }}>
            D
          </span>
          业务效果
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <MetricCard
            label="业务转化率"
            icon="📈"
            value={metrics.conversionRate != null ? metrics.conversionRate : '-'}
            unit="%"
            delta={metrics.conversionRateDelta || null}
            deltaUp={metrics.conversionRateDeltaUp}
            sub={metrics.conversionDetail || '-'}
            sparkColor="#d48806"
            empty={metrics.conversionRate == null}
            emptyText="暂无"
          />
          <MetricCard
            label="违规率"
            icon="🚨"
            value={metrics.violationRate != null ? metrics.violationRate : '-'}
            unit="%"
            delta={metrics.violationRateDelta || null}
            deltaUp={metrics.violationRateDeltaUp}
            sub="敏感操作拦截 / 合规检查"
            sparkColor="#d48806"
            empty={metrics.violationRate == null}
            emptyText="暂无"
          />
        </div>
      </div>

      {/* 趋势图 + 饼图 */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div style={{
          background: '#fff',
          borderRadius: 8,
          boxShadow: '0 1px 2px rgba(0,0,0,.03)',
          padding: 20,
        }}>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: 16,
          }}>
            <span style={{ fontSize: 15, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
              请求 & Token 趋势
            </span>
            <span style={{ fontSize: 13, color: 'rgba(0,0,0,.45)', cursor: 'pointer' }}>
              最近 6h
            </span>
          </div>
          <TrendChart height={280} data={trend} />
        </div>

        <div style={{
          background: '#fff',
          borderRadius: 8,
          boxShadow: '0 1px 2px rgba(0,0,0,.03)',
          padding: 20,
        }}>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: 16,
          }}>
            <span style={{ fontSize: 15, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
              Agent 分布
            </span>
            <span style={{ fontSize: 13, color: 'rgba(0,0,0,.45)' }}>
              按执行层
            </span>
          </div>
          <PieChart
            height={280}
            data={[
              { name: 'L0', value: metrics.l0Calls || 0 },
              { name: 'L1', value: metrics.l1Calls || 0 },
              { name: 'L2', value: metrics.l2Calls || 0 },
            ]}
          />
        </div>
      </div>
    </div>
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
