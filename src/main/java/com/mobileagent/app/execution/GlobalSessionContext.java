package com.mobileagent.app.execution;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 全局会话上下文 - 所有会话状态统一存储在 OverAllState 中
 *
 * 设计:
 * - messages key (AppendStrategy): 存储用户/助手的一问一答
 * - _lastDomain key (ReplaceStrategy): 存储最近一次路由的领域信息
 * - 不按域区分, LLM从内容自身推断上下文
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
    private final BaseCheckpointSaver checkpointSaver;

    public GlobalSessionContext(String sessionId, OverAllState state, BaseCheckpointSaver checkpointSaver) {
        this.sessionId = sessionId;
        this.state = state;
        this.checkpointSaver = checkpointSaver;
    }

    // ==================== 读取 ====================

    public <T> Optional<T> value(String key) {
        return state.value(key);
    }

    public Map<String, Object> data() {
        return state.data();
    }

    // ==================== 消息写入 (通过 OverAllState 的 messages key + AppendStrategy) ====================

    public void addUserMessage(String content) {
        state.updateState(Map.of("messages", "用户: " + content));
    }

    public void addAssistantMessage(String content) {
        state.updateState(Map.of("messages", "助手: " + content));
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

    // ==================== 清理 ====================

    public void clear() {
        state.clear();
        log.info("[GlobalSessionContext] Cleared: sessionId={}", sessionId);
    }
}
