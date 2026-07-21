package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.repository.LogRepository;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.RedisMetricsSnapshotRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * 运维管理接口（本地可观测后端，非生产鉴权场景）
 *
 * POST /api/v1/admin/purge?days=2 — 清理 H2 老数据，仅保留最近 days 天。
 *   用于解决历史脏数据导致 L1 统计失真、H2 文件过大等问题。
 *   清理范围：spans / metrics_agg / redis_metrics_snapshot / logs（原始遥测层）。
 *   不清理 sessions / session_turns（业务会话聚合，供 DAU / 历史回访）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final SpanRepository spanRepository;
    private final MetricsAggRepository metricsAggRepository;
    private final RedisMetricsSnapshotRepository snapshotRepository;
    private final LogRepository logRepository;

    public AdminController(SpanRepository spanRepository,
                           MetricsAggRepository metricsAggRepository,
                           RedisMetricsSnapshotRepository snapshotRepository,
                           LogRepository logRepository) {
        this.spanRepository = spanRepository;
        this.metricsAggRepository = metricsAggRepository;
        this.snapshotRepository = snapshotRepository;
        this.logRepository = logRepository;
    }

    /**
     * 死 key 5 元组（与 test_dead_keys.py:DEAD_KEYS 必须一致，设计 §9.1）。
     * T30 已删除 RedisH2SyncService 中这 5 个 addDoubleGauge 调用，快照表应不再含这些 key。
     */
    private static final List<String> DEAD_KEYS = List.of(
            "accuracy:intent", "accuracy:rewrite", "reroute_rate",
            "business_completion", "conversion");

    /**
     * 只读诊断端点（Q1/T-A）：直查 redis_metrics_snapshot，返回 5 死 key 的命中行数与总量。
     *
     * <p>无副作用、不暴露业务数据；仅用于自动断言「T30 死 key 已清理（totalDead == 0）」。
     * 端点不可达时由 Python 侧优雅 SKIP，守住 0 FAIL 不变量。
     */
    @GetMapping("/diagnostics/dead-keys")
    public ApiResponse<Map<String, Object>> deadKeys() {
        List<Object[]> grouped = snapshotRepository.countDeadKeysGrouped(DEAD_KEYS);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : grouped) {
            String key = (String) row[0];
            long count = ((Number) row[1]).longValue();
            counts.put(key, count);
        }

        List<Map<String, Object>> deadKeysList = new ArrayList<>();
        long totalDead = 0L;
        for (String key : DEAD_KEYS) {
            long c = counts.getOrDefault(key, 0L);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metricKey", key);
            item.put("count", c);
            deadKeysList.add(item);
            totalDead += c;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deadKeys", deadKeysList);
        data.put("totalDead", totalDead);
        data.put("allClear", totalDead == 0L);
        log.info("[Admin] dead-keys diagnostics: totalDead={} allClear={}", totalDead, totalDead == 0L);
        return ApiResponse.ok(data);
    }

    /**
     * 清理 H2 老数据，仅保留最近 days 天。
     */
    @PostMapping("/purge")
    public ApiResponse<Map<String, Object>> purge(@RequestParam(defaultValue = "2") int days) {
        Instant cutoff = Instant.now().minusSeconds((long) days * 86400L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retainedDays", days);
        result.put("cutoff", cutoff.toString());

        // 逐表隔离清理：任一表失败不影响其余表，且不会让整个接口抛 500
        int delSpans = safeDelete("spans", () -> spanRepository.deleteByStartTimeBefore(cutoff));
        int delMetrics = safeDelete("metrics_agg", () -> metricsAggRepository.deleteByTimestampBefore(cutoff));
        int delSnap = safeDelete("redis_metrics_snapshot", () -> snapshotRepository.deleteByTimestampBefore(cutoff));
        int delLogs = safeDelete("logs", () -> logRepository.deleteByTimestampBefore(cutoff));

        result.put("deletedSpans", delSpans);
        result.put("deletedMetricsAgg", delMetrics);
        result.put("deletedSnapshots", delSnap);
        result.put("deletedLogs", delLogs);
        result.put("purged", delSpans >= 0 && delMetrics >= 0 && delSnap >= 0 && delLogs >= 0);
        log.info("[Admin] Purged H2 data older than {}d (cutoff={}) -> spans={} metrics={} snapshots={} logs={}",
                days, cutoff, delSpans, delMetrics, delSnap, delLogs);
        return ApiResponse.ok(result);
    }

    /** 单表清理：隔离异常，避免任一表失败导致整个接口 500；返回删除行数，-1 表示失败 */
    private int safeDelete(String table, IntSupplier del) {
        try {
            return del.getAsInt();
        } catch (Exception e) {
            log.error("[Admin] Purge failed for table {}: {}", table, e.getMessage(), e);
            return -1;
        }
    }
}
