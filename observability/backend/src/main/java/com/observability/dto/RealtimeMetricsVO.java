package com.observability.dto;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 实时指标视图对象 — 对应 GET /api/v1/metrics/realtime 响应
 *
 * Phase 1 扩展：从 9 字段扩展至约 20 字段，覆盖 Zone A/B/C/D 四区
 */
public class RealtimeMetricsVO {

    // ── 保留字段（9个）──
    private long requestCount;
    private long errorCount;
    private double avgLatency;
    private long tokenInput;
    private long tokenOutput;
    private Map<String, Long> intentDistribution;
    private long activeSessions;
    private double p50Latency;
    private double p95Latency;
    private double p99Latency;

    // ── Zone A 系统健康（新增6个）──
    /** 日活跃用户数 */
    @JsonProperty("dau")
    private Long dailyActiveUsers;
    /** 实时在线用户数 */
    @JsonProperty("realTimeOnline")
    private Long realtimeOnline;
    /** 每秒请求数 */
    private Double qps;
    /** L0 Agent调用次数 */
    @JsonProperty("l0Calls")
    private Long agentCallsL0;
    /** L1 Agent调用次数 */
    @JsonProperty("l1Calls")
    private Long agentCallsL1;
    /** L2 Agent调用次数 */
    @JsonProperty("l2Calls")
    private Long agentCallsL2;
    /** Agent总调用次数 (L0+L1+L2) */
    @JsonProperty("agentCallCount")
    private Long agentCallCount;

    // ── Zone B AI性能（新增4个）──
    /** 首Token时延 P50 (ms) */
    @JsonProperty("ttftP50")
    private Long ttftP50Ms;
    /** 首Token时延 P95 (ms) */
    @JsonProperty("ttftP95")
    private Long ttftP95Ms;
    /** 首Token时延 P99 (ms) */
    @JsonProperty("ttftP99")
    private Long ttftP99Ms;
    /** 错误率 (0.0012 = 0.12%) */
    private Double errorRate;

    // ── Zone C 语义质量（新增4个）──
    /** 意图识别准确率 (0.942 = 94.2%) */
    private Double intentAccuracy;
    /** 改写准确率 (0.915 = 91.5%) */
    private Double rewriteAccuracy;
    /** Reroute率 (0.142 = 14.2%) */
    private Double rerouteRate;
    /** 业务完成率 */
    @JsonProperty("completionRate")
    private Double businessCompletionRate;

    // ── Zone D 业务效果（新增3个）──
    /** 业务转化率 */
    @JsonProperty("conversionRate")
    private Double businessConversionRate;
    /** 违规率（占位，P0返回null → 前端展示"暂无"） */
    private Double violationRate;
    /** 转人工率（占位，P0返回null → 前端展示"暂无"） */
    private Double transferToHumanRate;

    // ── Agent分布（新增1个）──
    /** L0/L1/L2 Agent调用分布，key=agent level */
    private Map<String, Long> agentDistribution;

    // ---- Fallback tracking ----
    /**
     * Names of metrics that came from H2 snapshot fallback (not Redis real-time).
     * Frontend can use this to show a "fallback" indicator.
     * Empty set means all metrics are real-time from Redis.
     */
    @JsonProperty("fallbackMetrics")
    private Set<String> fallbackMetrics = new HashSet<>();

    /** True if any metric is currently using H2 fallback */
    @JsonProperty("usingFallback")
    private boolean usingFallback = false;

    public RealtimeMetricsVO() {}

    public RealtimeMetricsVO(long requestCount, long errorCount, double avgLatency,
                             long tokenInput, long tokenOutput,
                             Map<String, Long> intentDistribution,
                             long activeSessions, double p50Latency, double p95Latency, double p99Latency) {
        this.requestCount = requestCount;
        this.errorCount = errorCount;
        this.avgLatency = avgLatency;
        this.tokenInput = tokenInput;
        this.tokenOutput = tokenOutput;
        this.intentDistribution = intentDistribution;
        this.activeSessions = activeSessions;
        this.p50Latency = p50Latency;
        this.p95Latency = p95Latency;
        this.p99Latency = p99Latency;
    }

    // ── 保留字段 Getters/Setters ──

