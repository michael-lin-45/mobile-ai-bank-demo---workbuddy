package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.memory.SessionStateStore;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.router.IntentRouter;
import com.mobileagent.app.execution.GlobalSessionStore;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.util.TemplateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 单子智能体领域服务 - 1-1关系 (1个L1对应1个L2)
 *
 * 适用场景: 转账(TRANSFER), 账单(BILL_QUERY), 余额查询, 支付, 存款 等
 * 特点: 只有一个子意图，无需suspendedAgents、无需消歧
 *
 * 控制流:
 * 1. activeAgent在 + lastQuestion在 → ContextRouter(含lastQuestion) → FOLLOW/SWITCH
 *    - FOLLOW → resumeActiveAgent
 *    - SWITCH → IntentRouter → auto-upgrade检查 → REROUTE检查 → executeNewAgent
 * 2. activeAgent在 + lastQuestion为空 → 直接resumeActiveAgent
 * 3. 无activeAgent → ContextRouter → IntentRouter → REROUTE检查 → executeNewAgent
 *
 * Cancel:
 * - 不暴露cancel公共方法
 * - 用户说"取消"→ FOLLOW → resumeGraph → 子Graph的cancelAwareExtractParams检测并处理
 */
@Slf4j
public class SingleSubAgentDomainService extends AbstractDomainService {

    private static final String DEFAULT_ROUTING_TEMPLATE = "prompts/l1-routing-simple.st";
    private static final String DEFAULT_INTENTION_TEMPLATE = "prompts/l1-intention.st";

    private final String intent;
    private final String intentDescription;
    private final String routingTemplatePath;
    private final String intentionTemplatePath;
    private final IntentRouter intentRouter;

    // ==================== 构造(Builder) ====================

    private SingleSubAgentDomainService(Builder builder) {
        super(builder.domainName, builder.logTag, builder.chatMemory,
                builder.contextRouter, builder.graphExecutionEngine, builder.intentRegistry,
                builder.globalSessionStore, builder.activeAgentStore, builder.activeAgentExpireMinutes);
        this.intent = builder.intent;
        this.intentDescription = builder.intentDescription;
        this.routingTemplatePath = builder.routingTemplatePath != null
                ? builder.routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
        this.intentionTemplatePath = builder.intentionTemplatePath != null
                ? builder.intentionTemplatePath : DEFAULT_INTENTION_TEMPLATE;
        this.intentRouter = builder.intentRouter;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String domainName;
        private String logTag;
        private String intent;
        private String intentDescription;
        private String routingTemplatePath;
        private String intentionTemplatePath;
        private ChatMemory chatMemory;
        private ContextRouter contextRouter;
        private IntentRouter intentRouter;
        private GraphExecutionEngine graphExecutionEngine;
        private IntentRegistry intentRegistry;
        private GlobalSessionStore globalSessionStore;
        private SessionStateStore<ActiveAgentInfo> activeAgentStore;
        private long activeAgentExpireMinutes = 20;

        public Builder domainName(String domainName) { this.domainName = domainName; return this; }
        public Builder logTag(String logTag) { this.logTag = logTag; return this; }
        public Builder intent(String intent) { this.intent = intent; return this; }
        public Builder intentDescription(String intentDescription) { this.intentDescription = intentDescription; return this; }
        public Builder routingTemplatePath(String routingTemplatePath) { this.routingTemplatePath = routingTemplatePath; return this; }
        public Builder intentionTemplatePath(String intentionTemplatePath) { this.intentionTemplatePath = intentionTemplatePath; return this; }
        public Builder chatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; return this; }
        public Builder contextRouter(ContextRouter contextRouter) { this.contextRouter = contextRouter; return this; }
        public Builder intentRouter(IntentRouter intentRouter) { this.intentRouter = intentRouter; return this; }
        public Builder graphExecutionEngine(GraphExecutionEngine graphExecutionEngine) { this.graphExecutionEngine = graphExecutionEngine; return this; }
        public Builder intentRegistry(IntentRegistry intentRegistry) { this.intentRegistry = intentRegistry; return this; }
        public Builder globalSessionStore(GlobalSessionStore globalSessionStore) { this.globalSessionStore = globalSessionStore; return this; }
        public Builder activeAgentStore(SessionStateStore<ActiveAgentInfo> activeAgentStore) { this.activeAgentStore = activeAgentStore; return this; }
        public Builder activeAgentExpireMinutes(long activeAgentExpireMinutes) { this.activeAgentExpireMinutes = activeAgentExpireMinutes; return this; }

