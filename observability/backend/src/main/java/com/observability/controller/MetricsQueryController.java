package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.dto.RealtimeMetricsVO;
import com.observability.dto.TrendVO;
import com.observability.model.MetricsAgg;
import com.observability.service.MetricsQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 指标查询控制器
 *
 * GET /api/v1/metrics/realtime — 实时指标（Redis）
 * GET /api/v1/metrics/history — 历史指标（H2）
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsQueryController {

    private final MetricsQueryService metricsQueryService;

    public MetricsQueryController(MetricsQueryService metricsQueryService) {
        this.metricsQueryService = metricsQueryService;
    }

    /**
     * 获取实时指标（Redis 热层，前端 3s 轮询）
     */
    @GetMapping("/realtime")
    public ApiResponse<RealtimeMetricsVO> getRealtime() {
        try {
            RealtimeMetricsVO vo = metricsQueryService.getRealtime();
            return ApiResponse.ok(vo);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch realtime metrics: " + e.getMessage());
        }
    }

    /**
     * 获取历史指标（H2 温层）
     *
     * @param from 开始时间 ISO 8601
     * @param to   结束时间 ISO 8601
     * @param step 聚合窗口（1m/5m/15m/1h）
     */
    @GetMapping("/history")
    public ApiResponse<List<MetricsAgg>> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "5m") String step) {
        try {
            List<MetricsAgg> history = metricsQueryService.getHistory(from, to, step);
            return ApiResponse.ok(history);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch metrics history: " + e.getMessage());
        }
    }

    /**
     * 请求 & Token 趋势（最近 hours 小时，按 30 分钟分桶）
     */
    @GetMapping("/trend")
    public ApiResponse<TrendVO> getTrend(@RequestParam(defaultValue = "6") int hours) {
        try {
            return ApiResponse.ok(metricsQueryService.getTrend(hours));
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch trend: " + e.getMessage());
        }
    }
}
