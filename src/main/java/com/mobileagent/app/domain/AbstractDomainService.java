package com.mobileagent.app.domain;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.data.ChunkType;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.data.WorkflowStatus;
import com.mobileagent.app.execution.GlobalSessionStore;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.execution.StreamingChatMemoryWriter;
import com.mobileagent.app.memory.SessionStateStore;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * 不共性的逻辑(由子类实现):
 * - handle(): 控制流完全不同,各自实现
 * - 依赖注入: Single和Multi都用IntentRouter(通过IntentResolver或直接)
 * - 状态: Multi额外有suspendedAgents + disambiguationStates
 *
 * Cancel设计:
 * - L1层不暴露cancel公共方法
 * - 子智能体执行中的取消(如"不转了"): 由L2子Graph的cancelAwareExtractParams检测并处理
 * - Multi消歧中的取消(如"算了"): 由IntentResolver.isCancelExpression()检测 → CANCELLED状态
 */
@Slf4j
public abstract class AbstractDomainService implements DomainHandler {

    protected final String domainName;
    protected final String logTag;
    protected final ChatMemory chatMemory;
    protected final ContextRouter contextRouter;
    protected final GraphExecutionEngine graphExecutionEngine;
    protected final IntentRegistry intentRegistry;
    protected final GlobalSessionStore globalSessionStore;

    // ==================== 共享状态 ====================

    /** 本领域自有的activeAgent (per session) — 通过SessionStateStore抽象,支持InMemory/Redis切换 */
    private final SessionStateStore<ActiveAgentInfo> activeAgentStore;

    /** activeAgent过期时间(分钟) - 与suspendedExpireMinutes保持一致 */
    private final long activeAgentExpireMinutes;

    // ==================== 共享数据类 ====================

    @Data
    public static class ActiveAgentInfo {
        private final String intent;
        private final String threadId;
        private final Instant createdAt;
        private final Instant expiresAt;
        /** L2子智能体最后的提问(INTERRUPTED时设置,COMPLETED时清空) */
        private String lastQuestion;

