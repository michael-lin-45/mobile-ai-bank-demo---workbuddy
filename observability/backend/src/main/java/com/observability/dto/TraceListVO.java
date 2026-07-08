package com.observability.dto;

import java.time.Instant;

/**
 * Trace 列表视图对象 — 对应 GET /api/v1/traces 响应
 */
public class TraceListVO {
    private String traceId;
    private String userId;
    private String sessionId;
    private String intent;
    private String agentChain;
    private int llmCalls;
    private long durationMs;
    private String statusCode;
    private Instant timestamp;

    /** TTFT 首Token时延 (ms) */
    private Long ttftMs;

    /** 总Token消耗 */
    private Long tokenTotal;

    /** 状态圆点: 🟢正常 / 🔴异常 */
    private String statusDot;

    public TraceListVO() {}

    public TraceListVO(String traceId, String userId, String sessionId, String intent,
                       String agentChain, int llmCalls, long durationMs,
                       String statusCode, Instant timestamp) {
        this.traceId = traceId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.intent = intent;
        this.agentChain = agentChain;
        this.llmCalls = llmCalls;
        this.durationMs = durationMs;
        this.statusCode = statusCode;
        this.timestamp = timestamp;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getAgentChain() { return agentChain; }
    public void setAgentChain(String agentChain) { this.agentChain = agentChain; }
    public int getLlmCalls() { return llmCalls; }
    public void setLlmCalls(int llmCalls) { this.llmCalls = llmCalls; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Long getTtftMs() { return ttftMs; }
    public void setTtftMs(Long ttftMs) { this.ttftMs = ttftMs; }
    public Long getTokenTotal() { return tokenTotal; }
    public void setTokenTotal(Long tokenTotal) { this.tokenTotal = tokenTotal; }
    public String getStatusDot() { return statusDot; }
    public void setStatusDot(String statusDot) { this.statusDot = statusDot; }
}
