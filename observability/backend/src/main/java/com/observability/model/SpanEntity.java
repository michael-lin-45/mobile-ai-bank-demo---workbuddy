package com.observability.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA Entity — Span 记录
 * 对应 H2 表 spans
 */
@Entity
@Table(name = "spans", indexes = {
    @Index(name = "idx_spans_trace", columnList = "traceId"),
    @Index(name = "idx_spans_time", columnList = "startTime"),
    @Index(name = "idx_spans_opname", columnList = "operationName")
})
public class SpanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "span_id", nullable = false, length = 64)
    private String spanId;

    @Column(name = "parent_span_id", length = 64)
    private String parentSpanId;

    @Column(name = "service_name", length = 64)
    private String serviceName;

    @Column(name = "operation_name", length = 256)
    private String operationName;

    /** INTERNAL / SERVER / CLIENT */
    @Column(length = 16)
    private String kind;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** OK / ERROR */
    @Column(name = "status_code", length = 16)
    private String statusCode;

    /**
     * JSON 自定义属性
     * 含 ai.io.prompt / ai.io.response / ai.token.* 等
     */
    @Column(length = 4096)
    private String attributes;

    public SpanEntity() {}

    // ── Getters / Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getOperationName() { return operationName; }
    public void setOperationName(String operationName) { this.operationName = operationName; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
}
