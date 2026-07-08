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
 * OTLP 接收控制器 — 接收来自 OTel Collector / Logback Appender 的 OTLP JSON 数据
 *
 * POST /api/v1/metrics — OTel Collector 上报 Metrics
 * POST /api/v1/traces  — OTel Collector 上报 Traces
 * POST /api/v1/logs    — Logback OTel Appender 直发 Logs
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class OTLPReceiverController {

    private final OtlpParserService parserService;

    public OTLPReceiverController(OtlpParserService parserService) {
        this.parserService = parserService;
    }

    /**
     * 接收 OTLP Metrics JSON
     */
    @PostMapping(value = "/metrics", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> receiveMetrics(@RequestBody OtlpMetricPayload payload) {
        log.info("[OTLP] Received metrics payload");
        try {
            int parsed = parserService.parseMetrics(payload);
            return ApiResponse.ok(Map.of("parsed", parsed));
        } catch (Exception e) {
            log.error("[OTLP] Failed to parse metrics", e);
            return ApiResponse.error(500, "Failed to parse metrics: " + e.getMessage());
        }
    }

    /**
     * 接收 OTLP Traces JSON
     */
    @PostMapping(value = "/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> receiveTraces(@RequestBody OtlpTracePayload payload) {
        log.info("[OTLP] Received traces payload");
        try {
            int parsed = parserService.parseTraces(payload);
            return ApiResponse.ok(Map.of("parsed", parsed));
        } catch (Exception e) {
            log.error("[OTLP] Failed to parse traces", e);
            return ApiResponse.error(500, "Failed to parse traces: " + e.getMessage());
        }
    }

    /**
     * 接收 OTLP Logs JSON（Logback Appender 直发）
     */
    @PostMapping(value = "/logs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> receiveLogs(@RequestBody OtlpLogPayload payload) {
        log.info("[OTLP] Received logs payload");
        try {
            int parsed = parserService.parseLogs(payload);
            return ApiResponse.ok(Map.of("parsed", parsed));
        } catch (Exception e) {
            log.error("[OTLP] Failed to parse logs", e);
            return ApiResponse.error(500, "Failed to parse logs: " + e.getMessage());
        }
    }
}
