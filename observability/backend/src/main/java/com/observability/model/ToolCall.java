package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — 工具调用记录
 * 对应 H2 表 tool_calls
 */
@Entity
@Table(name = "tool_calls", indexes = {
    @Index(name = "idx_mcp_name", columnList = "toolName, timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "call_count")
    private Integer callCount;

    @Column(name = "success_count")
    private Integer successCount;

    @Column(name = "fail_count")
    private Integer failCount;

    @Column(name = "avg_duration_ms")
    private Long avgDurationMs;

    @Column(name = "p95_duration_ms")
    private Long p95DurationMs;

    @Column(name = "error_rate", columnDefinition = "DOUBLE")
    private Double errorRate;

    @Column(name = "typical_errors", length = 512)
    private String typicalErrors;

    /** 工具提供方，如 banking-api */
    @Column(length = 64)
    private String provider;

    /** 聚合窗口: 1m / 5m / 15m / 1h */
    @Column(name = "agg_window", length = 16)
    private String aggWindow;

    @Column(name = "\"timestamp\"", nullable = false)
    private Instant timestamp;
}
