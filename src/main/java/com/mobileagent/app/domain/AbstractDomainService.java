package com.mobileagent.app.domain;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.data.ChunkType;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.execution.GlobalSessionStore;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.memory.SessionStateStore;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * L1领域服务抽象基类 - 提取Single/Multi共性的状态管理和工具方法
 *
 * 核心设计（threadId独立架构 + 全局OverAllState注入）:
 * - 每次新执行(SWITCH/首次)生成独立threadId: sessionId + "-" + intent + "-" + hexSuffix
 * - ActiveAgentInfo/SuspendedInfo存储各自的threadId，resume时从存储中取出
 * - L1每次调用L2时(execute/resume)，将当前Session的GlobalSessionContext.data()注入L2
 * - L2图的KeyStrategyFactory作为白名单，"想用就用，不想用不用"
 *
 * 对话历史:
 * - 统一使用 GlobalSessionContext.messages 存取对话历史
 * - L1 只读，BankController 统一写入
 */
@Slf4j
public abstract class AbstractDomainService implements DomainHandler {

    protected final String domainName;
    protected final String logTag;
    protected final String domainKey;
    protected final ContextRouter contextRouter;
    protected final GraphExecutionEngine graphExecutionEngine;
    protected final IntentRegistry intentRegistry;
    protected final GlobalSessionStore globalSessionStore;
    protected final int l1DomainPairs;

    // ==================== 共享状态 ====================

    private final SessionStateStore<ActiveAgentInfo> activeAgentStore;
    private final long activeAgentExpireMinutes;

    // ==================== 共享数据类 ====================

    @Data
    public static class ActiveAgentInfo {
        private final String intent;
        private final String threadId;
        private final Instant createdAt;
        private final Instant expiresAt;
        private String lastQuestion;

