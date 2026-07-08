package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — 会话轮次明细记录
 * 对应 H2 表 session_turns
 */
@Entity
@Table(name = "session_turns", indexes = {
    @Index(name = "idx_turns_sid", columnList = "sessionId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "turn_number", nullable = false)
    private Integer turnNumber;

    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    @Column(length = 64)
    private String intent;

    @Column(name = "agent_path", length = 256)
    private String agentPath;

    @Column(columnDefinition = "DOUBLE")
    private Double confidence;

    @Column(name = "duration_ms")
    private Long durationMs;

    private Integer tokens;

    @Column(length = 32)
    private String status;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "\"timestamp\"", nullable = false)
    private Instant timestamp;
}
