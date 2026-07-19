package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — 会话聚合记录
 * 对应 H2 表 sessions
 */
@Entity
@Table(name = "sessions", indexes = {
    @Index(name = "idx_sessions_sid", columnList = "sessionId", unique = true),
    @Index(name = "idx_sessions_time", columnList = "startTime")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(length = 32)
    private String channel;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "turn_count")
    private Integer turnCount;

    @Column(name = "intent_flow", length = 1024)
    private String intentFlow;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(length = 32)
    private String status;

    /** satisfied | neutral | unsatisfied */
    @Column(name = "satisfaction_rating", length = 16)
    private String satisfactionRating;

    /** 反馈原因（可选） */
    @Column(name = "satisfaction_reason", length = 512)
    private String satisfactionReason;

    /** 待定项 B：reRoute 原报文透传标记，该会话是否经过二次路由 */
    @Column(name = "reroute_triggered", columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean rerouteTriggered;
}