    public long getRequestCount() { return requestCount; }
    public void setRequestCount(long requestCount) { this.requestCount = requestCount; }
    public long getErrorCount() { return errorCount; }
    public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
    public double getAvgLatency() { return avgLatency; }
    public void setAvgLatency(double avgLatency) { this.avgLatency = avgLatency; }
    public long getTokenInput() { return tokenInput; }
    public void setTokenInput(long tokenInput) { this.tokenInput = tokenInput; }
    public long getTokenOutput() { return tokenOutput; }
    public void setTokenOutput(long tokenOutput) { this.tokenOutput = tokenOutput; }
    public Map<String, Long> getIntentDistribution() { return intentDistribution; }
    public void setIntentDistribution(Map<String, Long> intentDistribution) { this.intentDistribution = intentDistribution; }
    public long getActiveSessions() { return activeSessions; }
    public void setActiveSessions(long activeSessions) { this.activeSessions = activeSessions; }
    public double getP50Latency() { return p50Latency; }
    public void setP50Latency(double p50Latency) { this.p50Latency = p50Latency; }
    public double getP95Latency() { return p95Latency; }
    public void setP95Latency(double p95Latency) { this.p95Latency = p95Latency; }
    public double getP99Latency() { return p99Latency; }
    public void setP99Latency(double p99Latency) { this.p99Latency = p99Latency; }

    // ── Zone A Getters/Setters ──

    public Long getDailyActiveUsers() { return dailyActiveUsers; }
    public void setDailyActiveUsers(Long dailyActiveUsers) { this.dailyActiveUsers = dailyActiveUsers; }
    public Long getRealtimeOnline() { return realtimeOnline; }
    public void setRealtimeOnline(Long realtimeOnline) { this.realtimeOnline = realtimeOnline; }
    public Double getQps() { return qps; }
    public void setQps(Double qps) { this.qps = qps; }
    public Long getAgentCallsL0() { return agentCallsL0; }
    public void setAgentCallsL0(Long agentCallsL0) { this.agentCallsL0 = agentCallsL0; }
    public Long getAgentCallsL1() { return agentCallsL1; }
    public void setAgentCallsL1(Long agentCallsL1) { this.agentCallsL1 = agentCallsL1; }
    public Long getAgentCallsL2() { return agentCallsL2; }
    public void setAgentCallsL2(Long agentCallsL2) { this.agentCallsL2 = agentCallsL2; }
    public Long getAgentCallCount() { return agentCallCount; }
    public void setAgentCallCount(Long agentCallCount) { this.agentCallCount = agentCallCount; }

    // ── Zone B Getters/Setters ──

    public Long getTtftP50Ms() { return ttftP50Ms; }
    public void setTtftP50Ms(Long ttftP50Ms) { this.ttftP50Ms = ttftP50Ms; }
    public Long getTtftP95Ms() { return ttftP95Ms; }
    public void setTtftP95Ms(Long ttftP95Ms) { this.ttftP95Ms = ttftP95Ms; }
    public Long getTtftP99Ms() { return ttftP99Ms; }
    public void setTtftP99Ms(Long ttftP99Ms) { this.ttftP99Ms = ttftP99Ms; }
    public Double getErrorRate() { return errorRate; }
    public void setErrorRate(Double errorRate) { this.errorRate = errorRate; }

    // ── Zone C Getters/Setters ──

    public Double getIntentAccuracy() { return intentAccuracy; }
    public void setIntentAccuracy(Double intentAccuracy) { this.intentAccuracy = intentAccuracy; }
    public Double getRewriteAccuracy() { return rewriteAccuracy; }
    public void setRewriteAccuracy(Double rewriteAccuracy) { this.rewriteAccuracy = rewriteAccuracy; }
    public Double getRerouteRate() { return rerouteRate; }
    public void setRerouteRate(Double rerouteRate) { this.rerouteRate = rerouteRate; }
    public Double getBusinessCompletionRate() { return businessCompletionRate; }
    public void setBusinessCompletionRate(Double businessCompletionRate) { this.businessCompletionRate = businessCompletionRate; }

    // ── Zone D Getters/Setters ──

    public Double getBusinessConversionRate() { return businessConversionRate; }
    public void setBusinessConversionRate(Double businessConversionRate) { this.businessConversionRate = businessConversionRate; }
    public Double getViolationRate() { return violationRate; }
    public void setViolationRate(Double violationRate) { this.violationRate = violationRate; }
    public Double getTransferToHumanRate() { return transferToHumanRate; }
    public void setTransferToHumanRate(Double transferToHumanRate) { this.transferToHumanRate = transferToHumanRate; }

    // ── Agent分布 Getters/Setters ──

    public Map<String, Long> getAgentDistribution() { return agentDistribution; }
    public void setAgentDistribution(Map<String, Long> agentDistribution) { this.agentDistribution = agentDistribution; }

    // ---- Fallback Getters/Setters ----

    public Set<String> getFallbackMetrics() { return fallbackMetrics; }
    public void setFallbackMetrics(Set<String> fallbackMetrics) { this.fallbackMetrics = fallbackMetrics; }
    public boolean isUsingFallback() { return usingFallback; }
    public void setUsingFallback(boolean usingFallback) { this.usingFallback = usingFallback; }
}
