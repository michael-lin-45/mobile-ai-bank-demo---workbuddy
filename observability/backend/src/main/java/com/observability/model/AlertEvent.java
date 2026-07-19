package com.observability.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA Entity — 告警事件记录
 * 对应 H2 表 alert_events
 */
@Entity
@Table(name = "alert_events", indexes = {
    @Index(name = "idx_ae_time", columnList = "triggeredAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_name", length = 128)
    private String ruleName;

    /** CRITICAL / WARNING / INFO */
    @Column(length = 16)
    private String severity;

    /** FIRING / ACKED / RESOLVED / SUPPRESSED（T-B 状态机扩展） */
    @Column(length = 32)
    private String status;

    @Column(name = "triggered_at")
    private Instant triggeredAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String message;

    // ===== T-B DDL 增强（§3.2③）=====
    /** 通知渠道（来自规则的 notifyChannels 快照） */
    @Column(name = "notified_channels", length = 128)
    private String notifiedChannels;

    /** 通知结果（LogNotifier：LOGGED:... 不真实外发；SUPPRESSED:...） */
    @Column(name = "notify_result", length = 64)
    private String notifyResult;

    /** 抑制计数：抑制窗口内重复触发的累计次数 */
    @Column(name = "suppression_count", columnDefinition = "INT DEFAULT 0")
    private Integer suppressionCount;
}
