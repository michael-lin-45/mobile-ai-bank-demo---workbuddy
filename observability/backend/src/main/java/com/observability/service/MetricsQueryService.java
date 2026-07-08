package com.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.observability.dto.RealtimeMetricsVO;
import com.observability.model.MetricsAgg;
import com.observability.model.SpanEntity;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.RedisMetricsSnapshotRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 指标查询服务 — Redis 实时 + H2 历史
 */
@Slf4j
@Service
public class MetricsQueryService {

    private final RedisMetricsService redisMetrics;
    private final MetricsAggRepository metricsAggRepository;
    private final RedisMetricsSnapshotRepository snapshotRepository;
    private final SpanRepository spanRepository;
    private final ObjectMapper objectMapper;
    private final AIInsightsService aiInsightsService;

    /** H2 回退节流：距上次回退至少 60s 才再次执行 */
    private volatile long lastFallbackMs = 0L;

    public MetricsQueryService(RedisMetricsService redisMetrics,
                               MetricsAggRepository metricsAggRepository,
                               RedisMetricsSnapshotRepository snapshotRepository,
                               SpanRepository spanRepository,
                               ObjectMapper objectMapper,
                               AIInsightsService aiInsightsService) {
        this.redisMetrics = redisMetrics;
        this.metricsAggRepository = metricsAggRepository;
        this.snapshotRepository = snapshotRepository;
        this.spanRepository = spanRepository;
        this.objectMapper = objectMapper;
        this.aiInsightsService = aiInsightsService;
    }

