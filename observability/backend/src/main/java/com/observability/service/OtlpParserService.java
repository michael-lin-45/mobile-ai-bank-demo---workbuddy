package com.observability.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.dto.ApiResponse;
import com.observability.dto.OtlpLogPayload;
import com.observability.dto.OtlpMetricPayload;
import com.observability.dto.OtlpTracePayload;
import com.observability.model.LogEntity;
import com.observability.model.MetricsAgg;
import com.observability.model.SpanEntity;
import com.observability.repository.LogRepository;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * OTLP JSON 解析�?�?解析 OTLP HTTP JSON �?分发�?Redis（热层）�?H2（温层）
 *
 * 职责:
 * - Metrics: 提取 histogram/sum/gauge dataPoints �?Redis 计数�?+ H2 INSERT
 * - Traces:  提取 spans �?H2 INSERT + Redis LPUSH recent trace
 * - Logs:    提取 logRecords �?H2 INSERT + Redis LPUSH recent log
 */
@Slf4j
@Service
public class OtlpParserService {

    private final RedisMetricsService redisMetricsService;
    private final MetricsAggRepository metricsAggRepository;
    private final SpanRepository spanRepository;
    private final LogRepository logRepository;
    private final ObjectMapper objectMapper;

    public OtlpParserService(RedisMetricsService redisMetricsService,
                             MetricsAggRepository metricsAggRepository,
                             SpanRepository spanRepository,
                             LogRepository logRepository,
                             ObjectMapper objectMapper) {
        this.redisMetricsService = redisMetricsService;
        this.metricsAggRepository = metricsAggRepository;
        this.spanRepository = spanRepository;
        this.logRepository = logRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== Metrics 解析 ====================

    /**
     * 解析 OTLP Metrics JSON �?Redis 热层 + H2 温层
     *
     * @return 成功解析�?dataPoint 数量
     */
    public int parseMetrics(OtlpMetricPayload payload) {
        if (payload == null || payload.getResourceMetrics() == null) {
            log.warn("[OtlpParser] Empty metrics payload");
            return 0;
        }

        int parsed = 0;
        String serviceName = payload.extractServiceName();
        Instant now = Instant.now();

        for (var resourceMetric : payload.getResourceMetrics()) {
            if (resourceMetric.getScopeMetrics() == null) continue;

            for (var scopeMetric : resourceMetric.getScopeMetrics()) {
                if (scopeMetric.getMetrics() == null) continue;

                for (var metric : scopeMetric.getMetrics()) {
                    String metricName = metric.getName();
                    String tags = metric.extractTags();

                    // ── Histogram (延迟类指�? ──
                    if (metric.getHistogram() != null && metric.getHistogram().getDataPoints() != null) {
                        for (var dp : metric.getHistogram().getDataPoints()) {
                            try {
                                String dpTags = extractDataPointTags(dp.getAttributes());
                                double count = parseCount(dp.getCount());
                                double avg = count > 0 ? dp.getSum() / count : 0;

                                // Redis 热层
                                if (isLatencyMetric(metricName)) {
                                    redisMetricsService.recordLatency("1m", avg);
                                    redisMetricsService.recordLatency("5m", avg);
                                    redisMetricsService.recordLatency("15m", avg);
                                }
                                // TTFT: �?Token 延迟指标 �?写入独立 ZSET
                                if (metricName.contains("first_token") || metricName.contains("first.token") || metricName.contains("ttft")) {
                                    redisMetricsService.recordTTFT((long) avg);
                                }
                                // H2 温层
                                saveMetric(metricName, dpTags, avg, "1m", now);
                                saveMetric(metricName, dpTags, dp.getSum(), "1m", now);
                                parsed++;
                            } catch (Exception e) {
                                log.warn("[OtlpParser] Failed to process histogram dp for metric={}: {}",
                                        metricName, e.getMessage());
                            }
                        }
                    }

                    // ── Sum (Counter 类指�? ──
                    if (metric.getSum() != null && metric.getSum().getDataPoints() != null) {
                        for (var dp : metric.getSum().getDataPoints()) {
                            try {
                                String dpTags = extractDataPointTags(dp.getAttributes());
                                double value = dp.getValueAsDouble();

                                if (metricName.contains("request") || metricName.contains("intent.recognized")) {
                                    redisMetricsService.incrRequestCount("1m");
                                    redisMetricsService.incrRequestCount("5m");
                                    redisMetricsService.incrRequestCount("15m");
                                    redisMetricsService.incrRequestCount("6h");
                                }
                                if (metricName.contains("error")) {
                                    redisMetricsService.incrErrorCount("1m");
                                    redisMetricsService.incrErrorCount("5m");
                                    redisMetricsService.incrErrorCount("15m");
                                    redisMetricsService.incrErrorCount("6h");
                                }
                                // 意图/路由/领域�?metric: 提取 tags 中的 domain/intent
                                if (dpTags != null && (metricName.contains("intent") || metricName.contains("router") || metricName.contains("domain"))) {
                                    extractAndUpdateIntent(dpTags);
                                }
                                // Token 计数：只认 llm.token.* 或 agent.token.* 开头的标准指标，
                                // 排除 agent.rewrite.*.token 等非 LLM 相关的 token 指标
                                if (metricName.startsWith("llm.token.") || metricName.startsWith("agent.token.")) {
                                    String tokenType = metricName.contains("input") ? "input" : 
                                                      (metricName.contains("output") ? "output" : "unknown");
                                    if (!"unknown".equals(tokenType)) {
                                        redisMetricsService.incrTokenCountDelta("1m", tokenType, (long) value, metricName);
                                        redisMetricsService.incrTokenCountDelta("5m", tokenType, (long) value, metricName);
                                    }
                                }
                                // ── agent.* �?Redis 实时�?──
                                if (metricName.startsWith("agent.")) {
                                    incrementAgentRedis(metricName, dpTags, (long) value);
                                }
                                if (metricName.startsWith("llm.")) {
                                    incrementAgentRedis(metricName, dpTags, (long) value);
                                }

                                saveMetric(metricName, dpTags, value, "1m", now);
                                parsed++;
                            } catch (Exception e) {
                                log.warn("[OtlpParser] Failed to process sum dp for metric={}: {}",
                                        metricName, e.getMessage());
                            }
                        }
                    }

                    // ── Gauge (当前值类指标) ──
                    if (metric.getGauge() != null && metric.getGauge().getDataPoints() != null) {
                        for (var dp : metric.getGauge().getDataPoints()) {
                            try {
                                String dpTags = extractDataPointTags(dp.getAttributes());
                                double value = dp.getValueAsDouble();

                                if (metricName.contains("session") && metricName.contains("active")) {
                                    redisMetricsService.setActiveSessions((long) value);
                                }

                                saveMetric(metricName, dpTags, value, "1m", now);
                                parsed++;
                            } catch (Exception e) {
                                log.warn("[OtlpParser] Failed to process gauge dp for metric={}: {}",
                                        metricName, e.getMessage());
                            }
                        }
                    }
                }
            }
        }

        log.info("[OtlpParser] Parsed {} metric dataPoints, service={}", parsed, serviceName);
        return parsed;
    }

    // ==================== Traces 解析 ====================

    /**
     * 解析 OTLP Traces JSON �?H2 温层 + Redis recent list
     *
     * @return 成功解析�?span 数量
     */
    public int parseTraces(OtlpTracePayload payload) {
        if (payload == null || payload.getResourceSpans() == null) {
            log.warn("[OtlpParser] Empty traces payload");
            return 0;
        }

        int parsed = 0;
        String serviceName = payload.extractServiceName();

        for (var resourceSpan : payload.getResourceSpans()) {
            if (resourceSpan.getScopeSpans() == null) continue;

            for (var scopeSpan : resourceSpan.getScopeSpans()) {
                if (scopeSpan.getSpans() == null) continue;

                for (var span : scopeSpan.getSpans()) {
                    try {
                        SpanEntity entity = new SpanEntity();
                        entity.setTraceId(span.getTraceId());
                        entity.setSpanId(span.getSpanId());
                        entity.setParentSpanId(span.getParentSpanId());
                        entity.setServiceName(resourceSpan.getServiceName());
                        entity.setOperationName(span.getName());
                        entity.setKind(OtlpTracePayload.Span.kindName(span.getKind()));

                        long startMs = OtlpTracePayload.Span.nanoToMillis(span.getStartTimeUnixNano());
                        long endMs = OtlpTracePayload.Span.nanoToMillis(span.getEndTimeUnixNano());
                        entity.setStartTime(Instant.ofEpochMilli(startMs));
                        entity.setEndTime(Instant.ofEpochMilli(endMs));
                        entity.setDurationMs(endMs - startMs);

                        if (span.getStatus() != null) {
                            entity.setStatusCode(span.getStatus().codeName());
                        } else {
                            entity.setStatusCode("UNSET");
                        }

                        entity.setAttributes(span.extractAttributesJson());
                        spanRepository.save(entity);
                        parsed++;

                        // �?trace 推一�?recent list (去重�?Redis 层面处理)
                        pushRecentTrace(span.getTraceId(), serviceName, span.getName());

                    } catch (Exception e) {
                        log.warn("[OtlpParser] Failed to process span traceId={}, spanId={}: {}",
                                span.getTraceId(), span.getSpanId(), e.getMessage());
                    }
                }
            }
        }

        log.info("[OtlpParser] Parsed {} spans", parsed);
        return parsed;
    }

    // ==================== Logs 解析 ====================

    /**
     * 解析 OTLP Logs JSON �?H2 温层 + Redis recent list
     *
     * @return 成功解析�?logRecord 数量
     */
    public int parseLogs(OtlpLogPayload payload) {
        if (payload == null || payload.getResourceLogs() == null) {
            log.warn("[OtlpParser] Empty logs payload");
            return 0;
        }

        int parsed = 0;
        String serviceName = payload.extractServiceName();

        for (var resourceLog : payload.getResourceLogs()) {
            if (resourceLog.getScopeLogs() == null) continue;

            for (var scopeLog : resourceLog.getScopeLogs()) {
                if (scopeLog.getLogRecords() == null) continue;

                for (var record : scopeLog.getLogRecords()) {
                    try {
                        LogEntity entity = new LogEntity();
                        entity.setTraceId(record.getTraceId());
                        entity.setSpanId(record.getSpanId());
                        entity.setLevel(record.getNormalizedLevel());
                        entity.setServiceName(resourceLog.getServiceName());
                        entity.setMessage(record.getMessage());
                        entity.setTimestamp(Instant.ofEpochMilli(
                                OtlpLogPayload.LogRecord.nanoToMillis(record.getTimeUnixNano())));
                        entity.setAttributes(record.extractAttributesJson());

                        logRepository.save(entity);
                        parsed++;

                        // 推送到 Redis recent list
                        try {
                            String logJson = objectMapper.writeValueAsString(Map.of(
                                    "traceId", entity.getTraceId() != null ? entity.getTraceId() : "",
                                    "level", entity.getLevel(),
                                    "message", entity.getMessage().length() > 200
                                            ? entity.getMessage().substring(0, 200) + "..." : entity.getMessage(),
                                    "serviceName", entity.getServiceName(),
                                    "timestamp", entity.getTimestamp().toString()
                            ));
                            redisMetricsService.pushRecentLog(logJson);
                        } catch (JsonProcessingException e) {
                            log.debug("[OtlpParser] Failed to serialize log for Redis: {}", e.getMessage());
                        }

                    } catch (Exception e) {
                        log.warn("[OtlpParser] Failed to process log record: {}", e.getMessage());
                    }
                }
            }
        }

        log.info("[OtlpParser] Parsed {} log records", parsed);
        return parsed;
    }

    // ==================== Helpers ====================

    private void saveMetric(String name, String tags, double value, String window, Instant ts) {
        try {
            MetricsAgg agg = new MetricsAgg(name, tags, value, window, ts);
            metricsAggRepository.save(agg);
        } catch (Exception e) {
            log.warn("[OtlpParser] Failed to save metric {}: {}", name, e.getMessage());
        }
    }

    private boolean isLatencyMetric(String name) {
        return name.contains("latency") || name.contains("duration") || name.contains("response.time")
                || name.contains("http.server.request.duration");
    }

    /** Extract tags JSON from a dataPoint's attribute list */
    private String extractDataPointTags(List<OtlpMetricPayload.Attribute> attrs) {
        if (attrs == null || attrs.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < attrs.size(); i++) {
            if (i > 0) sb.append(",");
            var a = attrs.get(i);
            sb.append("\"").append(a.getKey()).append("\":\"")
              .append(a.getValue() != null ? a.getValue().getStringValue() : "").append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private double parseCount(String countStr) {
        if (countStr == null) return 0;
        try { return Double.parseDouble(countStr); }
        catch (NumberFormatException e) { return 0; }
    }

    /** �?tags JSON 中提取意图并更新 Redis 分布 */
    private void extractAndUpdateIntent(String tags) {
        try {
            var node = objectMapper.readTree(tags);
            if (node.has("intent")) {
                redisMetricsService.updateIntentDistribution(node.get("intent").asText());
            }
            if (node.has("domain")) {
                redisMetricsService.updateIntentDistribution(node.get("domain").asText());
            }
        } catch (Exception e) {
            log.debug("[OtlpParser] Failed to extract intent from tags: {}", e.getMessage());
        }
    }

    /** �?trace 摘要推入 Redis recent list */
    private void pushRecentTrace(String traceId, String serviceName, String operationName) {
        try {
            String traceJson = objectMapper.writeValueAsString(Map.of(
                    "traceId", safeSubstring(traceId, 32),
                    "serviceName", serviceName,
                    "operationName", operationName,
                    "timestamp", Instant.now().toString()
            ));
            redisMetricsService.pushRecentTrace(traceJson);
        } catch (JsonProcessingException e) {
            log.debug("[OtlpParser] Failed to serialize trace recent: {}", e.getMessage());
        }
    }

    private String safeSubstring(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /** �?agent.* / llm.* 指标同步�?Redis 热层 */
    private void incrementAgentRedis(String metricName, String tags, long value) {
        try {
            // L0: router decisions and intent recognition
            if (metricName.contains("router.decision") || metricName.contains("intent.recognized")) {
                redisMetricsService.incrAgentCall("L0");
            }
            // L2: workflow execution / interrupt
            if (metricName.contains("workflow.execution") || metricName.contains("workflow.interrupt")) {
                redisMetricsService.incrAgentCall("L2");
            }
            // L1: 意图分类 / L1 Agent 调用阶段的实时计数（修复 GAP-A6/N4）。
            // 仅当 Core 显式发射 L1 指标（如 intent.classify / agent.l1 / router.l1 等）时计入；
            // 在此之前 L1 仍由 MetricsQueryService 的 Span operationName 启发式回退兜底。
            if (metricName.contains("intent.classif") || metricName.contains("agent.l1")
                    || metricName.contains("l1.call") || metricName.contains("intent.l1")
                    || metricName.contains("router.l1")) {
                redisMetricsService.incrAgentCall("L1");
            }
            // 注意：intent.accuracy / rewrite.accuracy 是校验类指标（高频），
            // 不是调用计数，绝不能映射到 L1 调用。
        } catch (Exception e) {
            log.debug("[OtlpParser] incrementAgentRedis failed for {}: {}", metricName, e.getMessage());
        }
    }

}

