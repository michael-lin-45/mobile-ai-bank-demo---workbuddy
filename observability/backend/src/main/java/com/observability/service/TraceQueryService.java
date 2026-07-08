package com.observability.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.dto.*;
import com.observability.model.SpanEntity;
import com.observability.repository.SessionRepository;
import com.observability.repository.SessionTurnRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Trace 查询服务 — H2 查询 + Redis recent fallback
 */
@Slf4j
@Service
public class TraceQueryService {

    private final SpanRepository spanRepository;
    private final SessionRepository sessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final RedisMetricsService redisMetrics;
    private final ObjectMapper objectMapper;

    public TraceQueryService(SpanRepository spanRepository,
                              SessionRepository sessionRepository,
    SessionTurnRepository sessionTurnRepository,
                              RedisMetricsService redisMetrics,
                              ObjectMapper objectMapper) {
        this.spanRepository = spanRepository;
        this.sessionRepository = sessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.redisMetrics = redisMetrics;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询 Trace 列表（按最近开始时间排序）
     */
    public List<TraceListVO> listTraces(Instant from, String intent, String sessionId,
                                         String userId, String statusCode, int limit) {
        if (from == null) from = Instant.now().minusSeconds(7 * 86400);
        if (limit <= 0) limit = 50;

        // 从 H2 获取根 span（parentSpanId IS NULL）
        var pageable = PageRequest.of(0, Math.min(Math.max(limit * 10, 500), 2000));
        var rootSpans = spanRepository.findRootSpansByTimeRange(from, pageable);

        // Business root span fallback: if all root spans are infrastructure (OTLP/health),
        // use SERVER kind spans as trace entry points.
        boolean hasBusinessRoot = false;
        for (var s : rootSpans.getContent()) {
            String op = s.getOperationName();
            if (op != null && !op.equals("POST") && !op.contains("/actuator/")
                && !op.contains("/health") && !op.contains("ResponseFacade")) {
                hasBusinessRoot = true;
                break;
            }
        }
        if (!hasBusinessRoot) {
            rootSpans = spanRepository.findBusinessRootSpansByTimeRange(from, pageable);
        }

        List<TraceListVO> results = new ArrayList<>();
        for (var root : rootSpans) {
            TraceListVO vo = buildTraceListVO(root);

            // 意图过滤
            if (intent != null && !intent.isBlank()) {
                if (vo.getIntent() == null || !vo.getIntent().toUpperCase().contains(intent.toUpperCase())) {
                    continue;
                }
            }
            // sessionId 过滤
            if (sessionId != null && !sessionId.isBlank()) {
                if (vo.getSessionId() == null || !vo.getSessionId().contains(sessionId)) {
                    continue;
                }
            }
            // userId 过滤
            if (userId != null && !userId.isBlank()) {
                if (vo.getUserId() == null || !vo.getUserId().contains(userId)) {
                    continue;
                }
            }
            // statusCode 过滤
            if (statusCode != null && !statusCode.isBlank()) {
                if (vo.getStatusCode() == null || !vo.getStatusCode().equalsIgnoreCase(statusCode)) {
                    continue;
                }
            }
            // --- Filter infrastructure/OTLP spans ---
            String opName = root.getOperationName();
            String rootKind = root.getKind();
            String rootAttrs = root.getAttributes();
            // Skip actuator/health/metrics endpoints
            if (opName != null && (opName.contains("/actuator/") || opName.contains("/health") || opName.contains("/metrics"))) {
                continue;
            }
            // Skip OTLP export spans (POST + CLIENT + url pointing to collector port 4318)
            if ("CLIENT".equals(rootKind) && "POST".equals(opName)) {
                if (rootAttrs != null && (rootAttrs.contains("4318") || rootAttrs.contains("/v1/metrics") || rootAttrs.contains("/v1/traces") || rootAttrs.contains("/v1/logs"))) {
                    continue;
                }
            }
            // Skip ResponseFacade.sendError (error handling noise)
            if (opName != null && opName.contains("ResponseFacade.sendError")) {
                continue;
            }
            // Skip failed short requests (HTTP 4xx/5xx with duration < 500ms = not real business)
            if (rootAttrs != null && rootAttrs.contains("\"http.response.status_code\":\"4") && root.getDurationMs() != null && root.getDurationMs() < 500) {
                continue;
            }
            if (rootAttrs != null && rootAttrs.contains("\"http.response.status_code\":\"5") && root.getDurationMs() != null && root.getDurationMs() < 500) {
                continue;
            }
            // Keep all remaining traces (business traces with or without intent/agentChain)
            results.add(vo);
            if (results.size() >= limit) break;
        }

        return results;
    }

    /**
     * Trace list with pagination - collects all filtered results then slices by page/size.
     */
    public Map<String, Object> listTracesPaginated(Instant from, String intent, String sessionId,
                                                    String userId, String statusCode, int limit,
                                                    int page, int size) {
        if (from == null) from = Instant.now().minusSeconds(7 * 86400);
        if (size <= 0) size = 10;
        if (size > 200) size = 200;
        if (page < 0) page = 0;
        // Calculate fetch size: (page+1)*size*5, capped at 500.
        // buildTraceListVO does 2-3 DB queries per span, so keep bounded.
        int neededAfterFilter = (page + 1) * size;
        int fetchSize = Math.min(neededAfterFilter * 5, 500);

        // 从 H2 获取根 span（parentSpanId IS NULL）
        var pageable = PageRequest.of(0, fetchSize);
        var rootSpans = spanRepository.findRootSpansByTimeRange(from, pageable);

        // Business root span fallback: if all root spans are infrastructure (OTLP/health),
        // use SERVER kind spans as trace entry points.
        boolean hasBusinessRoot = false;
        for (var s : rootSpans.getContent()) {
            String op = s.getOperationName();
            if (op != null && !op.equals("POST") && !op.contains("/actuator/")
                && !op.contains("/health") && !op.contains("ResponseFacade")) {
                hasBusinessRoot = true;
                break;
            }
        }
        if (!hasBusinessRoot) {
            rootSpans = spanRepository.findBusinessRootSpansByTimeRange(from, pageable);
        }

        List<TraceListVO> allFiltered = new ArrayList<>();
        for (var root : rootSpans) {
            TraceListVO vo = buildTraceListVOFast(root);

            // 意图过滤
            if (intent != null && !intent.isBlank()) {
                if (vo.getIntent() == null || !vo.getIntent().toUpperCase().contains(intent.toUpperCase())) {
                    continue;
                }
            }
            // sessionId 过滤
            if (sessionId != null && !sessionId.isBlank()) {
                if (vo.getSessionId() == null || !vo.getSessionId().contains(sessionId)) {
                    continue;
                }
            }
            // userId 过滤
            if (userId != null && !userId.isBlank()) {
                if (vo.getUserId() == null || !vo.getUserId().contains(userId)) {
                    continue;
                }
            }
            // statusCode 过滤
            if (statusCode != null && !statusCode.isBlank()) {
                if (vo.getStatusCode() == null || !vo.getStatusCode().equalsIgnoreCase(statusCode)) {
                    continue;
                }
            }
            // --- Filter infrastructure/OTLP spans ---
            String opName = root.getOperationName();
            String rootKind = root.getKind();
            String rootAttrs = root.getAttributes();
            // Skip actuator/health/metrics endpoints
            if (opName != null && (opName.contains("/actuator/") || opName.contains("/health") || opName.contains("/metrics"))) {
                continue;
            }
            // Skip OTLP export spans (POST + CLIENT + url pointing to collector port 4318)
            if ("CLIENT".equals(rootKind) && "POST".equals(opName)) {
                if (rootAttrs != null && (rootAttrs.contains("4318") || rootAttrs.contains("/v1/metrics") || rootAttrs.contains("/v1/traces") || rootAttrs.contains("/v1/logs"))) {
                    continue;
                }
            }
            // Skip ResponseFacade.sendError (error handling noise)
            if (opName != null && opName.contains("ResponseFacade.sendError")) {
                continue;
            }
            // Skip failed short requests (HTTP 4xx/5xx with duration < 500ms = not real business)
            if (rootAttrs != null && rootAttrs.contains("\"http.response.status_code\":\"4") && root.getDurationMs() != null && root.getDurationMs() < 500) {
                continue;
            }
            if (rootAttrs != null && rootAttrs.contains("\"http.response.status_code\":\"5") && root.getDurationMs() != null && root.getDurationMs() < 500) {
                continue;
            }
            // Keep all remaining traces (business traces with or without intent/agentChain)
            allFiltered.add(vo);
        }

        int total = allFiltered.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int fromIdx = page * size;
        int toIdx = Math.min(fromIdx + size, total);
        List<TraceListVO> pageContent = (fromIdx < total)
                ? allFiltered.subList(fromIdx, toIdx)
                : new ArrayList<>();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", pageContent);
        result.put("totalElements", total);
        result.put("totalPages", totalPages);
        result.put("number", page);
        result.put("size", size);
        return result;
    }


    /**
     * Get Trace detail
     */
    public TraceDetailVO getTraceDetail(String traceId) {
        List<SpanEntity> spans = spanRepository.findByTraceIdOrderByStartTimeAsc(traceId);
        if (spans.isEmpty()) {
            return new TraceDetailVO(traceId, List.of(), 0L, "unknown");
        }

        // Filter to last L0->L1->L2 agent chain (handles reRoute scenarios)
        List<SpanEntity> chainSpans = extractLastAgentChain(spans);
        // Build span tree from filtered chain
        List<SpanNodeVO> spanTree = buildSpanTree(chainSpans);

        SpanEntity firstSpan = chainSpans.get(0);
        SpanEntity lastSpan = chainSpans.get(chainSpans.size() - 1);
        // Compute duration from the first to last business span in the chain
        // (not the HTTP root span, which may have stale timestamps)
        long chainStartMs = 0;
        long chainEndMs = 0;
        for (var s : chainSpans) {
            String opName = s.getOperationName();
            if (opName == null) continue;
            // Only consider business spans for duration calculation
            if (opName.startsWith("L0:") || opName.startsWith("L1") || opName.startsWith("L2:") || opName.startsWith("llm:")) {
                if (s.getStartTime() != null) {
                    long start = s.getStartTime().toEpochMilli();
                    if (chainStartMs == 0 || start < chainStartMs) chainStartMs = start;
                }
                if (s.getEndTime() != null) {
                    long end = s.getEndTime().toEpochMilli();
                    if (end > chainEndMs) chainEndMs = end;
                }
            }
        }
        long totalDurationMs = (chainStartMs > 0 && chainEndMs > chainStartMs)
                ? chainEndMs - chainStartMs
                : (firstSpan.getDurationMs() != null ? firstSpan.getDurationMs() : 0);

        String rootService = firstSpan.getServiceName() != null ? firstSpan.getServiceName() : "unknown";

        TraceDetailVO vo = new TraceDetailVO(traceId, spanTree, totalDurationMs, rootService);

        // ── 增强字段：从根 Span url.query 提取 sessionId，并关联 Session 表 ──
        String sessionId = extractSessionIdFromUrl(firstSpan.getAttributes());
        String userId = null;
        String intent = null;
        String agentChain = null;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                var sessionOpt = sessionRepository.findBySessionId(sessionId);
                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();
                    userId = session.getUserId();
                    intent = session.getIntentFlow();
                    agentChain = session.getIntentFlow();
                }
            } catch (Exception e) {
                log.debug("[TraceQuery] Failed to lookup session for traceId={}: {}", traceId, e.getMessage());
            }
        }

        // 兜底：从跨 Span 属性提取
        Map<String, String> attrs = extractBusinessAttributes(traceId, firstSpan.getAttributes());
        if (userId == null || userId.isBlank()) {
            userId = attrs.getOrDefault("user_id", attrs.getOrDefault("userId", "未采集"));
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = attrs.getOrDefault("session_id", attrs.getOrDefault("sessionId", "未采集"));
        }
        if (intent == null || intent.isBlank()) {
            intent = attrs.getOrDefault("intent", attrs.getOrDefault("domain", "未识别"));
        }

        vo.setUserId(userId);
        vo.setSessionId(sessionId);
        vo.setIntent(intent);
        String traceAgentChain = buildAgentChainFromSpans(spans);
        vo.setAgentChain(traceAgentChain != null ? traceAgentChain : (agentChain != null && !agentChain.isBlank() ? agentChain : buildAgentChain(traceId)));

        // TTFT — compute from first LLM span startTime vs trace root startTime
        Long ttftVal = computeTTFT(spans);
        if (ttftVal != null) {
            vo.setTtft(ttftVal);
            vo.setTtftMs(ttftVal);
        } else {
            String ttftStr = attrs.get("llm.first_token_time");
            if (ttftStr != null) {
                try {
                    long attrTtft = Long.parseLong(ttftStr);
                    vo.setTtft(attrTtft);
                    vo.setTtftMs(attrTtft);
                } catch (NumberFormatException e) {
                    log.debug("[TraceQuery] Invalid ttft value: {}", ttftStr);
                }
            }
        }

        // 状态码规范化：UNSET → OK
        String rawStatusCode = firstSpan.getStatusCode();
        if ("UNSET".equalsIgnoreCase(rawStatusCode)) {
            rawStatusCode = "OK";
        }
        if (rawStatusCode == null) {
            rawStatusCode = "OK";
        }
        vo.setStatusCode(rawStatusCode);
        vo.setStatus("ERROR".equalsIgnoreCase(rawStatusCode) ? "🔴异常" : "🟢正常");

        // 时长别名
        vo.setDurationMs(totalDurationMs);

        // ── IO 卡片数据：从 Span 树中查找第一个有 IO 数据的节点 ──
        // Enrich business spans from SessionTurn table
        com.observability.model.SessionTurn matchedTurn = null;
        try {
            // Strategy 1: match by traceId (most accurate, works for new traces)
            var turnsByTrace = sessionTurnRepository.findByTraceId(traceId);
            if (turnsByTrace != null && !turnsByTrace.isEmpty()) {
                matchedTurn = turnsByTrace.get(0);
            }
            // Strategy 2: match by sessionId + timestamp proximity (fallback for old traces with empty traceId)
            if (matchedTurn == null && sessionId != null && !sessionId.isBlank()) {
                var turns = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
                if (turns != null && !turns.isEmpty()) {
                    long traceStartMs = firstSpan.getStartTime() != null ? firstSpan.getStartTime().toEpochMilli() : 0;
                    com.observability.model.SessionTurn best = null;
                    long bestDiff = Long.MAX_VALUE;
                    for (var turn : turns) {
                        if (turn.getTimestamp() == null) continue;
                        long diff = Math.abs(turn.getTimestamp().toEpochMilli() - traceStartMs);
                        if (diff < bestDiff) {
                            bestDiff = diff;
                            best = turn;
                        }
                    }
                    if (best != null) {
                        matchedTurn = best;
                    }
                }
            }
            if (matchedTurn != null) {
                enrichBusinessSpans(spanTree, matchedTurn);
            }
        } catch (Exception e) {
            log.debug("[TraceQuery] Failed to enrich business spans from SessionTurn: {}", e.getMessage());
        }

        // Set trace-level ioInput/ioOutput from SessionTurn if not already set
        if (matchedTurn != null) {
            if (matchedTurn.getUserMessage() != null && !matchedTurn.getUserMessage().isBlank()) {
                Map<String, String> ioInput = new LinkedHashMap<>();
                ioInput.put("type", "text");
                ioInput.put("content", matchedTurn.getUserMessage());
                vo.setIoInput(ioInput);
                vo.setInputText(matchedTurn.getUserMessage());
            }
            if (matchedTurn.getAiResponse() != null && !matchedTurn.getAiResponse().isBlank()) {
                Map<String, String> ioOutput = new LinkedHashMap<>();
                ioOutput.put("type", "text");
                ioOutput.put("content", matchedTurn.getAiResponse());
                vo.setIoOutput(ioOutput);
                vo.setOutputText(matchedTurn.getAiResponse());
            }
        }

        SpanNodeVO iosSpan = findFirstSpanWithIO(spanTree);
        if (iosSpan != null) {
            if (iosSpan.getIoPrompt() != null && !iosSpan.getIoPrompt().isBlank()) {
                Map<String, String> ioInput = new LinkedHashMap<>();
                ioInput.put("type", "text");
                ioInput.put("content", iosSpan.getIoPrompt());
                vo.setIoInput(ioInput);
                vo.setInputText(iosSpan.getIoPrompt());
            }
            if (iosSpan.getIoResponse() != null && !iosSpan.getIoResponse().isBlank()) {
                Map<String, String> ioOutput = new LinkedHashMap<>();
                ioOutput.put("type", "text");
                ioOutput.put("content", iosSpan.getIoResponse());
                vo.setIoOutput(ioOutput);
                vo.setOutputText(iosSpan.getIoResponse());
            }
        }

        // ── 瀑布图 Span：展平 Span 树 ──
        Map<String, SpanEntity> spanMap = new HashMap<>();
        for (SpanEntity s : spans) {
            spanMap.put(s.getSpanId(), s);
        }
        // Use the first business span (L0) startTime as waterfall base,
        // since the HTTP root span startTime may be stale (traceId reused across requests)
        long traceStartMs = 0;
        for (var s : chainSpans) {
            String opName = s.getOperationName();
            if (opName != null && opName.startsWith("L0:") && s.getStartTime() != null) {
                traceStartMs = s.getStartTime().toEpochMilli();
                break;
            }
        }
        if (traceStartMs == 0 && firstSpan.getStartTime() != null) {
            traceStartMs = firstSpan.getStartTime().toEpochMilli();
        }
        vo.setWaterfallSpans(buildWaterfallSpans(spanTree, spanMap, traceStartMs));

        return vo;
    }

    /**
     * Extract the LAST complete L0->L1->L2 agent chain from a trace span list.
     * In reRoute scenarios, multiple chains exist; we show the last (effective) one.
     * Normal case: only one chain, so returns root + all business spans.
     */
    private List<SpanEntity> extractLastAgentChain(List<SpanEntity> spans) {
        if (spans == null || spans.isEmpty()) return spans;

        // Find the LAST L0 span (business entry point)
        int lastL0Idx = -1;
        for (int i = spans.size() - 1; i >= 0; i--) {
            String opName = spans.get(i).getOperationName();
            if (opName != null && (opName.startsWith("L0:") || opName.contains("DomainRouter"))) {
                lastL0Idx = i;
                break;
            }
        }
        if (lastL0Idx < 0) return spans;

        // Find the root HTTP span that is the PARENT of the last L0
        SpanEntity rootSpan = null;
        String l0ParentId = spans.get(lastL0Idx).getParentSpanId();
        if (l0ParentId != null && !l0ParentId.isEmpty()) {
            for (var s : spans) {
                if (l0ParentId.equals(s.getSpanId())) {
                    rootSpan = s;
                    break;
                }
            }
        }
        // Fallback: SERVER span closest BEFORE the L0
        if (rootSpan == null) {
            for (int i = lastL0Idx; i >= 0; i--) {
                var s = spans.get(i);
                if ("SERVER".equals(s.getKind()) && (s.getParentSpanId() == null || s.getParentSpanId().isEmpty())) {
                    rootSpan = s;
                    break;
                }
            }
        }
        // Fallback: first root span
        if (rootSpan == null) {
            for (var s : spans) {
                if (s.getParentSpanId() == null || s.getParentSpanId().isEmpty()) {
                    rootSpan = s;
                    break;
                }
            }
        }

        List<SpanEntity> result = new ArrayList<>();
        if (rootSpan != null) result.add(rootSpan);
        for (int i = lastL0Idx; i < spans.size(); i++) {
            result.add(spans.get(i));
        }
        return result;
    }

    /**
     * Compute TTFT = first LLM business span startTime - trace root startTime.
     */
    private Long computeTTFT(List<SpanEntity> spans) {
        if (spans == null || spans.isEmpty()) return null;
        // Find the LAST L0 span
        int lastL0Idx = -1;
        for (int i = spans.size() - 1; i >= 0; i--) {
            String opName = spans.get(i).getOperationName();
            if (opName != null && (opName.startsWith("L0:") || opName.contains("DomainRouter"))) {
                lastL0Idx = i;
                break;
            }
        }
        if (lastL0Idx < 0) return null;

        SpanEntity firstLLMSpan = spans.get(lastL0Idx);
        if (firstLLMSpan.getStartTime() == null) return null;

        // Find the root HTTP span that is the PARENT of this L0 span
        String l0ParentId = firstLLMSpan.getParentSpanId();
        SpanEntity rootSpan = null;
        if (l0ParentId != null && !l0ParentId.isEmpty()) {
            for (var s : spans) {
                if (l0ParentId.equals(s.getSpanId())) {
                    rootSpan = s;
                    break;
                }
            }
        }
        // Fallback: find SERVER span closest BEFORE the L0
        if (rootSpan == null) {
            for (int i = lastL0Idx; i >= 0; i--) {
                var s = spans.get(i);
                if ("SERVER".equals(s.getKind()) && (s.getParentSpanId() == null || s.getParentSpanId().isEmpty())) {
                    rootSpan = s;
                    break;
                }
            }
        }

        if (rootSpan == null || rootSpan.getStartTime() == null) return null;
        long ttft = firstLLMSpan.getStartTime().toEpochMilli() - rootSpan.getStartTime().toEpochMilli();
        // If TTFT is unreasonably large (> 60s), the root span startTime is stale
        // (instrumentation reused traceId across multiple HTTP requests).
        // In this case, use the L0 startTime as the base (TTFT ≈ 0).
        if (ttft > 60000) {
            return 0L;
        }
        return ttft > 0 ? ttft : null;
    }

    /**
     * Build agent chain from the LAST agent chain spans (not session.intentFlow).
     */
    private String buildAgentChainFromSpans(List<SpanEntity> spans) {
        List<SpanEntity> chainSpans = extractLastAgentChain(spans);
        List<String> layers = new ArrayList<>();
        for (var s : chainSpans) {
            String name = s.getOperationName();
            if (name == null) continue;
            String layer = null;
            if (name.startsWith("L0:") || name.contains("DomainRouter")) layer = "L0";
            else if (name.startsWith("L1-LLM1")) layer = "L1-LLM1";
            else if (name.startsWith("L1-LLM2")) layer = "L1-LLM2";
            else if (name.startsWith("L1:") || name.startsWith("L1-")) layer = "L1";
            else if (name.startsWith("L2:")) layer = "L2";
            if (layer != null && !layers.contains(layer)) layers.add(layer);
        }
        return layers.isEmpty() ? null : String.join(" → ", layers);
    }

    // ==================== Span 树构建算法 ====================

    /**
     * 递归构建 Span 层级树
     * 按 parentSpanId 分组，root 节点 parentSpanId 为空
     */
    private List<SpanNodeVO> buildSpanTree(List<SpanEntity> spans) {
        // 按 parentSpanId 分组
        Map<String, List<SpanEntity>> childrenMap = new HashMap<>();
        List<SpanEntity> roots = new ArrayList<>();

        for (var span : spans) {
            String parentId = span.getParentSpanId();
            if (parentId == null || parentId.isEmpty()) {
                roots.add(span);
            } else {
                childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(span);
            }
        }

        // 如果没有明确的 root（parentSpanId 都有值），用第一个 span
        if (roots.isEmpty() && !spans.isEmpty()) {
            roots.add(spans.get(0));
        }

        List<SpanNodeVO> tree = new ArrayList<>();
        for (var root : roots) {
            tree.add(buildSpanNode(root, childrenMap));
        }
        return tree;
    }

    private SpanNodeVO buildSpanNode(SpanEntity span, Map<String, List<SpanEntity>> childrenMap) {
        SpanNodeVO node = entityToVO(span);

        List<SpanEntity> children = childrenMap.getOrDefault(span.getSpanId(), List.of());
        List<SpanNodeVO> childNodes = new ArrayList<>();
        for (var child : children) {
            childNodes.add(buildSpanNode(child, childrenMap));
        }
        node.setChildren(childNodes);

        return node;
    }

    private SpanNodeVO entityToVO(SpanEntity entity) {
        SpanNodeVO vo = new SpanNodeVO();
        vo.setSpanId(entity.getSpanId());
        vo.setParentSpanId(entity.getParentSpanId());
        vo.setOperationName(entity.getOperationName());
        vo.setKind(entity.getKind());
        vo.setDurationMs(entity.getDurationMs() != null ? entity.getDurationMs() : 0);
        vo.setStatusCode(entity.getStatusCode());

        // 解析 attributes JSON → Map
        Map<String, String> attrs = parseAttributes(entity.getAttributes());
        vo.setAttributes(attrs);
        vo.setIoPrompt(attrs.get("ai.io.prompt"));
        vo.setIoResponse(attrs.get("ai.io.response"));

        // 解析 Token 信息
        TokenBreakdownVO token = parseTokenBreakdown(entity.getAttributes());
        if (token != null && (token.getSystemTokens() > 0 || token.getContextTokens() > 0 || token.getOutputTokens() > 0)) {
            vo.setTokenBreakdown(token);
        }

        // ── SpanTree 组件扩展字段 ──

        // Agent 名称：取 operationName
        vo.setAgentName(entity.getOperationName());

        // Agent 描述：kind + " span"
        String kind = entity.getKind() != null ? entity.getKind() : "INTERNAL";
        vo.setAgentDesc(kind + " span");

        // Agent 层级：根据 operationName 判断
        vo.setAgentType(determineLayer(entity.getOperationName()));

        // LLM 调用列表：占位空数组
        vo.setLlmCalls(new ArrayList<>());

        // Duration 回退：若 durationMs==0 但有 startTime/endTime，用时间差计算
        if (vo.getDurationMs() == 0 && entity.getStartTime() != null && entity.getEndTime() != null) {
            long calcMs = entity.getEndTime().toEpochMilli() - entity.getStartTime().toEpochMilli();
            if (calcMs > 0) {
                vo.setDurationMs(calcMs);
            }
        }

        // StatusCode 默认值
        if (vo.getStatusCode() == null) {
            vo.setStatusCode("UNSET");
        }

        return vo;
    }

    // ==================== 属性解析 ====================

    private Map<String, String> parseAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(attributesJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.debug("[TraceQuery] Failed to parse attributes: {}", e.getMessage());
            return Map.of();
        }
    }

    private TokenBreakdownVO parseTokenBreakdown(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return null;
        try {
            Map<String, String> attrs = objectMapper.readValue(attributesJson,
                    new TypeReference<Map<String, String>>() {});
            int sys = parseIntSafe(attrs.get("ai.token.system"));
            int ctx = parseIntSafe(attrs.get("ai.token.context"));
            int out = parseIntSafe(attrs.get("ai.token.output"));
            if (sys > 0 || ctx > 0 || out > 0) {
                return new TokenBreakdownVO(sys, ctx, out);
            }
        } catch (Exception e) {
            log.debug("[TraceQuery] Failed to parse token breakdown: {}", e.getMessage());
        }
        return null;
    }

    private int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ==================== 列表 VO 构建 ====================


    /**
     * Fast version of buildTraceListVO for list pagination.
     * Skips expensive child-span and SessionTurn DB lookups to keep list queries fast.
     */
    private TraceListVO buildTraceListVOFast(SpanEntity rootSpan) {
        String traceId = rootSpan.getTraceId();
        TraceListVO vo = new TraceListVO();
        vo.setTraceId(traceId);
        vo.setTimestamp(rootSpan.getStartTime());
        vo.setDurationMs(rootSpan.getDurationMs() != null ? rootSpan.getDurationMs() : 0);
        vo.setStatusCode(rootSpan.getStatusCode());

        // 1. Extract sessionId from root span URL
        String sessionId = extractSessionIdFromUrl(rootSpan.getAttributes());

        // 2. Enrich from Session table (single lookup)
        String intent = null;
        String userId = null;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                var sessionOpt = sessionRepository.findBySessionId(sessionId);
                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();
                    userId = session.getUserId();
                    intent = session.getIntentFlow();
                    // agentChain will be set below from trace spans, not session
                    // (session.intentFlow accumulates all turns in the session)
                }
            } catch (Exception e) {
                log.debug("[TraceQuery] Fast: Failed to lookup session for traceId={}: {}", traceId, e.getMessage());
            }
        }

        // 3. Parse root span attributes only (no child span search)
        Map<String, String> attrs = parseAttributes(rootSpan.getAttributes());

        if (intent == null || intent.isBlank()) {
            intent = attrs.getOrDefault("intent", attrs.getOrDefault("domain", null));
        }
        if (userId == null || userId.isBlank()) {
            userId = attrs.getOrDefault("user_id", attrs.getOrDefault("userId", null));
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = attrs.getOrDefault("session_id", attrs.getOrDefault("sessionId", null));
        }

        vo.setIntent(intent);
        vo.setUserId(userId);
        vo.setSessionId(sessionId);
        // Build agentChain from trace spans (not session.intentFlow)
        String traceAgentChain = buildAgentChainFromSpans(
                spanRepository.findByTraceIdOrderByStartTimeAsc(traceId));
        vo.setAgentChain(traceAgentChain);

        // TTFT and tokens from root span attributes only (no SessionTurn fallback)
        String ttftStr = attrs.get("llm.first_token_time");
        if (ttftStr != null) {
            try { vo.setTtftMs(Long.parseLong(ttftStr)); } catch (NumberFormatException e) { /* ignore */ }
        }
        int sysToken = parseIntSafe(attrs.get("ai.token.system"));
        int ctxToken = parseIntSafe(attrs.get("ai.token.context"));
        int outToken = parseIntSafe(attrs.get("ai.token.output"));
        long tokenTotal = (long) sysToken + ctxToken + outToken;
        vo.setTokenTotal(tokenTotal > 0 ? tokenTotal : null);

        // Status code normalization
        String sc = vo.getStatusCode();
        if ("UNSET".equalsIgnoreCase(sc)) {
            vo.setStatusCode("OK");
            sc = "OK";
        }
        vo.setStatusDot("ERROR".equalsIgnoreCase(sc) ? "ERR" : "OK");

        return vo;
    }

    private TraceListVO buildTraceListVO(SpanEntity rootSpan) {
        String traceId = rootSpan.getTraceId();
        TraceListVO vo = new TraceListVO();
        vo.setTraceId(traceId);
        vo.setTimestamp(rootSpan.getStartTime());
        vo.setDurationMs(rootSpan.getDurationMs() != null ? rootSpan.getDurationMs() : 0);
        vo.setStatusCode(rootSpan.getStatusCode());

        // ── 1. 从根 Span 的 url.query 中提取 sessionId ──
        String sessionIdFromUrl = extractSessionIdFromUrl(rootSpan.getAttributes());

        // ── 2. 从 Session 表补充业务属性（意图、Agent链、userId）──
        String sessionId = sessionIdFromUrl;
        String intent = null;
        String userId = null;
        String agentChain = null;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                var sessionOpt = sessionRepository.findBySessionId(sessionId);
                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();
                    userId = session.getUserId();
                    intent = session.getIntentFlow();
                    agentChain = session.getIntentFlow();
                }
            } catch (Exception e) {
                log.debug("[TraceQuery] Failed to lookup session for traceId={}: {}", traceId, e.getMessage());
            }
        }

        // ── 3. 跨 Span 属性提取兜底 ──
        Map<String, String> attrs = extractBusinessAttributes(traceId, rootSpan.getAttributes());

        if (intent == null || intent.isBlank()) {
            intent = attrs.getOrDefault("intent", attrs.getOrDefault("domain", "未识别"));
        }
        if (userId == null || userId.isBlank()) {
            userId = attrs.getOrDefault("user_id", attrs.getOrDefault("userId", "未采集"));
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = attrs.getOrDefault("session_id", attrs.getOrDefault("sessionId", "未采集"));
        }

        vo.setIntent(intent);
        vo.setUserId(userId);
        vo.setSessionId(sessionId);

