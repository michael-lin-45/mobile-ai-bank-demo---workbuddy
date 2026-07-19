package com.observability.service;

import com.observability.model.RedisMetricsSnapshot;
import com.observability.repository.RedisMetricsSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Periodic Redis -> H2 sync service.
 *
 * Snapshots all Redis counter/gauge metrics into the redis_metrics_snapshot H2 table
 * every 30 seconds, so that the frontend can fall back to H2 when Redis is empty
 * (e.g. during debugging / backend restarts).
 *
 * Sync interval: 30s
 *  - Short enough to keep dashboard data fresh during Redis outages
 *  - Long enough to avoid excessive H2 writes (each sync writes ~25 rows)
 *  - At 30s interval, 7-day retention = ~600K rows (well within H2 capacity)
 */
@Slf4j
@Service
public class RedisH2SyncService {

    private final RedisMetricsService redisMetrics;
    private final RedisMetricsSnapshotRepository snapshotRepository;

    public RedisH2SyncService(RedisMetricsService redisMetrics,
                               RedisMetricsSnapshotRepository snapshotRepository) {
        this.redisMetrics = redisMetrics;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * Sync Redis metrics to H2 every 30 seconds.
     * fixedDelay = 30s (waits for previous run to complete before starting next).
     * initialDelay = 15s (give the app time to fully start up).
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void syncRedisToH2() {
        try {
            Instant now = Instant.now();
            List<RedisMetricsSnapshot> snapshots = new ArrayList<>();

            // === Counter metrics (all windows) ===
            for (String window : new String[]{"1m", "5m", "15m", "6h"}) {
                snapshots.add(new RedisMetricsSnapshot(
                        "request_count:" + window, "counter",
                        (double) redisMetrics.getRequestCount(window), null, now));
                snapshots.add(new RedisMetricsSnapshot(
                        "error_count:" + window, "counter",
                        (double) redisMetrics.getErrorCount(window), null, now));
                snapshots.add(new RedisMetricsSnapshot(
                        "token_input:" + window, "counter",
                        (double) redisMetrics.getTokenCount(window, "input"), null, now));
                snapshots.add(new RedisMetricsSnapshot(
                        "token_output:" + window, "counter",
                        (double) redisMetrics.getTokenCount(window, "output"), null, now));
            }

            // === Latency stats (derived from ZSET) ===
            for (String window : new String[]{"1m", "5m", "15m"}) {
                Map<String, Double> latencyStats = redisMetrics.getLatencyStats(window);
                snapshots.add(new RedisMetricsSnapshot(
                        "latency_avg:" + window, "gauge",
                        latencyStats.getOrDefault("avg", 0.0), null, now));
                snapshots.add(new RedisMetricsSnapshot(
                        "latency_p50:" + window, "gauge",
                        latencyStats.getOrDefault("p50", 0.0), null, now));
                snapshots.add(new RedisMetricsSnapshot(
                        "latency_p95:" + window, "gauge",
                        latencyStats.getOrDefault("p95", 0.0), null, now));
            }

            // === TTFT stats (derived from ZSET) ===
            Map<String, Long> ttftStats = redisMetrics.getTTFTStats("1m");
            snapshots.add(new RedisMetricsSnapshot(
                    "ttft_p50:1m", "gauge",
                    (double) ttftStats.getOrDefault("p50", 0L), null, now));
            snapshots.add(new RedisMetricsSnapshot(
                    "ttft_p95:1m", "gauge",
                    (double) ttftStats.getOrDefault("p95", 0L), null, now));
            snapshots.add(new RedisMetricsSnapshot(
                    "ttft_p99:1m", "gauge",
                    (double) ttftStats.getOrDefault("p99", 0L), null, now));

            // === Agent call counts ===
            for (String level : new String[]{"L0", "L1", "L2"}) {
                snapshots.add(new RedisMetricsSnapshot(
                        "agent_call:" + level, "counter",
                        (double) redisMetrics.getAgentCallCount(level), null, now));
            }

            // === Intent distribution (HASH) ===
            Map<String, Long> intentDist = redisMetrics.getIntentDistribution();
            for (var entry : intentDist.entrySet()) {
                snapshots.add(new RedisMetricsSnapshot(
                        "intent_distribution:" + entry.getKey(), "counter",
                        (double) entry.getValue(), null, now));
            }

            // === Gauge/rate metrics ===
            addGaugeIfNotNull(snapshots, "active_sessions", redisMetrics.getActiveSessions(), now);
            addGaugeIfNotNull(snapshots, "dau", redisMetrics.getDau(), now);
            addGaugeIfNotNull(snapshots, "online_users", redisMetrics.getOnlineUsers(), now);
            addGaugeIfNotNull(snapshots, "total_visits", redisMetrics.getTotalVisits(), now);

            // T-E (T30): 删除 5 个恒为 null 的死 key 同步（accuracy:intent / accuracy:rewrite /
            // reroute_rate / business_completion / conversion）。主指标已改 H2 读时算，删除无副作用。

            // Batch save all snapshots
            if (!snapshots.isEmpty()) {
                snapshotRepository.saveAll(snapshots);
                log.debug("[RedisH2Sync] Synced {} metric snapshots to H2", snapshots.size());
            }
        } catch (Exception e) {
            log.warn("[RedisH2Sync] Failed to sync Redis metrics to H2: {}", e.getMessage());
        }
    }

    private void addGaugeIfNotNull(List<RedisMetricsSnapshot> list, String key, long value, Instant now) {
        list.add(new RedisMetricsSnapshot(key, "gauge", (double) value, null, now));
    }
}
