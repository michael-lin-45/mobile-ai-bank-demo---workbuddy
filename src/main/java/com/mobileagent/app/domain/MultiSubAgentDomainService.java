package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResolution;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.memory.model.ActiveAgentInfo;
import com.mobileagent.app.memory.model.DisambiguationState;
import com.mobileagent.app.memory.model.DomainState;
import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.memory.model.SuspendedInfo;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.router.IntentResolver;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.util.TemplateUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 多子智能体领域服务 - 1-N关系 (1个L1对应N个L2)
 *
 * 适用场景: 理财(WEALTH_CONSULT/WEALTH_INTERPRET) 等
 * 特点: 多个子意图之间可切换，需要suspendedAgents、消歧、Phase2意图识别
 */
@Slf4j
public class MultiSubAgentDomainService extends AbstractDomainService {

    private static final String DEFAULT_ROUTING_TEMPLATE = "prompts/l1-routing.st";
    private static final String DEFAULT_INTENTION_TEMPLATE = "prompts/l1-intention.st";

    private final IntentResolver intentResolver;
    private final String routingTemplatePath;
    private final String intentionTemplatePath;
    private final String rejectedMessage;
    private final List<IntentInfo> handledIntents;

    private final int maxSuspendedDepth;
    private final long suspendedExpireMinutes;

    // ==================== 构造(Builder) ====================

    private MultiSubAgentDomainService(Builder builder) {
        super(builder.domainName, builder.logTag, builder.domainKey,
                builder.contextRouter, builder.graphExecutionEngine, builder.intentRegistry,
                builder.globalSessionStore, builder.activeAgentExpireMinutes,
                builder.l1DomainPairs);
        this.intentResolver = builder.intentResolver;
        this.routingTemplatePath = builder.routingTemplatePath != null
                ? builder.routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
        this.intentionTemplatePath = builder.intentionTemplatePath != null
                ? builder.intentionTemplatePath : DEFAULT_INTENTION_TEMPLATE;
        this.rejectedMessage = builder.rejectedMessage != null
                ? builder.rejectedMessage : "该功能暂不支持";
        this.handledIntents = builder.handledIntents != null
                ? List.copyOf(builder.handledIntents) : List.of();
        this.maxSuspendedDepth = builder.maxSuspendedDepth;
        this.suspendedExpireMinutes = builder.suspendedExpireMinutes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String domainName;
        private String logTag;
        private String domainKey;
        private ContextRouter contextRouter;
        private IntentResolver intentResolver;
        private GraphExecutionEngine graphExecutionEngine;
        private IntentRegistry intentRegistry;
        private GlobalSessionStateStore globalSessionStore;
        private String routingTemplatePath;
        private String intentionTemplatePath;
        private String rejectedMessage;
        private List<IntentInfo> handledIntents;
        private int maxSuspendedDepth = 3;
        private long suspendedExpireMinutes = 20;
        private long activeAgentExpireMinutes = 20;
        private int l1DomainPairs = 6;

        public Builder domainName(String domainName) { this.domainName = domainName; return this; }
        public Builder logTag(String logTag) { this.logTag = logTag; return this; }
        public Builder domainKey(String domainKey) { this.domainKey = domainKey; return this; }
        public Builder contextRouter(ContextRouter contextRouter) { this.contextRouter = contextRouter; return this; }
        public Builder intentResolver(IntentResolver intentResolver) { this.intentResolver = intentResolver; return this; }
        public Builder graphExecutionEngine(GraphExecutionEngine graphExecutionEngine) { this.graphExecutionEngine = graphExecutionEngine; return this; }
        public Builder intentRegistry(IntentRegistry intentRegistry) { this.intentRegistry = intentRegistry; return this; }
        public Builder globalSessionStore(GlobalSessionStateStore globalSessionStore) { this.globalSessionStore = globalSessionStore; return this; }
        public Builder routingTemplatePath(String routingTemplatePath) { this.routingTemplatePath = routingTemplatePath; return this; }
        public Builder intentionTemplatePath(String intentionTemplatePath) { this.intentionTemplatePath = intentionTemplatePath; return this; }
        public Builder rejectedMessage(String rejectedMessage) { this.rejectedMessage = rejectedMessage; return this; }
        public Builder handledIntents(List<IntentInfo> handledIntents) { this.handledIntents = handledIntents; return this; }
        public Builder maxSuspendedDepth(int maxSuspendedDepth) { this.maxSuspendedDepth = maxSuspendedDepth; return this; }
        public Builder suspendedExpireMinutes(long suspendedExpireMinutes) { this.suspendedExpireMinutes = suspendedExpireMinutes; return this; }
        public Builder activeAgentExpireMinutes(long activeAgentExpireMinutes) { this.activeAgentExpireMinutes = activeAgentExpireMinutes; return this; }
        public Builder l1DomainPairs(int l1DomainPairs) { this.l1DomainPairs = l1DomainPairs; return this; }

        public MultiSubAgentDomainService build() {
            Objects.requireNonNull(domainName, "domainName is required");
            Objects.requireNonNull(logTag, "logTag is required");
            Objects.requireNonNull(domainKey, "domainKey is required");
            Objects.requireNonNull(contextRouter, "contextRouter is required");
            Objects.requireNonNull(intentResolver, "intentResolver is required");
            Objects.requireNonNull(graphExecutionEngine, "graphExecutionEngine is required");
            Objects.requireNonNull(intentRegistry, "intentRegistry is required");
            Objects.requireNonNull(globalSessionStore, "globalSessionStore is required");
            String routingPath = routingTemplatePath != null ? routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
            String intentionPath = intentionTemplatePath != null ? intentionTemplatePath : DEFAULT_INTENTION_TEMPLATE;
            TemplateUtils.warmUp(routingPath);
            TemplateUtils.warmUp(intentionPath);
            return new MultiSubAgentDomainService(this);
        }
    }

