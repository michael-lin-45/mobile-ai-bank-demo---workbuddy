package com.mobileagent.app.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 智能体状态管理器 - 已废弃
 *
 * 状态已下沉到各L1领域服务基类自管:
 * - SingleSubAgentDomainService: activeThreads (via AbstractDomainService)
 * - MultiSubAgentDomainService: activeThreads + suspendedAgents + disambiguationStates
 *
 * 此类不再由Spring容器管理。保留源码仅供参考。
 *
 * @deprecated 状态已下沉到AbstractDomainService/SingleSubAgentDomainService/MultiSubAgentDomainService基类
 */
@Slf4j
// @Component — 已移除,状态由基类管理
@Deprecated
public class AgentStateManager {

    public AgentStateManager() {
        log.info("[AgentStateManager] Initialized (deprecated - state now managed by L1 Services)");
    }

    /**
     * @deprecated 使用各L1 Service的clearSession()代替
     */
    @Deprecated
    public void clearSession(String sessionId) {
        // no-op: 由各L1 Service自管
    }

    /**
     * @deprecated 使用各L1 Service的getSessionStateDescription()代替
     */
    @Deprecated
    public String getSessionStateDescription(String sessionId) {
        return "状态已迁移到各L1 Service (deprecated)";
    }
}
