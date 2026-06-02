package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.mobileagent.app.memory.model.DomainState;

/**
 * L1 域状态感知接口 — 每个 L1 域服务实现此接口, 声明自己在 OverAllState 中的 key 和策略
 *
 * 用法:
 * 1. L1 域服务实现此接口
 * 2. GlobalSessionStateStore 构造时 Spring 自动收集所有实现
 * 3. OverAllState 自动注册对应的 key + ReplaceStrategy
 * 4. 新增域无需改动 GlobalSessionStateStore
 */
public interface DomainStateAware {

    /** OverAllState 中的 key, 如 "_transferState" */
    String getStateKey();

    /** KeyStrategy, 固定 ReplaceStrategy */
    KeyStrategy getStateStrategy();

    /** 首次创建 DomainState 时的初始值 */
    DomainState initialState();
}
