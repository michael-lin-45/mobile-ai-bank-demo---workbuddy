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

        // 归一化筛选入参（去除首尾空白/换行，兼容 trace 携带的 sessionId 末尾 \n）
        String sidFilter = (sessionId != null && !sessionId.isBlank()) ? sessionId.trim() : null;
        String uidFilter = (userId != null && !userId.isBlank()) ? userId.trim() : null;
        String chFilter  = (channel != null && !channel.isBlank()) ? channel.trim() : null;
        String intentFilter = (intent != null && !intent.isBlank()) ? intent.trim() : null;
        String lvlFilter = (agentLevel != null && !agentLevel.isBlank()) ? agentLevel.trim() : null;
        String statusFilter = (status != null && !status.isBlank()) ? status.trim() : null;

        // 全量拉取时间范围内的会话（不分页），再在内存做多条件筛选，避免只筛当前页导致漏数据
        List<Session> all = (statusFilter != null)
                ? sessionRepository.findAllByTimeRangeAndStatus(from, to, statusFilter)
                : sessionRepository.findAllByTimeRange(from, to);

        List<Session> filtered = new ArrayList<>();
        for (Session s : all) {
            String sSid = s.getSessionId() != null ? s.getSessionId().trim() : null;
            if (sidFilter != null && !sidFilter.equals(sSid)) continue;
            if (uidFilter != null) {
                String sid = s.getUserId() != null ? s.getUserId().trim() : null;
                if (sid == null || !uidFilter.equals(sid)) continue;
            }
            if (chFilter != null) {
                String ch = s.getChannel() != null ? s.getChannel().trim() : null;
                if (ch == null || !chFilter.equals(ch)) continue;
            }
            if (intentFilter != null) {
                String flow = s.getIntentFlow();
                if (flow == null || !flow.toUpperCase().contains(intentFilter.toUpperCase())) continue;
            }
            if (lvlFilter != null) {
                String flow = s.getIntentFlow();
                if (flow == null || !flow.contains(lvlFilter)) continue;
            }
            filtered.add(s);
        }

        long totalElements = filtered.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        if (totalPages < 1) totalPages = 1;

        int fromIdx = Math.min(page * size, filtered.size());
        int toIdx = Math.min(fromIdx + size, filtered.size());
        List<Session> pageSlice = filtered.subList(fromIdx, toIdx);

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
        vo.put("rerouteTriggered", false);
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
