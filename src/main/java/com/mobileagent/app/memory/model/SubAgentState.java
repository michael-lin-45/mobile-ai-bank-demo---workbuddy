package com.mobileagent.app.memory.model;

import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * L2 子图数据快照
 *
 * L2 子图执行中有自己的 OverAllState, 子图结束前将关键数据打包为 SubAgentState
 * 写回 GlobalSessionContext 的 DomainState.subAgents 中。
 *
 * 设计:
 * - name: 子图名称 (intent name)
 * - interrupted: 是否在中断状态 (interruptBefore 机制)
 * - nextNode: 中断后下一个要执行的节点名称 (仅 interrupted=true 时有意义)
 * - data: 子图自己的业务数据 (由各子图自行定义 key-value)
 */
@Data
public class SubAgentState implements Serializable {

    /** 子图名称 (intent name): "TRANSFER", "WEALTH_CONSULT", "WEALTH_INTERPRET" */
    private String name;

    /** 子图是否在中断状态 (interruptBefore 机制) */
    private boolean interrupted;

    /** 中断后下一个要执行的节点名称 (仅 interrupted=true 时有意义) */
    private String nextNode;

    /** 子图自己的业务数据 (由各子图自行定义 key-value) */
    private Map<String, Object> data;

    public SubAgentState() {
    }

    public SubAgentState(String name, boolean interrupted, String nextNode, Map<String, Object> data) {
        this.name = name;
        this.interrupted = interrupted;
        this.nextNode = nextNode;
        this.data = data != null ? data : new HashMap<>();
    }
}
