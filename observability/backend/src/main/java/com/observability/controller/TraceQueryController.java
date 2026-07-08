package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.dto.TraceDetailVO;
import com.observability.dto.TraceListVO;
import com.observability.service.TraceQueryService;
import com.observability.repository.SpanRepository;
import com.observability.model.SpanEntity;
import org.springframework.data.domain.PageRequest;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Trace 查询控制器
 *
 * GET /api/v1/traces          — Trace 列表
 * GET /api/v1/traces/{traceId} — Trace 详情（含 Span 树）
 */
@RestController
@RequestMapping("/api/v1/traces")
public class TraceQueryController {

    private final TraceQueryService traceQueryService;
    private final SpanRepository spanRepository;

    public TraceQueryController(TraceQueryService traceQueryService, SpanRepository spanRepository) {
        this.traceQueryService = traceQueryService;
        this.spanRepository = spanRepository;
    }

    /**
     * 查询 Trace 列表
     *
     * @param from       开始时间 ISO 8601（可选，默认最近 1 小时）
     * @param intent     意图筛选（可选）
     * @param sessionId  会话ID筛选（可选）
     * @param userId     用户ID筛选（可选）
     * @param statusCode 状态码筛选（可选，OK/ERROR）
     * @param limit      返回数量（默认 50）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listTraces(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        try {
            Map<String, Object> result = traceQueryService.listTracesPaginated(
                    from, intent, sessionId, userId, statusCode, limit, page, size);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to list traces: " + e.getMessage());
        }
    }

    /**
     * 获取 Trace 详情（含 Span 层级树）
     */
    @GetMapping("/{traceId}")
    public ApiResponse<TraceDetailVO> getTraceDetail(@PathVariable String traceId) {
        try {
            TraceDetailVO detail = traceQueryService.getTraceDetail(traceId);
            return ApiResponse.ok(detail);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get trace detail: " + e.getMessage());
        }
    }

    // === DEBUG: Span statistics ===
    @GetMapping("/debug/span-stats")
    public ApiResponse<Map<String, Object>> debugSpanStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            long total = spanRepository.count();
            result.put("totalSpans", total);

            // Root spans
            var rootSpans = spanRepository.findRootSpansByTimeRange(
                Instant.now().minusSeconds(7 * 86400), PageRequest.of(0, 100));
            result.put("rootSpansIn7d", rootSpans.getTotalElements());
            result.put("rootSpansFetched", rootSpans.getContent().size());

            // Show first 20 root spans with details
            List<Map<String, Object>> samples = new ArrayList<>();
            for (var s : rootSpans.getContent()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("traceId", s.getTraceId());
                m.put("spanId", s.getSpanId());
                m.put("opName", s.getOperationName());
                m.put("kind", s.getKind());
                m.put("startTime", s.getStartTime() != null ? s.getStartTime().toString() : null);
                m.put("parentSpanId", s.getParentSpanId());
                m.put("statusCode", s.getStatusCode());
                m.put("serviceName", s.getServiceName());
                m.put("attributes", s.getAttributes());
                samples.add(m);
            }
            result.put("rootSpanSamples", samples);

            // Operation name distribution
            var opNames = spanRepository.findDistinctOperationNames();
            result.put("distinctOpNames", opNames);

        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("stackTrace", Arrays.toString(e.getStackTrace()));
        }
        return ApiResponse.ok(result);
    }

}
