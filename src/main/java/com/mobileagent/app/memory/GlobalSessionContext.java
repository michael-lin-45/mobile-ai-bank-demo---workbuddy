package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.mobileagent.app.memory.model.DomainState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 全局会话上下文 - 所有会话状态统一存储在 OverAllState 中
 *
 * 设计:
 * - messages key (AppendStrategy): 存储用户/助手的一问一答
 * - _lastDomain key (ReplaceStrategy): 存储最近一次路由的领域信息
 * - _xxxState key (ReplaceStrategy): 每个域一个 key, 存储该域的 DomainState
 *
 * OverAllState 层级:
 * - GlobalSessionContext.state: Session级别, 跨L2子图共享
 * - L2子图的 OverAllState: Graph级别, 每次执行独立
 * - 两者互不干扰, 同名key不冲突
 */
@Slf4j
@Getter
public class GlobalSessionContext {

    /** 最近路由领域信息 - 封装为结构体存入 state._lastDomain */
    public record LastDomainEntry(String domain, long expireAt) implements Serializable {
        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private final String sessionId;
    private final OverAllState state;
    private final int maxStoredPairs;

    public GlobalSessionContext(String sessionId, OverAllState state) {
        this(sessionId, state, 0);
    }

    public GlobalSessionContext(String sessionId, OverAllState state, int maxStoredPairs) {
        this.sessionId = sessionId;
        this.state = state;
        this.maxStoredPairs = maxStoredPairs;
    }

    // ==================== 通用读取 ====================

    public <T> Optional<T> value(String key) {
        return state.value(key);
    }

    public Map<String, Object> data() {
        return state.data();
    }

    // ==================== 消息写入 (通过 OverAllState 的 messages key + AppendStrategy) ====================

    public void addUserMessage(String content) {
        state.updateState(Map.of("messages", "用户: " + content));
        evictMessagesIfNeeded();
    }

    public void addAssistantMessage(String content) {
        state.updateState(Map.of("messages", "助手: " + content));
        evictMessagesIfNeeded();
    }

    // ==================== 消息读取 ====================

    /**
     * 格式化最近X对消息, 供 L0/L1/ChatService prompt 使用
     *
     * @param maxPairs 最大对话对数(1对=1条用户+1条助手)
     * @return 格式化字符串, 如 "用户: 转账\n助手: 好的\n用户: 解读理财"
     */
    @SuppressWarnings("unchecked")
    public String formatRecentMessages(int maxPairs) {
        List<String> messages = (List<String>) (List<?>) state.value("messages")
                .filter(List.class::isInstance)
                .orElse(List.of());

        if (messages.isEmpty()) return "(无历史对话)";

        int maxItems = maxPairs * 2;
        int start = Math.max(0, messages.size() - maxItems);
        List<String> recent = messages.subList(start, messages.size());

        return String.join("\n", recent);
    }

    // ==================== lastDomain管理 (通过 OverAllState 的 _lastDomain key + ReplaceStrategy) ====================

    public void setLastDomain(String domain, long expireAt) {
        state.updateState(Map.of("_lastDomain", new LastDomainEntry(domain, expireAt)));
    }

    public String getLastDomain() {
        LastDomainEntry entry = state.value("_lastDomain")
                .filter(LastDomainEntry.class::isInstance)
                .map(LastDomainEntry.class::cast)
                .orElse(null);
        if (entry == null || entry.isExpired()) return null;
        return entry.domain();
    }

    public void clearLastDomain() {
        state.data().remove("_lastDomain");
    }

    // ==================== DomainState 读写 (每个域一个 OverAllState key, ReplaceStrategy) ====================

    /** 读取 DomainState */
    public DomainState getDomainState(String stateKey) {
        return state.value(stateKey)
                .filter(DomainState.class::isInstance)
                .map(DomainState.class::cast)
                .orElse(null);
    }

    /**
     * 更新 DomainState (read-modify-write 封装)
     *
     * @param stateKey OverAllState 中的 key, 如 "_transferState"
     * @param modifier 修改函数, 如 ds -> ds.setActiveAgent(...)
     */
    public void updateDomainState(String stateKey, Consumer<DomainState> modifier) {
        DomainState ds = state.value(stateKey)
                .filter(DomainState.class::isInstance)
                .map(DomainState.class::cast)
                .orElseGet(() -> new DomainState(domainKeyFromStateKey(stateKey)));
        modifier.accept(ds);
        state.updateState(Map.of(stateKey, ds));
    }

    /** 从 stateKey 反推 domain 名: "_transferState" → "TRANSFER" */
    private String domainKeyFromStateKey(String stateKey) {
        if (stateKey.startsWith("_") && stateKey.endsWith("State")) {
            return stateKey.substring(1, stateKey.length() - 5).toUpperCase();
        }
        return stateKey;
    }

    // ==================== 消息淘汰 (maxStoredPairs 限制) ====================

    /**
     * 当 messages 列表超过 maxStoredPairs * 2 条时, 淘汰最早的记录, 保留最近的完整对.
     * <p>
     * 淘汰策略:
     * - maxStoredPairs <= 0 时不限制, 不淘汰
     * - 仅在消息条数为偶数时淘汰(确保淘汰完整的一问一答对, 不会截断半对)
     * - 使用框架的 ReplaceAllWith 走 AppendStrategy 正规通道替换整个列表
     */
    @SuppressWarnings("unchecked")
    private void evictMessagesIfNeeded() {
        if (maxStoredPairs <= 0) return;

        List<String> messages = (List<String>) (List<?>) state.value("messages")
                .filter(List.class::isInstance)
                .orElse(List.of());

        int maxItems = maxStoredPairs * 2;
        if (messages.size() <= maxItems) return;

        // 奇数条 = 半对(用户消息已写入, 助手消息尚未写入), 等下一条凑成完整对再淘汰
        if (messages.size() % 2 != 0) return;

        List<String> trimmed = new ArrayList<>(
                messages.subList(messages.size() - maxItems, messages.size()));
        state.updateState(Map.of("messages", ReplaceAllWith.of(trimmed)));
        log.debug("[GlobalSessionContext] Evicted {} old messages, remaining: {} (maxStoredPairs={})",
                messages.size() - maxItems, trimmed.size(), maxStoredPairs);
    }

    // ==================== 清理 ====================

    public void clear() {
        state.clear();
        log.info("[GlobalSessionContext] Cleared: sessionId={}", sessionId);
    }
}
