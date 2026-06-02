package com.mobileagent.app.memory.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 意图消歧状态 (仅 Multi 域使用)
 *
 * 从 MultiSubAgentDomainService.DisambiguationState 迁移。
 */
@Data
public class DisambiguationState implements Serializable {

    /** 消歧意图组 ID */
    private String groupId;

    public DisambiguationState() {
    }

    public DisambiguationState(String groupId) {
        this.groupId = groupId;
    }
}
