package com.mobileagent.app.state;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体状态管理器 - 管理多意图对话的线程状态
 *
 * 核心数据表:
 * 1. activeThreads: 当前活跃线程 (sessionId → ActiveThreadInfo)
 * 2. suspendedAgents: 挂起的意图线程 (sessionId → {intent → SuspendedInfo})
 * 3. disambiguationStates: 消歧状态 (sessionId → DisambiguationState)
 */
@Slf4j
@Component
public class AgentStateManager {

    /** 当前活跃线程 */
    private final Map<String, ActiveThreadInfo> activeThreads = new ConcurrentHashMap<>();

    /** 挂起的意图线程表 */
    private final Map<String, Map<String, SuspendedInfo>> suspendedAgents = new ConcurrentHashMap<>();

    /** 消歧状态表 */
    private final Map<String, DisambiguationState> disambiguationStates = new ConcurrentHashMap<>();

    /** 最大挂起深度 */
    private static final int MAX_SUSPENDED_DEPTH = 3;

    /** 挂起超时时间(秒) */
    private static final long SUSPENDED_EXPIRE_SECONDS = 1800; // 30分钟

    // --- 活跃线程管理 ---

    public void setActiveThread(String sessionId, String threadId, String intent) {
        if (threadId == null || intent == null) {
            activeThreads.remove(sessionId);
            log.debug("[StateMgr] Cleared active thread for session={}", sessionId);
        } else {
            activeThreads.put(sessionId, new ActiveThreadInfo(threadId, intent, Instant.now()));
            log.debug("[StateMgr] Set active thread: session={}, threadId={}, intent={}", sessionId, threadId, intent);
        }
    }

    public ActiveThreadInfo getActiveThread(String sessionId) {
        ActiveThreadInfo info = activeThreads.get(sessionId);
        if (info != null) {
            log.debug("[StateMgr] Get active thread: session={}, threadId={}, intent={}", sessionId, info.getThreadId(), info.getIntent());
        } else {
            log.debug("[StateMgr] No active thread for session={}", sessionId);
        }
        return info;
    }

    public void clearActiveThread(String sessionId) {
        activeThreads.remove(sessionId);
        log.debug("[StateMgr] Cleared active thread for session={}", sessionId);
    }

    // --- 挂起管理 ---

    public void suspendAgent(String sessionId, String intent, String threadId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

        // 检查深度限制
        if (!sessionMap.containsKey(intent) && sessionMap.size() >= MAX_SUSPENDED_DEPTH) {
            // 移除最早的挂起项
            String oldestKey = sessionMap.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().getSuspendedAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestKey != null) {
                sessionMap.remove(oldestKey);
                log.warn("[StateMgr] Exceeded max suspended depth, evicted oldest: {}", oldestKey);
            }
        }