        public SingleSubAgentDomainService build() {
            Objects.requireNonNull(domainName, "domainName is required");
            Objects.requireNonNull(logTag, "logTag is required");
            Objects.requireNonNull(intent, "intent is required");
            Objects.requireNonNull(chatMemory, "chatMemory is required");
            Objects.requireNonNull(contextRouter, "contextRouter is required");
            Objects.requireNonNull(intentRouter, "intentRouter is required");
            Objects.requireNonNull(graphExecutionEngine, "graphExecutionEngine is required");
            Objects.requireNonNull(intentRegistry, "intentRegistry is required");
            Objects.requireNonNull(globalSessionStore, "globalSessionStore is required");
            Objects.requireNonNull(activeAgentStore, "activeAgentStore is required");
            // 预热模板: 构造时加载到缓存,运行时零IO
            String resolvedRoutingPath = routingTemplatePath != null ? routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
            String resolvedIntentionPath = intentionTemplatePath != null ? intentionTemplatePath : DEFAULT_INTENTION_TEMPLATE;
            TemplateUtils.warmUp(resolvedRoutingPath, () -> "");
            TemplateUtils.warmUp(resolvedIntentionPath, () -> "");
            return new SingleSubAgentDomainService(this);
        }
    }

    // ==================== 主入口 ====================

    @Override
    public Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory) {
        log.info("[{}] Handling: sessionId={}, input={}", logTag, sessionId, userInput);

        try {
            ActiveAgentInfo ownActive = getOwnActiveAgent(sessionId);

            // ========== activeAgent在 + lastQuestion在 → 走ContextRouter(含lastQuestion) ==========
            if (ownActive != null && ownActive.getLastQuestion() != null) {
                return handleWithLastQuestion(sessionId, userInput, globalChatHistory, ownActive);
            }

            // ========== activeAgent在 + lastQuestion为空 → 直接FOLLOW ==========
            if (ownActive != null) {
                return resumeActiveAgent(sessionId, userInput, ownActive);
            }

            // ========== 无activeAgent → ContextRouter + IntentRouter ==========
            return handleNewIntention(sessionId, userInput, globalChatHistory);

        } catch (Exception e) {
            log.error("[{}] Error handling message", logTag, e);
            return Flux.just(StreamChunk.error("处理" + domainName + "请求时出错: " + e.getMessage()));
        }
    }

    /**
     * activeAgent + lastQuestion 场景
     * ContextRouter精准判断用户是否在回答子智能体的问题
     */
    private Flux<StreamChunk> handleWithLastQuestion(String sessionId, String userInput,
                                                      String globalChatHistory, ActiveAgentInfo ownActive) {
        String currentAgent = ownActive.getIntent();
        String pendingAgents = "无";

        RoutingResult phase1 = contextRouter.route(domainSessionId(sessionId), userInput,
                currentAgent, pendingAgents,
                routingTemplatePath, domainName, chatMemory,
                ownActive.getLastQuestion());
        log.info("[{}] Phase1 (activeAgent+lastQuestion): routeType={}, confidence={}",
                logTag, phase1.getRouteType(), phase1.getConfidence());

        if (phase1.isFollow()) {
            return resumeActiveAgent(sessionId, userInput, ownActive);
        }

        // SWITCH → Phase2 IntentRouter
        RoutingResult phase2 = runIntentRouter(sessionId, userInput, phase1,
                currentAgent, pendingAgents, globalChatHistory);

        // REROUTE判断: 意图不属于本域 (必须在auto-upgrade之前，否则知识FAQ会被误升级为FOLLOW)
        if (!phase2.isBelongsToDomain()) {
            log.info("[{}] REROUTE: belongsToDomain=false, intent={}", logTag, phase2.getIntentName());
            return Flux.just(StreamChunk.reroute(phase2.getIntentName(), null));
        }

        // Auto-upgrade保护: IntentRouter识别的意图与activeAgent一致 → 降级回FOLLOW
        Flux<StreamChunk> upgraded = tryAutoUpgradeFollowUp(ownActive, phase2, sessionId, userInput);
        if (upgraded != null) return upgraded;

        addUserMessage(sessionId, userInput);
        return handleSwitchNew(sessionId, phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput);
    }

    /**
     * 无activeAgent场景 — ContextRouter + IntentRouter + REROUTE检查
     */
    private Flux<StreamChunk> handleNewIntention(String sessionId, String userInput, String globalChatHistory) {
        String currentAgent = "无";
        String pendingAgents = "无";

        RoutingResult phase1 = contextRouter.route(domainSessionId(sessionId), userInput,
                currentAgent, pendingAgents,
                routingTemplatePath, domainName, chatMemory, null);
        log.info("[{}] Phase1 (no activeAgent): routeType={}, confidence={}",
                logTag, phase1.getRouteType(), phase1.getConfidence());

        RoutingResult phase2 = runIntentRouter(sessionId, userInput, phase1,
                currentAgent, pendingAgents, globalChatHistory);

        // REROUTE判断
        if (!phase2.isBelongsToDomain()) {
            log.info("[{}] REROUTE: belongsToDomain=false, intent={}", logTag, phase2.getIntentName());
            return Flux.just(StreamChunk.reroute(phase2.getIntentName(), null));
        }

        String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
        addUserMessage(sessionId, userInput);
        return handleSwitchNew(sessionId, rewrittenInput);
    }

    /**
     * 调用IntentRouter做意图识别+改写
     */
    private RoutingResult runIntentRouter(String sessionId, String userInput, RoutingResult phase1,
                                           String currentAgent, String pendingAgents,
                                           String globalChatHistory) {
        String sessionState = "Phase1路由: " + phase1.getRouteType();
        String disambigContext = "无"; // Single无消歧
        String domainIntentScopeList = intentRegistry.getDomainIntentScopeDescription(List.of(intent));

        RoutingResult phase2 = intentRouter.rewriteAndIdentify(domainSessionId(sessionId), userInput, phase1,
                currentAgent, pendingAgents, sessionState, disambigContext,
                intentionTemplatePath, chatMemory, globalChatHistory, domainIntentScopeList);
        log.info("[{}] Phase2 IntentRouter: intent={}, belongsToDomain={}, rewritten=[{}]",
                logTag, phase2.getIntentName(), phase2.isBelongsToDomain(), phase2.getRewrittenInput());
        return phase2;
    }

    // ==================== SWITCH执行 ====================

    private Flux<StreamChunk> handleSwitchNew(String sessionId, String rewrittenInput) {
        return executeNewAgent(sessionId, intent, rewrittenInput);
    }

    // ==================== 定时清理 ====================

    @Scheduled(fixedRate = 60_000)
    public void scheduledCleanup() {
        cleanupExpiredActiveAgents();
    }

    // ==================== 状态管理 ====================

    @Override
    public void clearSession(String sessionId) {
        clearOwnActiveAgent(sessionId);
    }

    @Override
    public String getSessionStateDescription(String sessionId) {
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        if (active != null) {
            return "当前活跃意图: " + active.getIntent();
        }
        return "当前无活跃意图";
    }

    @Override
    public List<IntentInfo> getHandledIntents() {
        return List.of(new IntentInfo(intent,
                intentDescription != null ? intentDescription : domainName));
    }
}
