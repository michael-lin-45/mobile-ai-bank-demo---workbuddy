package com.observability.service;

import com.observability.dto.ApiResponse;
import com.observability.model.Session;
import com.observability.model.SessionTurn;
import com.observability.repository.SessionRepository;
import com.observability.repository.SessionTurnRepository;
import com.observability.repository.SpanRepository;
import com.observability.model.SpanEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 会话查询服务 — 列表 + 详情
 */
@Slf4j
@Service
public class SessionService {

    /** 全局时区：北京时间（东八区）。所有面向前端的 Instant 展示均按此时区格式化。 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BJ_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING);

    private final SessionRepository sessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final SpanRepository spanRepository;
    private final RedisMetricsService redisMetricsService;

    public SessionService(SessionRepository sessionRepository,
                          SessionTurnRepository sessionTurnRepository,
                          SpanRepository spanRepository,
                          RedisMetricsService redisMetricsService) {
        this.sessionRepository = sessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.spanRepository = spanRepository;
        this.redisMetricsService = redisMetricsService;
    }

    /**
     * 会话列表（分页 + 多条件筛选）
     *
     * @param from      开始时间
     * @param to        结束时间
     * @param intent    意图筛选（按 intentFlow LIKE 匹配）
     * @param agentLevel Agent层级筛选（L0/L1/L2）
     * @param status    状态筛选
     * @param page      页码（0-based）
     * @param size      每页大小
     * @return 分页结果 Map
     */
    public Map<String, Object> listSessions(Instant from, Instant to, String intent,
                                             String agentLevel, String status,
                                             String sessionId, String userId, String channel,
                                             int page, int size) {
        if (from == null) from = Instant.now().minusSeconds(7 * 86400);
        if (to == null) to = Instant.now();
        if (size <= 0) size = 20;
        if (size > 200) size = 200;
        if (page < 0) page = 0;

        // 捕获为 effectively-final 局部变量，供下方 Specification lambda 使用
        final Instant fromFinal = from;
        final Instant toFinal = to;

        // 归一化筛选入参（去除首尾空白/换行，兼容 trace 携带的 sessionId 末尾 \n）
        String sidFilter = (sessionId != null && !sessionId.isBlank()) ? sessionId.trim() : null;
        String uidFilter = (userId != null && !userId.isBlank()) ? userId.trim() : null;
        String chFilter  = (channel != null && !channel.isBlank()) ? channel.trim() : null;
        String intentFilter = (intent != null && !intent.isBlank()) ? intent.trim() : null;
        String lvlFilter = (agentLevel != null && !agentLevel.isBlank()) ? agentLevel.trim() : null;
        String statusFilter = (status != null && !status.isBlank()) ? status.trim() : null;

        // T-D (T29)：DB 侧分页 + 多条件筛选，避免全量内存加载（大 offset 不再 OOM / 500）。
        // 构造 Specification，将时间范围、等值（sessionId/userId/channel/status）、
        // 模糊（intentFlow LIKE）条件全部下推到数据库。
        Specification<Session> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
            if (fromFinal != null) preds.add(cb.greaterThanOrEqualTo(root.get("startTime"), fromFinal));
            if (toFinal != null) preds.add(cb.lessThanOrEqualTo(root.get("startTime"), toFinal));
            if (sidFilter != null) preds.add(cb.equal(root.get("sessionId"), sidFilter));
            if (uidFilter != null) preds.add(cb.equal(root.get("userId"), uidFilter));
            if (chFilter != null) preds.add(cb.equal(root.get("channel"), chFilter));
            if (statusFilter != null) preds.add(cb.equal(root.get("status"), statusFilter));
            if (intentFilter != null) {
                preds.add(cb.like(cb.upper(root.get("intentFlow")),
                        "%" + intentFilter.toUpperCase().replace("%", "\\%") + "%"));
            }
            if (lvlFilter != null) {
                preds.add(cb.like(root.get("intentFlow"),
                        "%" + lvlFilter.replace("%", "\\%") + "%"));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Session> pageResult = sessionRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime")));

        List<Session> pageSlice = pageResult.getContent();
        long totalElements = pageResult.getTotalElements();
        int totalPages = pageResult.getTotalPages();

        // 批量加载 token（仅当前页切片）
        Map<String, Long> sessionTokenMap = new HashMap<>();
        for (Session s : pageSlice) {
            long totalTokens = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(s.getSessionId())
                    .stream()
                    .mapToLong(t -> t.getTokens() != null ? t.getTokens() : 0)
                    .sum();
            if (totalTokens == 0) {
                totalTokens = sumTokensFromSpans(s.getSessionId());
            }
            sessionTokenMap.put(s.getSessionId(), totalTokens);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        for (Session s : pageSlice) {
            content.add(toSessionListVO(s, sessionTokenMap.get(s.getSessionId())));
        }

        return Map.of(
                "content", content,
                "totalElements", totalElements,
                "totalPages", totalPages,
                "number", page
        );
    }

    public Map<String, Object> getSessionDetail(String sessionId) {
        Session session = sessionRepository.findBySessionId(sessionId)
                .orElse(null);
        if (session == null) {
            return null;
        }

        List<SessionTurn> turns = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);

        Map<String, Object> detail = toSessionDetailVO(session);

        List<Map<String, Object>> turnVOs = new ArrayList<>();
        for (SessionTurn turn : turns) {
            turnVOs.add(toTurnVO(turn));
        }
        detail.put("turns", turnVOs);

        return detail;
    }

    // ── Upsert (Session Bridge) ──

    /**
     * 接收主应用桥接的会话数据，对 sessions 表做 upsert（同 sessionId 覆盖写）
     */
    @SuppressWarnings("unchecked")
    public void upsertSession(Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        if (sessionId != null) sessionId = sessionId.trim();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[SessionBridge] upsertSession: sessionId is empty");
            return;
        }

        Session session = sessionRepository.findBySessionId(sessionId).orElse(null);
        Instant now = Instant.now();

        if (session == null) {
            session = new Session();
            session.setSessionId(sessionId);
            session.setStartTime(now);
            session.setTurnCount(0);
            session.setChannel("chat");
        }

        // 更新元信息
        String userId = (String) body.getOrDefault("userId", extractUserId(sessionId));
        session.setUserId(userId);

        // 实时指标：DAU（按 userId 去重，幂等）+ 在线用户（5 分钟滑动窗口）
        if (!"unknown".equals(userId)) {
            redisMetricsService.recordDauUser(userId);
            redisMetricsService.recordOnlineUser(userId);
        }

        // 累计轮次
        Integer turnCount = session.getTurnCount();
        session.setTurnCount(turnCount != null ? turnCount + 1 : 1);

        // 追加意图流：优先使用显式传入的 intentFlow，否则按 intent 追加
        String intentFlowIn = (String) body.get("intentFlow");
        if (intentFlowIn != null && !intentFlowIn.isBlank()) {
            session.setIntentFlow(intentFlowIn);
        } else {
            String intent = (String) body.getOrDefault("intent", "UNKNOWN");
            String flow = session.getIntentFlow();
            session.setIntentFlow(flow != null ? flow + " → " + intent : intent);
        }

        // 待定项 B：reRoute 原报文透传。reRouted / originalQuery 透传，仅落 rerouteTriggered 标记；
        // originalQuery 按设计 §6.2 不新增列（仅透传，回填原始 query），故不持久化。
        Boolean reRouted = toBoolean(body.get("reRouted"));
        String originalQuery = (String) body.get("originalQuery");
        boolean sessionReroute = Boolean.TRUE.equals(reRouted);
        if (originalQuery != null && !originalQuery.isBlank()) {
            log.debug("[SessionBridge] reRoute originalQuery 透传: sessionId={}, originalQuery={}",
                    sessionId, originalQuery);
        }

        // 更新状态
        String status = (String) body.getOrDefault("status", "active");
        session.setStatus(status);

        session.setEndTime(now);
        if (session.getStartTime() != null) {
            session.setDurationSeconds(now.getEpochSecond() - session.getStartTime().getEpochSecond());
        }

        // 累计 tokens
        Object tokensObj = body.get("tokens");
        long tokens = tokensObj instanceof Number ? ((Number) tokensObj).longValue() : 0L;
        Long existing = session.getTotalTokens();
        session.setTotalTokens(existing != null ? existing + tokens : tokens);

        sessionRepository.save(session);

        // 构建 turns：优先使用 body.turns 数组（QA 测试 / 未来桥接约定），
        // 否则从顶层字段构造单轮（兼容 Core SessionBridge 既有调用）。
        List<Map<String, Object>> turns = toMapList(body.get("turns"));
        if (turns.isEmpty()) {
            turns = List.of(singleTurnFromBody(body));
        }

        boolean anyTurnReroute = false;
        for (Map<String, Object> t : turns) {
            Boolean tReroute = toBoolean(t.get("rerouteTriggered"));
            if (Boolean.TRUE.equals(tReroute)) anyTurnReroute = true;

            SessionTurn turn = new SessionTurn();
            turn.setSessionId(sessionId);
            turn.setTurnNumber(session.getTurnCount());
            turn.setUserMessage(str(t.get("userMessage"), str(t.get("userInput"), "")));
            turn.setAiResponse(str(t.get("aiResponse"), ""));
            turn.setIntent(str(t.get("intent"), "UNKNOWN"));
            turn.setAgentPath(str(t.get("agentPath"), ""));
            turn.setConfidence(toDouble(t.get("confidence")));
            turn.setDurationMs(toLong(t.get("durationMs")));
            turn.setTokens(toLong(t.get("tokens")).intValue());
            turn.setTraceId(str(t.get("traceId"), ""));
            turn.setStatus(str(t.get("status"), status));
            turn.setRerouteTriggered(Boolean.TRUE.equals(tReroute));
            turn.setTimestamp(now);
            sessionTurnRepository.save(turn);
        }

        // 会话级 reroute 标记：任意来源为真即标记
        session.setRerouteTriggered(sessionReroute || anyTurnReroute);
        sessionRepository.save(session);

        log.info("[SessionBridge] Upserted session: sessionId={}, turn={}, status={}, reRouted={}",
                sessionId, session.getTurnCount(), status,
                Boolean.TRUE.equals(session.getRerouteTriggered()));
    }

