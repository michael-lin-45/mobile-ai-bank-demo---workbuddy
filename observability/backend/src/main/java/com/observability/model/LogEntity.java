package com.observability.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA Entity — 日志记录
 * 对应 H2 表 logs
 */
@Entity
@Table(name = "logs", indexes = {
    @Index(name = "idx_logs_time", columnList = "timestamp"),
    @Index(name = "idx_logs_trace", columnList = "traceId"),
    @Index(name = "idx_logs_level", columnList = "level")
})
public class LogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "span_id", length = 64)
    private String spanId;

    /** INFO / WARN / ERROR */
    @Column(nullable = false, length = 16, name = "\"level\"")
    private String level;

    @Column(name = "service_name", length = 64)
    private String serviceName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, name = "\"timestamp\"")
    private Instant timestamp;

    /** JSON 附加属性 */
    @Column(length = 1024)
    private String attributes;

    public LogEntity() {}

    // ── Getters / Setters ──

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

    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
}