        public ActiveAgentInfo(String intent, String threadId, Instant createdAt, Instant expiresAt) {
            this.intent = intent;
            this.threadId = threadId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    @Data
    public static class IntentInfo {
        private final String intentName;
        private final String description;

        public IntentInfo(String intentName, String description) {
            this.intentName = intentName;
            this.description = description;
        }
    }

    // ==================== 构造 ====================

    protected AbstractDomainService(String domainName, String logTag, String domainKey,
                                    ContextRouter contextRouter,
                                    GraphExecutionEngine graphExecutionEngine,
                                    IntentRegistry intentRegistry,
                                    GlobalSessionStore globalSessionStore,
                                    SessionStateStore<ActiveAgentInfo> activeAgentStore,
                                    long activeAgentExpireMinutes,
                                    int l1DomainPairs) {
        this.domainName = domainName;
        this.logTag = logTag;
        this.domainKey = domainKey;
        this.contextRouter = contextRouter;
        this.graphExecutionEngine = graphExecutionEngine;
        this.intentRegistry = intentRegistry;
        this.globalSessionStore = globalSessionStore;
        this.activeAgentStore = activeAgentStore;
        this.activeAgentExpireMinutes = activeAgentExpireMinutes;
        this.l1DomainPairs = l1DomainPairs;
    }

    // ==================== activeAgent管理 ====================

    protected ActiveAgentInfo getOwnActiveAgent(String sessionId) {
        ActiveAgentInfo info = activeAgentStore.get(logTag, sessionId).orElse(null);
        if (info != null && info.getExpiresAt().isBefore(Instant.now())) {
            activeAgentStore.remove(logTag, sessionId);
            log.info("[{}] ActiveAgent expired: session={}, intent={}, createdAt={}",
                    logTag, sessionId, info.getIntent(), info.getCreatedAt());
            return null;
        }
        return info;
    }

    protected void setOwnActiveAgent(String sessionId, String intent, String threadId) {
        if (intent == null) {
            activeAgentStore.remove(logTag, sessionId);
        } else {
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(activeAgentExpireMinutes * 60);
            activeAgentStore.put(logTag, sessionId, new ActiveAgentInfo(intent, threadId, now, expiresAt));
        }
    }

    protected void clearOwnActiveAgent(String sessionId) {
        activeAgentStore.remove(logTag, sessionId);
    }

    protected void cleanupExpiredActiveAgents() {
        activeAgentStore.cleanExpired(logTag, ActiveAgentInfo::isExpired);
    }

    // ==================== 对话历史 ====================

    /**
     * 从 GlobalSessionContext.messages 获取最近X对消息
     *
     * 不按域区分, LLM从内容自身推断上下文
     */
    protected String getFormattedChatHistory(String sessionId) {
        return globalSessionStore.getOrCreate(sessionId).formatRecentMessages(l1DomainPairs);
    }

    // ==================== activeAgent状态管理 ====================

    protected void handleActiveAgentState(String sessionId, StreamChunk chunk) {
        if (!chunk.isTerminal()) return;

        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        if (active == null) return;

        if (chunk.getType() == ChunkType.INTERRUPTED && chunk.getQuestion() != null) {
            active.setLastQuestion(chunk.getQuestion());
        } else if (chunk.getType() == ChunkType.COMPLETE) {
            active.setLastQuestion(null);
            clearOwnActiveAgent(sessionId);
        }
    }

    // ==================== 工具方法 ====================

    protected String generateThreadId(String sessionId, String intent) {
        String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
        return sessionId + "-" + intent + "-" + suffix;
    }

    protected String buildCurrentAgent(String sessionId) {
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        return active != null ? active.getIntent() : "无";
    }

    // ==================== 共享流程方法 ====================

    protected Flux<StreamChunk> resumeActiveAgent(String sessionId, String userInput, ActiveAgentInfo active) {
        log.info("[{}] FOLLOW with own activeAgent: intent={}, threadId={}", logTag, active.getIntent(), active.getThreadId());

        var graph = intentRegistry.getGraph(active.getIntent());
        if (graph == null) {
            return Flux.just(StreamChunk.error("Graph not found for intent: " + active.getIntent()));
        }

        Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();

        return graphExecutionEngine.resumeGraph(graph, active.getIntent(), userInput,
                        active.getThreadId(), globalStateData)
                .doOnNext(chunk -> handleActiveAgentState(sessionId, chunk));
    }

    protected Flux<StreamChunk> executeNewAgent(String sessionId, String intent, String rewrittenInput) {
        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return Flux.just(StreamChunk.error("Graph not found for intent: " + intent));
        }

        String threadId = generateThreadId(sessionId, intent);
        Map<String, Object> input = buildGraphInput(sessionId, rewrittenInput);
        setOwnActiveAgent(sessionId, intent, threadId);

        return graphExecutionEngine.executeGraph(graph, intent, input, threadId)
                .doOnNext(chunk -> handleActiveAgentState(sessionId, chunk));
    }

    private Map<String, Object> buildGraphInput(String sessionId, String rewrittenInput) {
        Map<String, Object> input = new HashMap<>();
        input.put("messages", rewrittenInput);
        input.put("_latestUserInput", rewrittenInput);
        input.put("_question", null);

        Map<String, Object> globalData = globalSessionStore.getOrCreate(sessionId).data();
        if (globalData != null && !globalData.isEmpty()) {
            input.put("_globalStateData", globalData);
            log.debug("[{}] Injected global OverAllState data: keys={}", logTag, globalData.keySet());
        }

        return input;
    }

    // ==================== 抽象方法 ====================

    protected Flux<StreamChunk> tryAutoUpgradeFollowUp(ActiveAgentInfo activeAgent,
                                                     RoutingResult phase2,
                                                     String sessionId, String userInput) {
        if (activeAgent != null && phase2 != null) {
            String identifiedIntent = phase2.getIntentName();
            if (identifiedIntent != null && identifiedIntent.equals(activeAgent.getIntent())) {
                log.info("[{}] Auto-upgrade SWITCH→FOLLOW: identifiedIntent={} matches activeAgent.intent={}",
                        logTag, identifiedIntent, activeAgent.getIntent());
                return resumeActiveAgent(sessionId, userInput, activeAgent);
            }
        }
        return null;
    }

    @Override
    public abstract Flux<StreamChunk> handle(String sessionId, String userInput);

    public abstract void clearSession(String sessionId);

    public abstract String getSessionStateDescription(String sessionId);

    public abstract List<IntentInfo> getHandledIntents();

    @Override
    public String getDomainName() {
        return domainName;
    }
}
