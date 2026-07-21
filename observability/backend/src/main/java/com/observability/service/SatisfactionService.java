package com.observability.service;

import com.observability.model.Session;
import com.observability.model.SessionTurn;
import com.observability.repository.SessionRepository;
import com.observability.repository.SessionTurnRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 满意度查询与反馈服务
 */
@Slf4j
@Service
public class SatisfactionService {

    private final SessionRepository sessionRepository;
    private final SessionTurnRepository sessionTurnRepository;

    public SatisfactionService(SessionRepository sessionRepository,
                               SessionTurnRepository sessionTurnRepository) {
        this.sessionRepository = sessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
    }

    /**
     * 获取满意度聚合数据
     *
     * @param from 开始时间
     * @param to   结束时间
     */
    public Map<String, Object> getSatisfaction(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(604800); // 默认7天
        if (to == null) to = Instant.now();

        List<Session> sessions = sessionRepository.findByTimeRange(from, to,
                PageRequest.of(0, Integer.MAX_VALUE)).getContent();

        // 满意度分布
        long satisfied = 0, neutral = 0, unsatisfied = 0, unrated = 0;
        for (Session s : sessions) {
            String rating = s.getSatisfactionRating();
            if ("satisfied".equals(rating)) satisfied++;
            else if ("neutral".equals(rating)) neutral++;
            else if ("unsatisfied".equals(rating)) unsatisfied++;
            else unrated++;
        }

        long rated = satisfied + neutral + unsatisfied;
        double satisfiedRate = rated > 0 ? satisfied * 100.0 / rated : 0.0;

        Map<String, Long> distribution = new LinkedHashMap<>();
        distribution.put("satisfied", satisfied);
        distribution.put("neutral", neutral);
        distribution.put("unsatisfied", unsatisfied);

        // 7天趋势
        List<Map<String, Object>> trend = buildTrend(from, to);

        // 不满意原因分布
        List<Map<String, Object>> reasons = buildUnsatisfiedReasons(sessions);

        // 低分会话列表
        List<Map<String, Object>> lowScoreSessions = buildLowScoreSessions(sessions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("distribution", distribution);
        result.put("satisfiedRate", Math.round(satisfiedRate * 10.0) / 10.0);
        result.put("trend", trend);
        result.put("unsatisfiedReasons", reasons);
        result.put("lowScoreSessions", lowScoreSessions);
        return result;
    }

    /**
     * 提交满意度反馈
     *
     * @param sessionId 会话ID
     * @param rating    评分 (satisfied|neutral|unsatisfied)
     * @param reason    反馈原因（可选）
     */
    @Transactional
    public boolean submitSatisfaction(String sessionId, String rating, String reason) {
        try {
            Session session = sessionRepository.findBySessionId(sessionId).orElse(null);
            if (session == null) {
                log.warn("[Satisfaction] Session not found: {}", sessionId);
                return false;
            }

            session.setSatisfactionRating(rating);
            session.setSatisfactionReason(reason);
            sessionRepository.save(session);

            log.info("[Satisfaction] Feedback saved: sessionId={}, rating={}, reason={}",
                    sessionId, rating, reason);
            return true;
        } catch (Exception e) {
            log.error("[Satisfaction] Failed to submit feedback: {}", e.getMessage());
            return false;
        }
    }

    // ── Builders ──

    private List<Map<String, Object>> buildTrend(Instant from, Instant to) {
        List<Map<String, Object>> trend = new ArrayList<>();
        // 按天分组计算（统一使用北京时间，使"今日满意度"边界与北京展示一致）
        ZoneId beijing = ZoneId.of("Asia/Shanghai");
        LocalDate startDate = from.atZone(beijing).toLocalDate();
        LocalDate endDate = to.atZone(beijing).toLocalDate();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(beijing).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(beijing).toInstant();

            List<Session> daySessions = sessionRepository.findByTimeRange(dayStart, dayEnd,
                    PageRequest.of(0, Integer.MAX_VALUE)).getContent();

            long daySat = 0, dayTotal = 0;
            for (Session s : daySessions) {
                String r = s.getSatisfactionRating();
                if (r != null) {
                    dayTotal++;
                    if ("satisfied".equals(r)) daySat++;
                }
            }

            double rate = dayTotal > 0 ? Math.round(daySat * 1000.0 / dayTotal) / 10.0 : 0.0;
            trend.add(Map.of("date", date.toString(), "satisfiedRate", rate));
        }

        return trend;
    }

    private List<Map<String, Object>> buildUnsatisfiedReasons(List<Session> sessions) {
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        long totalUnsatisfied = 0;

        for (Session s : sessions) {
            if ("unsatisfied".equals(s.getSatisfactionRating())) {
                totalUnsatisfied++;
                String reason = s.getSatisfactionReason();
                if (reason != null && !reason.isBlank()) {
                    reasonCounts.merge(reason, 1L, Long::sum);
                } else {
                    reasonCounts.merge("未注明", 1L, Long::sum);
                }
            }
        }

        List<Map<String, Object>> reasons = new ArrayList<>();
        for (var entry : reasonCounts.entrySet()) {
            long count = entry.getValue();
            double pct = totalUnsatisfied > 0 ? Math.round(count * 1000.0 / totalUnsatisfied) / 10.0 : 0.0;
            reasons.add(Map.of("reason", entry.getKey(), "count", count, "percentage", pct));
        }

        // 如果没有数据，提供默认分类
        if (reasons.isEmpty()) {
            reasons.add(Map.of("reason", "暂无数据", "count", 0L, "percentage", 0.0));
        }

        return reasons;
    }

    private List<Map<String, Object>> buildLowScoreSessions(List<Session> sessions) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;

        for (Session s : sessions) {
            if ("unsatisfied".equals(s.getSatisfactionRating()) && count < 20) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", s.getSessionId());
                item.put("userId", s.getUserId());
                item.put("rating", s.getSatisfactionRating());
                item.put("reason", s.getSatisfactionReason());

                // 获取第一条用户消息作为 query
                List<SessionTurn> turns = sessionTurnRepository
                        .findBySessionIdOrderByTurnNumberAsc(s.getSessionId());
                String firstQuery = turns.isEmpty() ? "" : turns.get(0).getUserMessage();
                item.put("query", firstQuery != null ? firstQuery : "");

                result.add(item);
                count++;
            }
        }

        return result;
    }
}
