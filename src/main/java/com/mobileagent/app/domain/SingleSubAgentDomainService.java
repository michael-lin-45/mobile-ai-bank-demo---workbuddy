package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.memory.model.ActiveAgentInfo;
import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.router.subgraph.ContextRouter;
import com.mobileagent.app.router.registry.SubGraphRegistry;
import com.mobileagent.app.router.subgraph.SubGraphRouter;
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

    private static final String DEFAULT_ROUTING_TEMPLATE = "prompts/l1-context-simple.st";
    private static final String DEFAULT_INTENTION_TEMPLATE = "prompts/l1-intention.st";

    private final String intent;
    private final String intentDescription;
    private final String contextRoutingTemplatePath;
    private final String intentRoutingTemplatePath;
    private final SubGraphRouter subGraphRouter;

    // ==================== 构造(Builder) ====================

    private SingleSubAgentDomainService(Builder builder) {
        super(builder.domainName, builder.logTag, builder.domainKey,
                builder.contextRouter, builder.graphExecutionEngine, builder.subGraphRegistry,
                builder.globalSessionStore, builder.activeAgentExpireMinutes,
                builder.l1DomainPairs);
        this.intent = builder.intent;
        this.intentDescription = builder.intentDescription;
        this.contextRoutingTemplatePath = builder.contextRoutingTemplatePath != null
                ? builder.contextRoutingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
        this.intentRoutingTemplatePath = builder.intentRoutingTemplatePath != null
                ? builder.intentRoutingTemplatePath : DEFAULT_INTENTION_TEMPLATE;
        this.subGraphRouter = builder.subGraphRouter;
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
        private String contextRoutingTemplatePath;
        private String intentRoutingTemplatePath;
        private ContextRouter contextRouter;
        private SubGraphRouter subGraphRouter;
        private GraphExecutionEngine graphExecutionEngine;
        private SubGraphRegistry subGraphRegistry;
        private GlobalSessionStateStore globalSessionStore;
        private long activeAgentExpireMinutes = 20;
        private int l1DomainPairs = 6;

        public Builder domainName(String domainName) { this.domainName = domainName; return this; }
        public Builder logTag(String logTag) { this.logTag = logTag; return this; }
        public Builder domainKey(String domainKey) { this.domainKey = domainKey; return this; }
        public Builder intent(String intent) { this.intent = intent; return this; }
        public Builder intentDescription(String intentDescription) { this.intentDescription = intentDescription; return this; }
        public Builder contextRoutingTemplatePath(String contextRoutingTemplatePath) { this.contextRoutingTemplatePath = contextRoutingTemplatePath; return this; }
        public Builder intentRoutingTemplatePath(String intentRoutingTemplatePath) { this.intentRoutingTemplatePath = intentRoutingTemplatePath; return this; }
        public Builder contextRouter(ContextRouter contextRouter) { this.contextRouter = contextRouter; return this; }
        public Builder subGraphRouter(SubGraphRouter subGraphRouter) { this.subGraphRouter = subGraphRouter; return this; }
        public Builder graphExecutionEngine(GraphExecutionEngine graphExecutionEngine) { this.graphExecutionEngine = graphExecutionEngine; return this; }
        public Builder subGraphRegistry(SubGraphRegistry subGraphRegistry) { this.subGraphRegistry = subGraphRegistry; return this; }
        public Builder globalSessionStore(GlobalSessionStateStore globalSessionStore) { this.globalSessionStore = globalSessionStore; return this; }
        public Builder activeAgentExpireMinutes(long activeAgentExpireMinutes) { this.activeAgentExpireMinutes = activeAgentExpireMinutes; return this; }
        public Builder l1DomainPairs(int l1DomainPairs) { this.l1DomainPairs = l1DomainPairs; return this; }

        public SingleSubAgentDomainService build() {
            Objects.requireNonNull(domainName, "domainName is required");
            Objects.requireNonNull(logTag, "logTag is required");
            Objects.requireNonNull(domainKey, "domainKey is required");
            Objects.requireNonNull(intent, "intent is required");
            Objects.requireNonNull(contextRouter, "contextRouter is required");
            Objects.requireNonNull(subGraphRouter, "subGraphRouter is required");
            Objects.requireNonNull(graphExecutionEngine, "graphExecutionEngine is required");
            Objects.requireNonNull(subGraphRegistry, "subGraphRegistry is required");
            Objects.requireNonNull(globalSessionStore, "globalSessionStore is required");
            String resolvedRoutingPath = contextRoutingTemplatePath != null ? contextRoutingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
            String resolvedIntentionPath = intentRoutingTemplatePath != null ? intentRoutingTemplatePath : DEFAULT_INTENTION_TEMPLATE;
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
                contextRoutingTemplatePath, domainName, chatHistory,
                ownActive.getLastQuestion());
        log.info("[{}] Phase1 (activeAgent+lastQuestion): routeType={}, confidence={}",
                logTag, phase1.getRouteType(), phase1.getConfidence());

        if (phase1.isFollow()) {
            return resumeActiveAgent(sessionId, userInput, ownActive);
        }

        RoutingResult phase2 = runSubGraphRouter(sessionId, userInput, phase1,
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
                contextRoutingTemplatePath, domainName, chatHistory, null);
        log.info("[{}] Phase1 (no activeAgent): routeType={}, confidence={}",
                logTag, phase1.getRouteType(), phase1.getConfidence());

        RoutingResult phase2 = runSubGraphRouter(sessionId, userInput, phase1,
                currentAgent, pendingAgents);

        if (!phase2.isBelongsToDomain()) {
            log.info("[{}] REROUTE: belongsToDomain=false, intent={}", logTag, phase2.getIntentName());
            return Flux.just(StreamChunk.reroute(phase2.getIntentName(), null));
        }

        String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
        return handleSwitchNew(sessionId, rewrittenInput);
    }

    private RoutingResult runSubGraphRouter(String sessionId, String userInput, RoutingResult phase1,
                                           String currentAgent, String pendingAgents) {
        String sessionState = "Phase1路由: " + phase1.getRouteType();
        String disambigContext = "无";
        String domainIntentScopeList = subGraphRegistry.getDomainIntentScopeDescription(List.of(intent));
        String chatHistory = getFormattedChatHistory(sessionId);

        RoutingResult phase2 = subGraphRouter.rewriteAndIdentify(sessionId, userInput, phase1,
                currentAgent, pendingAgents, sessionState, disambigContext,
                intentRoutingTemplatePath, chatHistory, domainIntentScopeList);
        log.info("[{}] Phase2 SubGraphRouter: intent={}, belongsToDomain={}, rewritten=[{}]",
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
