package com.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.observability.dto.RealtimeMetricsVO;
import com.observability.dto.TrendVO;
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

    /** 错误率缓存：基于 H2 spans 真实计算，60s 节流避免每次 3s 轮询都做 COUNT */
    private volatile long lastErrorRateMs = 0L;
    private volatile double cachedErrorRate = 0.0;

    /** Agent 分层调用数缓存：基于 H2 spans 真实计数（operation_name 前缀），60s 节流 */
    private volatile long lastAgentCallMs = 0L;
    private volatile long cachedL0 = 0L;
    private volatile long cachedL1 = 0L;
    private volatile long cachedL2 = 0L;

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

        Instant h2From = Instant.now().minusSeconds(2 * 86400); // 最近 2d（与 H2 数据保留期一致，见数据清理）
        Instant h2To = Instant.now();

        // ── Token / Error：Redis 1m 为 0 时，每次查询都用 H2 累计最大值兜底 ──
        // 廉价 MAX 查询，不节流（避免大屏在无实时流量时反复闪 0）。
        // 注意：Core 重启会使 cumulative 计数器归零，故取窗口内 MAX（=真实累计总量）而非最后一行。
        if (tokenInput == 0) {
            long h2Input = getMaxMetricValue("llm.token.input", h2From, h2To);
            if (h2Input > 0) { tokenInput = h2Input; fallbackMetrics.add("tokenInput"); }
        }
        if (tokenOutput == 0) {
            long h2Output = getMaxMetricValue("llm.token.output", h2From, h2To);
            if (h2Output > 0) { tokenOutput = h2Output; fallbackMetrics.add("tokenOutput"); }
        }
        if (errorCount == 0) {
            long h2Err = getMaxMetricValue("llm.error.count", h2From, h2To);
            if (h2Err > 0) errorCount = h2Err;
        }

        // ── RequestCount / Intent分布 / Agent分层：较贵（span 全表扫描），保留 60s 节流 ──
        boolean needFallback = (requestCount == 0)
                && (System.currentTimeMillis() - lastFallbackMs > 60_000);
        if (needFallback) {
            lastFallbackMs = System.currentTimeMillis();
            if (requestCount == 0) {
                long h2Req = sumMetricValue("agent.intent.recognized", h2From, h2To);
                if (h2Req > 0) requestCount = h2Req;
            }
            if (intentDistribution == null || intentDistribution.isEmpty()) {
                intentDistribution = aggregateIntentDistribution(h2From, h2To);
            }

        }

        // agent 分层调用数：基于 H2 spans 真实计数（operation_name 前缀），避免 per-export 失真（第十五轮诊断）。
        // 60s 节流缓存（与 errorRate 同模式），避免每次 3s 轮询都做全表 COUNT。
        long[] agentCounts = getAgentCallCountsFromSpans();
        long countL0 = agentCounts[0];
        long countL1 = agentCounts[1];
        long countL2 = agentCounts[2];
        redisMetrics.setAgentCallCount("L0", countL0);
        redisMetrics.setAgentCallCount("L1", countL1);
        redisMetrics.setAgentCallCount("L2", countL2);

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
        // Agent 分层调用数已基于 H2 spans 真实计数（countL0/L1/L2 为方法作用域变量，见上文节流块）
        vo.setAgentCallsL0(countL0);
        vo.setAgentCallsL1(countL1);
        vo.setAgentCallsL2(countL2);
        vo.setAgentCallCount(countL0 + countL1 + countL2);

        // ── Zone B: AI 性能 ──
        Map<String, Long> redisTTFT = redisMetrics.getTTFTStats("1m");
        Map<String, Long> ttftStats = getTTFTStatsWithFallback("1m", redisTTFT);
        if ((redisTTFT == null || redisTTFT.getOrDefault("p50", 0L) == 0) && ttftStats.getOrDefault("p50", 0L) > 0) fallbackMetrics.add("ttft");
        vo.setTtftP50Ms(ttftStats.getOrDefault("p50", 0L));
        vo.setTtftP95Ms(ttftStats.getOrDefault("p95", 0L));
        vo.setTtftP99Ms(ttftStats.getOrDefault("p99", 0L));
        // 错误率改为基于 H2 spans 真实计算（errored / total），避免 Redis 计数器按「导出次数」累加导致比例失真（曾出现 366%）。
        // 始终为 0~1 之间的分数，前端 ×100 显示 2 位小数百分比。
        vo.setErrorRate(getErrorRateFromSpans());

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
        // REROUTE 比率 = rerouteCount / (countL0 + countL1) × 100（分母为 L0/L1 路由层调用数）。
        Double redisReroute = redisMetrics.getRerouteRate();
        double rerouteRate;
        long rerouteCount = aiInsightsService.computeRerouteCount(
                Instant.now().minusSeconds(6 * 3600), Instant.now()).longValue();
        if (redisReroute != null) {
            rerouteRate = redisReroute;
        } else if ((countL0 + countL1) > 0) {
            rerouteRate = Math.round(rerouteCount * 10000.0 / (countL0 + countL1)) / 100.0;
        } else {
            rerouteRate = 0.0;
        }
        vo.setRerouteRate(rerouteRate);
        if (redisReroute == null && rerouteCount == 0) fallbackMetrics.add("rerouteRate");
        // C4 修复：Redis business_completion 全库无写入方（永远 null）→ 改从 H2 agent.business.outcome
        // 实时计算业务完成率（success/(success+fail)×100，0-100）。与 C1/C2/C3 同构兜底。
        Double redisCompletion = redisMetrics.getBusinessCompletionRate();
        Double completionRate = redisCompletion != null
                ? redisCompletion
                : aiInsightsService.computeBusinessSuccessRate(
                        Instant.now().minusSeconds(6 * 3600), Instant.now());
        vo.setBusinessCompletionRate(completionRate);
        if (completionRate == null) fallbackMetrics.add("businessCompletion");

        // ── Zone D: 业务效果 ──
        // D1 调整：业务转化率依赖外部业务系统（转化/到达语义）输入，Core 无该埋点，
        // 无法真实计算 → 与违规率(violationRate)一致，返回 null，前端展示"暂无"。
        vo.setBusinessConversionRate(null);
        // D2 violationRate：安全围栏系统未对接，Core 无违规语义埋点 → 暂无数据源，保持 null（前端展示"暂无"）。
        vo.setViolationRate(null);
        vo.setTransferToHumanRate(null); // P0 占位：转人工率暂无独立埋点

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

    /**
     * 请求 & Token 趋势（最近 hours 小时，按 30 分钟分桶，共 hours*2 个桶）
     *
     * - 请求量：根 span（parentSpanId 为空）按时间分桶计数。
     *   （request_count 仅存 Redis，未落 H2，故趋势请求量改由根 span 推导，数据来自 H2 spans 表）
     * - Token：llm.token.input / llm.token.output 为累计 Counter，
     *   按桶计算「相邻 1m 累计值之差」作为该桶实际消耗量（处理 Core 重启归零：差值为负时把当前值当作增量）。
     */
    public TrendVO getTrend(int hours) {
        int buckets = Math.max(1, hours * 2);          // 30min / 桶
        long bucketSec = 1800;
        Instant to = Instant.now();
        Instant from = to.minusSeconds((long) hours * 3600);
        long fromSec = from.getEpochSecond();

        // ── 请求量：根 span 计数 ──
        long[] req = new long[buckets];
        try {
            List<Instant> rootTimes = spanRepository.findRootSpanStartTimesSince(from);
            for (Instant t : rootTimes) {
                int idx = (int) ((t.getEpochSecond() - fromSec) / bucketSec);
                if (idx >= 0 && idx < buckets) req[idx]++;
            }
        } catch (Exception e) {
            log.debug("[MetricsQuery] trend root span count failed: {}", e.getMessage());
        }

        // ── Token：累计增量 ──
        long[] tok = new long[buckets];
        addTokenDeltaByBucket(tok, "llm.token.input", from, to, fromSec, bucketSec);
        addTokenDeltaByBucket(tok, "llm.token.output", from, to, fromSec, bucketSec);

        // ── 时间标签（HH:mm）──
        List<String> times = new java.util.ArrayList<>();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        for (int i = 0; i < buckets; i++) {
            java.time.LocalDateTime b = java.time.LocalDateTime.ofInstant(from.plusSeconds((long) i * bucketSec), zone);
            times.add(String.format("%02d:%02d", b.getHour(), b.getMinute()));
        }

        List<Long> requests = new java.util.ArrayList<>();
        for (long v : req) requests.add(v);
        List<Long> tokens = new java.util.ArrayList<>();
        for (long v : tok) tokens.add(v);

        return new TrendVO(times, requests, tokens);
    }

    /** 将累计 Counter 按桶拆成增量并累加（处理计数器重启归零） */
    private void addTokenDeltaByBucket(long[] buckets, String metric, Instant from, Instant to,
                                       long fromSec, long bucketSec) {
        try {
            List<MetricsAgg> list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metric, from, to);
            Double prev = null;
            for (MetricsAgg m : list) {
                double v = m.getValue() != null ? m.getValue() : 0.0;
                if (prev != null) {
                    double delta = v - prev;
                    int idx = (int) ((m.getTimestamp().getEpochSecond() - fromSec) / bucketSec);
                    if (idx >= 0 && idx < buckets.length) {
                        if (delta > 0 && delta <= v) {
                            buckets[idx] += (long) delta;            // 正常增量
                        } else if (delta < 0) {
                            buckets[idx] += (long) v;                // 重启归零：当前累计值即本桶增量
                        }
                    }
                }
                prev = v;
            }
        } catch (Exception e) {
            log.debug("[MetricsQuery] trend token delta failed for {}: {}", metric, e.getMessage());
        }
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

    /** Get the max metric value over the window - for cumulative counters.
     *  Core 重启会使 cumulative 计数器归零，故取窗口内 MAX（=真实累计总量），
     *  而非最后一行（最后一行可能只是重启后的小计）。 */
    private long getMaxMetricValue(String metricName, Instant from, Instant to) {
        try {
            var list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
            if (list.isEmpty()) return 0L;
            long max = 0L;
            for (var m : list) {
                if (m.getValue() != null) {
                    long v = m.getValue().longValue();
                    if (v > max) max = v;
                }
            }
            return max;
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 max value failed for {}: {}", metricName, e.getMessage());
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

    /**
     * 基于 H2 spans 真实统计 Agent 分层调用数（L0/L1/L2，按 agent 视角每请求计 1 次）。
     * <p>
     * 旧实现用 Redis 计数器按 OTLP 指标导出次数累加（incrementAgentRedis），因指标是累积计数器、
     * 按 scrape 周期反复导出，计数被放大 N 倍且 L1 匹配名集更宽导致 L1 反超 L0（第十五轮诊断）。
     * 改为 COUNT(DISTINCT trace_id)：operation_name 前缀 'L0:%' / 'L1%' / 'L2:%'，
     * 使每个请求（每 trace）在 L1/L2 只计 1 次，即使该层内部展开多个 LLM 子 span（L1-LLM1/L1-LLM2、
     * L2 多 step）也只算 1 个 agent —— 符合第十八轮用户决策「agent 统计依然是 1」。
     * 结果天然 L0 ≥ L1 ≥ L2（正常流每请求各 1 次；仅工作流请求才有 L2，故 L2 ≤ L1 = L0）。
     * 60s 节流缓存，避免每次 3s 轮询都做全表 COUNT。
     */
    private long[] getAgentCallCountsFromSpans() {
        long now = System.currentTimeMillis();
        if (now - lastAgentCallMs < 60_000) {
            return new long[]{cachedL0, cachedL1, cachedL2};
        }
        lastAgentCallMs = now;
        try {
            Instant from = Instant.now().minusSeconds(2 * 86400L); // 最近 2d，与 H2 保留期一致
            long l0 = spanRepository.countDistinctTraceByOpNamePrefixSince("L0:%", from);
            // L1 层有三种 span 名：L1:（ChatService 纯 CHAT 直答）、L1-LLM1:（ContextRouter）、
            // L1-LLM2:（SubGraphRouter）。用 'L1%' 匹配全部三种，再按 trace DISTINCT 去重=每请求计 1 次。
            // 切勿用 'L1:%'——那样只会匹配到极少数纯 CHAT 的 L1: span，漏掉几乎每请求都有的 L1-LLM1/L1-LLM2。
            long l1 = spanRepository.countDistinctTraceByOpNamePrefixSince("L1%", from);
            long l2 = spanRepository.countDistinctTraceByOpNamePrefixSince("L2:%", from);
            cachedL0 = l0; cachedL1 = l1; cachedL2 = l2;
            return new long[]{l0, l1, l2};
        } catch (Exception e) {
            log.debug("[MetricsQuery] agent call count from spans failed: {}", e.getMessage());
            return new long[]{cachedL0, cachedL1, cachedL2};
        }
    }

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

    /**
     * 基于 H2 的真实错误率 = 错误数 / 时间范围内操作总数（6h 窗口），结果恒为 0~1 分数。
     * 错误数优先取 spans 中 statusCode='ERROR' 的计数；若 Core 优雅处理异常未标记 ERROR 状态，
     * 则回退到权威错误计数器 llm.error.count（OTel 累计值，窗口内取 MAX），避免错误率恒为 0。
     * 与 Redis 计数器（按指标「导出次数」累加，比例严重失真，曾达 366%）彻底解耦。
     * 带 60s 缓存，避免每次 3s 轮询都做全表 COUNT。
     */
    private double getErrorRateFromSpans() {
        long now = System.currentTimeMillis();
        if (now - lastErrorRateMs < 60_000) {
            return cachedErrorRate;
        }
        lastErrorRateMs = now;
        try {
            Instant to = Instant.now();
            Instant from = to.minusSeconds(6 * 3600L);
            long total = spanRepository.countByTimeRange(from, to);
            if (total <= 0) {
                cachedErrorRate = 0.0;
                return 0.0;
            }
            long errorSpans = spanRepository.countErrorsByTimeRange(from, to);
            long llmErr = getMaxMetricValue("llm.error.count", from, to);
            long errors = Math.max(errorSpans, llmErr);
            double rate = (double) errors / total;
            if (rate < 0) rate = 0.0;
            if (rate > 1) rate = 1.0;
            cachedErrorRate = rate;
            return rate;
        } catch (Exception e) {
            log.debug("[MetricsQuery] span-based error rate failed: {}", e.getMessage());
            return cachedErrorRate;
        }
    }
}
