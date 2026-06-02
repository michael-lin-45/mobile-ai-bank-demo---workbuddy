package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.memory.model.DomainState;

/**
 * L1 域状态感知接口 — 声明域在 OverAllState 中的 key 和策略
 *
 * 用法:
 * 1. 在 DomainServiceConfig 中以轻量级匿名 Bean 注册 (零依赖, 与 DomainService 解耦)
 * 2. KeyStrategyFactoryConfig 自动收集所有 DomainStateAware 实现
 * 3. OverAllState 自动注册对应的 key + ReplaceStrategy
 * 4. 新增域只需在 DomainServiceConfig 添加一个匿名 Bean, 零改动框架层
 */
public interface DomainStateAware {

    /** OverAllState 中的 key, 如 "_transferState" */
    String getStateKey();

    /** KeyStrategy, 固定 ReplaceStrategy */
    KeyStrategy getStateStrategy();

    /** 首次创建 DomainState 时的初始值 */
    DomainState initialState();

    /**
     * 工厂方法 — 快速创建 DomainStateAware (默认 ReplaceStrategy + new DomainState(domain))
     *
     * @param stateKey OverAllState 中的 key, 如 "_transferState"
     * @param domain   域名, 如 "TRANSFER"
     */
    static DomainStateAware of(String stateKey, String domain) {
        return new DomainStateAware() {
            @Override public String getStateKey() { return stateKey; }
            @Override public KeyStrategy getStateStrategy() { return new ReplaceStrategy(); }
            @Override public DomainState initialState() { return new DomainState(domain); }
        };
    }
}
