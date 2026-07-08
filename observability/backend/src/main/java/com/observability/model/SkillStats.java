package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — Skill业务效果统计
 *
 * ⚠️ P1 实现，P0 不建表。Entity Bean 仅占位，标注 @Deprecated。
 * 对应 H2 表 skill_stats（P1 阶段建表）。
 */
@Entity
@Table(name = "skill_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Deprecated
public class SkillStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_name", length = 128)
    private String skillName;

    @Column(length = 64)
    private String intent;

    @Column(name = "total_calls")
    private Integer totalCalls;

    @Column(name = "success_calls")
    private Integer successCalls;

    @Column(name = "completion_rate", columnDefinition = "DOUBLE")
    private Double completionRate;

    @Column(name = "avg_duration_ms", columnDefinition = "DOUBLE")
    private Double avgDurationMs;

    @Column(name = "avg_turns", columnDefinition = "DOUBLE")
    private Double avgTurns;

    @Column(name = "drop_off_rate", columnDefinition = "DOUBLE")
    private Double dropOffRate;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;
}
