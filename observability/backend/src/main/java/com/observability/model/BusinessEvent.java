package com.observability.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 业务埋点事件实体 — 映射 {@code business_events} 表。
 *
 * <p>V23 新增两个真实前端埋点：
 * <ul>
 *   <li>{@code mbank_card_click} — 业务引导办理次数（必传 card_type）</li>
 *   <li>{@code mbank_human_click} — 转人工次数（必传 source）</li>
 * </ul>
 *
 * <p>所有时间统一北京时间（UTC+8），入库为 TIMESTAMP；展示层按 Asia/Shanghai 格式化。
 */
@Entity
@Table(name = "business_events")
public class BusinessEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "agent", length = 64)
    private String agent;

    /** mbank_card_click / mbank_human_click（非空） */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    /** 仅 mbank_card_click：卡片业务类型 */
    @Column(name = "card_type", length = 32)
    private String cardType;

    /** 仅 mbank_human_click：chat_bar|card_menu|timeout（可 groupBy） */
    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "channel", length = 32)
    private String channel;

    @Column(name = "created_at")
    private Instant createdAt;

    public BusinessEvent() {}

    public BusinessEvent(Long id, String sessionId, String traceId, String userId, String agent,
                         String eventType, String cardType, String source, String channel, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.traceId = traceId;
        this.userId = userId;
        this.agent = agent;
        this.eventType = eventType;
        this.cardType = cardType;
        this.source = source;
        this.channel = channel;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
