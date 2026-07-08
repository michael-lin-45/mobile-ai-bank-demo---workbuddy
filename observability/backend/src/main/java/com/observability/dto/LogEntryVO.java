package com.observability.dto;

import java.time.Instant;

/**
 * 日志条目视图对象 — 对应 GET /api/v1/logs 响应
 */
public class LogEntryVO {
    private Long id;
    private String traceId;
    private String spanId;
    private String level;
    private String serviceName;
    private String message;
    private Instant timestamp;

    public LogEntryVO() {}

    public LogEntryVO(Long id, String traceId, String spanId, String level,
                      String serviceName, String message, Instant timestamp) {
        this.id = id;
        this.traceId = traceId;
        this.spanId = spanId;
        this.level = level;
        this.serviceName = serviceName;
        this.message = message;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
