package com.mobileagent.app.execution;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.memory.CheckpointSaverConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局会话状态存储 - 管理 session 级别的 GlobalSessionContext
 *
 * 核心设计:
 * - GlobalSessionContext 包含一个全局 OverAllState，类似"全局版"的 L2 子图 OverAllState
 * - GlobalSessionStore 持有独立的 MemorySaver，用于持久化 GlobalSessionContext
 * - L2 子图各自维护自己的 MemorySaver + OverAllState（不变）
 *
 * 数据流:
 * - 注入(进): L1 executeGraph/resumeGraph 前 → 从 GlobalSessionContext.data() 读取 → 注入 L2 input/extStateData
 * - 回写(出): L2 执行完毕后 → DomainService 提取本域数据 → 写入 GlobalSessionContext
 *
 * OverAllState 创建方式:
 * - 使用公共构造器 + registerKeyAndStrategy() 注册所有域的 key strategy
 * - 效果等价：GlobalSessionContext 的 OverAllState 能识别所有域的 key
 */
@Slf4j
@Component
public class GlobalSessionStore {

    /** 全局 KeyStrategyFactory — 包含所有域的 key，用于创建 GlobalSessionContext 的 OverAllState */
    private final KeyStrategyFactory keyStrategyFactory;

    /** CheckpointSaver工厂 — 创建独立的saver实例给GlobalSessionContext */
    private final CheckpointSaverConfig.CheckpointSaverFactory checkpointSaverFactory;

    /** sessionId → GlobalSessionContext */
    private final Map<String, GlobalSessionContext> sessionContexts = new ConcurrentHashMap<>();

    public GlobalSessionStore(CheckpointSaverConfig.CheckpointSaverFactory checkpointSaverFactory) {
        this.keyStrategyFactory = createUnifiedKeyStrategyFactory();
        this.checkpointSaverFactory = checkpointSaverFactory;
        log.info("[GlobalSessionStore] Initialized with unified KeyStrategyFactory + CheckpointSaverFactory");
    }

    // ==================== GlobalSessionContext 管理 ====================

    /**
     * 获取或创建 session 的 GlobalSessionContext
     *
     * GlobalSessionContext 内含一个 OverAllState，通过 registerKeyAndStrategy
     * 注册了所有域的 key strategy，使其能理解跨域数据。
     */
    public GlobalSessionContext getOrCreate(String sessionId) {
        return sessionContexts.computeIfAbsent(sessionId, this::createNewContext);
    }

    private GlobalSessionContext createNewContext(String sessionId) {
        log.info("[GlobalSessionStore] Creating new GlobalSessionContext for sessionId={}", sessionId);

        Map<String, KeyStrategy> strategies = keyStrategyFactory.apply();
        OverAllState state = new OverAllState();
        state.registerKeyAndStrategy(strategies);

        BaseCheckpointSaver saver = checkpointSaverFactory.create();

        return new GlobalSessionContext(sessionId, state, saver);
    }

    // ==================== Session 生命周期 ====================

    /** 清理 session（BankController.clearSession 时调用） */
    public void clearSession(String sessionId) {
        sessionContexts.remove(sessionId);
        log.info("[GlobalSessionStore] Cleared session: sessionId={}", sessionId);
    }

    // ==================== 统一 KeyStrategyFactory ====================

    /**
     * 创建统一 KeyStrategyFactory — 包含所有域的 key
     *
     * 这是 GlobalSessionContext 的 OverAllState 能理解跨域数据的基础。
     * 当 L2 子图回写 {transfer.receiver=张三, transfer.amount=500} 到 GlobalSessionContext，
     * GlobalSessionContext 的 OverAllState 能正确处理这些 key（ReplaceStrategy）。
     *
     * 命名规范（硬性约束）: 所有业务 key 必须以意图前缀开头
     *   TRANSFER         → transfer.*
     *   BILL_QUERY       → bill.*
     *   WEALTH_CONSULT   → wealth.*
     *   WEALTH_INTERPRET → wealth.*
     */
    private KeyStrategyFactory createUnifiedKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();

            // 公共 keys（与 AbstractGraphConfig.createKeyStrategyFactory 保持一致）
            strategies.put("messages", new AppendStrategy());
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("_question", new ReplaceStrategy());
            strategies.put("_paramName", new ReplaceStrategy());
            strategies.put("_outputContent", new ReplaceStrategy());
            strategies.put("_outputType", new ReplaceStrategy());
            strategies.put("_isFinal", new ReplaceStrategy());
            strategies.put("_cancelSignal", new ReplaceStrategy());
            strategies.put("_globalStateData", new ReplaceStrategy());
            strategies.put("_lastDomain", new ReplaceStrategy());

            // Transfer 域
            strategies.put("transfer.receiver", new ReplaceStrategy());
            strategies.put("transfer.amount", new ReplaceStrategy());
            strategies.put("transfer.purpose", new ReplaceStrategy());

            // Bill 域
            strategies.put("bill.timePeriod", new ReplaceStrategy());
            strategies.put("bill.expenseType", new ReplaceStrategy());

            // Wealth 域
            strategies.put("wealth.riskLevel", new ReplaceStrategy());
            strategies.put("wealth.focusArea", new ReplaceStrategy());
            strategies.put("wealth.productName", new ReplaceStrategy());
            strategies.put("wealth.interpretResult", new ReplaceStrategy());

            return strategies;
        };
    }
}