    /**
     * 获取实时指标（Redis 热层，Redis 为空时回退到 H2 温层）
     *
     * 注意：H2 回退仅在 Redis 完全为空时触发，且带节流（至少间隔 60s），
     * 避免每次 3s 轮询都做全表扫描压垮连接池。
     */
    public RealtimeMetricsVO getRealtime() {
        Set<String> fallbackMetrics = new HashSet<>();
        long requestCount = redisMetrics.getRequestCount("6h");
        long errorCount = redisMetrics.getErrorCount("6h");
        long tokenInput = redisMetrics.getTokenCount("1m", "input");
        long tokenOutput = redisMetrics.getTokenCount("1m", "output");
        Map<String, Long> redisIntentDist = redisMetrics.getIntentDistribution();
        Map<String, Long> intentDistribution = getIntentDistributionWithFallback(redisIntentDist);
        if ((redisIntentDist == null || redisIntentDist.isEmpty()) && !intentDistribution.isEmpty()) fallbackMetrics.add("intentDistribution");
        long redisActiveSessions = redisMetrics.getActiveSessions();
        long activeSessions = getLongWithFallback("active_sessions", redisActiveSessions);
        if (redisActiveSessions == 0 && activeSessions > 0) fallbackMetrics.add("activeSessions");

        // H2 回退：仅当核心指标全部为 0 且距上次回退 ≥ 60s 时执行
        boolean needFallback = (requestCount == 0 || tokenInput == 0 || tokenOutput == 0)
                && (System.currentTimeMillis() - lastFallbackMs > 60_000);
        if (needFallback) {
            lastFallbackMs = System.currentTimeMillis();
            Instant h2From = Instant.now().minusSeconds(7 * 86400); // 最近 7d
            Instant h2To = Instant.now();

            if (tokenInput == 0 && tokenOutput == 0) {
                long h2Input = getLatestMetricValue("llm.token.input", h2From, h2To);
                long h2Output = getLatestMetricValue("llm.token.output", h2From, h2To);
                if (h2Input > 0) tokenInput = h2Input;
                if (h2Output > 0) tokenOutput = h2Output;
            }
            if (requestCount == 0) {
                long h2Req = sumMetricValue("agent.intent.recognized", h2From, h2To);
                if (h2Req > 0) requestCount = h2Req;
            }
            if (errorCount == 0) {
                long h2Err = getLatestMetricValue("llm.error.count", h2From, h2To);
                if (h2Err > 0) errorCount = h2Err;
            }
            if (intentDistribution == null || intentDistribution.isEmpty()) {
                intentDistribution = aggregateIntentDistribution(h2From, h2To);
            }

            // agent layer fallback: 从 SpanEntity 表按 operationName 统计
            long l0c = redisMetrics.getAgentCallCount("L0");
            long l1c = redisMetrics.getAgentCallCount("L1");
            long l2c = redisMetrics.getAgentCallCount("L2");
            if (l0c == 0 || l1c == 0 || l2c == 0) {
                try {
                    // 从 SpanEntity 中按 operationName 统计各层
                    List<String> allOpNames = spanRepository.findDistinctOperationNames();
                    long countL0 = 0, countL1 = 0, countL2 = 0;
                    for (String opName : allOpNames) {
                        if (opName == null) continue;
                        if (opName.contains("L0") || opName.contains("DomainRouter")) countL0++;
                        else if (opName.contains("L1") || opName.contains("Intent") || opName.contains("WorkflowL1")) countL1++;
                        else if (opName.contains("L2") || opName.contains("Service") || opName.contains("Graph")) countL2++;
                    }
                    if (l0c == 0 && countL0 > 0) redisMetrics.setAgentCallCount("L0", countL0);
                    if (l1c == 0 && countL1 > 0) redisMetrics.setAgentCallCount("L1", countL1);
                    if (l2c == 0 && countL2 > 0) redisMetrics.setAgentCallCount("L2", countL2);
                } catch (Exception e) {
                    log.debug("[MetricsQuery] SpanEntity fallback failed: {}", e.getMessage());
                }
            }
        }

        Map<String, Double> redisLatency = redisMetrics.getLatencyStats("1m");
        Map<String, Double> latencyStats = getLatencyStatsWithFallback("1m", redisLatency);
        if ((redisLatency == null || redisLatency.getOrDefault("avg", 0.0) == 0.0) && latencyStats.getOrDefault("avg", 0.0) > 0) fallbackMetrics.add("latency");
        double avgLatency = latencyStats.getOrDefault("avg", 0.0);
        double p50Latency = latencyStats.getOrDefault("p50", 0.0);
        double p95Latency = latencyStats.getOrDefault("p95", 0.0);

        RealtimeMetricsVO vo = new RealtimeMetricsVO(
                requestCount, errorCount, avgLatency,
                tokenInput, tokenOutput,
                intentDistribution, activeSessions, p50Latency, p95Latency
        );

        // ── Zone A: 系统健康 ──
        long redisDau = redisMetrics.getDau();
        vo.setDailyActiveUsers(getLongWithFallback("dau", redisDau));
        if (redisDau == 0) fallbackMetrics.add("dau");
        long redisOnline = redisMetrics.getOnlineUsers();
        vo.setRealtimeOnline(getLongWithFallback("online_users", redisOnline));
        if (redisOnline == 0) fallbackMetrics.add("onlineUsers");
        vo.setQps(requestCount / 21600.0);
        long redisL0 = redisMetrics.getAgentCallCount("L0");
        long l0Calls = getLongWithFallback("agent_call:L0", redisL0);
        if (redisL0 == 0) fallbackMetrics.add("l0Calls");
        long redisL1 = redisMetrics.getAgentCallCount("L1");
        long l1Calls = getLongWithFallback("agent_call:L1", redisL1);
        if (redisL1 == 0) fallbackMetrics.add("l1Calls");
        long redisL2 = redisMetrics.getAgentCallCount("L2");
        long l2Calls = getLongWithFallback("agent_call:L2", redisL2);
        if (redisL2 == 0) fallbackMetrics.add("l2Calls");
        vo.setAgentCallsL0(l0Calls);
        vo.setAgentCallsL1(l1Calls);
        vo.setAgentCallsL2(l2Calls);
        vo.setAgentCallCount(l0Calls + l1Calls + l2Calls);

        // ── Zone B: AI 性能 ──
        Map<String, Long> redisTTFT = redisMetrics.getTTFTStats("1m");
        Map<String, Long> ttftStats = getTTFTStatsWithFallback("1m", redisTTFT);
        if ((redisTTFT == null || redisTTFT.getOrDefault("p50", 0L) == 0) && ttftStats.getOrDefault("p50", 0L) > 0) fallbackMetrics.add("ttft");
        vo.setTtftP50Ms(ttftStats.getOrDefault("p50", 0L));
        vo.setTtftP95Ms(ttftStats.getOrDefault("p95", 0L));
        vo.setTtftP99Ms(ttftStats.getOrDefault("p99", 0L));
        vo.setErrorRate(requestCount > 0 ? (double) errorCount / requestCount : 0.0);

        // ── Zone C: 语义质量 ──
        Double redisIntentAcc = redisMetrics.getIntentAccuracy();
        // P0-3 修复：Redis accuracy:intent 全库无写入方（永远 null）→ 改从 H2 agent.intent.accuracy 实时计算真实准确率（0-100）。
        // 仅在 Redis 也无值时回退到 H2；若 H2 仍无数据才标记为 fallback。
        Double intentAcc = redisIntentAcc != null
                ? redisIntentAcc
                : aiInsightsService.computeIntentAccuracy(Instant.now().minusSeconds(6 * 3600), Instant.now());
        vo.setIntentAccuracy(intentAcc);
        if (intentAcc == null) fallbackMetrics.add("intentAccuracy");
        Double redisRewriteAcc = redisMetrics.getRewriteAccuracy();
        // C2 修复：Redis accuracy:rewrite 全库无写入方（永远 null）→ 改从 H2 agent.rewrite.accuracy 实时计算真实改写准确率（0-100）。
        Double rewriteAcc = redisRewriteAcc != null
                ? redisRewriteAcc
                : aiInsightsService.computeRewriteAccuracy(Instant.now().minusSeconds(6 * 3600), Instant.now());
        vo.setRewriteAccuracy(rewriteAcc);
        if (rewriteAcc == null) fallbackMetrics.add("rewriteAccuracy");
        // C3 修复：Redis reroute_rate 全库无写入方（永远 null）→ 改从 H2 agent.reroute.count 实时计算
        // REROUTE 比率 = rerouteCount / (l0Calls + l1Calls) × 100（分母为 L0/L1 路由层调用数）。
        Double redisReroute = redisMetrics.getRerouteRate();
        double rerouteRate;
        long rerouteCount = aiInsightsService.computeRerouteCount(
                Instant.now().minusSeconds(6 * 3600), Instant.now()).longValue();
        if (redisReroute != null) {
            rerouteRate = redisReroute;
        } else if ((l0Calls + l1Calls) > 0) {
            rerouteRate = Math.round(rerouteCount * 10000.0 / (l0Calls + l1Calls)) / 100.0;
        } else {
            rerouteRate = 0.0;
        }
        vo.setRerouteRate(rerouteRate);
        if (redisReroute == null && rerouteCount == 0) fallbackMetrics.add("rerouteRate");
        Double redisCompletion = redisMetrics.getBusinessCompletionRate();
        vo.setBusinessCompletionRate(getDoubleWithFallback("business_completion", redisCompletion));
        if (redisCompletion == null) fallbackMetrics.add("businessCompletion");

        // ── Zone D: 业务效果 ──
        Double redisConversion = redisMetrics.getConversionRate();
        vo.setBusinessConversionRate(getDoubleWithFallback("conversion", redisConversion));
        if (redisConversion == null) fallbackMetrics.add("conversionRate");
        vo.setViolationRate(null);  // P0 占位，前端展示"暂无"
        vo.setTransferToHumanRate(null); // P0 占位

        // ── Agent 分布 ──
        Map<String, Long> agentDist = new java.util.LinkedHashMap<>();
        agentDist.put("l0Calls", vo.getAgentCallsL0());
        agentDist.put("l1Calls", vo.getAgentCallsL1());
        agentDist.put("l2Calls", vo.getAgentCallsL2());
        vo.setAgentDistribution(agentDist);

        // Set fallback tracking on VO
        vo.setFallbackMetrics(fallbackMetrics);
        vo.setUsingFallback(!fallbackMetrics.isEmpty());

        return vo;
    }

