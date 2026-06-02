package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.memory.model.ActiveAgentInfo;
import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.router.IntentRouter;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.util.TemplateUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 单子智能体领域服务 - 1-1关系 (1个L1对应1个L2)
 *
 * 适用场景: 转账(TRANSFER), 账单(BILL_QUERY) 等
 * 特点: 只有一个子意图，无需suspendedAgents、无需消歧
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
        super(builder.domainName, builder.logTag, builder.domainKey,
                builder.contextRouter, builder.graphExecutionEngine, builder.intentRegistry,
                builder.globalSessionStore, builder.activeAgentExpireMinutes,
                builder.l1DomainPairs);
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
        private String domainKey;
        private String intent;
        private String intentDescription;
        private String routingTemplatePath;
        private String intentionTemplatePath;
        private ContextRouter contextRouter;
        private IntentRouter intentRouter;
        private GraphExecutionEngine graphExecutionEngine;
        private IntentRegistry intentRegistry;
        private GlobalSessionStateStore globalSessionStore;
        private long activeAgentExpireMinutes = 20;
        private int l1DomainPairs = 6;

        public Builder domainName(String domainName) { this.domainName = domainName; return this; }
        public Builder logTag(String logTag) { this.logTag = logTag; return this; }
        public Builder domainKey(String domainKey) { this.domainKey = domainKey; return this; }
        public Builder intent(String intent) { this.intent = intent; return this; }
        public Builder intentDescription(String intentDescription) { this.intentDescription = intentDescription; return this; }
        public Builder routingTemplatePath(String routingTemplatePath) { this.routingTemplatePath = routingTemplatePath; return this; }
        public Builder intentionTemplatePath(String intentionTemplatePath) { this.intentionTemplatePath = intentionTemplatePath; return this; }
        public Builder contextRouter(ContextRouter contextRouter) { this.contextRouter = contextRouter; return this; }
        public Builder intentRouter(IntentRouter intentRouter) { this.intentRouter = intentRouter; return this; }
        public Builder graphExecutionEngine(GraphExecutionEngine graphExecutionEngine) { this.graphExecutionEngine = graphExecutionEngine; return this; }
        public Builder intentRegistry(IntentRegistry intentRegistry) { this.intentRegistry = intentRegistry; return this; }
        public Builder globalSessionStore(GlobalSessionStateStore globalSessionStore) { this.globalSessionStore = globalSessionStore; return this; }
        public Builder activeAgentExpireMinutes(long activeAgentExpireMinutes) { this.activeAgentExpireMinutes = activeAgentExpireMinutes; return this; }
        public Builder l1DomainPairs(int l1DomainPairs) { this.l1DomainPairs = l1DomainPairs; return this; }

        public SingleSubAgentDomainService build() {
            Objects.requireNonNull(domainName, "domainName is required");
            Objects.requireNonNull(logTag, "logTag is required");
            Objects.requireNonNull(domainKey, "domainKey is required");
            Objects.requireNonNull(intent, "intent is required");
            Objects.requireNonNull(contextRouter, "contextRouter is required");
            Objects.requireNonNull(intentRouter, "intentRouter is required");
            Objects.requireNonNull(graphExecutionEngine, "graphExecutionEngine is required");
            Objects.requireNonNull(intentRegistry, "intentRegistry is required");
            Objects.requireNonNull(globalSessionStore, "globalSessionStore is required");
            String resolvedRoutingPath = routingTemplatePath != null ? routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
            String resolvedIntentionPath = intentionTemplatePath != null ? intentionTemplatePath : DEFAULT_INTENTION_TEMPLATE;
            TemplateUtils.warmUp(resolvedRoutingPath);
            TemplateUtils.warmUp(resolvedIntentionPath);
            return new SingleSubAgentDomainService(this);
        }
    }

    // ==================== 主入口 ====================

    @Override
    public Flux<StreamChunk> handle(String sessionId, String userInput) {
        log.info("[{}] Handling: sessionId={}, input={}", logTag, sessionId, userInput);

        try {
            ActiveAgentInfo ownActive = getOwnActiveAgent(sessionId);

            if (ownActive != null && ownActive.getLastQuestion() != null) {
                return handleWithLastQuestion(sessionId, userInput, ownActive);
            }

            if (ownActive != null) {
                return resumeActiveAgent(sessionId, userInput, ownActive);
            }

            return handleNewIntention(sessionId, userInput);

        } catch (Exception e) {
            log.error("[{}] Error handling message", logTag, e);
            return Flux.just(StreamChunk.error("处理" + domainName + "请求时出错: " + e.getMessage()));
        }
    }

    private Flux<StreamChunk> handleWithLastQuestion(String sessionId, String userInput,
                                                      ActiveAgentInfo ownActive) {
        String currentAgent = ownActive.getIntent();
        String pendingAgents = "无";
        String chatHistory = getFormattedChatHistory(sessionId);

        RoutingResult phase1 = contextRouter.route(sessionId, userInput,
                currentAgent, pendingAgents,
                routingTemplatePath, domainName, chatHistory,
                ownActive.getLastQuestion());
        log.info("[{}] Phase1 (activeAgent+lastQuestion): routeType={}, confidence={}",
                logTag, phase1.getRouteType(), phase1.getConfidence());

        if (phase1.isFollow()) {
            return resumeActiveAgent(sessionId, userInput, ownActive);
        }

        RoutingResult phase2 = runIntentRouter(sessionId, userInput, phase1,
                currentAgent, pendingAgents);

        if (!phase2.isBelongsToDomain()) {
            log.info("[{}] REROUTE: belongsToDomain=false, intent={}", logTag, phase2.getIntentName());
            return Flux.just(StreamChunk.reroute(phase2.getIntentName(), null));
        }

        Flux<StreamChunk> upgraded = tryAutoUpgradeFollowUp(ownActive, phase2, sessionId, userInput);
        if (upgraded != null) return upgraded;

        return handleSwitchNew(sessionId, phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput);
    }

    private Flux<StreamChunk> handleNewIntention(String sessionId, String userInput) {
        String currentAgent = "无";
        String pendingAgents = "无";
        String chatHistory = getFormattedChatHistory(sessionId);

        RoutingResult phase1 = contextRouter.route(sessionId, userInput,
                currentAgent, pendingAgents,
                routingTemplatePath, domainName, chatHistory, null);
        log.info("[{}] Phase1 (no activeAgent): routeType={}, confidence={}",
                logTag, phase1.getRouteType(), phase1.getConfidence());

        RoutingResult phase2 = runIntentRouter(sessionId, userInput, phase1,
                currentAgent, pendingAgents);

        if (!phase2.isBelongsToDomain()) {
            log.info("[{}] REROUTE: belongsToDomain=false, intent={}", logTag, phase2.getIntentName());
            return Flux.just(StreamChunk.reroute(phase2.getIntentName(), null));
        }

        String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
        return handleSwitchNew(sessionId, rewrittenInput);
    }

    private RoutingResult runIntentRouter(String sessionId, String userInput, RoutingResult phase1,
                                           String currentAgent, String pendingAgents) {
        String sessionState = "Phase1路由: " + phase1.getRouteType();
        String disambigContext = "无";
        String domainIntentScopeList = intentRegistry.getDomainIntentScopeDescription(List.of(intent));
        String chatHistory = getFormattedChatHistory(sessionId);

        RoutingResult phase2 = intentRouter.rewriteAndIdentify(sessionId, userInput, phase1,
                currentAgent, pendingAgents, sessionState, disambigContext,
                intentionTemplatePath, chatHistory, domainIntentScopeList);
        log.info("[{}] Phase2 IntentRouter: intent={}, belongsToDomain={}, rewritten=[{}]",
                logTag, phase2.getIntentName(), phase2.isBelongsToDomain(), phase2.getRewrittenInput());
        return phase2;
    }

    // ==================== SWITCH执行 ====================

    private Flux<StreamChunk> handleSwitchNew(String sessionId, String rewrittenInput) {
        return executeNewAgent(sessionId, intent, rewrittenInput);
    }

    // ==================== 状态管理 ====================

    @Override
    public void clearSession(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.updateDomainState(getStateKey(), ds -> {
            ds.setActiveAgent(null);
            ds.setSuspendedAgents(null);
            ds.setDisambiguation(null);
        });
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