        public ActiveAgentInfo(String intent, String threadId, Instant createdAt, Instant expiresAt) {
            this.intent = intent;
            this.threadId = threadId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        /** 是否已过期 */
        public boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    /**
     * 意图描述 - 声明此L1服务处理哪些意图
     *
     * 用于:
     * - DomainRouter/L0了解各领域服务的意图范围
     * - 未来reactAgent调度时查询可调度的意图
     * - 状态监控/调试时展示服务能力
     */
    @Data
    public static class IntentInfo {
        /** 意图名称 (如TRANSFER, WEALTH_CONSULT) */
        private final String intentName;
        /** 意图描述 (如"转账操作", "理财咨询与推荐") */
        private final String description;

        public IntentInfo(String intentName, String description) {
            this.intentName = intentName;
            this.description = description;
        }
    }

    // ==================== 构造 ====================

    protected AbstractDomainService(String domainName, String logTag,
                                    ChatMemory chatMemory,
                                    ContextRouter contextRouter,
                                    GraphExecutionEngine graphExecutionEngine,
                                    IntentRegistry intentRegistry,
                                    GlobalSessionStore globalSessionStore,
                                    SessionStateStore<ActiveAgentInfo> activeAgentStore,
                                    long activeAgentExpireMinutes) {
        this.domainName = domainName;
        this.logTag = logTag;
        this.chatMemory = chatMemory;
        this.contextRouter = contextRouter;
        this.graphExecutionEngine = graphExecutionEngine;
        this.intentRegistry = intentRegistry;
        this.globalSessionStore = globalSessionStore;
        this.activeAgentStore = activeAgentStore;
        this.activeAgentExpireMinutes = activeAgentExpireMinutes;
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

    /** 清理过期的activeAgent (由子类的@Scheduled方法调用) */
    protected void cleanupExpiredActiveAgents() {
        activeAgentStore.cleanExpired(logTag, ActiveAgentInfo::isExpired);
    }

    // ==================== ChatMemory管理 ====================

    /**
     * 领域ChatMemory的sessionId — 用 sessionId + "@" + logTag 隔离
     *
     * 4个ChatMemory bean共享同一个ChatMemoryRepository，如果都用原始sessionId，
     * L0和L1的写入会互相覆盖/重复。加前缀后：
     *   全局chatMemory:  "abc123"           → L0写，全局历史
     *   转账chatMemory:  "abc123@TransferService" → L1写，领域历史
     *   理财chatMemory:  "abc123@WealthService"   → L1写，领域历史
     *   账单chatMemory:  "abc123@BillService"     → L1写，领域历史
     */
    protected String domainSessionId(String sessionId) {
        return sessionId + "@" + logTag;
    }

    protected void addUserMessage(String sessionId, String userInput) {
        chatMemory.add(domainSessionId(sessionId), new UserMessage(userInput));
    }

    /**
     * activeAgent状态管理 — 与ChatMemory写入解耦
     *
     * 在doOnNext中调用，处理终结chunk的activeAgent状态变更
     */
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

    /**
     * 生成唯一threadId: sessionId + "-" + intent + "-" + hexSuffix
     *
     * 每次新执行(SWITCH/首次)生成独立threadId，避免同sessionId下
     * Checkpoint自动合并历史脏数据的问题。
     */
    protected String generateThreadId(String sessionId, String intent) {
        String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
        return sessionId + "-" + intent + "-" + suffix;
    }

    /**
     * 构建当前活跃意图字符串(供ContextRouter prompt用)
     */
    protected String buildCurrentAgent(String sessionId) {
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        return active != null ? active.getIntent() : "无";
    }

    // ==================== 共享流程方法 ====================

    /**
      * FOLLOW + activeAgent → resumeGraph模式（threadId独立架构 + 全局OverAllState注入）
      *
      * Single和Multi都有这个分支，逻辑完全一致:
      * 1. addUserMessage
      * 2. resumeGraph → Flux<StreamChunk>
      * 3. doOnNext: AssistantWriter.onChunk + handleActiveAgentState
      */
    protected Flux<StreamChunk> resumeActiveAgent(String sessionId, String userInput, ActiveAgentInfo active) {
        log.info("[{}] FOLLOW with own activeAgent: intent={}, threadId={}", logTag, active.getIntent(), active.getThreadId());
        addUserMessage(sessionId, userInput);

        var graph = intentRegistry.getGraph(active.getIntent());
        if (graph == null) {
            return Flux.just(StreamChunk.error("Graph not found for intent: " + active.getIntent()));
        }

        Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();

        // per-request独立的ChatMemory写入器 — 并发安全
        StreamingChatMemoryWriter.AssistantWriter assistantWriter =
                new StreamingChatMemoryWriter.AssistantWriter(chatMemory, domainSessionId(sessionId));

        return graphExecutionEngine.resumeGraph(graph, active.getIntent(), userInput,
                        active.getThreadId(), globalStateData)
                .doOnNext(chunk -> {
                    assistantWriter.onChunk(chunk);
                    handleActiveAgentState(sessionId, chunk);
                });
    }

    /**
      * 新建agent执行Graph模式 (handleSwitchNew的核心)
      *
      * threadId独立架构 + 全局OverAllState注入:
      * 1. 生成独立threadId（避免Checkpoint脏数据自动合并）
      * 2. 构建input Map（基础输入 + GlobalSessionContext.data()）
      * 3. 取Graph
      * 4. setActiveAgent(sessionId, intent, threadId)
      * 5. executeGraph → Flux<StreamChunk>
      * 6. doOnNext: AssistantWriter.onChunk + handleActiveAgentState
      *
      * 注意: suspend当前agent的逻辑由子类在调用前处理(Single不suspend, Multi先suspend再调用)
      *
      * @param sessionId 会话ID
      * @param intent 意图名(Single为固定值, Multi为动态值)
      * @param rewrittenInput 改写后的输入
      * @return Flux<StreamChunk>
      */
    protected Flux<StreamChunk> executeNewAgent(String sessionId, String intent, String rewrittenInput) {
        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return Flux.just(StreamChunk.error("Graph not found for intent: " + intent));
        }

        // 1. 生成独立threadId
        String threadId = generateThreadId(sessionId, intent);

        // 2. 构建input Map（含全局OverAllState注入）
        Map<String, Object> input = buildGraphInput(sessionId, rewrittenInput);

        // 3. 设置activeAgent（含threadId）
        setOwnActiveAgent(sessionId, intent, threadId);

        // 4. per-request独立的ChatMemory写入器 — 并发安全
        StreamingChatMemoryWriter.AssistantWriter assistantWriter =
                new StreamingChatMemoryWriter.AssistantWriter(chatMemory, domainSessionId(sessionId));

        // 5. 用独立threadId执行Graph
        return graphExecutionEngine.executeGraph(graph, intent, input, threadId)
                .doOnNext(chunk -> {
                    assistantWriter.onChunk(chunk);
                    handleActiveAgentState(sessionId, chunk);
                });
    }

    /**
     * 构建L2子图的input Map
     *
     * 包含基础输入 + 全局OverAllState数据（用 _globalStateData 一个key包住，不打散）。
     * L2图通过 state.value("_globalStateData") 获取整个Map，自己决定怎么用。
     */
    private Map<String, Object> buildGraphInput(String sessionId, String rewrittenInput) {
        Map<String, Object> input = new HashMap<>();
        input.put("messages", rewrittenInput);
        input.put("_latestUserInput", rewrittenInput);
        input.put("_question", null);

        // 全局OverAllState数据作为一个整体注入，L2图自己决定怎么用
        Map<String, Object> globalData = globalSessionStore.getOrCreate(sessionId).data();
        if (globalData != null && !globalData.isEmpty()) {
            input.put("_globalStateData", globalData);
            log.debug("[{}] Injected global OverAllState data: keys={}", logTag, globalData.keySet());
        }

        return input;
    }

    // ==================== 抽象方法 ====================

    /**
     * Auto-upgrade: ContextRouter 判 SWITCH 但 IntentRouter 识别的意图
     * 与 activeAgent 的意图一致 → 降级回 FOLLOW
     *
     * 保护场景: ContextRouter 误判导致意图丢失
     *
     * @return 如果升级成功返回 resumeActiveAgent 结果，否则返回 null（由调用方继续正常流程）
     */
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
        return null; // 不匹配，由调用方继续正常流程
    }

    /**
     * 处理消息 - 控制流由子类各自实现
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param globalChatHistory 全局跨域对话历史(由L0/BankController格式化后传入，不缓存，仅作改写参考)
     */
    public abstract Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory);

    /** 清除会话所有状态 */
    public abstract void clearSession(String sessionId);

    /** 获取会话状态描述 */
    public abstract String getSessionStateDescription(String sessionId);

    /**
     * 获取此领域服务处理的所有意图及其描述
     *
     * 供外部(BankController/DomainRouter/未来reactAgent)查询此服务的意图范围。
     * Single返回1个, Multi返回N个。
     */
    public abstract List<IntentInfo> getHandledIntents();

    /** 获取领域名称 */
    public String getDomainName() {
        return domainName;
    }
}