    /**
     * 获取历史指标（H2 温层）
     */
    public List<MetricsAgg> getHistory(Instant from, Instant to, String step) {
        if (from == null) from = Instant.now().minusSeconds(3600);
        if (to == null) to = Instant.now();
        if (step == null || step.isBlank()) step = "5m";

        return metricsAggRepository.findByAggWindowAndTimestampBetween(step, from, to);
    }

    /**
     * 获取指定指标名称的历史数据
     */
    public List<MetricsAgg> getMetricHistory(String metricName, Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(3600);
        if (to == null) to = Instant.now();
        return metricsAggRepository.findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
    }

    // ==================== H2 回退聚合工具（严格使用索引查询） ====================

    /** 按精确 metricName 查询，命中 idx_metrics_name_ts 索引 */
    private long sumMetricValue(String metricName, Instant from, Instant to) {
        try {
            return metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to)
                    .stream()
                    .mapToLong(m -> m.getValue() != null ? m.getValue().longValue() : 0L)
                    .sum();
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 fallback sum failed for {}: {}", metricName, e.getMessage());
            return 0L;
        }
    }

    /** Get the latest (most recent) metric value - for cumulative counters */
    private long getLatestMetricValue(String metricName, Instant from, Instant to) {
        try {
            var list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
            if (list.isEmpty()) return 0L;
            // Return the last (most recent) value for cumulative counters
            return list.get(list.size() - 1).getValue() != null
                    ? list.get(list.size() - 1).getValue().longValue() : 0L;
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 latest value failed for {}: {}", metricName, e.getMessage());
            return 0L;
        }
    }


    private Map<String, Long> aggregateIntentDistribution(Instant from, Instant to) {
        Map<String, Long> result = new HashMap<>();
        // 只查 router.decision.outcome 的 dataPoints（含 domain tag），不扫全表
        try {
            List<MetricsAgg> list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc("agent.router.decision.outcome", from, to);
            for (MetricsAgg m : list) {
                String domain = extractTagValue(m.getTags(), "domain");
                if (domain != null && !domain.isBlank()) {
                    result.merge(domain, m.getValue() != null ? m.getValue().longValue() : 0L, Long::sum);
                }
            }
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 intent fallback failed: {}", e.getMessage());
        }
        return result;
    }

    private String extractTagValue(String tagsJson, String key) {
        if (tagsJson == null || tagsJson.isBlank()) return null;
        try {
            Map<String, String> tags = objectMapper.readValue(tagsJson, new TypeReference<Map<String, String>>() {});
            return tags.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Redis-first H2-fallback helpers ====================
    //
    // Semantics:
    //   Counter metrics (request_count, token_*, agent_call, etc.):
    //     H2 snapshot stores the latest cumulative value. Fallback is semantically correct.
    //
    //   Gauge metrics (dau, accuracy, conversion, etc.):
    //     H2 snapshot stores the latest instantaneous value. Fallback represents
    //     'last known good value' (at most 30s stale).
    //
    //   ZSET distribution metrics (latency P50/P95, TTFT P50/P95/P99):
    //     H2 snapshot stores pre-computed percentile values, NOT the raw distribution.
    //     Fallback returns 'last computed percentiles' which may not reflect the current
    //     request window. This is acceptable for dashboard continuity during Redis outages.
    //
    //   HASH distribution (intent_distribution):
    //     H2 snapshot stores per-intent counts at snapshot time. Fallback returns the
    //     latest snapshot which is a point-in-time count, not a live cumulative total.
    //
    //   Derived metrics (error_rate, qps) are NOT snapshotted - they are always
    //   computed from the (possibly fallback) raw counters.

    private long getLongWithFallback(String redisKey, long redisValue) {
        if (redisValue > 0) return redisValue;
        try {
            return snapshotRepository.findLatestValueByKey(redisKey)
                    .map(v -> v.longValue())
                    .filter(v -> v > 0)
                    .orElse(0L);
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 snapshot fallback failed for {}: {}", redisKey, e.getMessage());
            return 0L;
        }
    }

    private Double getDoubleWithFallback(String redisKey, Double redisValue) {
        if (redisValue != null && redisValue > 0) return redisValue;
        try {
            return snapshotRepository.findLatestValueByKey(redisKey)
                    .filter(v -> v > 0)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 snapshot fallback failed for {}: {}", redisKey, e.getMessage());
            return null;
        }
    }

    private Map<String, Long> getIntentDistributionWithFallback(Map<String, Long> redisValue) {
        if (redisValue != null && !redisValue.isEmpty()) return redisValue;
        try {
            var snapshots = snapshotRepository.findByMetricKeyStartingWithOrderByTimestampDesc("intent_distribution:");
            Map<String, Long> result = new HashMap<>();
            for (var s : snapshots) {
                String intent = s.getMetricKey().substring("intent_distribution:".length());
                if (!result.containsKey(intent) && s.getMetricValue() != null && s.getMetricValue() > 0) {
                    result.put(intent, s.getMetricValue().longValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 intent distribution fallback failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Double> getLatencyStatsWithFallback(String window, Map<String, Double> redisValue) {
        if (redisValue != null && (redisValue.getOrDefault("avg", 0.0) > 0 || redisValue.getOrDefault("p50", 0.0) > 0)) {
            return redisValue;
        }
        try {
            double avg = snapshotRepository.findLatestValueByKey("latency_avg:" + window).orElse(0.0);
            double p50 = snapshotRepository.findLatestValueByKey("latency_p50:" + window).orElse(0.0);
            double p95 = snapshotRepository.findLatestValueByKey("latency_p95:" + window).orElse(0.0);
            return Map.of("avg", avg, "p50", p50, "p95", p95);
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 latency fallback failed: {}", e.getMessage());
            return Map.of("avg", 0.0, "p50", 0.0, "p95", 0.0);
        }
    }

    private Map<String, Long> getTTFTStatsWithFallback(String window, Map<String, Long> redisValue) {
        if (redisValue != null && (redisValue.getOrDefault("p50", 0L) > 0 || redisValue.getOrDefault("p95", 0L) > 0)) {
            return redisValue;
        }
        try {
            long p50 = snapshotRepository.findLatestValueByKey("ttft_p50:" + window).map(v -> v.longValue()).orElse(0L);
            long p95 = snapshotRepository.findLatestValueByKey("ttft_p95:" + window).map(v -> v.longValue()).orElse(0L);
            long p99 = snapshotRepository.findLatestValueByKey("ttft_p99:" + window).map(v -> v.longValue()).orElse(0L);
            return Map.of("p50", p50, "p95", p95, "p99", p99);
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 TTFT fallback failed: {}", e.getMessage());
            return Map.of("p50", 0L, "p95", 0L, "p99", 0L);
        }
    }
}
