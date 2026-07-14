package com.mobileagent.app.domain;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.data.ChunkType;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.memory.model.ActiveAgentInfo;
import com.mobileagent.app.memory.model.DomainState;
import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.observability.ObservabilityMetrics;
import com.mobileagent.app.router.subgraph.ContextRouter;
import com.mobileagent.app.router.registry.SubGraphRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * L1领域服务抽象基类 — 提取Single/Multi共性的状态管理和工具方法
 *
 * 核心设计（threadId独立架构 + 全局OverAllState注入）:
 * - 每次新执行(SWITCH/首次)生成独立threadId: sessionId + "-" + intent + "-" + hexSuffix
 * - ActiveAgentInfo/SuspendedInfo存储在GlobalSessionContext.state的DomainState中
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
    protected final SubGraphRegistry subGraphRegistry;
    protected final GlobalSessionStateStore globalSessionStore;
    protected final ObservabilityMetrics obsMetrics;
    protected final long activeAgentExpireMinutes;
    protected final int l1DomainPairs;

    // ==================== 共享数据类 ====================

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
                                    SubGraphRegistry subGraphRegistry,
                                    GlobalSessionStateStore globalSessionStore,
                                    ObservabilityMetrics obsMetrics,
                                    long activeAgentExpireMinutes,
                                    int l1DomainPairs) {
        this.domainName = domainName;
        this.logTag = logTag;
        this.domainKey = domainKey;
        this.contextRouter = contextRouter;
        this.graphExecutionEngine = graphExecutionEngine;
        this.subGraphRegistry = subGraphRegistry;
        this.globalSessionStore = globalSessionStore;
        this.obsMetrics = obsMetrics;
        this.activeAgentExpireMinutes = activeAgentExpireMinutes;
        this.l1DomainPairs = l1DomainPairs;
    }

    // ==================== DomainState key ====================

    /** OverAllState 中的 DomainState key, 如 "_transferState", "_billState", "_wealthState" */
    protected String getStateKey() {
        return "_" + domainKey.toLowerCase() + "State";
    }

    // ==================== activeAgent管理 (通过 GlobalSessionContext.updateDomainState) ====================

    protected ActiveAgentInfo getOwnActiveAgent(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        DomainState ds = ctx.getDomainState(getStateKey());
        if (ds == null || ds.getActiveAgent() == null) return null;

        ActiveAgentInfo active = ds.getActiveAgent();
        if (active.isExpired()) {
            ctx.updateDomainState(getStateKey(), d -> d.setActiveAgent(null));
            log.info("[{}] ActiveAgent expired: session={}, intent={}", logTag, sessionId, active.getIntent());
            return null;
        }
        return active;
    }

    protected void setOwnActiveAgent(String sessionId, String intent, String threadId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        if (intent == null) {
            ctx.updateDomainState(getStateKey(), ds -> ds.setActiveAgent(null));
        } else {
            long now = System.currentTimeMillis();
            long expiresAt = now + activeAgentExpireMinutes * 60 * 1000;
            ctx.updateDomainState(getStateKey(), ds ->
                    ds.setActiveAgent(new ActiveAgentInfo(intent, threadId, now, expiresAt)));
        }
    }

    protected void clearOwnActiveAgent(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.updateDomainState(getStateKey(), ds -> ds.setActiveAgent(null));
    }

    /**
     * 追加用户输入到 ActiveAgentInfo.userMessages — 在 executeNewAgent / resumeActiveAgent 时调用
     *
     * <p>首次调用时（executeNewAgent）作为原始输入记入 userMessages[0]，
     * 后续调用时（resumeActiveAgent）追加用户的回答。
     * 编排领养时可通过 userMessages 还原完整的L1交互上下文。
     */
    protected void appendUserMessageToActiveAgent(String sessionId, String message) {
        if (message == null || message.isEmpty()) return;
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.updateDomainState(getStateKey(), ds -> {
            if (ds.getActiveAgent() != null) {
                ds.getActiveAgent().getUserMessages().add(message);
            }
        });
        log.debug("[{}] Appended userMessage to activeAgent: session={}, message={}", logTag, sessionId, message);
    }

    // ==================== 对话历史 ====================

    /** 从 GlobalSessionContext.messages 获取最近X对消息 */
    protected String getFormattedChatHistory(String sessionId) {
        return globalSessionStore.getOrCreate(sessionId).formatRecentMessages(l1DomainPairs);
    }

    // ==================== activeAgent状态管理 ====================

    protected void handleActiveAgentState(String sessionId, StreamChunk chunk) {
        if (!chunk.isTerminal()) return;

        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        if (active == null) return;

        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        if (chunk.getType() == ChunkType.INTERRUPTED && chunk.getQuestion() != null) {
            ctx.updateDomainState(getStateKey(), ds -> {
                if (ds.getActiveAgent() != null) {
                    ds.getActiveAgent().setLastQuestion(chunk.getQuestion());
                }
            });
            // 埋点：追问槽位 +1
            obsMetrics.incrementAskbackSlot();
        } else if (chunk.getType() == ChunkType.COMPLETE) {
            ctx.updateDomainState(getStateKey(), ds -> {
                if (ds.getActiveAgent() != null) {
                    ds.getActiveAgent().setLastQuestion(null);
                }
                ds.setActiveAgent(null);
            });
            // 埋点：L2 完成 → 技能/会话/业务结果
            String intent = active.getIntent();
            obsMetrics.recordSkillOutcome(intent, "success");
            obsMetrics.recordSessionCompleted(intent, "success");
            obsMetrics.recordBusinessOutcome(intent, "success");
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

        obsMetrics.recordStateTransition("FOLLOW");

        var graph = subGraphRegistry.getGraph(active.getIntent());
        if (graph == null) {
            return Flux.just(StreamChunk.error("Graph not found for intent: " + active.getIntent()));
        }

        Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();

        appendUserMessageToActiveAgent(sessionId, userInput);

        return graphExecutionEngine.resumeGraph(graph, active.getIntent(), userInput,
                        active.getThreadId(), globalStateData)
                .doOnNext(chunk -> handleActiveAgentState(sessionId, chunk));
    }

    protected Flux<StreamChunk> executeNewAgent(String sessionId, String intent, String rewrittenInput) {
        var graph = subGraphRegistry.getGraph(intent);
        if (graph == null) {
            return Flux.just(StreamChunk.error("Graph not found for intent: " + intent));
        }

        obsMetrics.recordStateTransition("SWITCH");

        String threadId = generateThreadId(sessionId, intent);
        Map<String, Object> input = buildGraphInput(sessionId, rewrittenInput);
        setOwnActiveAgent(sessionId, intent, threadId);
        appendUserMessageToActiveAgent(sessionId, rewrittenInput);

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

    // ==================== L1 Context Adoption ====================

    /**
     * 自报告 L1 上下文 — 编排接管时调用
     *
     * <p>扫描本域的 activeAgent + suspendedAgents（子类可覆盖 collectSuspendedContexts），
     * 返回结构化快照供编排层汇总和注入。
     */
    @Override
    public L1ContextSnapshot collectL1Context(String sessionId) {
        List<L1ContextSnapshot.ActiveContext> actives = collectActiveContext(sessionId);
        List<L1ContextSnapshot.SuspendedContext> suspended = collectSuspendedContexts(sessionId);
        if ((actives == null || actives.isEmpty()) && (suspended == null || suspended.isEmpty())) {
            return L1ContextSnapshot.EMPTY;
        }
        return new L1ContextSnapshot(domainKey, actives, suspended);
    }

    /** 收集本域的活跃子图上下文 */
    protected List<L1ContextSnapshot.ActiveContext> collectActiveContext(String sessionId) {
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        if (active == null) return List.of();
        return List.of(new L1ContextSnapshot.ActiveContext(
            domainKey, active.getIntent(), active.getThreadId(), active.getLastQuestion(),
            active.getUserMessages() != null ? active.getUserMessages() : List.of()));
    }

    /** 收集本域的挂起子图上下文 — Single返回空，Multi覆盖 */
    protected List<L1ContextSnapshot.SuspendedContext> collectSuspendedContexts(String sessionId) {
        return List.of();
    }

    /**
     * 释放 L1 所有权 — 只释放 inheritedIntents 中的意图，其余保留
     *
     * <p>编排领养的意图：清 activeAgent/suspendedAgents（不 abort L2 checkpoint）
     * <p>未领养的意图：保留不动，用户编排结束后可 resume
     *
     * <p>L2 checkpoint 不在此处 abort：
     * 由 L2GraphTool 接管后通过 _cancelSignal 让 L2 子图走 cancelExecution 干净终止，
     * 或正常执行完毕后 GES 自动清理 checkpoint。
     */
    @Override
    public void releaseL1Resources(String sessionId, List<String> inheritedIntents) {
        if (inheritedIntents == null || inheritedIntents.isEmpty()) return;
        releaseActiveIfInherited(sessionId, inheritedIntents);
        releaseSuspendedIfInherited(sessionId, inheritedIntents);
    }

    /** 释放活跃子图所有权（仅当意图在 inheritedIntents 中）— 不 abort checkpoint */
    protected void releaseActiveIfInherited(String sessionId, List<String> inheritedIntents) {
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        if (active != null && inheritedIntents.contains(active.getIntent())) {
            log.info("[{}] Releasing inherited activeAgent (checkpoint preserved for L2GraphTool): intent={}, threadId={}",
                logTag, active.getIntent(), active.getThreadId());
            clearOwnActiveAgent(sessionId);
        }
    }

    /** 释放挂起子图资源（仅当意图在 inheritedIntents 中） — Single无操作，Multi覆盖 */
    protected void releaseSuspendedIfInherited(String sessionId, List<String> inheritedIntents) {
        // default: no suspended agents to release
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

    // ==================== 意图准确率埋点 ====================

    /**
     * 记录意图准确率（四态标签 §5.7）。
     * 在 L2 执行完成后，由子类调用。
     *
     * @param predictedIntent   L1-LLM2 预测的意图
     * @param actualIntent      L2 实际执行的意图
     * @param wasDisambiguated  是否经过消歧流程
     * @param triggeredReroute  是否触发了 REROUTE
     */
    protected void recordIntentAccuracy(String predictedIntent, String actualIntent,
                                         boolean wasDisambiguated, boolean triggeredReroute) {
        String state;
        if (wasDisambiguated) {
            state = "disambiguated";
        } else if (predictedIntent != null && predictedIntent.equals(actualIntent)) {
            state = "correct";
        } else if (triggeredReroute) {
            state = "error";
        } else {
            state = "fuzzy";
        }
        obsMetrics.recordIntentAccuracy(state,
                predictedIntent != null ? predictedIntent : "UNKNOWN",
                actualIntent != null ? actualIntent : "UNKNOWN");
    }
}
