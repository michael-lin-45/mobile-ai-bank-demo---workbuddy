package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.dto.OtlpLogPayload;
import com.observability.dto.OtlpMetricPayload;
import com.observability.dto.OtlpTracePayload;
import com.observability.service.OtlpParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * OTLP 备用接收控制器 — 映射 /v1/* 路径（OTel Collector 标准路径，不含 /api 前缀）
 *
 * POST /v1/metrics — OTel Collector 上报 Metrics
 * POST /v1/traces  — OTel Collector 上报 Traces
 * POST /v1/logs    — OTel Collector 上报 Logs
 */
@Slf4j
@RestController
@RequestMapping("/v1")
public class OtlpV1Receiver {

    private final OtlpParserService parserService;

    public OtlpV1Receiver(OtlpParserService parserService) {
        this.parserService = parserService;
    }

    @PostMapping(value = "/metrics", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> receiveMetrics(@RequestBody OtlpMetricPayload payload) {
        log.debug("[OTLP/v1] Received metrics");
        try { var rms = payload.getResourceMetrics(); if (rms != null && !rms.isEmpty()) { var metrics = rms.get(0).getScopeMetrics().get(0).getMetrics(); if (metrics != null) { for (var mi = 0; mi < Math.min(metrics.size(), 5); mi++) { var m = metrics.get(mi); if (m.getSum() != null && !m.getSum().getDataPoints().isEmpty()) { var dp = m.getSum().getDataPoints().get(0); log.info("[SUM-DEBUG] metric={} valueAsDouble={}", m.getName(), dp.getValueAsDouble()); } if (m.getHistogram() != null && !m.getHistogram().getDataPoints().isEmpty()) { var dp = m.getHistogram().getDataPoints().get(0); log.info("[HIST-DEBUG] metric={} count={} sum={}", m.getName(), dp.getCount(), dp.getSum()); } } } } } catch (Exception e) { log.warn("[DEBUG] failed: {}", e.getMessage()); }
        int parsed = parserService.parseMetrics(payload);
        return ApiResponse.ok(Map.of("parsed", parsed));
    }

    @PostMapping(value = "/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> receiveTraces(@RequestBody OtlpTracePayload payload) {
        log.debug("[OTLP/v1] Received traces");
        int parsed = parserService.parseTraces(payload);
        return ApiResponse.ok(Map.of("parsed", parsed));
    }

    @PostMapping(value = "/logs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> receiveLogs(@RequestBody OtlpLogPayload payload) {
        log.debug("[OTLP/v1] Received logs");
        int parsed = parserService.parseLogs(payload);
        return ApiResponse.ok(Map.of("parsed", parsed));
    }
}
