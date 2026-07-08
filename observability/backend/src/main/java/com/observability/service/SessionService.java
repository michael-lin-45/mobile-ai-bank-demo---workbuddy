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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 会话查询服务 — 列表 + 详情
 */
@Slf4j
@Service
public class SessionService {

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

        PageRequest pageable = PageRequest.of(page, size);
        Page<Session> result;

        if (status != null && !status.isBlank()) {
            result = sessionRepository.findByTimeRangeAndStatus(from, to, status, pageable);
        } else {
            result = sessionRepository.findByTimeRange(from, to, pageable);
        }

        // Batch load token aggregates from session_turns for all sessions on this page
        // Falls back to span attributes (ai.token.system/context/output) when session_turns tokens are 0
        Map<String, Long> sessionTokenMap = new HashMap<>();
        for (Session s : result.getContent()) {
            long totalTokens = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(s.getSessionId())
                    .stream()
                    .mapToLong(t -> t.getTokens() != null ? t.getTokens() : 0)
                    .sum();
            // Fallback: if session_turns tokens are 0, try to sum from span attributes
            if (totalTokens == 0) {
                totalTokens = sumTokensFromSpans(s.getSessionId());
            }
            sessionTokenMap.put(s.getSessionId(), totalTokens);
        }

        // 内存筛选 intent、agentLevel、sessionId、userId、channel（因为 H2 不支持复杂的 JSON/LIKE 过滤优化）
        List<Map<String, Object>> content = new ArrayList<>();
        for (Session s : result.getContent()) {
            // sessionId 精确筛选
            if (sessionId != null && !sessionId.isBlank()) {
                if (!sessionId.equals(s.getSessionId())) {
                    continue;
                }
            }
            // userId 筛选
            if (userId != null && !userId.isBlank()) {
                String sid = s.getUserId();
                if (sid == null || !sid.equals(userId)) {
                    continue;
                }
            }
            // channel 筛选
            if (channel != null && !channel.isBlank()) {
                String ch = s.getChannel();
                if (ch == null || !ch.equals(channel)) {
                    continue;
                }
            }
            // intent 筛选
            if (intent != null && !intent.isBlank()) {
                String flow = s.getIntentFlow();
                if (flow == null || !flow.toUpperCase().contains(intent.toUpperCase())) {
                    continue;
                }
            }
            // agentLevel 筛选（通过 intentFlow 中的 L0/L1/L2 判断）
            if (agentLevel != null && !agentLevel.isBlank()) {
                String flow = s.getIntentFlow();
                if (flow == null || !flow.contains(agentLevel)) {
                    continue;
                }
            }
            content.add(toSessionListVO(s, sessionTokenMap.get(s.getSessionId())));
        }

        return Map.of(
                "content", content,
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "number", page
        );
    }

    /**
     * 会话详情 — session + turns 列表
     */
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
    public void upsertSession(Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
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
        // 修复 GAP-A2/A3：原 setDau/setOnlineUsers 全库无调用方，DAU/在线永远为 null。
        // 这里挂到 SessionBridge 的每次会话交互上，Core 上报会话即产生真实数据。
        if (!"unknown".equals(userId)) {
            redisMetricsService.recordDauUser(userId);
            redisMetricsService.recordOnlineUser(userId);
        }

        // 累计轮次
        Integer turnCount = session.getTurnCount();
        session.setTurnCount(turnCount != null ? turnCount + 1 : 1);

        // 追加意图流
        String intent = (String) body.getOrDefault("intent", "UNKNOWN");
        String flow = session.getIntentFlow();
        session.setIntentFlow(flow != null ? flow + " → " + intent : intent);

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

        // 写入 session_turns
        String userInput = (String) body.getOrDefault("userInput", "");
        String aiResponse = (String) body.getOrDefault("aiResponse", "");
        String agentPath = (String) body.getOrDefault("agentPath", "");
        String traceId = (String) body.getOrDefault("traceId", "");
        Object durObj = body.get("durationMs");
        long durationMs = durObj instanceof Number ? ((Number) durObj).longValue() : 0L;
        Object confObj = body.get("confidence");
        double confidence = confObj instanceof Number ? ((Number) confObj).doubleValue() : 0.0;

        SessionTurn turn = new SessionTurn();
        turn.setSessionId(sessionId);
        turn.setTurnNumber(session.getTurnCount());
        turn.setUserMessage(userInput);
        turn.setAiResponse(aiResponse);
        turn.setIntent(intent);
        turn.setAgentPath(agentPath);
        turn.setConfidence(confidence);
        turn.setDurationMs(durationMs);
        turn.setTokens((int) tokens);
        turn.setTraceId(traceId);
        turn.setStatus(status);
        turn.setTimestamp(now);

        sessionTurnRepository.save(turn);

        log.info("[SessionBridge] Upserted session: sessionId={}, turn={}, status={}",
                sessionId, session.getTurnCount(), status);
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
        // Extract first intent from agentChain (e.g. "TRANSFER → INQUIRY" → "TRANSFER")
        if (intentFlow != null) {
            String[] parts = intentFlow.split("\\s*→\\s*");
            vo.put("intent", parts.length > 0 ? parts[0] : intentFlow);
            vo.put("agents", Arrays.asList(parts));
            vo.put("domainSwitches", countDomainSwitches(parts));
        } else {
            vo.put("intent", null);
            vo.put("agents", Collections.emptyList());
            vo.put("domainSwitches", 0);
        }

        return vo;
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
        vo.put("ttftMs", 0L); // TODO: TTFT should come from OTel span attributes or SessionBridge when available
        vo.put("rerouteTriggered", false);
        vo.put("businessOutcome", t.getStatus());
        vo.put("status", t.getStatus()); // used by SessionDetailModal for turn status badges
        // Additional fields used by SessionDetailModal
        vo.put("time", t.getTimestamp() != null ? t.getTimestamp().toString() : null);
        vo.put("confidence", t.getConfidence());
        vo.put("model", "qwen-plus"); // placeholder — real model info from OTel trace attributes TBD
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
        if (end == null) return start.toString();
        return start.toString() + " ~ " + end.toString();
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
