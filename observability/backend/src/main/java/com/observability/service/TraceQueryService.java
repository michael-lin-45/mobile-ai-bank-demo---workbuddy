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

        // 按 trace_id 去重枚举（不再依赖根 span 语义，规避 OTLP 导出 span 被误判为根导致列表为空）
        List<TraceListVO> all = collectTraceListVOs(from, intent, sessionId, userId, statusCode, limit);
        all.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });
        return all.size() > limit ? all.subList(0, limit) : all;
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
        if (limit <= 0) limit = 500;

        // 按 trace_id 去重枚举（不再依赖根 span 语义，规避 OTLP 导出 span 被误判为根导致列表为空）
        List<TraceListVO> all = collectTraceListVOs(from, intent, sessionId, userId, statusCode, limit);
        all.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });

        int total = all.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int fromIdx = page * size;
        int toIdx = Math.min(fromIdx + size, total);
        List<TraceListVO> pageContent = (fromIdx < total)
                ? all.subList(fromIdx, toIdx)
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

        // 归一化「畸大」span 时长（上游 Core OTel 偶发上报 ~1000x 畸大值），
        // 用子树正常 CLIENT span 的墙钟包络兜底重算，确保 E2E / 业务层 / TTFT 展示正确。
        SpanDurationNormalizer.normalize(spans);

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
        // E2E 总耗时 = HTTP 根 span 时长（与列表 durationMs 一致），业务链时长作为兜底
        long totalDurationMs = (firstSpan.getDurationMs() != null && firstSpan.getDurationMs() > 0)
                ? firstSpan.getDurationMs()
                : ((chainStartMs > 0 && chainEndMs > chainStartMs) ? chainEndMs - chainStartMs : 0);

        String rootService = firstSpan.getServiceName() != null ? firstSpan.getServiceName() : "unknown";

        TraceDetailVO vo = new TraceDetailVO(traceId, spanTree, totalDurationMs, rootService);

        // ── 增强字段：从根 Span url.query 提取 sessionId，仅关联 Session 表取 userId ──
        String sessionId = extractSessionIdFromUrl(firstSpan.getAttributes());
        String userId = null;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                var sessionOpt = sessionRepository.findBySessionId(sessionId);
                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();
                    userId = session.getUserId();
                }
            } catch (Exception e) {
                log.debug("[TraceQuery] Failed to lookup session for traceId={}: {}", traceId, e.getMessage());
            }
        }

        // 兜底：从跨 Span 属性提取 userId / sessionId
        Map<String, String> attrs = extractBusinessAttributes(traceId, firstSpan.getAttributes());
        if (userId == null || userId.isBlank()) {
            userId = attrs.getOrDefault("user_id", attrs.getOrDefault("userId", "未采集"));
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = attrs.getOrDefault("session_id", attrs.getOrDefault("sessionId", "未采集"));
        }

        vo.setUserId(userId);
        vo.setSessionId(sessionId);

        // 单请求视角：意图与 Agent 路径取该 trace 内「最后一段」业务链，
        // 避免把被多轮复用同一个 traceId 的整段会话当成一次请求（表现为"session 内容"）。
        String[] summary = buildTraceSummaryFromSpans(spans);
        vo.setIntent(summary[0] != null ? summary[0] : "未识别");
        vo.setAgentChain(summary[1]);

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

        // ── IO 卡片数据：优先使用 SessionTurn 的 userMessage / aiResponse ──
        // 这是「用户原始请求」与「Agent 最终输出」，而非某层 LLM 的内部 prompt/response。
        com.observability.model.SessionTurn matchedTurn = null;
        try {
            // Strategy 1: match by traceId (most accurate, works for new traces)
            var turnsByTrace = sessionTurnRepository.findByTraceId(traceId);
            if (turnsByTrace != null && !turnsByTrace.isEmpty()) {
                // traceId 可能被多轮对话复用 → 取轮次最靠后的一条，对齐列表展示的「最后一段意图」
                matchedTurn = turnsByTrace.stream()
                        .max(Comparator.comparing(t -> t.getTurnNumber() != null ? t.getTurnNumber() : 0))
                        .orElse(turnsByTrace.get(0));
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

        // 优先用 SessionTurn：请求概要 = 用户原始输入；最终输出 = Agent 最终回答
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

        // 兜底：仅当 SessionTurn 缺失对应字段时，才从 Span 树取。
        // 优先最终执行层（L2）的输出，避免把 L0 领域路由器的内部 prompt/response 当成请求/回答。
        boolean needInput = (vo.getInputText() == null || vo.getInputText().isBlank());
        boolean needOutput = (vo.getOutputText() == null || vo.getOutputText().isBlank());
        if (needInput || needOutput) {
            SpanNodeVO finalSpan = findL2OrLastSpanWithIO(spanTree);
            if (finalSpan != null) {
                if (needInput && finalSpan.getIoPrompt() != null && !finalSpan.getIoPrompt().isBlank()) {
                    Map<String, String> ioInput = new LinkedHashMap<>();
                    ioInput.put("type", "text");
                    ioInput.put("content", finalSpan.getIoPrompt());
                    vo.setIoInput(ioInput);
                    vo.setInputText(finalSpan.getIoPrompt());
                }
                if (needOutput && finalSpan.getIoResponse() != null && !finalSpan.getIoResponse().isBlank()) {
                    Map<String, String> ioOutput = new LinkedHashMap<>();
                    ioOutput.put("type", "text");
                    ioOutput.put("content", finalSpan.getIoResponse());
                    vo.setIoOutput(ioOutput);
                    vo.setOutputText(finalSpan.getIoResponse());
                }
            }
        }

        // ── 瀑布图 Span：展平 Span 树 ──
        Map<String, SpanEntity> spanMap = new HashMap<>();
        for (SpanEntity s : spans) {
            spanMap.put(s.getSpanId(), s);
        }
        // 瀑布图基准 = 整条 trace 最早开始时间（通常是 HTTP 根 span），
        // 使 E2E 条从 0 开始、其余 span 偏移为正，TTFT 虚线对齐。
        long traceStartMs = Long.MAX_VALUE;
        for (var s : chainSpans) {
            if (s.getStartTime() != null) {
                long st = s.getStartTime().toEpochMilli();
                if (st < traceStartMs) traceStartMs = st;
            }
        }
        if (traceStartMs == Long.MAX_VALUE) {
            traceStartMs = (firstSpan.getStartTime() != null) ? firstSpan.getStartTime().toEpochMilli() : 0;
        }
        vo.setWaterfallSpans(buildWaterfallSpans(spanTree, spanMap, traceStartMs));

        // ── 重路由 (reRoute) 检测 ──
        // 一条 trace 内出现 >1 个 L0（领域路由）span → 发生过重路由。
        // 生成完整分层路径（含回环），供前端显示 "↻ 重路由" 横幅。
        int l0Count = 0;
        for (var s : spans) {
            String op = s.getOperationName();
            if (op != null && (op.startsWith("L0:") || op.contains("DomainRouter"))) {
                l0Count++;
            }
        }
        if (l0Count > 1) {
            vo.setRerouted(true);
            vo.setReRoutePath(buildFullAgentChain(spans));
        }

        return vo;
    }

    /**
     * 构建一条 trace 内完整的分层路径（按时间顺序，含重路由回环）。
     * 例如正常: "L0 → L1-LLM1 → L1-LLM2 → L2"
     * 重路由:   "L0 → L1 → L2 ↻ L0 → L1 → L2"
     */
    private String buildFullAgentChain(List<SpanEntity> spans) {
        List<String> layers = new ArrayList<>();
        boolean lastWasL2 = false;
        for (var s : spans) {
            String name = s.getOperationName();
            if (name == null) continue;
            String layer = null;
            if (name.startsWith("L0:") || name.contains("DomainRouter")) layer = "L0";
            else if (name.startsWith("L1-LLM1")) layer = "L1-LLM1";
            else if (name.startsWith("L1-LLM2")) layer = "L1-LLM2";
            else if (name.startsWith("L1:") || name.startsWith("L1-")) layer = "L1";
            else if (name.startsWith("L2:")) layer = "L2";
            if (layer == null) continue;
            // 在 L2 之后再次出现 L0 → 插入回环标记
            if ("L0".equals(layer) && lastWasL2) {
                layers.add("↻");
                lastWasL2 = false;
            }
            layers.add(layer);
            lastWasL2 = "L2".equals(layer);
        }
        return String.join(" → ", layers);
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
     * Compute TTFT = startTime of the FINAL execution LLM (the one producing the
     * user-visible answer) minus the trace root startTime.
     *
     * Rationale: the user only sees tokens streamed from the deepest execution agent
     * (L2 if present, else L1, else L0). Measuring to that span's start approximates
     * "time to first token of the answer" far better than measuring to the first
     * routing LLM (L0), which would yield a near-zero, misleading value.
     */
    private Long computeTTFT(List<SpanEntity> spans) {
        if (spans == null || spans.isEmpty()) return null;

        // Locate the HTTP root span (SERVER /api/bank/chat) as the time base.
        SpanEntity rootSpan = null;
        for (var s : spans) {
            if (s.getParentSpanId() == null || s.getParentSpanId().isEmpty()) {
                if ("SERVER".equals(s.getKind())
                        || (s.getOperationName() != null && s.getOperationName().contains("/api/bank/chat"))) {
                    rootSpan = s;
                    break;
                }
            }
        }
        if (rootSpan == null) rootSpan = spans.get(0);
        if (rootSpan.getStartTime() == null) return null;
        long rootStart = rootSpan.getStartTime().toEpochMilli();

        // Pick the execution span with the highest layer rank (L2 > L1 > L0);
        // tie-break by latest start time (the final answer-generating LLM).
        SpanEntity bestExec = null;
        int bestRank = -1;
        for (var s : spans) {
            String op = s.getOperationName();
            if (op == null || s.getStartTime() == null) continue;
            int rank = layerRank(op);
            if (rank <= 0) continue;
            long st = s.getStartTime().toEpochMilli();
            if (rank > bestRank
                    || (rank == bestRank && (bestExec == null || st > bestExec.getStartTime().toEpochMilli()))) {
                bestRank = rank;
                bestExec = s;
            }
        }
        if (bestExec == null) return null;

        long ttft = bestExec.getStartTime().toEpochMilli() - rootStart;
        // If TTFT is unreasonably large (> 60s), the root span startTime is stale
        // (instrumentation reused traceId across multiple HTTP requests). Returning a
        // fake 0ms would mislead the UI; return null so the frontend shows "-".
        // (With SpanDurationNormalizer correcting stale root/exec starts, a real TTFT is
        //  typically recovered and returned above instead of hitting this guard.)
        if (ttft > 60000) return null;
        return ttft > 0 ? ttft : null;
    }

    /**
     * Layer rank for TTFT selection: L2=3, L1 / L1-LLM* / llm: =2, L0 / DomainRouter =1, else 0.
     */
    private int layerRank(String opName) {
        if (opName.startsWith("L2:")) return 3;
        if (opName.startsWith("L1") || opName.startsWith("llm:")) return 2;
        if (opName.startsWith("L0:") || opName.contains("DomainRouter")) return 1;
        return 0;
    }

    /**
     * Build agent chain from the LAST agent chain spans (not session.intentFlow).
     */
    /**
     * 构建 Agent 链（业务视角）：
     * - L0 → 显示 "L0"
     * - L1 / L1-LLM1 / L1-LLM2 → 合并显示为 "L1"（两个 LLM 调用在内部，对外仍是 L1）
     * - L2 → 显示业务智能体名称（WEALTH / TRANSFER 等），从 span 的 intent/agent.name 推导
     * - reroute 场景按 L0 边界切分为多段，拼接为 "L0 → L1 → WEALTH → L0 → L1 → TRANSFER"
     */
    private String buildAgentChainFromSpans(List<SpanEntity> spans) {
        List<SpanEntity> biz = new ArrayList<>();
        for (var s : spans) {
            String op = s.getOperationName();
            if (op != null && (op.startsWith("L0:") || op.startsWith("L1") || op.startsWith("L2:") || op.startsWith("llm:"))) {
                biz.add(s);
            }
        }
        if (biz.isEmpty()) return null;
        biz.sort(Comparator.comparing(s -> s.getStartTime() == null ? Instant.EPOCH : s.getStartTime()));

        // 按 L0 边界切分多个子链路（reroute 场景）
        List<List<SpanEntity>> segments = new ArrayList<>();
        List<SpanEntity> cur = null;
        for (var s : biz) {
            String op = s.getOperationName();
            boolean isL0 = op.startsWith("L0:") || op.contains("DomainRouter");
            if (isL0 && cur != null && !cur.isEmpty()) {
                segments.add(cur);
                cur = new ArrayList<>();
            }
            if (cur == null) cur = new ArrayList<>();
            cur.add(s);
        }
        if (cur != null && !cur.isEmpty()) segments.add(cur);

        List<String> chain = new ArrayList<>();
        for (var seg : segments) {
            boolean hasL0 = false, hasL1 = false;
            String l2Biz = null;
            for (var s : seg) {
                String op = s.getOperationName();
                if (op.startsWith("L0:") || op.contains("DomainRouter")) hasL0 = true;
                else if (op.startsWith("L1")) hasL1 = true;
                else if (op.startsWith("L2:")) l2Biz = businessNameOf(s);
            }
            if (hasL0) chain.add("L0");
            if (hasL1) chain.add("L1");
            if (l2Biz != null && !l2Biz.isBlank()) chain.add(l2Biz);
        }
        return chain.isEmpty() ? null : String.join(" → ", chain);
    }

    /** L2 业务 span 的业务智能体名称（如 WEALTH / TRANSFER），优先用 intent，回退 agent.name */
    private String businessNameOf(SpanEntity s) {
        Map<String, String> attrs = parseAttributes(s.getAttributes());
        String intent = attrs.get("intent");
        if (intent != null && !intent.isBlank()) {
            String up = intent.toUpperCase();
            if (up.contains("WEALTH")) return "WEALTH";
            if (up.contains("TRANSFER")) return "TRANSFER";
            if (up.contains("BILL")) return "BILL";
            if (up.endsWith("GRAPH")) return intent.substring(0, intent.length() - 5).toUpperCase();
            return intent.toUpperCase();
        }
        String an = attrs.get("agent.name");
        if (an != null && an.contains("WealthInterpret")) return "WEALTH";
        return an != null ? an : "L2";
    }

    /**
     * 构建意图链（业务视角，单轮 trace 只取本 trace 各层的真实识别结果）：
     * - L0 意图 = 领域（WEALTH 等），追加 " Domain" 后缀便于区分
     * - L1-LLM1 意图 = 上下文路由结果（switch-new 等）
     * - L1-LLM2 意图 = 意图识别结果（wealth-filter 等）
     * - L2 意图 = 工作流意图
     * 拼接为 "WEALTH Domain → switch-new → wealth-filter → XXX"
     */
    private String buildIntentChainFromSpans(List<SpanEntity> spans, String sessionDomain) {
        List<SpanEntity> biz = new ArrayList<>();
        for (var s : spans) {
            String op = s.getOperationName();
            if (op != null && (op.startsWith("L0:") || op.startsWith("L1") || op.startsWith("L2:") || op.startsWith("llm:"))) biz.add(s);
        }
        if (biz.isEmpty()) return null;
        biz.sort(Comparator.comparing(s -> s.getStartTime() == null ? Instant.EPOCH : s.getStartTime()));

        // 按 L0 边界切分
        List<List<SpanEntity>> segments = new ArrayList<>();
        List<SpanEntity> cur = null;
        for (var s : biz) {
            String op = s.getOperationName();
            boolean isL0 = op.startsWith("L0:") || op.contains("DomainRouter");
            if (isL0 && cur != null && !cur.isEmpty()) {
                segments.add(cur);
                cur = new ArrayList<>();
            }
            if (cur == null) cur = new ArrayList<>();
            cur.add(s);
        }
        if (cur != null && !cur.isEmpty()) segments.add(cur);

        List<String> parts = new ArrayList<>();
        for (var seg : segments) {
            String l0Intent = null, l1a = null, l1b = null, l2Intent = null, l2Biz = null;
            for (var s : seg) {
                String op = s.getOperationName();
                Map<String, String> a = parseAttributes(s.getAttributes());
                String it = a.get("intent");
                if (op.startsWith("L0:") || op.contains("DomainRouter")) {
                    if (it != null && !it.isBlank()) l0Intent = it;
                } else if (op.startsWith("L1-LLM1")) {
                    l1a = it;
                } else if (op.startsWith("L1-LLM2")) {
                    l1b = it;
                } else if (op.startsWith("L1:")) {
                    if (l1a == null && it != null && !it.isBlank()) l1a = it;
                } else if (op.startsWith("L2:")) {
                    l2Intent = it;
                    l2Biz = businessNameOf(s);
                }
            }
            String domain = l0Intent;
            if (domain == null || domain.isBlank()) domain = l2Biz;
            if (domain == null || domain.isBlank()) domain = sessionDomain;
            if (domain == null || domain.isBlank()) domain = "未识别";
            parts.add(domain + " Domain");
            if (l1a != null && !l1a.isBlank()) parts.add(l1a);
            if (l1b != null && !l1b.isBlank()) parts.add(l1b);
            if (l2Intent != null && !l2Intent.isBlank()) parts.add(l2Intent);
        }
        return parts.isEmpty() ? null : String.join(" → ", parts);
    }

    /**
     * 从 trace 的 spans 推导「单请求视角」的意图与 Agent 路径。
     *
     * 一条 trace 的 traceId 可能被多轮对话复用（历史数据缺陷），
     * 此时按 L0 边界切分为多段，取【最后一段】作为该 trace 所代表的那一次用户请求。
     *
     * @return String[2] — [0]=意图(单一领域/业务标识), [1]=Agent 路径(如 "L0 → L1 → WEALTH")
     */
    private String[] buildTraceSummaryFromSpans(List<SpanEntity> spans) {
        List<SpanEntity> biz = new ArrayList<>();
        for (var s : spans) {
            String op = s.getOperationName();
            if (op != null && (op.startsWith("L0:") || op.startsWith("L1") || op.startsWith("L2:") || op.startsWith("llm:"))) {
                biz.add(s);
            }
        }
        if (biz.isEmpty()) return new String[]{ "未识别", null };
        biz.sort(Comparator.comparing(s -> s.getStartTime() == null ? Instant.EPOCH : s.getStartTime()));

        // 按 L0 边界切分为多段（reroute / 多轮复用）
        List<List<SpanEntity>> segments = new ArrayList<>();
        List<SpanEntity> cur = null;
        for (var s : biz) {
            String op = s.getOperationName();
            boolean isL0 = op.startsWith("L0:") || op.contains("DomainRouter");
            if (isL0 && cur != null && !cur.isEmpty()) {
                segments.add(cur);
                cur = new ArrayList<>();
            }
            if (cur == null) cur = new ArrayList<>();
            cur.add(s);
        }
        if (cur != null && !cur.isEmpty()) segments.add(cur);
        if (segments.isEmpty()) segments.add(biz);

        // 取最后一段（最近一次用户请求）
        List<SpanEntity> seg = segments.get(segments.size() - 1);

        // 意图：优先 L2 业务名，其次 L0 领域
        String intent = null;
        String l0Domain = null;
        for (var s : seg) {
            String op = s.getOperationName();
            Map<String, String> a = parseAttributes(s.getAttributes());
            if (op.startsWith("L0:") || op.contains("DomainRouter")) {
                String it = a.get("intent");
                if (it != null && !it.isBlank()) l0Domain = normalizeDomain(it);
            }
            if (op.startsWith("L2:")) {
                String bizName = businessNameOf(s);
                if (bizName != null && !bizName.isBlank()) intent = normalizeDomain(bizName);
            }
        }
        if (intent == null) intent = l0Domain != null ? l0Domain : "未识别";

        // Agent 路径：L0 → L1 → (L2 业务名)
        boolean hasL0 = false, hasL1 = false;
        String l2Biz = null;
        for (var s : seg) {
            String op = s.getOperationName();
            if (op.startsWith("L0:") || op.contains("DomainRouter")) hasL0 = true;
            else if (op.startsWith("L1")) hasL1 = true;
            else if (op.startsWith("L2:")) l2Biz = businessNameOf(s);
        }
        List<String> chain = new ArrayList<>();
        if (hasL0) chain.add("L0");
        if (hasL1) chain.add("L1");
        if (l2Biz != null && !l2Biz.isBlank()) chain.add(l2Biz);
        String agentChain = chain.isEmpty() ? null : String.join(" → ", chain);

        return new String[]{ intent, agentChain };
    }

    /** 将任意 intent / 业务名归一为单一领域标识（与前端意图 Badge 取值一致） */
    private String normalizeDomain(String intent) {
        if (intent == null || intent.isBlank()) return "未识别";
        String up = intent.toUpperCase();
        if (up.contains("WEALTH")) return "WEALTH";
        if (up.contains("TRANSFER")) return "TRANSFER";
        if (up.contains("BILL")) return "BILL_QUERY";
        if (up.contains("CHAT")) return "CHAT";
        if (up.contains("UNSUPPORTED")) return "UNSUPPORTED";
        return "未识别";
    }

    /**
     * 在 Span 树中查找用于兜底「最终输出」的节点：
     * 优先返回最深层的、带 ioResponse 的业务 span（L2 > L1 > L0），
     * 避免把 L0 路由器的内部响应当成回答。
     */
    private SpanNodeVO findL2OrLastSpanWithIO(List<SpanNodeVO> tree) {
        SpanNodeVO best = null;
        int bestRank = -1;
        for (var node : tree) {
            SpanNodeVO f = findL2OrLastSpanWithIORec(node);
            if (f != null) {
                int rank = nodeLayerRank(f);
                if (rank > bestRank) { bestRank = rank; best = f; }
            }
        }
        return best;
    }

    private SpanNodeVO findL2OrLastSpanWithIORec(SpanNodeVO node) {
        SpanNodeVO found = null;
        if (node.getChildren() != null) {
            for (var c : node.getChildren()) {
                SpanNodeVO f = findL2OrLastSpanWithIORec(c);
                if (f != null) found = f;
            }
        }
        if (found != null) return found;
        boolean hasIo = (node.getIoPrompt() != null && !node.getIoPrompt().isBlank())
                || (node.getIoResponse() != null && !node.getIoResponse().isBlank());
        return hasIo ? node : null;
    }

    private int nodeLayerRank(SpanNodeVO node) {
        String op = node.getOperationName();
        if (op == null) return 0;
        if (op.startsWith("L2:")) return 3;
        if (op.startsWith("L1")) return 2;
        if (op.startsWith("L0:") || op.contains("DomainRouter")) return 1;
        return 0;
    }

    private String lastIntentOf(String flow) {
        if (flow == null || flow.isBlank()) return null;
        String[] parts = flow.split("\\s*→\\s*");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i].trim();
        }
        return null;
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
            Map<String, String> raw = objectMapper.readValue(attributesJson, new TypeReference<Map<String, String>>() {});
            // 清洗：session_id / sessionId / user_id / userId 可能携带换行或首尾空白
            // （Core 早期未在入口 trim，导致 span 属性带 \n）。读取时统一 trim，
            // 避免 trace 列表/详情显示 test3\n 以及 trace→session 关联失败。
            for (String key : new String[]{"session_id", "sessionId", "user_id", "userId"}) {
                String v = raw.get(key);
                if (v != null) {
                    String t = v.trim();
                    if (t.isEmpty()) raw.remove(key);
                    else if (!t.equals(v)) raw.put(key, t);
                }
            }
            return raw;
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
            // Core 实际 emit 的是 ai.token.input / ai.token.output
            int sys = parseIntSafe(attrs.get("ai.token.system"));
            int ctx = parseIntSafe(attrs.get("ai.token.context"));
            int in = parseIntSafe(attrs.get("ai.token.input"));
            int out = parseIntSafe(attrs.get("ai.token.output"));
            if (sys > 0 || ctx > 0 || in > 0 || out > 0) {
                return new TokenBreakdownVO(sys, ctx, in, out);
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

    /**
     * 聚合一条 trace 内所有业务 span（L0:/L1-/L2:/llm:）的 token 总量。
     * 业务 token 由 Core 在 LLM span 上以 ai.token.input / ai.token.output 注入。
     */
    private long aggregateTokenTotal(List<SpanEntity> spans) {
        long total = 0;
        for (var s : spans) {
            String op = s.getOperationName();
            if (op == null) continue;
            if (op.startsWith("L0:") || op.startsWith("L1") || op.startsWith("L2:") || op.startsWith("llm:")) {
                Map<String, String> a = parseAttributes(s.getAttributes());
                total += parseIntSafe(a.get("ai.token.input"));
                total += parseIntSafe(a.get("ai.token.output"));
                total += parseIntSafe(a.get("ai.token.system"));
                total += parseIntSafe(a.get("ai.token.context"));
            }
        }
        return total;
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
        List<SpanEntity> traceSpans = spanRepository.findByTraceIdOrderByStartTimeAsc(traceId);
        String traceAgentChain = buildAgentChainFromSpans(traceSpans);
        vo.setAgentChain(traceAgentChain);

        // TTFT — 从 span 链计算（与详情一致），仅兜底读根 span 属性
        Long ttftVal = computeTTFT(traceSpans);
        if (ttftVal != null) {
            vo.setTtftMs(ttftVal);
        } else {
            String ttftStr = attrs.get("llm.first_token_time");
            if (ttftStr != null) {
                try { vo.setTtftMs(Long.parseLong(ttftStr)); } catch (NumberFormatException e) { /* ignore */ }
            }
        }

        // Token 总量 — 聚合业务 span（L0:/L1-/L2:/llm:）的 ai.token.input/output
        long tokenTotal = aggregateTokenTotal(traceSpans);
        if (tokenTotal <= 0) {
            int sysToken = parseIntSafe(attrs.get("ai.token.system"));
            int ctxToken = parseIntSafe(attrs.get("ai.token.context"));
            int outToken = parseIntSafe(attrs.get("ai.token.output"));
            tokenTotal = (long) sysToken + ctxToken + outToken;
        }
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

    /**
     * 按 trace_id 去重枚举 trace 列表（核心修复：不再依赖"根 span"语义）。
     * 当前数据形态：真实请求入口的 HTTP SERVER span 未被导出/命名，
     * 所有 parentSpanId 为空的"根 span"都是 Core OTel 导出器发往 Collector 的 OTLP 导出 span，
     * 导致旧逻辑 findRootSpansByTimeRange + findBusinessRootSpansByTimeRange 全部落空 → 列表为空。
     * 改为枚举去重 trace_id，对每个 trace 的 spans 聚合出列表 VO。
     */
    private List<TraceListVO> collectTraceListVOs(Instant from, String intent, String sessionId,
                                                  String userId, String statusCode, int limit) {
        int cap = Math.min(Math.max(limit * 4, 500), 2000);
        List<String> traceIds = spanRepository.findDistinctTraceIdsSince(from);
        if (traceIds.size() > cap) traceIds = traceIds.subList(0, cap);

        List<TraceListVO> result = new ArrayList<>();
        for (String tid : traceIds) {
            List<SpanEntity> spans = spanRepository.findByTraceIdOrderByStartTimeAsc(tid);
            if (spans.isEmpty()) continue;
            TraceListVO vo = buildTraceListVOFromSpans(spans);
            if (vo == null) continue; // 无业务 span（OTLP 导出自 trace / 无埋点请求）→ 跳过

            // 意图过滤
            if (intent != null && !intent.isBlank()) {
                if (vo.getIntent() == null || !vo.getIntent().toUpperCase().contains(intent.toUpperCase())) continue;
            }
            // sessionId 过滤
            if (sessionId != null && !sessionId.isBlank()) {
                if (vo.getSessionId() == null || !vo.getSessionId().contains(sessionId)) continue;
            }
            // userId 过滤
            if (userId != null && !userId.isBlank()) {
                if (vo.getUserId() == null || !vo.getUserId().contains(userId)) continue;
            }
            // statusCode 过滤
            if (statusCode != null && !statusCode.isBlank()) {
                if (vo.getStatusCode() == null || !vo.getStatusCode().equalsIgnoreCase(statusCode)) continue;
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 从一个 trace 的全部 spans 聚合出列表 VO（不依赖根 span）。
     * 仅当 trace 含至少一条业务 span（L0/L1/L2/llm）时才返回，否则返回 null（排除 OTLP 导出自 trace）。
     */
    private TraceListVO buildTraceListVOFromSpans(List<SpanEntity> spans) {
        if (spans == null || spans.isEmpty()) return null;
        // 归一化「畸大」span 时长（见 SpanDurationNormalizer），使列表「耗时」展示真实量级。
        SpanDurationNormalizer.normalize(spans);
        String traceId = spans.get(0).getTraceId();

        // 必须有业务 span，否则是 OTLP 导出自 trace / 无埋点请求 → 跳过
        boolean hasBusiness = false;
        SpanEntity firstBiz = null;
        for (var s : spans) {
            String op = s.getOperationName();
            if (op != null && (op.startsWith("L0:") || op.startsWith("L1") || op.startsWith("L2:") || op.startsWith("llm:"))) {
                hasBusiness = true;
                if (firstBiz == null) firstBiz = s;
            }
        }
        if (!hasBusiness) return null;

        TraceListVO vo = new TraceListVO();
        vo.setTraceId(traceId);

        SpanEntity first = spans.get(0);
        vo.setTimestamp(first.getStartTime());

        // E2E 耗时：业务 span 首末时间差；无则取首 span durationMs
        long startMs = Long.MAX_VALUE, endMs = Long.MIN_VALUE;
        for (var s : spans) {
            String op = s.getOperationName();
            if (op != null && (op.startsWith("L0:") || op.startsWith("L1") || op.startsWith("L2:") || op.startsWith("llm:"))) {
                if (s.getStartTime() != null) startMs = Math.min(startMs, s.getStartTime().toEpochMilli());
                if (s.getEndTime() != null) endMs = Math.max(endMs, s.getEndTime().toEpochMilli());
            }
        }
        long duration = (startMs != Long.MAX_VALUE && endMs > startMs) ? (endMs - startMs)
                : (first.getDurationMs() != null ? first.getDurationMs() : 0);
        vo.setDurationMs(duration);

        // 状态码：有 ERROR span → ERROR；否则取首个非 UNSET 状态；否则 OK
        String status = "OK";
        for (var s : spans) {
            String sc = s.getStatusCode();
            if ("ERROR".equalsIgnoreCase(sc)) { status = "ERROR"; break; }
        }
        if (!"ERROR".equalsIgnoreCase(status)) {
            for (var s : spans) {
                String sc = s.getStatusCode();
                if (sc != null && !sc.isBlank() && !"UNSET".equalsIgnoreCase(sc)) { status = sc; break; }
            }
        }
        vo.setStatusCode(status);
        vo.setStatusDot("ERROR".equalsIgnoreCase(status) ? "ERR" : "OK");

        // sessionId / userId：优先业务 span 属性，回退 Session 表
        SpanEntity biz = firstBiz != null ? firstBiz : first;
        String sessionId = extractSessionIdFromUrl(biz.getAttributes());
        Map<String, String> attrs = parseAttributes(biz.getAttributes());
        if (sessionId == null || sessionId.isBlank()) sessionId = attrs.getOrDefault("session_id", attrs.getOrDefault("sessionId", null));
        String userId = attrs.getOrDefault("user_id", attrs.getOrDefault("userId", null));
        String sessionDomain = null;

        if (sessionId != null && !sessionId.isBlank()) {
            try {
                var sessionOpt = sessionRepository.findBySessionId(sessionId);
                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();
                    if (userId == null || userId.isBlank()) userId = session.getUserId();
                    sessionDomain = lastIntentOf(session.getIntentFlow());
                }
            } catch (Exception e) {
                log.debug("[TraceQuery] collect: Failed to lookup session for traceId={}: {}", traceId, e.getMessage());
            }
        }

        vo.setUserId(userId);
        vo.setSessionId(sessionId);

        // 单请求视角：意图与 Agent 路径取该 trace 内「最后一段」业务链，
        // 避免把被多轮复用同一个 traceId 的整段会话当成一次请求（表现为"session 内容"）。
        String[] summary = buildTraceSummaryFromSpans(spans);
        vo.setIntent(summary[0] != null ? summary[0] : "未识别");
        vo.setAgentChain(summary[1]);
        Long ttft = computeTTFT(spans);
        vo.setTtftMs(ttft != null ? ttft : null);
        long tokens = aggregateTokenTotal(spans);
        vo.setTokenTotal(tokens > 0 ? tokens : null);

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
        }
        // NOTE: the HTTP root span (SERVER /api/bank/chat) is intentionally excluded here.
        // The E2E 总耗时 bar is rendered by the frontend WaterfallChart from totalMs,
        // so including the HTTP root would create a duplicate, mislabeled E2E row.
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
        if ("L0".equals(layer)) return "L0 领域路由 · LLM选L1" + (model.isEmpty() ? "" : " / " + model);
        if ("L1-LLM1".equals(layer)) return "L1-LLM1 上下文分类" + (model.isEmpty() ? "" : " / " + model);
        if ("L1-LLM2".equals(layer)) return "L1-LLM2 意图改写+识别" + (model.isEmpty() ? "" : " / " + model);
        if ("L1".equals(layer)) return "L1 业务路由" + (model.isEmpty() ? "" : " / " + model);
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
