package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 全局会话状态存储 — 管理 session 级别的 GlobalSessionContext
 *
 * 核心设计:
 * - GlobalSessionContext 包含一个全局 OverAllState, 类似"全局版"的 L2 子图 OverAllState
 * - KeyStrategyFactory 由 KeyStrategyFactoryConfig 创建, 包含公共 key + 每个 DomainStateAware 域的 key
 * - 存储后端通过 GlobalSessionRepository 接口抽象, 支持 InMemory / Redis 切换
 *
 * 数据流:
 * - 注入(进): L1 executeGraph/resumeGraph 前 → 从 GlobalSessionContext.data() 读取 → 注入 L2 input/extStateData
 * - 回写(出): L2 执行完毕后 → DomainService 提取本域数据 → 写入 GlobalSessionContext
 */
@Slf4j
@Component
public class GlobalSessionStateStore {

    private final KeyStrategyFactory keyStrategyFactory;
    private final GlobalSessionRepository repository;
    private final int maxStoredPairs;

    public GlobalSessionStateStore(@Qualifier("globalKeyStrategyFactory") KeyStrategyFactory keyStrategyFactory,
                                    GlobalSessionRepository repository,
                                    @Value("${routing.history.max-stored-pairs:20}") int maxStoredPairs) {
        this.keyStrategyFactory = keyStrategyFactory;
        this.repository = repository;
        this.maxStoredPairs = maxStoredPairs;
        log.info("[GlobalSessionStateStore] Initialized, repository={}, maxStoredPairs={}",
                repository.getClass().getSimpleName(), maxStoredPairs);
    }

    // ==================== GlobalSessionContext 管理 ====================

    /** 获取或创建 session 的 GlobalSessionContext */
    public GlobalSessionContext getOrCreate(String sessionId) {
        GlobalSessionContext ctx = repository.get(sessionId);
        if (ctx != null) return ctx;
        return createAndStore(sessionId);
    }

    private GlobalSessionContext createAndStore(String sessionId) {
        log.info("[GlobalSessionStateStore] Creating new GlobalSessionContext for sessionId={}", sessionId);

        OverAllState state = new OverAllState();
        state.registerKeyAndStrategy(keyStrategyFactory.apply());

        GlobalSessionContext ctx = new GlobalSessionContext(sessionId, state, maxStoredPairs);
        repository.put(sessionId, ctx);
        return ctx;
    }

    // ==================== Session 生命周期 ====================

    /** 清理 session (BankController.clearSession 时调用) */
    public void clearSession(String sessionId) {
        repository.remove(sessionId);
        log.info("[GlobalSessionStateStore] Cleared session: sessionId={}", sessionId);
    }
}
