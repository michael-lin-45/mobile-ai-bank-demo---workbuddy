package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — 告警规则定义
 * 对应 H2 表 alert_rules
 */
@Entity
@Table(name = "alert_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    @Column(name = "metric_name", length = 128)
    private String metricName;

    @Column(columnDefinition = "DOUBLE")
    private Double threshold;

    /** GT / GTE / LT / LTE */
    @Column(length = 8)
    private String operator;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** CRITICAL / WARNING / INFO */
    @Column(length = 16)
    private String severity;

    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled;

    /** JSON数组: ["email","dingtalk","feishu"] */
    @Column(name = "notify_channels", length = 256)
    private String notifyChannels;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ===== T-B DDL 增强（§3.2③）=====
    /** 求值周期（秒），默认 30 */
    @Column(name = "evaluation_interval")
    private Integer evaluationInterval;

    /** 最近一次 @Scheduled 求值时间 */
    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    /** 最近一次求值得到的指标当前值 */
    @Column(name = "current_value")
    private Double currentValue;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
        if (enabled == null) {
            enabled = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
