package com.observability.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis  ? CRUD
 *
 * Redis Key :
 *   obs:metrics:request_count:{window}  ?String (INCR + EXPIRE)
 *   obs:metrics:error_count:{window}    ?String (INCR + EXPIRE)
 *   obs:metrics:token_{type}:{window}   ?String (INCRBY + EXPIRE)
 *   obs:metrics:latency:{window}        ?ZSET  (ZADD + EXPIRE)
 *   obs:metrics:intent_distribution     ?HASH  (HINCRBY)
 *   obs:metrics:active_sessions         ?String (SET, 30s TTL)
 *   obs:traces:recent                   ?LIST  (LPUSH + LTRIM 100)
 *   obs:logs:recent                     ?LIST  (LPUSH + LTRIM 1000)
 */
@Slf4j
@Service
public class RedisMetricsService {

    private static final String PREFIX = "obs:metrics:";
    private static final String TRACES_RECENT = "obs:traces:recent";
    private static final String LOGS_RECENT = "obs:logs:recent";

    private static final Map<String, Integer> WINDOW_TTL = Map.of(
            "1m", 120,
            "5m", 600,
            "15m", 1200,
            "6h", 21600
    );

    private final StringRedisTemplate redis;

    public RedisMetricsService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== Section ====================

    public void incrRequestCount(String window) {
        safeOp(() -> {
            String key = PREFIX + "request_count:" + window;
            redis.opsForValue().increment(key);
            redis.expire(key, WINDOW_TTL.getOrDefault(window, 120), TimeUnit.SECONDS);
        });
    }

    public long getRequestCount(String window) {
        return safeGetLong(PREFIX + "request_count:" + window);
    }

    // ==================== Section ====================

    public void incrErrorCount(String window) {
        safeOp(() -> {
            String key = PREFIX + "error_count:" + window;
            redis.opsForValue().increment(key);
            redis.expire(key, WINDOW_TTL.getOrDefault(window, 120), TimeUnit.SECONDS);
        });
    }

    public long getErrorCount(String window) {
        return safeGetLong(PREFIX + "error_count:" + window);
    }

    // ==================== Section ====================

    public void recordLatency(String window, double latencyMs) {
        safeOp(() -> {
            String key = PREFIX + "latency:" + window;
            String member = UUID.randomUUID().toString();
            redis.opsForZSet().add(key, member, latencyMs);
            redis.expire(key, WINDOW_TTL.getOrDefault(window, 120), TimeUnit.SECONDS);
            // comment
            Long size = redis.opsForZSet().zCard(key);
            if (size != null && size > 10000) {
                redis.opsForZSet().removeRange(key, 0, size - 10000 - 1);
            }
        });
    }

    /** ?*/
    public List<Double> getLatencyValues(String window) {
        try {
            String key = PREFIX + "latency:" + window;
            var values = redis.opsForZSet().rangeByScore(key, 0, Double.MAX_VALUE);
            if (values == null || values.isEmpty()) return List.of();
            // ZSET members are UUIDs, we need scores
            var tuples = redis.opsForZSet().rangeWithScores(key, 0, -1);
            if (tuples == null) return List.of();
            return tuples.stream().map(t -> t.getScore()).toList();
        } catch (Exception e) {
            log.warn("[RedisMetrics] Failed to get latency values: {}", e.getMessage());
            return List.of();
        }
    }

