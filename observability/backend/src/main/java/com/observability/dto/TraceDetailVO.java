package com.observability.dto;

import java.util.List;

/**
 * Trace 详情视图对象 — 包含 Span 树
 * 对应 GET /api/v1/traces/{traceId} 响应
 */
public class TraceDetailVO {
    // ── 核心字段 ──
    private String traceId;
    private List<SpanNodeVO> spanTree;
    private long totalDurationMs;
    private String rootServiceName;

    // ── 业务属性（跨 Span 提取） ──
    private String userId;
    private String sessionId;
    private String intent;
    private String agentChain;

    // ── 性能指标 ──
    /** TTFT 首Token时延 (ms) */
    private Long ttft;
    /** 与 ttft 同值，前端兼容 */
    private Long ttftMs;

    // ── 状态 ──
    /** 状态展示字符串："🟢正常" / "🔴异常" */
    private String status;
    /** 状态码："OK" / "ERROR" */
    private String statusCode;

    // ── 时长别名 ──
    /** 与 totalDurationMs 同值 */
    private long durationMs;

    // ── IO 卡片数据 ──
    /** 输入卡片对象 {type, content} */
    private Object ioInput;
    /** 输出卡片对象 {type, content} */
    private Object ioOutput;
    /** 输入文本（纯文本） */
    private String inputText;
    /** 输出文本（纯文本） */
    private String outputText;

    // ── 瀑布图 Span ──
    /** 展平后的瀑布图 Span 列表 */
    private List<Object> waterfallSpans;

    // ── 重路由 (reRoute) ──
    /** 是否发生重路由：一条 trace 内出现 >1 个 L0（领域路由）span */
    private boolean reRouted;
    /** 重路由分层路径，如 "L0 → L1 → L2 ↻ L0 → L1 → L2" */
    private String reRoutePath;

    public TraceDetailVO() {}

    public TraceDetailVO(String traceId, List<SpanNodeVO> spanTree,
                         long totalDurationMs, String rootServiceName) {
        this.traceId = traceId;
        this.spanTree = spanTree;
        this.totalDurationMs = totalDurationMs;
        this.rootServiceName = rootServiceName;
    }

    // ── 核心 Getters/Setters ──

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public List<SpanNodeVO> getSpanTree() { return spanTree; }
    public void setSpanTree(List<SpanNodeVO> spanTree) { this.spanTree = spanTree; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }
    public String getRootServiceName() { return rootServiceName; }
    public void setRootServiceName(String rootServiceName) { this.rootServiceName = rootServiceName; }

    // ── 业务属性 Getters/Setters ──

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getAgentChain() { return agentChain; }
    public void setAgentChain(String agentChain) { this.agentChain = agentChain; }

    // ── 性能指标 Getters/Setters ──

    public Long getTtft() { return ttft; }
    public void setTtft(Long ttft) { this.ttft = ttft; }
    public Long getTtftMs() { return ttftMs; }
    public void setTtftMs(Long ttftMs) { this.ttftMs = ttftMs; }

    // ── 状态 Getters/Setters ──

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    // ── 时长别名 ──

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    // ── IO 卡片 Getters/Setters ──

    public Object getIoInput() { return ioInput; }
    public void setIoInput(Object ioInput) { this.ioInput = ioInput; }
    public Object getIoOutput() { return ioOutput; }
    public void setIoOutput(Object ioOutput) { this.ioOutput = ioOutput; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }

    // ── 瀑布图 Getters/Setters ──

    public List<Object> getWaterfallSpans() { return waterfallSpans; }
    public void setWaterfallSpans(List<Object> waterfallSpans) { this.waterfallSpans = waterfallSpans; }

    // ── 重路由 Getters/Setters ──

    public boolean isRerouted() { return reRouted; }
    public void setRerouted(boolean reRouted) { this.reRouted = reRouted; }
    public String getReRoutePath() { return reRoutePath; }
    public void setReRoutePath(String reRoutePath) { this.reRoutePath = reRoutePath; }
}