        SuspendedInfo info = new SuspendedInfo(threadId, intent, Instant.now(), Instant.now().plusSeconds(SUSPENDED_EXPIRE_SECONDS));
        // 继承activeThread的累积参数
        ActiveThreadInfo active = activeThreads.get(sessionId);
        if (active != null && active.getIntent().equals(intent)) {
            info.setAccumulatedParams(active.getAccumulatedParams());
        }
        sessionMap.put(intent, info);
        log.debug("[StateMgr] Suspended agent: session={}, intent={}, threadId={}, params={}", sessionId, intent, threadId, info.getAccumulatedParams());
    }

    public SuspendedInfo getSuspendedThread(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap == null) return null;
        SuspendedInfo info = sessionMap.get(intent);
        if (info != null && info.getExpiresAt().isBefore(Instant.now())) {
            sessionMap.remove(intent);
            log.debug("[StateMgr] Expired suspended agent removed: session={}, intent={}", sessionId, intent);
            return null;
        }
        return info;
    }

    public Map<String, SuspendedInfo> getAllSuspended(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap == null) return Collections.emptyMap();
        // 清理过期项
        sessionMap.entrySet().removeIf(e -> e.getValue().getExpiresAt().isBefore(Instant.now()));
        return Collections.unmodifiableMap(sessionMap);
    }

    public void resumeAgent(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap != null) {
            sessionMap.remove(intent);
            log.debug("[StateMgr] Resumed agent: session={}, intent={}", sessionId, intent);
        }
    }

    public boolean hasSuspendedAgents(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap == null || sessionMap.isEmpty()) return false;
        sessionMap.entrySet().removeIf(e -> e.getValue().getExpiresAt().isBefore(Instant.now()));
        return !sessionMap.isEmpty();
    }

    /** 清除会话所有状态 */
    public void clearSession(String sessionId) {
        activeThreads.remove(sessionId);
        suspendedAgents.remove(sessionId);
        disambiguationStates.remove(sessionId);
        log.debug("[StateMgr] Cleared all session state: session={}", sessionId);
    }

    /** 任务完成时清理: 清空activeThread + 从suspended移除 */
    public void completeAgent(String sessionId, String intent) {
        activeThreads.remove(sessionId);
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap != null) {
            sessionMap.remove(intent);
        }
        log.debug("[StateMgr] Completed agent: session={}, intent={}", sessionId, intent);
    }

    // --- 消歧状态管理 ---

    public void setDisambiguationState(String sessionId, DisambiguationState state) {
        disambiguationStates.put(sessionId, state);
        log.debug("[StateMgr] Set disambiguation state: session={}, groupId={}",
                sessionId, state.getGroupId());
    }

    public DisambiguationState getDisambiguationState(String sessionId) {
        return disambiguationStates.get(sessionId);
    }

    public void clearDisambiguationState(String sessionId) {
        disambiguationStates.remove(sessionId);
        log.debug("[StateMgr] Cleared disambiguation state: session={}", sessionId);
    }

    public boolean isInDisambiguation(String sessionId) {
        DisambiguationState state = disambiguationStates.get(sessionId);
        return state != null;
    }

    /** 生成当前会话状态描述(供LLM使用) */
    public String getSessionStateDescription(String sessionId) {
        StringBuilder sb = new StringBuilder();
        ActiveThreadInfo active = getActiveThread(sessionId);
        Map<String, SuspendedInfo> suspended = getAllSuspended(sessionId);
        DisambiguationState disambiguation = disambiguationStates.get(sessionId);

        if (disambiguation != null) {
            sb.append("当前在消歧模式: 意图组=").append(disambiguation.getGroupId());
        } else if (active != null) {
            sb.append("当前活跃意图: ").append(active.getIntent())
              .append(" (线程: ").append(active.getThreadId().substring(0, 8)).append("...)");
        } else {
            sb.append("当前无活跃意图");
        }

        if (!suspended.isEmpty()) {
            sb.append("\n挂起的意图: ");
            for (SuspendedInfo info : suspended.values()) {
                sb.append(info.getIntent()).append(" ");
            }
        }

        return sb.toString();
    }

    // --- 数据类 ---

    @Data
    public static class ActiveThreadInfo {
        private final String threadId;
        private final String intent;
        private final Instant createdAt;
        /** 累积的参数 (如transfer.receiver, transfer.amount等) */
        private Map<String, Object> accumulatedParams = new HashMap<>();

        public ActiveThreadInfo(String threadId, String intent, Instant createdAt) {
            this.threadId = threadId;
            this.intent = intent;
            this.createdAt = createdAt;
        }

        public void setAccumulatedParams(Map<String, Object> params) {
            this.accumulatedParams = params != null ? new HashMap<>(params) : new HashMap<>();
        }
    }

    @Data
    public static class SuspendedInfo {
        private final String threadId;
        private final String intent;
        private final Instant suspendedAt;
        private final Instant expiresAt;
        /** 累积的参数 (从activeThread继承) */
        private Map<String, Object> accumulatedParams = new HashMap<>();

        public SuspendedInfo(String threadId, String intent, Instant suspendedAt, Instant expiresAt) {
            this.threadId = threadId;
            this.intent = intent;
            this.suspendedAt = suspendedAt;
            this.expiresAt = expiresAt;
        }

        public void setAccumulatedParams(Map<String, Object> params) {
            this.accumulatedParams = params != null ? new HashMap<>(params) : new HashMap<>();
        }
    }

    /**
     * 消歧状态 - Controller层追问用户明确意图时使用
     * 最小状态: 只保留groupId, 用于下次回答时知道消歧哪个意图组
     */
    @Data
    public static class DisambiguationState {
        private final String groupId;

        public DisambiguationState(String groupId) {
            this.groupId = groupId;
        }
    }
}
