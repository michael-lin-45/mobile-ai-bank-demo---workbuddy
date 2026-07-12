package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.repository.LogRepository;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.RedisMetricsSnapshotRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
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
