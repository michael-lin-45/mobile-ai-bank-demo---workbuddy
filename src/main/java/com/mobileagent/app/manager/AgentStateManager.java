package com.mobileagent.app.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 智能体状态管理器 - 已废弃
 *
 * 状态已下沉到各L1 Service自管:
 * - TransferService: transferActiveThreads
 * - BillService: billActiveThreads
 * - WealthService: wealthActiveThreads + wealthSuspendedAgents + disambiguationStates
 *
 * 保留此类仅为防止Spring扫描报错，后续重构为SingleSubAgentLayer/MultiSubAgentLayer时彻底移除。
 *
 * @deprecated 状态已下沉到各L1 Service，不再使用全局状态管理器
 */
@Slf4j
@Component
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