    /**  P50/P95/ */
    /**
     * 系统时延 P50/P95 — 优先返回 OtlpParserService 从直方图桶估算并写入 Hash 的分位数（准确）；
     * 若热层未命中，则回退到 ZSET 中时延样本的分位数（旧逻辑兜底）。
     */
    public Map<String, Double> getLatencyStats(String window) {
        try {
            String key = PREFIX + "latency:stats:" + window;
            var hash = redis.opsForHash().entries(key);
            if (hash != null && hash.containsKey("p95")) {
                double p50 = toDouble(hash.get("p50"));
                double p95 = toDouble(hash.get("p95"));
                double avg = hash.containsKey("avg") ? toDouble(hash.get("avg")) : ((p50 + p95) / 2.0);
                return Map.of("avg", avg, "p50", p50, "p95", p95);
            }
        } catch (Exception e) {
            log.debug("[RedisMetrics] Failed to read latency percentiles for {}: {}", window, e.getMessage());
        }
        // 兜底：ZSET 时延样本
        List<Double> values = getLatencyValues(window);
        if (values.isEmpty()) {
            return Map.of("avg", 0.0, "p50", 0.0, "p95", 0.0);
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        double avg = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double p50 = percentile(sorted, 0.50);
        double p95 = percentile(sorted, 0.95);

        return Map.of("avg", avg, "p50", p50, "p95", p95);
    }

    /** 写入从直方图桶估算的时延分位数（OtlpParserService 调用） */
    public void setLatencyPercentiles(String window, double p50, double p95, double avg) {
        safeOp(() -> {
            String key = PREFIX + "latency:stats:" + window;
            redis.opsForHash().put(key, "p50", p50);
            redis.opsForHash().put(key, "p95", p95);
            redis.opsForHash().put(key, "avg", avg);
            redis.expire(key, WINDOW_TTL.getOrDefault(window, 120), TimeUnit.SECONDS);
        });
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ==================== Section ====================

    /**
     *  Token  OTel Cumulative Counter?     *  {@link #setTokenCount} + delta 
     */
    public void incrTokenCount(String window, String type, long value) {
        safeOp(() -> {
            String key = PREFIX + "token_" + type + ":" + window;
            redis.opsForValue().increment(key, value);
            redis.expire(key, WINDOW_TTL.getOrDefault(window, 120), TimeUnit.SECONDS);
        });
    }

    /**
     * OTel Cumulative Counter : delta 
     *
     * : OTel Counter ?monotonic sum) INCRBY ?     * ???  delta?     *
     * @param window       ("1m"/"5m"/"15m")
     * @param type        token  ("input"/"output")
     * @param cumulative  ?( OTel Sum DataPoint)
     * @param metricName   delta  key?     */
    public void incrTokenCountDelta(String window, String type, long cumulative, String metricName) {
        safeOp(() -> {
            String counterKey = PREFIX + "token_" + type + ":" + window;
            String lastKey = PREFIX + "token_" + type + ":" + window + ":last:" + metricName;

            String lastStr = redis.opsForValue().get(lastKey);
            long last = lastStr != null ? Long.parseLong(lastStr) : 0L;

            long delta = cumulative - last;
            if (delta > 0 && delta <= cumulative) { // delta is valid: positive and not exceeding cumulative
                redis.opsForValue().increment(counterKey, delta);
            } else if (delta < 0 || delta > cumulative) {
                // cumulative reset (process restart) -> use current value as delta
                    redis.opsForValue().increment(counterKey, cumulative);
            }
            // delta == 0 -> refresh TTL to keep counter alive
            if (delta == 0 && redis.opsForValue().get(counterKey) != null) {
                redis.expire(counterKey, WINDOW_TTL.getOrDefault(window, 120), TimeUnit.SECONDS);
            }
            redis.opsForValue().set(lastKey, String.valueOf(cumulative));
            redis.expire(counterKey, WINDOW_TTL.getOrDefault(window, 120), TimeUnit.SECONDS);
            redis.expire(lastKey, WINDOW_TTL.getOrDefault(window, 120) * 2, TimeUnit.SECONDS);
        });
    }

    public long getTokenCount(String window, String type) {
        return safeGetLong(PREFIX + "token_" + type + ":" + window);
    }

    // ==================== Section ====================

    public void updateIntentDistribution(String intent) {
        safeOp(() -> {
            redis.opsForHash().increment(PREFIX + "intent_distribution", intent, 1);
            redis.expire(PREFIX + "intent_distribution", 24, TimeUnit.HOURS);
        });
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> getIntentDistribution() {
        try {
            var entries = redis.opsForHash().entries(PREFIX + "intent_distribution");
            Map<String, Long> result = new LinkedHashMap<>();
            for (var entry : entries.entrySet()) {
                String intent = String.valueOf(entry.getKey());
                Long count = Long.parseLong(String.valueOf(entry.getValue()));
                result.put(intent, count);
            }
            return result;
        } catch (Exception e) {
            log.warn("[RedisMetrics] Failed to get intent distribution: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== Section ====================

    public void setActiveSessions(long count) {
        safeOp(() -> {
            redis.opsForValue().set(PREFIX + "active_sessions", String.valueOf(count), 30, TimeUnit.SECONDS);
        });
    }

    public long getActiveSessions() {
        return safeGetLong(PREFIX + "active_sessions");
    }

    // ==================== Section ====================

    public void pushRecentTrace(String traceJson) {
        safeOp(() -> {
            redis.opsForList().leftPush(TRACES_RECENT, traceJson);
            redis.opsForList().trim(TRACES_RECENT, 0, 99);
        });
    }

    public List<String> getRecentTraces() {
        try {
            return redis.opsForList().range(TRACES_RECENT, 0, 99);
        } catch (Exception e) {
            return List.of();
        }
    }

    public void pushRecentLog(String logJson) {
        safeOp(() -> {
            redis.opsForList().leftPush(LOGS_RECENT, logJson);
            redis.opsForList().trim(LOGS_RECENT, 0, 999);
        });
    }

    public List<String> getRecentLogs() {
        try {
            return redis.opsForList().range(LOGS_RECENT, 0, 999);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== Section ====================

    private long safeGetLong(String key) {
        try {
            String val = redis.opsForValue().get(key);
            return val != null ? Long.parseLong(val) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Double safeGetDouble(String key) {
        try {
            String val = redis.opsForValue().get(key);
            return val != null ? Double.parseDouble(val) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void safeOp(Runnable op) {
        try {
            op.run();
        } catch (Exception e) {
            log.warn("[RedisMetrics] Redis operation failed: {}", e.getMessage());
        }
    }

    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    // ==================== Section ====================

    /**  */
    public long getDau() {
        return safeGetLong(PREFIX + "dau");
    }

    public void setDau(long count) {
        safeOp(() -> {
            redis.opsForValue().set(PREFIX + "dau", String.valueOf(count), 24, TimeUnit.HOURS);
        });
    }

    /** ?*/
    public long getOnlineUsers() {
        return safeGetLong(PREFIX + "online_users");
    }

    public void setOnlineUsers(long count) {
        safeOp(() -> {
            redis.opsForValue().set(PREFIX + "online_users", String.valueOf(count), 300, TimeUnit.SECONDS);
        });
    }

    /**
     * 记录当日活跃用户（按 userId 去重），并刷新 DAU 计数。
     * 幂等：同一 userId 同一天重复调用不会重复计数（底层用 Redis SET）。
     */
    public void recordDauUser(String userId) {
        if (userId == null || userId.isBlank()) return;
        safeOp(() -> {
            String dateKey = java.time.LocalDate.now().toString();
            String setKey = PREFIX + "dau_users:" + dateKey;
            redis.opsForSet().add(setKey, userId);
            redis.expire(setKey, 24, TimeUnit.HOURS);
            Long size = redis.opsForSet().size(setKey);
            if (size != null) setDau(size);
        });
    }

    /**
     * 记录实时在线用户（按 userId，5 分钟滑动窗口），并刷新在线人数。
     * 每次会话交互调用一次：用 ZSET 以 userId 为 member、当前秒级时间戳为 score，
     * 读取时裁剪掉 5 分钟前的成员后统计基数，即为"近 5 分钟活跃用户数"。
     */
    public void recordOnlineUser(String userId) {
        if (userId == null || userId.isBlank()) return;
        safeOp(() -> {
            String zsetKey = PREFIX + "online_users_active";
            long nowSec = System.currentTimeMillis() / 1000;
            redis.opsForZSet().add(zsetKey, userId, nowSec);
            redis.expire(zsetKey, 360, TimeUnit.SECONDS);
            redis.opsForZSet().removeRangeByScore(zsetKey, 0, nowSec - 300);
            Long count = redis.opsForZSet().zCard(zsetKey);
            if (count != null) setOnlineUsers(count);
        });
    }

    /** Agent  (L0/L1/L2) */
    public long getAgentCallCount(String level) {
        return safeGetLong(PREFIX + "agent_call:" + level);
    }

    public void incrAgentCall(String level) {
        safeOp(() -> {
            String key = PREFIX + "agent_call:" + level;
            redis.opsForValue().increment(key);
            // 6h TTL: agent call counters are long-lived, dashboard polls them frequently
            redis.expire(key, 21600, TimeUnit.SECONDS);
        });
    }

    /** SET agent call count directly (used for H2 fallback, not INCR) */
    public void setAgentCallCount(String level, long count) {
        safeOp(() -> {
            String key = PREFIX + "agent_call:" + level;
            redis.opsForValue().set(key, String.valueOf(count), 120, TimeUnit.SECONDS);
        });
    }

    // ==================== Section ====================

    /**  TTFT ?*/
    public void recordTTFT(long ttftMs) {
        safeOp(() -> {
            String key = PREFIX + "ttft:1m";
            String member = UUID.randomUUID().toString();
            redis.opsForZSet().add(key, member, ttftMs);
            redis.expire(key, 120, TimeUnit.SECONDS);
            // comment
            Long size = redis.opsForZSet().zCard(key);
            if (size != null && size > 10000) {
                redis.opsForZSet().removeRange(key, 0, size - 10000 - 1);
            }
        });
    }

    /**  TTFT  (P50/P95/P99) */
    /**
     * TTFT 首 Token 时延 (P50/P95/P99) — 优先返回 OtlpParserService 从直方图桶估算并写入 Hash 的分位数（准确）；
     * 若热层未命中，则回退到 ZSET 中 TTFT 样本的分位数（旧逻辑兜底）。
     */
    public Map<String, Long> getTTFTStats(String window) {
        try {
            String key = PREFIX + "ttft:stats:" + window;
            var hash = redis.opsForHash().entries(key);
            if (hash != null && hash.containsKey("p99")) {
                return Map.of(
                        "p50", Math.round(toDouble(hash.get("p50"))),
                        "p95", Math.round(toDouble(hash.get("p95"))),
                        "p99", Math.round(toDouble(hash.get("p99")))
                );
            }
        } catch (Exception e) {
            log.debug("[RedisMetrics] Failed to read TTFT percentiles for {}: {}", window, e.getMessage());
        }
        // 兜底：ZSET TTFT 样本
        try {
            String key = PREFIX + "ttft:" + window;
            var tuples = redis.opsForZSet().rangeWithScores(key, 0, -1);
            if (tuples == null || tuples.isEmpty()) {
                return Map.of("p50", 0L, "p95", 0L, "p99", 0L);
            }
            List<Double> values = tuples.stream().map(t -> t.getScore()).sorted().toList();
            return Map.of(
                    "p50", Math.round(percentile(new ArrayList<>(values), 0.50)),
                    "p95", Math.round(percentile(new ArrayList<>(values), 0.95)),
                    "p99", Math.round(percentile(new ArrayList<>(values), 0.99))
            );
        } catch (Exception e) {
            log.warn("[RedisMetrics] Failed to get TTFT stats: {}", e.getMessage());
            return Map.of("p50", 0L, "p95", 0L, "p99", 0L);
        }
    }

    /** 写入从直方图桶估算的 TTFT 分位数（OtlpParserService 调用，仅 1m 实时窗口） */
    public void setTTFTPercentiles(double p50, double p95, double p99) {
        safeOp(() -> {
            String key = PREFIX + "ttft:stats:1m";
            redis.opsForHash().put(key, "p50", p50);
            redis.opsForHash().put(key, "p95", p95);
            redis.opsForHash().put(key, "p99", p99);
            redis.expire(key, 120, TimeUnit.SECONDS);
        });
    }

    /**  */
    public void incrErrorRate() {
        safeOp(() -> {
            String key = PREFIX + "error_rate:1m";
            redis.opsForValue().increment(key);
            redis.expire(key, 120, TimeUnit.SECONDS);
        });
    }

    // ==================== Section ====================

    /** ?*/
    public Double getIntentAccuracy() {
        return safeGetDouble(PREFIX + "accuracy:intent");
    }

    public void setIntentAccuracy(double accuracy) {
        safeOp(() -> {
            redis.opsForValue().set(PREFIX + "accuracy:intent", String.valueOf(accuracy),
                    120, TimeUnit.SECONDS);
        });
    }

    /** ?*/
    public Double getRewriteAccuracy() {
        return safeGetDouble(PREFIX + "accuracy:rewrite");
    }

    public void setRewriteAccuracy(double accuracy) {
        safeOp(() -> {
            redis.opsForValue().set(PREFIX + "accuracy:rewrite", String.valueOf(accuracy),
                    120, TimeUnit.SECONDS);
        });
    }

    /** Reroute ?*/
    public Double getRerouteRate() {
        return safeGetDouble(PREFIX + "reroute_rate");
    }

    public void incrRerouteCount() {
        safeOp(() -> {
            String key = PREFIX + "reroute_count:1m";
            redis.opsForValue().increment(key);
            redis.expire(key, 120, TimeUnit.SECONDS);
        });
    }

    /** ?*/
    public Double getBusinessCompletionRate() {
        return safeGetDouble(PREFIX + "business_completion");
    }

    public void setBusinessCompletionRate(double rate) {
        safeOp(() -> {
            redis.opsForValue().set(PREFIX + "business_completion", String.valueOf(rate),
                    120, TimeUnit.SECONDS);
        });
    }

    // ==================== Section ====================

    /** ?*/
    public Double getConversionRate() {
        return safeGetDouble(PREFIX + "conversion");
    }

    public void setConversionRate(double rate) {
        safeOp(() -> {
            redis.opsForValue().set(PREFIX + "conversion", String.valueOf(rate),
                    120, TimeUnit.SECONDS);
        });
    }

    /**  */
    public long getTotalVisits() {
        return safeGetLong(PREFIX + "total_visits");
    }

    public void incrTotalVisits() {
        safeOp(() -> {
            redis.opsForValue().increment(PREFIX + "total_visits");
            redis.expire(PREFIX + "total_visits", 24, TimeUnit.HOURS);
        });
    }
}
