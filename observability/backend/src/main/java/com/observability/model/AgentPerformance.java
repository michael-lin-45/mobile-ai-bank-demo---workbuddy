package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — Agent性能快照记录
 * 对应 H2 表 agent_performance
 */
@Entity
@Table(name = "agent_performance", indexes = {
    @Index(name = "idx_ap_agent", columnList = "agentName, timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_name", length = 64)
    private String agentName;

    @Column(name = "agent_level", length = 8)
    private String agentLevel;

    @Column(length = 64)
    private String model;

    @Column(name = "call_count")
    private Integer callCount;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    @Column(name = "ttft_p50_ms")
    private Long ttftP50Ms;

    @Column(name = "ttft_p95_ms")
    private Long ttftP95Ms;

    @Column(name = "tpot_p50_ms")
    private Long tpotP50Ms;

    @Column(name = "tpot_p95_ms")
    private Long tpotP95Ms;

    @Column(name = "total_tokens_in")
    private Long totalTokensIn;

    @Column(name = "total_tokens_out")
    private Long totalTokensOut;

    @Column(name = "error_count")
    private Integer errorCount;

    /** 聚合窗口: 1m / 5m / 15m / 1h */
    @Column(name = "agg_window", length = 16)
    private String aggWindow;

    @Column(name = "\"timestamp\"", nullable = false)
    private Instant timestamp;
}