    // ==================== suspendedAgents管理 ====================

    private void suspendOwnAgent(String sessionId, String intent, String threadId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.updateDomainState(getStateKey(), ds -> {
            Map<String, SuspendedInfo> suspended = ds.getSuspendedAgents();

            if (suspended.containsKey(intent)) {
                log.debug("[{}] Suspended agent already exists: session={}, intent={}", logTag, sessionId, intent);
                return;
            }

            if (suspended.size() >= maxSuspendedDepth) {
                suspended.entrySet().stream()
                        .min(Comparator.comparing(e -> e.getValue().getSuspendedAt()))
                        .map(Map.Entry::getKey)
                        .ifPresent(key -> {
                            suspended.remove(key);
                            log.warn("[{}] Exceeded max suspended depth, evicted oldest: {}", logTag, key);
                        });
            }

            long now = System.currentTimeMillis();
            suspended.put(intent, new SuspendedInfo(intent, threadId, now,
                    now + suspendedExpireMinutes * 60 * 1000));
            log.debug("[{}] Suspended agent: session={}, intent={}, threadId={}", logTag, sessionId, intent, threadId);
        });
    }

    private SuspendedInfo getOwnSuspendedAgent(String sessionId, String intent) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        DomainState ds = ctx.getDomainState(getStateKey());
        if (ds == null || ds.getSuspendedAgents() == null) return null;

