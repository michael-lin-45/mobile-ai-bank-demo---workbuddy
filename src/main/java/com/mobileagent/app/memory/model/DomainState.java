package com.mobileagent.app.memory.model;

import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一的 L1 域状态 — 不管 Single 还是 Multi 都用这一个类
 *
 * Single 域: suspendedAgents=null, disambiguation=null
 * Multi 域: suspendedAgents/disambiguation 可用
 */
@Data
public class DomainState implements Serializable {

    /** 域名: "TRANSFER", "BILL", "WEALTH" */
    private String domain;

    /** 当前活跃子图信息 (Single/Multi 都有) */
    private ActiveAgentInfo activeAgent;

    /** 挂起的子图 (仅 Multi, Single 为 null) */
    private Map<String, SuspendedInfo> suspendedAgents;

    /** 意图消歧状态 (仅 Multi, Single 为 null) */
    private DisambiguationState disambiguation;

    /** L2 子图业务数据快照 */
    private Map<String, SubAgentState> subAgents;

    public DomainState() {
    }

    public DomainState(String domain) {
        this.domain = domain;
        this.subAgents = new HashMap<>();
    }

    /** 获取或创建 subAgents Map */
    public Map<String, SubAgentState> getSubAgents() {
        if (subAgents == null) {
            subAgents = new HashMap<>();
        }
        return subAgents;
    }

    /** 获取或创建 suspendedAgents Map */
    public Map<String, SuspendedInfo> getSuspendedAgents() {
        if (suspendedAgents == null) {
            suspendedAgents = new HashMap<>();
        }
        return suspendedAgents;
    }
}