// TTFT: compute from all spans of this trace
        Long ttftVal = computeTTFT(
                spanRepository.findByTraceIdOrderByStartTimeAsc(traceId));
        if (ttftVal != null) {
            vo.setTtftMs(ttftVal);
        } else {
            // Fallback: try attribute
            String ttftStr = attrs.get("llm.first_token_time");
            if (ttftStr != null) {
                try { vo.setTtftMs(Long.parseLong(ttftStr)); } catch (NumberFormatException e) { /* ignore */ }
            }
        }

// Token total from attributes
        int sysToken = parseIntSafe(attrs.get("ai.token.system"));
        int ctxToken = parseIntSafe(attrs.get("ai.token.context"));
        int outToken = parseIntSafe(attrs.get("ai.token.output"));
        long tokenTotal = (long) sysToken + ctxToken + outToken;
        vo.setTokenTotal(tokenTotal > 0 ? tokenTotal : null);
        
        // Fallback: aggregate tokens from SessionTurn if span attributes are empty
        if (tokenTotal <= 0 && sessionId != null && !sessionId.isBlank()) {
            try {
                var turns = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
                if (turns != null && !turns.isEmpty()) {
                    // Sum tokens from the last turn (matching this trace)
                    var lastTurn = turns.get(turns.size() - 1);
                    if (lastTurn.getTokens() != null && lastTurn.getTokens() > 0) {
                        vo.setTokenTotal(lastTurn.getTokens().longValue());
                    }
                }
            } catch (Exception e) {
                log.debug("[TraceQuery] Failed to enrich tokens from SessionTurn: {}", e.getMessage());
            }
        }

        // Fallback: enrich TTFT and tokens from SessionTurn if span attributes are empty
        if ((vo.getTtftMs() == null || vo.getTtftMs() == 0) && sessionId != null && !sessionId.isBlank()) {
            try {
                var turns = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
                if (turns != null && !turns.isEmpty()) {
                    var turn = turns.get(turns.size() - 1);
                    // SessionTurn doesn't have ttftMs field — skip TTFT enrichment
                    if (tokenTotal <= 0 && turn.getTokens() != null && turn.getTokens() > 0) {
                        vo.setTokenTotal(turn.getTokens().longValue());
                    }
                }
            } catch (Exception e) {
                log.debug("[TraceQuery] Failed to enrich TTFT/tokens from SessionTurn: {}", e.getMessage());
            }
        }

        // Status code 规范化：UNSET → OK
        String sc = vo.getStatusCode();
        if ("UNSET".equalsIgnoreCase(sc)) {
            vo.setStatusCode("OK");
            sc = "OK";
        }
        vo.setStatusDot("ERROR".equalsIgnoreCase(sc) ? "🔴异常" : "🟢正常");

        // 统计 LLM 调用次数
        long llmCalls = spanRepository.countByTraceId(traceId);
        vo.setLlmCalls((int) llmCalls);

        // 构建 Agent 链（优先用 Session 表的 intentFlow，其次从 operationName 提取 L0/L1/L2）
        if (agentChain == null || agentChain.isBlank()) {
            agentChain = buildAgentChain(traceId);
        }
        vo.setAgentChain(agentChain);

        return vo;
    }

    /**
     * 从 Span 的 url.query 属性解析 sessionId
     * 例如: url.query="sessionId=diagnose-fix1" → "diagnose-fix1"
     */
    private String extractSessionIdFromUrl(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return null;
        try {
            Map<String, String> attrs = objectMapper.readValue(attributesJson,
                    new TypeReference<Map<String, String>>() {});
            String query = attrs.get("url.query");
            if (query == null || query.isBlank()) return null;

            // 解析 query string 中的 sessionId
            for (String pair : query.split("&")) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = pair.substring(0, idx);
                    String value = pair.substring(idx + 1);
                    if ("sessionId".equals(key) || "session_id".equals(key)) {
                        try { return java.net.URLDecoder.decode(value, "UTF-8").trim(); } catch (Exception ex) { return value.trim(); }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[TraceQuery] Failed to extract sessionId from url.query: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 业务属性跨 Span 提取 ====================

    /**
     * 跨所有 Span 提取业务属性（userId / sessionId / intent / token 等）。
     *
     * OTel 自动 Span（根 Span）通常由 Micrometer Bridge 生成，不含业务
     * 属性。业务属性由 Agent 层在自己创建的 Span 上注入。此方法先检查
     * rootSpan 的 attributes，对于缺失的 key 再从所有子 Span 中补齐。
     *
     * @param traceId         Trace ID
     * @param rootAttributes  根 Span 的 attributes JSON 字符串（可为 null）
     * @return 聚合后的业务属性 Map
     */
    private Map<String, String> extractBusinessAttributes(String traceId,
                                                          String rootAttributes) {
        // 第一步：解析根 Span 属性
        Map<String, String> merged = new HashMap<>();
        if (rootAttributes != null && !rootAttributes.isBlank()) {
            merged.putAll(parseAttributes(rootAttributes));
        }

        // 第二步：补齐缺失的关键属性
        // 定义需要跨 Span 搜索的关键属性
        String[] keyAttrs = {"user_id", "userId", "session_id", "sessionId",
                "intent", "domain", "ai.token.system", "ai.token.context",
                "ai.token.output", "llm.first_token_time",
                "ai.io.prompt", "ai.io.response"};

        boolean needSearch = false;
        for (String key : keyAttrs) {
            if (!merged.containsKey(key)) {
                needSearch = true;
                break;
            }
        }

        if (!needSearch) return merged;

        // 第三步：从所有子 Span 中查找缺失属性
        try {
            List<SpanEntity> allSpans = spanRepository.findByTraceIdOrderByStartTimeAsc(traceId);
            for (SpanEntity span : allSpans) {
                if (span.getAttributes() == null || span.getAttributes().isBlank()) continue;

                // 跳过根 Span 自身（已解析过）
                if (span.getParentSpanId() == null || span.getParentSpanId().isEmpty()) continue;

                Map<String, String> childAttrs = parseAttributes(span.getAttributes());
                for (String key : keyAttrs) {
                    if (!merged.containsKey(key) && childAttrs.containsKey(key)) {
                        merged.put(key, childAttrs.get(key));
                    }
                }

                // 如果所有关键属性都齐了，提前退出
                boolean allFound = true;
                for (String key : keyAttrs) {
                    if (!merged.containsKey(key)) { allFound = false; break; }
                }
                if (allFound) break;
            }
        } catch (Exception e) {
            log.debug("[TraceQuery] Failed to search child spans for attributes: {}", e.getMessage());
        }

        return merged;
    }

    // ==================== Agent 链构建 ====================

    private String buildAgentChain(String traceId) {
        try {
            List<SpanEntity> spans = spanRepository.findByTraceIdOrderByStartTimeAsc(traceId);
            List<String> layers = new ArrayList<>();
            for (var s : spans) {
                String name = s.getOperationName();
                if (name != null) {
                    if (name.contains("DomainRouter") || name.contains("L0")) {
                        if (!layers.contains("L0")) layers.add("L0");
                    } else if (name.contains("L1") || name.contains("Intent")) {
                        if (!layers.contains("L1")) layers.add("L1");
                    } else if (name.contains("L2") || name.contains("Service") || name.contains("Graph")) {
                        if (!layers.contains("L2")) layers.add("L2");
                    }
                }
            }
            return layers.isEmpty() ? "N/A" : String.join("→", layers);
        } catch (Exception e) {
            return "N/A";
        }
    }

    // ==================== IO 数据查找 ====================

    /**
     * 在 Span 树中递归查找第一个包含 IO 数据的节点（ioPrompt 或 ioResponse）
     */
    /**
     * Enrich business spans (L0:/L1-LLM1:/L1-LLM2:/L2:) with userMessage/aiResponse
     * from SessionTurn table, since OTel span attributes may be empty.
     */
    private void enrichBusinessSpans(List<SpanNodeVO> spans, com.observability.model.SessionTurn turn) {
        for (var span : spans) {
            String name = span.getOperationName();
            if (name != null && (name.startsWith("L0:") || name.startsWith("L1-") || name.startsWith("L2:"))) {
                if (span.getIoPrompt() == null || span.getIoPrompt().isBlank()) {
                    span.setIoPrompt(turn.getUserMessage());
                }
                if (span.getIoResponse() == null || span.getIoResponse().isBlank()) {
                    span.setIoResponse(turn.getAiResponse());
                }
            }
            if (span.getChildren() != null) {
                enrichBusinessSpans(span.getChildren(), turn);
            }
        }
    }

        private SpanNodeVO findFirstSpanWithIO(List<SpanNodeVO> tree) {
        for (SpanNodeVO node : tree) {
            SpanNodeVO found = findFirstSpanWithIORecursive(node);
            if (found != null) return found;
        }
        return null;
    }

    private SpanNodeVO findFirstSpanWithIORecursive(SpanNodeVO node) {
        // 当前节点有 IO 数据？
        if ((node.getIoPrompt() != null && !node.getIoPrompt().isBlank())
                || (node.getIoResponse() != null && !node.getIoResponse().isBlank())) {
            return node;
        }
        // 递归子节点
        if (node.getChildren() != null) {
            for (SpanNodeVO child : node.getChildren()) {
                SpanNodeVO found = findFirstSpanWithIORecursive(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ==================== 瀑布图构建 ====================

    /**
     * 将 Span 树展平为瀑布图格式的列表。
     * 每个元素为 Map，包含 spanId, parentSpanId, operationName, durationMs,
     * startOffsetMs（相对于 trace 起始的偏移）, layer（L0/L1/L2）, color。
     */
    private List<Object> buildWaterfallSpans(List<SpanNodeVO> spanTree,
                                              Map<String, SpanEntity> spanMap,
                                              long traceStartEpochMs) {
        List<Object> waterfall = new ArrayList<>();
        for (SpanNodeVO node : spanTree) {
            flattenForWaterfall(node, spanMap, traceStartEpochMs, waterfall);
        }
        return waterfall;
    }

    private void flattenForWaterfall(SpanNodeVO node,
                                      Map<String, SpanEntity> spanMap,
                                      long traceStartEpochMs,
                                      List<Object> result) {
        String opName = node.getOperationName();

        // Skip non-business spans: POST CLIENT (LLM API calls), actuator, etc.
        // Only show: HTTP root (SERVER), L0:, L1:, L1-LLM1:, L1-LLM2:, L2:, llm:
        boolean isBusiness = false;
        if (opName != null) {
            if (opName.startsWith("L0:") || opName.startsWith("L1") || opName.startsWith("L2:") || opName.startsWith("llm:")) {
                isBusiness = true;
            }
            // HTTP root span (SERVER kind)
            if ("SERVER".equals(node.getKind()) || opName.contains("/api/bank/chat")) {
                isBusiness = true;
            }
        }
        if (!isBusiness) {
            // Still recurse into children to find business spans
            if (node.getChildren() != null) {
                for (SpanNodeVO child : node.getChildren()) {
                    flattenForWaterfall(child, spanMap, traceStartEpochMs, result);
                }
            }
            return;
        }

        SpanEntity entity = spanMap.get(node.getSpanId());
        long startOffsetMs = 0;
        if (entity != null && entity.getStartTime() != null) {
            startOffsetMs = entity.getStartTime().toEpochMilli() - traceStartEpochMs;
        }

        String layer = determineLayer(opName);
        String color = getLayerColor(layer);

        // Build a readable label for the waterfall
        String label = buildWaterfallLabel(opName, layer);

        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("spanId", node.getSpanId());
        ws.put("parentSpanId", node.getParentSpanId());
        ws.put("operationName", opName);
        ws.put("label", label);
        ws.put("durationMs", node.getDurationMs());
        ws.put("startOffsetMs", startOffsetMs);
        ws.put("layer", layer);
        ws.put("color", color);
        result.add(ws);

        if (node.getChildren() != null) {
            for (SpanNodeVO child : node.getChildren()) {
                flattenForWaterfall(child, spanMap, traceStartEpochMs, result);
            }
        }
    }

    /**
     * Build a human-readable label for waterfall rows.
     */
    private String buildWaterfallLabel(String opName, String layer) {
        if (opName == null) return "unknown";
        // Extract model name from operation name (e.g. "L0:qwen3-8b" -> "qwen3-8b")
        String model = opName.contains(":") ? opName.substring(opName.indexOf(":") + 1) : "";
        if ("L0".equals(layer)) return "L0 领域路由" + (model.isEmpty() ? "" : " / " + model);
        if ("L1-LLM1".equals(layer)) return "L1-LLM1 会话分类" + (model.isEmpty() ? "" : " / " + model);
        if ("L1-LLM2".equals(layer)) return "L1-LLM2 改写+识别" + (model.isEmpty() ? "" : " / " + model);
        if ("L1".equals(layer)) return "L1 路由" + (model.isEmpty() ? "" : " / " + model);
        if ("L2".equals(layer)) return "L2 业务执行" + (model.isEmpty() ? "" : " / " + model);
        if (opName.contains("/api/bank/chat")) return "总耗时 / HTTP 入口";
        return opName;
    }

    /**
     * 根据 operationName 判断 Agent 层级
     */
    private String determineLayer(String operationName) {
        if (operationName == null) return "L0";
        if (operationName.contains("L1-LLM1")) return "L1-LLM1";
        if (operationName.contains("L1-LLM2")) return "L1-LLM2";
        if (operationName.contains("L0") || operationName.contains("DomainRouter")) return "L0";
        if (operationName.contains("L1") || operationName.contains("Intent")) return "L1";
        if (operationName.contains("L2") || operationName.contains("Service") || operationName.contains("Graph")) return "L2";
        return "L0";
    }

    /**
     * 根据层级返回对应的颜色
     */
    private String getLayerColor(String layer) {
        switch (layer) {
            case "L0": return "#1677ff";  // blue
            case "L1-LLM1": return "#722ed1";  // purple
            case "L1-LLM2": return "#722ed1";  // purple
            case "L1": return "#722ed1";  // purple
            case "L2": return "#52c41a";  // green
            case "HTTP": return "#8c8c8c";  // gray
            default:   return "#1677ff";
        }
    }

    /**
     * 判断字符串是否为业务空值：null / 空字符串 / "N/A" / "未识别" / "未采集"
     */
    private boolean isBlankOrSentinel(String s) {
        if (s == null || s.isBlank()) return true;
        return "N/A".equalsIgnoreCase(s.trim())
                || "未识别".equals(s.trim())
                || "未采集".equals(s.trim());
    }
}