        SuspendedInfo info = ds.getSuspendedAgents().get(intent);
        if (info != null && info.isExpired()) {
            ctx.updateDomainState(getStateKey(), d -> {
                if (d.getSuspendedAgents() != null) {
                    d.getSuspendedAgents().remove(intent);
                    if (d.getSuspendedAgents().isEmpty()) {
                        d.setSuspendedAgents(null);
                    }
                }
            });
            return null;
        }
        return info;
    }

    private Map<String, SuspendedInfo> getAllOwnSuspended(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        DomainState ds = ctx.getDomainState(getStateKey());
        if (ds == null || ds.getSuspendedAgents() == null || ds.getSuspendedAgents().isEmpty()) {
            return Collections.emptyMap();
        }

        // 惰性清理过期项
        ds.getSuspendedAgents().entrySet().removeIf(e -> e.getValue().isExpired());
        if (ds.getSuspendedAgents().isEmpty()) {
            ctx.updateDomainState(getStateKey(), d -> d.setSuspendedAgents(null));
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<>(ds.getSuspendedAgents()));
    }

    private void resumeOwnAgent(String sessionId, String intent) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.updateDomainState(getStateKey(), ds -> {
            Map<String, SuspendedInfo> suspended = ds.getSuspendedAgents();
            if (suspended != null) {
                suspended.remove(intent);
                if (suspended.isEmpty()) {
                    ds.setSuspendedAgents(null);
                }
            }
        });
        log.debug("[{}] Resumed agent: session={}, intent={}", logTag, sessionId, intent);
    }

    private boolean hasOwnSuspendedAgents(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        DomainState ds = ctx.getDomainState(getStateKey());
        if (ds == null || ds.getSuspendedAgents() == null || ds.getSuspendedAgents().isEmpty()) {
            return false;
        }

        // 惰性清理过期项
        ds.getSuspendedAgents().entrySet().removeIf(e -> e.getValue().isExpired());
        if (ds.getSuspendedAgents().isEmpty()) {
            ctx.updateDomainState(getStateKey(), d -> d.setSuspendedAgents(null));
            return false;
        }
        return true;
    }

    // ==================== 消歧管理 ====================

    private boolean isInDisambiguation(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        DomainState ds = ctx.getDomainState(getStateKey());
        return ds != null && ds.getDisambiguation() != null;
    }

    private void setDisambiguationState(String sessionId, DisambiguationState state) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.updateDomainState(getStateKey(), ds -> ds.setDisambiguation(state));
    }

    private DisambiguationState getDisambiguationState(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        DomainState ds = ctx.getDomainState(getStateKey());
        return ds != null ? ds.getDisambiguation() : null;
    }

    private void clearDisambiguationState(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.updateDomainState(getStateKey(), ds -> ds.setDisambiguation(null));
    }

    public String getPendingAgentsDescription(String sessionId) {
        Map<String, SuspendedInfo> suspended = getAllOwnSuspended(sessionId);
        if (suspended.isEmpty()) return "无";
        return String.join(", ", suspended.keySet());
    }

    // ==================== 主入口 ====================

    @Override
    public Flux<StreamChunk> handle(String sessionId, String userInput) {
        log.info("[{}] Handling: sessionId={}, input={}", logTag, sessionId, userInput);

        try {
            String currentAgent = buildCurrentAgent(sessionId);
            String pendingAgents = getPendingAgentsDescription(sessionId);

            ActiveAgentInfo activeAgentForRouting = getOwnActiveAgent(sessionId);
            String lastQuestion = (activeAgentForRouting != null) ? activeAgentForRouting.getLastQuestion() : null;
            String chatHistory = getFormattedChatHistory(sessionId);

            RoutingResult phase1 = contextRouter.route(sessionId, userInput,
                    currentAgent, pendingAgents,
                    routingTemplatePath, domainName, chatHistory, lastQuestion);
            log.info("[{}] Phase1: routeType={}, confidence={}", logTag, phase1.getRouteType(), phase1.getConfidence());

            if (phase1.isFollow() && !isInDisambiguation(sessionId)) {
                ActiveAgentInfo activeAgent = getOwnActiveAgent(sessionId);
                if (activeAgent != null) {
                    return resumeActiveAgent(sessionId, userInput, activeAgent);
                }

                if (!hasOwnSuspendedAgents(sessionId)) {
                    log.info("[{}] FOLLOW but no context → fallback to SWITCH", logTag);
                    phase1 = RoutingResult.builder()
                            .routeType("SWITCH").confidence(0.5)
                            .reasoning("FOLLOW但无活跃线程,降级为新意图").build();
                }
            }

            if ("RESUME".equals(phase1.getRouteType()) && !hasOwnSuspendedAgents(sessionId)) {
                log.info("[{}] RESUME but no suspended agents → fallback to SWITCH", logTag);
                phase1 = RoutingResult.builder()
                        .routeType("SWITCH").confidence(0.5)
                        .reasoning("RESUME但无挂起线程,降级为新意图").build();
            }

            DisambiguationState disambigState = getDisambiguationState(sessionId);
            String disambigGroupId = disambigState != null ? disambigState.getGroupId() : null;
            String domainIntentScopeList = intentRegistry.getDomainIntentScopeDescription(
                    handledIntents.stream().map(IntentInfo::getIntentName).toList());

            RoutingResolution resolution = intentResolver.resolve(sessionId, userInput, phase1, chatHistory,
                    isInDisambiguation(sessionId), disambigGroupId,
                    hasOwnSuspendedAgents(sessionId), getAllOwnSuspended(sessionId),
                    intentionTemplatePath, domainIntentScopeList);

            log.info("[{}] Routing resolution: status={}, intent={}, outOfDomain={}",
                    logTag, resolution.getStatus(), resolution.getIntentName(), resolution.isOutOfDomain());

            if (resolution.isResolved()) {
                String effectiveIntent = resolution.getIntentName();

                if (!isOwnIntent(effectiveIntent)) {
                    log.info("[{}] Cross-domain intent detected: intent={} not in handledIntents, → REROUTE",
                            logTag, effectiveIntent);
                    return Flux.just(StreamChunk.reroute(effectiveIntent, null));
                }

                if (resolution.isOutOfDomain()) {
                    log.info("[{}] Out-of-domain intent detected: intent={}, → REROUTE",
                            logTag, effectiveIntent);
                    return Flux.just(StreamChunk.reroute(effectiveIntent, null));
                }
            }

            if (resolution.isResolved() && activeAgentForRouting != null
                    && !"RESUME".equals(resolution.getRouteType())) {
                String identifiedIntent = resolution.getIntentName();
                if (identifiedIntent != null && identifiedIntent.equals(activeAgentForRouting.getIntent())) {
                    log.info("[{}] Auto-upgrade SWITCH→FOLLOW: identifiedIntent={} matches activeAgent.intent={}",
                            logTag, identifiedIntent, activeAgentForRouting.getIntent());
                    return resumeActiveAgent(sessionId, userInput, activeAgentForRouting);
                }
            }

            return switch (resolution.getStatus()) {
                case RESOLVED -> executeRoute(sessionId, resolution);
                case DISAMBIGUATION -> {
                    ActiveAgentInfo active = getOwnActiveAgent(sessionId);
                    if (active != null) {
                        suspendOwnAgent(sessionId, active.getIntent(), active.getThreadId());
                        clearOwnActiveAgent(sessionId);
                        log.info("[{}] Disambiguation: suspended own activeAgent intent={}, threadId={}", logTag, active.getIntent(), active.getThreadId());
                    }
                    String groupId = resolveGroupId(resolution);
                    if (groupId != null) {
                        setDisambiguationState(sessionId, new DisambiguationState(groupId));
                    }
                    yield Flux.just(StreamChunk.disambiguation(resolution.getQuestion(), resolution.getCandidateIntents()));
                }
                case REJECTED -> Flux.just(StreamChunk.complete(null, rejectedMessage));
                case CANCELLED -> {
                    clearDisambiguationState(sessionId);
                    yield Flux.just(StreamChunk.complete(null, "好的,已取消当前操作。还有什么可以帮您的吗？"));
                }
            };

        } catch (Exception e) {
            log.error("[{}] Error handling message", logTag, e);
            return Flux.just(StreamChunk.error("处理" + domainName + "请求时出错: " + e.getMessage()));
        }
    }

    // ==================== 路由执行 ====================

    private Flux<StreamChunk> executeRoute(String sessionId, RoutingResolution resolution) {
        if (!"RESUME".equals(resolution.getRouteType())
                && getOwnSuspendedAgent(sessionId, resolution.getIntentName()) != null) {
            log.info("[{}] Auto-upgrade {}→RESUME for suspended intent={}",
                    logTag, resolution.getRouteType(), resolution.getIntentName());
            return handleResume(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
        }
        return switch (resolution.getRouteType()) {
            case "RESUME" -> handleResume(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
            default -> handleSwitchNew(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
        };
    }

    private Flux<StreamChunk> handleSwitchNew(String sessionId, String intent, String rewrittenInput) {
        ActiveAgentInfo currentActive = getOwnActiveAgent(sessionId);
        if (currentActive != null) {
            suspendOwnAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[{}] Suspended current: intent={}, threadId={}", logTag, currentActive.getIntent(), currentActive.getThreadId());
        }
        return executeNewAgent(sessionId, intent, rewrittenInput);
    }

    private Flux<StreamChunk> handleResume(String sessionId, String intent, String userInput) {
        SuspendedInfo suspendedInfo = getOwnSuspendedAgent(sessionId, intent);
        if (suspendedInfo == null) {
            log.warn("[{}] RESUME but no suspended agent for intent={}, fallback to SWITCH", logTag, intent);
            return handleSwitchNew(sessionId, intent, userInput);
        }

        ActiveAgentInfo currentActive = getOwnActiveAgent(sessionId);
        if (currentActive != null && !currentActive.getIntent().equals(suspendedInfo.getIntent())) {
            suspendOwnAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
        }

        resumeOwnAgent(sessionId, intent);

        String threadId = suspendedInfo.getThreadId();
        log.info("[{}] RESUME with checkpoint: intent={}, threadId={}", logTag, intent, threadId);

        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return Flux.just(StreamChunk.error("Graph not found for intent: " + intent));
        }

        setOwnActiveAgent(sessionId, intent, threadId);

        Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();

        return graphExecutionEngine.resumeGraph(graph, intent, userInput, threadId, globalStateData)
                .doOnNext(chunk -> handleActiveAgentState(sessionId, chunk));
    }

    // ==================== 工具方法 ====================

    private String resolveGroupId(RoutingResolution resolution) {
        if (resolution.getCandidateIntents() != null && !resolution.getCandidateIntents().isEmpty()) {
            for (String candidate : resolution.getCandidateIntents()) {
                IntentRegistry.IntentGroup group = intentRegistry.findGroupByIntent(candidate);
                if (group != null) {
                    return group.getGroupId();
                }
            }
        }
        return null;
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
        StringBuilder sb = new StringBuilder();
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        Map<String, SuspendedInfo> suspended = getAllOwnSuspended(sessionId);
        DisambiguationState disambiguation = getDisambiguationState(sessionId);

        if (disambiguation != null) {
            sb.append("当前在消歧模式: 意图组=").append(disambiguation.getGroupId());
        } else if (active != null) {
            sb.append("当前活跃意图: ").append(active.getIntent());
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

    @Override
    public List<IntentInfo> getHandledIntents() {
        return handledIntents;
    }

    private boolean isOwnIntent(String intentName) {
        if (intentName == null || "UNKNOWN".equalsIgnoreCase(intentName)) return false;
        return handledIntents.stream()
                .anyMatch(info -> info.getIntentName().equals(intentName));
    }
}