    /** 从顶层 body 字段构造单轮（兼容 Core SessionBridge 既有调用约定） */
    private Map<String, Object> singleTurnFromBody(Map<String, Object> body) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("userMessage", body.getOrDefault("userInput", ""));
        t.put("aiResponse", body.getOrDefault("aiResponse", ""));
        t.put("intent", body.getOrDefault("intent", "UNKNOWN"));
        t.put("agentPath", body.getOrDefault("agentPath", ""));
        t.put("confidence", body.get("confidence"));
        t.put("durationMs", body.get("durationMs"));
        t.put("tokens", body.get("tokens"));
        t.put("traceId", body.get("traceId"));
        t.put("status", body.getOrDefault("status", "active"));
        t.put("rerouteTriggered", body.get("reRouted"));
        return t;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object obj) {
        if (obj instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    result.add((Map<String, Object>) m);
                }
            }
            return result;
        }
        return List.of();
    }

    private Boolean toBoolean(Object obj) {
        if (obj instanceof Boolean b) return b;
        if (obj instanceof Number n) return n.intValue() != 0;
        if (obj instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
        return null;
    }

    private String str(Object obj, String def) {
        return obj != null ? obj.toString() : def;
    }

    private Double toDouble(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (Exception e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private Long toLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        if (obj instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (Exception e) {
                return 0L;
            }
        }
        return 0L;
    }

    private String extractUserId(String sessionId) {
        if (sessionId == null) return "unknown";
        int dash = sessionId.lastIndexOf('-');
        return dash > 0 ? sessionId.substring(0, dash) : sessionId;
    }

    // ── VO Builders ──

    private Map<String, Object> toSessionListVO(Session s, Long totalTokens) {
        Map<String, Object> vo = new LinkedHashMap<>();
        String intentFlow = s.getIntentFlow();
        Long durationMs = s.getDurationSeconds() != null ? s.getDurationSeconds() * 1000 : 0;
        Long tokenVal = totalTokens != null ? totalTokens : 0L;

        vo.put("sessionId", s.getSessionId());
        vo.put("userId", s.getUserId());
        vo.put("channel", s.getChannel());
        vo.put("turnCount", s.getTurnCount());
        vo.put("agentChain", intentFlow);
        vo.put("totalDurationMs", durationMs);
        vo.put("totalTokenInput", tokenVal);
        vo.put("totalTokenOutput", 0L);
        vo.put("status", s.getStatus());
        vo.put("statusLabel", mapStatusLabel(s.getStatus()));
        vo.put("createdAt", s.getStartTime() != null ? s.getStartTime().toString() : null);
        vo.put("updatedAt", s.getEndTime() != null ? s.getEndTime().toString() : null);

        // Frontend-aligned aliases (SessionTable.jsx expects these names)
        vo.put("rounds", s.getTurnCount());
        vo.put("duration", durationMs);
        vo.put("tokens", tokenVal);
        vo.put("time", s.getStartTime() != null ? s.getStartTime().toString() : null);
        // 意图（取首个，用于筛选/徽标）
        if (intentFlow != null) {
            String[] parts = intentFlow.split("\\s*→\\s*");
            vo.put("intent", parts.length > 0 ? parts[0] : intentFlow);
            vo.put("domainSwitches", countDomainSwitches(parts));
        } else {
            vo.put("intent", null);
            vo.put("domainSwitches", 0);
        }
        // 执行智能体：每轮最终 L2 业务名（无 L2 则 L1），连续相同折叠交由前端 dedupAgents
        vo.put("agents", buildExecutingAgents(s.getSessionId()));

        return vo;
    }

    /**
     * 构建「执行智能体」列表（用于会话回放列表的「执行智能体」列）：
     * - 每轮对话取该轮最终到达的业务智能体名称：
     *   - 若本轮 intent 为业务领域（WEALTH/TRANSFER/BILL...）→ 即该轮的 L2 业务名
     *   - 否则（CHAT/UNKNOWN 等，未到达 L2）→ 显示 "L1"（代表意图识别/路由异常）
     * - 与 Session.intentFlow（原始意图流）区分：intentFlow 用于意图统计，
     *   本列表用于展示每轮实际执行的智能体，连续相同由前端 dedupAgents 折叠。
     */
    private List<String> buildExecutingAgents(String sessionId) {
        List<String> exec = new ArrayList<>();
        try {
            List<SessionTurn> turns = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
            for (var t : turns) {
                String intent = t.getIntent();
                exec.add(isBusinessDomain(intent) ? intent : "L1");
            }
        } catch (Exception e) {
            log.debug("[Session] Failed to build executing agents for sessionId={}: {}", sessionId, e.getMessage());
        }
        return exec;
    }

    private boolean isBusinessDomain(String intent) {
        if (intent == null || intent.isBlank()) return false;
        String up = intent.toUpperCase();
        return !up.equals("CHAT") && !up.equals("UNKNOWN") && !up.equals("UNSUPPORTED");
    }

    private Map<String, Object> toSessionDetailVO(Session s) {
        Map<String, Object> vo = new LinkedHashMap<>();
        String intentFlow = s.getIntentFlow();
        Long durationMs = s.getDurationSeconds() != null ? s.getDurationSeconds() * 1000 : 0;
        Long tokenVal = s.getTotalTokens() != null ? s.getTotalTokens() : 0;
        if (tokenVal == 0) {
            tokenVal = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(s.getSessionId())
                    .stream()
                    .mapToLong(t -> t.getTokens() != null ? t.getTokens() : 0)
                    .sum();
        }
        if (tokenVal == 0) {
            tokenVal = sumTokensFromSpans(s.getSessionId());
        }

        vo.put("sessionId", s.getSessionId());
        vo.put("userId", s.getUserId());
        vo.put("channel", s.getChannel());
        vo.put("turnCount", s.getTurnCount());
        vo.put("agentChain", intentFlow);
        vo.put("totalDurationMs", durationMs);
        vo.put("totalTokenInput", tokenVal);
        vo.put("totalTokenOutput", 0L);
        vo.put("status", s.getStatus());
        vo.put("satisfactionRating", s.getSatisfactionRating());
        vo.put("satisfactionReason", s.getSatisfactionReason());
        vo.put("rerouteTriggered", Boolean.TRUE.equals(s.getRerouteTriggered())); // 待定项 B

        // Frontend-aligned aliases (SessionDetailModal.jsx expects these names)
        vo.put("duration", durationMs);
        vo.put("durationText", formatDuration(durationMs));
        vo.put("timeRange", formatTimeRange(s.getStartTime(), s.getEndTime()));
        vo.put("domainSwitches", countDomainSwitchesFromFlow(intentFlow));
        vo.put("tokens", tokenVal);

        // Summary object for modal footer
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("intentFlow", intentFlow != null ? intentFlow : "-");
        summary.put("domainSwitches", countDomainSwitchesFromFlow(intentFlow));
        summary.put("totalDuration", formatDuration(durationMs));
        summary.put("totalTokens", tokenVal);
        vo.put("summary", summary);

        return vo;
    }

    private Map<String, Object> toTurnVO(SessionTurn t) {
        Map<String, Object> vo = new LinkedHashMap<>();
        // P0-2 整改：从 trace spans 解析真实 model 与 TTFT（替换原硬编码 0L / "qwen-plus"）
        String resolvedModel = "未知";
        long resolvedTtft = 0L;
        try {
            if (t.getTraceId() != null) {
                List<SpanEntity> spans = spanRepository.findByTraceIdOrderByStartTimeAsc(t.getTraceId());
                if (spans != null && !spans.isEmpty()) {
                    SpanEntity firstSpan = spans.get(0);
                    SpanEntity llmSpan = null;
                    for (SpanEntity s : spans) {
                        String op = s.getOperationName();
                        // P0-2 增强：不限定 qwen，凡 L0: 前缀的 span 即视为真实 LLM 模型调用，
                        // 取首个作为本轮模型名（兼容 gpt-4o / claude / deepseek 等任意供应商）。
                        if (op != null && op.startsWith("L0:")) {
                            resolvedModel = op.length() > 3 ? op.substring(3) : "";
                            llmSpan = s;
                            break;
                        }
                    }
                    if (llmSpan == null) resolvedModel = "规则模式(确定性路由)";
                    if (llmSpan != null && firstSpan.getStartTime() != null && llmSpan.getStartTime() != null) {
                        resolvedTtft = java.time.Duration.between(firstSpan.getStartTime(), llmSpan.getStartTime()).toMillis();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SessionService] resolve model/ttft failed for trace={}: {}", t.getTraceId(), e.getMessage());
        }
        vo.put("turnNumber", t.getTurnNumber());
        vo.put("traceId", t.getTraceId());
        // Frontend SessionDetailModal expects userMessage / aiMessage (NOT userQuery / aiResponse)
        vo.put("userMessage", t.getUserMessage());
        vo.put("aiMessage", t.getAiResponse());
        vo.put("intent", t.getIntent());
        vo.put("agentLevel", extractAgentLevel(t.getAgentPath()));
        vo.put("agentPath", t.getAgentPath());
        vo.put("agentName", t.getAgentPath()); // keep backward compat
        vo.put("duration", t.getDurationMs());
        vo.put("durationMs", t.getDurationMs()); // keep backward compat
        vo.put("tokenInput", t.getTokens() != null ? t.getTokens() : 0);
        vo.put("tokenOutput", 0);
        vo.put("tokens", t.getTokens() != null ? t.getTokens() : 0); // alias used by SessionDetailModal
        vo.put("ttftMs", resolvedTtft); // P0-2 整改：真实 TTFT（首 LLM span 起始 - 首 span 起始，毫秒）
        vo.put("rerouteTriggered", Boolean.TRUE.equals(t.getRerouteTriggered())); // 待定项 B：读真实字段，不再硬编码 false
        vo.put("businessOutcome", t.getStatus());
        vo.put("status", t.getStatus()); // used by SessionDetailModal for turn status badges
        // Additional fields used by SessionDetailModal
        vo.put("time", t.getTimestamp() != null ? BJ_FMT.format(t.getTimestamp()) : null);
        vo.put("confidence", t.getConfidence());
        vo.put("model", resolvedModel); // P0-2 整改：从 L0:qwen-* span operationName 解析真实模型
        vo.put("params", null);
        return vo;
    }

    private String extractAgentLevel(String agentPath) {
        if (agentPath == null) return "L0";
        if (agentPath.contains("L2")) return "L2";
        if (agentPath.contains("L1")) return "L1";
        return "L0";
    }

    private String mapStatusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "completed" -> "正常";
            case "error" -> "异常";
            case "active" -> "进行中";
            case "abandoned" -> "已放弃";
            default -> status;
        };
    }

    /**
     * Format time range: "startTime ~ endTime" or just "startTime" if end is null.
     */
    private String formatTimeRange(Instant start, Instant end) {
        if (start == null) return "-";
        if (end == null) return BJ_FMT.format(start);
        return BJ_FMT.format(start) + " ~ " + BJ_FMT.format(end);
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "-";
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        long rs = s % 60;
        if (m < 60) return m + "m " + rs + "s";
        long h = m / 60;
        long rm = m % 60;
        return h + "h " + rm + "m " + rs + "s";
    }

    // ==================== Token / Domain Switch helpers ====================

    /**
     * Count domain switches: number of agent transitions in the deduped chain.
     * e.g. A->A->B => 1 switch, A->B->A => 2 switches, A->A => 0 switches
     */
    private int countDomainSwitches(String[] agents) {
        if (agents == null || agents.length <= 1) return 0;
        int switches = 0;
        for (int i = 1; i < agents.length; i++) {
            if (!agents[i].equals(agents[i - 1])) {
                switches++;
            }
        }
        return switches;
    }

    /**
     * Count domain switches from an intentFlow string (e.g. "L0 → L1 → L2").
     */
    private int countDomainSwitchesFromFlow(String intentFlow) {
        if (intentFlow == null || intentFlow.isBlank()) return 0;
        String arrow = "→";
        String[] parts = intentFlow.split("\\s*" + arrow + "\\s*");
        return countDomainSwitches(parts);
    }

    /**
     * Sum token consumption from span attributes for a session.
     * Collects all traceIds from session_turns, then sums ai.token.system/context/output
     * from all spans matching those traceIds.
     */
    private long sumTokensFromSpans(String sessionId) {
        try {
            var turns = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
            if (turns == null || turns.isEmpty()) return 0;

            List<String> traceIds = new ArrayList<>();
            for (var turn : turns) {
                if (turn.getTraceId() != null && !turn.getTraceId().isBlank() && !traceIds.contains(turn.getTraceId())) {
                    traceIds.add(turn.getTraceId());
                }
            }
            if (traceIds.isEmpty()) return 0;

            List<SpanEntity> spans = spanRepository.findByTraceIdIn(traceIds);
            if (spans == null || spans.isEmpty()) return 0;

            long total = 0;
            for (SpanEntity span : spans) {
                String attrs = span.getAttributes();
                if (attrs == null || attrs.isBlank()) continue;
                total += extractTokenValue(attrs, "ai.token.system");
                total += extractTokenValue(attrs, "ai.token.context");
                total += extractTokenValue(attrs, "ai.token.output");
            }
            return total;
        } catch (Exception e) {
            log.debug("[SessionService] Failed to sum tokens from spans for session {}: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * Extract a numeric attribute value from a JSON-like attributes string.
     */
    private long extractTokenValue(String attrs, String key) {
        if (attrs == null || !attrs.contains(key)) return 0;
        try {
            int idx = attrs.indexOf(key);
            if (idx < 0) return 0;
            int colonIdx = attrs.indexOf(':', idx + key.length());
            if (colonIdx < 0) return 0;
            int start = colonIdx + 1;
            while (start < attrs.length() && (attrs.charAt(start) == ' ' || attrs.charAt(start) == '"')) start++;
            int end = start;
            while (end < attrs.length() && Character.isDigit(attrs.charAt(end))) end++;
            if (end > start) {
                return Long.parseLong(attrs.substring(start, end));
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }
}
