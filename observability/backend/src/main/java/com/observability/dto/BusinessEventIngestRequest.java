package com.observability.dto;

/**
 * 业务埋点入库请求 — POST /api/v1/business-events。
 *
 * <p>校验规则（错误码见 §7.3）：
 * <ul>
 *   <li>{@code event_type} 必填，取值 mbank_card_click / mbank_human_click（40001 无效）</li>
 *   <li>mbank_card_click 必传 {@code card_type}（40002 缺必传维度）</li>
 *   <li>mbank_human_click 必传 {@code source}（40002 缺必传维度）</li>
 * </ul>
 */
public class BusinessEventIngestRequest {

    private String sessionId;
    private String traceId;
    private String userId;
    private String agent;

    private String eventType;

    private String cardType;
    private String source;
    private String channel;

    /** ISO-8601 时间戳字符串（北京时间），如 2026-07-23T10:00:00+08:00；为空则用当前时间 */
    private String ts;

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

    public String getTs() { return ts; }
    public void setTs(String ts) { this.ts = ts; }
}
