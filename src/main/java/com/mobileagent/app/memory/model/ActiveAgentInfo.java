package com.mobileagent.app.memory.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 当前活跃子图信息
 *
 * 从 AbstractDomainService.ActiveAgentInfo 迁移,
 * 时间字段从 Instant 改为 long (epoch millis), 统一序列化友好。
 */
@Data
public class ActiveAgentInfo implements Serializable {

    /** 子图意图: "TRANSFER", "WEALTH_CONSULT" */
    private String intent;

    /** L2 子图的 threadId */
    private String threadId;

    /** 创建时间 (epoch millis) */
    private long createdAt;

    /** 过期时间 (epoch millis) */
    private long expiresAt;

    /** 中断时的提问内容 */
    private String lastQuestion;

    public ActiveAgentInfo() {
    }

    public ActiveAgentInfo(String intent, String threadId, long createdAt, long expiresAt) {
        this.intent = intent;
        this.threadId = threadId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt < System.currentTimeMillis();
    }
}
