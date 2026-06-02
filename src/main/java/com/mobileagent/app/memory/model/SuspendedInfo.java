package com.mobileagent.app.memory.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 挂起的子图信息 (仅 Multi 域使用)
 *
 * 从 MultiSubAgentDomainService.SuspendedInfo 迁移,
 * 时间字段从 Instant 改为 long (epoch millis), 统一序列化友好。
 */
@Data
public class SuspendedInfo implements Serializable {

    /** 子图意图: "WEALTH_CONSULT" */
    private String intent;

    /** L2 子图的 threadId */
    private String threadId;

    /** 挂起时间 (epoch millis) */
    private long suspendedAt;

    /** 过期时间 (epoch millis) */
    private long expiresAt;

    public SuspendedInfo() {
    }

    public SuspendedInfo(String intent, String threadId, long suspendedAt, long expiresAt) {
        this.intent = intent;
        this.threadId = threadId;
        this.suspendedAt = suspendedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt < System.currentTimeMillis();
    }
}
