package com.observability.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Span 节点视图对象 — 递归 Span 树节点
 */
public class SpanNodeVO {
    private String spanId;
    private String parentSpanId;
    private String operationName;
    private String kind;
    private long durationMs;
    private String statusCode;
    private Map<String, String> attributes;
    private List<SpanNodeVO> children = new ArrayList<>();
    private String ioPrompt;
    private String ioResponse;
    private TokenBreakdownVO tokenBreakdown;

    // ── SpanTree 组件扩展字段 ──
    /** Agent 显示名称（取 operationName） */
    private String agentName;
    /** Agent 简要描述 */
    private String agentDesc;
    /** Agent 层级：L0 / L1 / L2 */
    private String agentType;
    /** LLM 调用列表（占位，后续从真实数据填充） */
    private List<Object> llmCalls;

    public SpanNodeVO() {}

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
    public String getOperationName() { return operationName; }
    public void setOperationName(String operationName) { this.operationName = operationName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    public List<SpanNodeVO> getChildren() { return children; }
    public void setChildren(List<SpanNodeVO> children) { this.children = children; }
    public String getIoPrompt() { return ioPrompt; }
    public void setIoPrompt(String ioPrompt) { this.ioPrompt = ioPrompt; }
    public String getIoResponse() { return ioResponse; }
    public void setIoResponse(String ioResponse) { this.ioResponse = ioResponse; }
    public TokenBreakdownVO getTokenBreakdown() { return tokenBreakdown; }
    public void setTokenBreakdown(TokenBreakdownVO tokenBreakdown) { this.tokenBreakdown = tokenBreakdown; }

    // ── SpanTree 扩展 Getters/Setters ──

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getAgentDesc() { return agentDesc; }
    public void setAgentDesc(String agentDesc) { this.agentDesc = agentDesc; }
    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public List<Object> getLlmCalls() { return llmCalls; }
    public void setLlmCalls(List<Object> llmCalls) { this.llmCalls = llmCalls; }
}
