package com.observability.dto;

import java.util.List;
import java.util.Map;

/**
 * 满意度视图对象 — 对应 GET /api/v1/ai/satisfaction 响应
 */
public class SatisfactionVO {

    /** 满意度分布: satisfied / neutral / unsatisfied */
    private Map<String, Long> distribution;

    /** 满意率 (satisfied / total) */
    private double satisfiedRate;

    /** 7天趋势 */
    private List<TrendPoint> trend;

    /** 不满意原因分布 */
    private List<ReasonItem> unsatisfiedReasons;

    /** 低分会话列表 */
    private List<LowScoreSession> lowScoreSessions;

    public SatisfactionVO() {}

    // ── Getters / Setters ──

    public Map<String, Long> getDistribution() { return distribution; }
    public void setDistribution(Map<String, Long> distribution) { this.distribution = distribution; }
    public double getSatisfiedRate() { return satisfiedRate; }
    public void setSatisfiedRate(double satisfiedRate) { this.satisfiedRate = satisfiedRate; }
    public List<TrendPoint> getTrend() { return trend; }
    public void setTrend(List<TrendPoint> trend) { this.trend = trend; }
    public List<ReasonItem> getUnsatisfiedReasons() { return unsatisfiedReasons; }
    public void setUnsatisfiedReasons(List<ReasonItem> unsatisfiedReasons) { this.unsatisfiedReasons = unsatisfiedReasons; }
    public List<LowScoreSession> getLowScoreSessions() { return lowScoreSessions; }
    public void setLowScoreSessions(List<LowScoreSession> lowScoreSessions) { this.lowScoreSessions = lowScoreSessions; }

    // ── TrendPoint ──

    public static class TrendPoint {
        private String date;
        private double satisfiedRate;

        public TrendPoint() {}
        public TrendPoint(String date, double satisfiedRate) { this.date = date; this.satisfiedRate = satisfiedRate; }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public double getSatisfiedRate() { return satisfiedRate; }
        public void setSatisfiedRate(double satisfiedRate) { this.satisfiedRate = satisfiedRate; }
    }

    // ── ReasonItem ──

    public static class ReasonItem {
        private String reason;
        private long count;
        private double percentage;

        public ReasonItem() {}
        public ReasonItem(String reason, long count, double percentage) {
            this.reason = reason; this.count = count; this.percentage = percentage;
        }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }
        public double getPercentage() { return percentage; }
        public void setPercentage(double percentage) { this.percentage = percentage; }
    }

    // ── LowScoreSession ──

    public static class LowScoreSession {
        private String sessionId;
        private String userId;
        private String rating;
        private String reason;
        private String query;

        public LowScoreSession() {}

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getRating() { return rating; }
        public void setRating(String rating) { this.rating = rating; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
    }
}
