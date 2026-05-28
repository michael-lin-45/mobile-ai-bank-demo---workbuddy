package com.mobileagent.app.execution;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * 全局会话上下文 - 包含一个全局 OverAllState
 *
 * 设计:
 * - 组合而非继承：GlobalSessionContext 包含一个 OverAllState
 * - OverAllState 注册了所有域的 KeyStrategy，能理解跨域数据
 * - 外层 GlobalSessionStore 持有独立的 MemorySaver，用于持久化此 OverAllState
 *
 * 生命周期:
 * - 创建: GlobalSessionStore.getOrCreate(sessionId)
 * - 读取: data() — L1 调用 L2 时将全局数据注入 L2 的 input/extStateData
 * - 持久化: 通过 MemorySaver 保存 Checkpoint（未来启用）
 * - 销毁: GlobalSessionStore.clearSession(sessionId)
 *
 * 与 L2 子图 OverAllState 的关系:
 * - L2 子图的 OverAllState 是运行时对象，stream() 返回后消亡
 * - GlobalSessionContext 的 OverAllState 是 session 级持久对象，跨 L2 执行存活
 * - L1 调用 L2 时注入 ← GlobalSessionContext.data()
 * - L2 执行完毕后回写 → GlobalSessionContext（待实现）
 */
@Slf4j
@Getter
public class GlobalSessionContext {

    private final String sessionId;
    private final OverAllState state;
    private final MemorySaver memorySaver;

    public GlobalSessionContext(String sessionId, OverAllState state, MemorySaver memorySaver) {
        this.sessionId = sessionId;
        this.state = state;
        this.memorySaver = memorySaver;
    }

    // ==================== 读取 ====================

    public <T> Optional<T> value(String key) {
        return state.value(key);
    }

    public Map<String, Object> data() {
        return state.data();
    }

    // ==================== 清理 ====================

    public void clear() {
        state.clear();
        log.info("[GlobalSessionContext] Cleared: sessionId={}", sessionId);
    }
}
