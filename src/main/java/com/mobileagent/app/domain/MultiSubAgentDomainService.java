package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResolution;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.memory.SessionStateStore;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.router.IntentResolver;
import com.mobileagent.app.execution.GlobalSessionStore;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.util.TemplateUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final SessionStateStore<Map<String, SuspendedInfo>> suspendedAgentStore;
    private final SessionStateStore<DisambiguationState> disambiguationStore;

    private final int maxSuspendedDepth;
    private final long suspendedExpireMinutes;

    @Data
    public static class SuspendedInfo {
        private final String intent;
        private final String threadId;
        private final Instant suspendedAt;
        private final Instant expiresAt;

        public SuspendedInfo(String intent, String threadId, Instant suspendedAt, Instant expiresAt) {
            this.intent = intent;
            this.threadId = threadId;
            this.suspendedAt = suspendedAt;
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    @Data
    public static class DisambiguationState {
        private final String groupId;

        public DisambiguationState(String groupId) {
            this.groupId = groupId;
        }
    }

    // ==================== 构造(Builder) ====================

    private MultiSubAgentDomainService(Builder builder) {
        super(builder.domainName, builder.logTag, builder.domainKey,
                builder.contextRouter, builder.graphExecutionEngine, builder.intentRegistry,
                builder.globalSessionStore, builder.activeAgentStore, builder.activeAgentExpireMinutes,
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
        this.suspendedAgentStore = builder.suspendedAgentStore;
        this.disambiguationStore = builder.disambiguationStore;
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
        private GlobalSessionStore globalSessionStore;
        private SessionStateStore<ActiveAgentInfo> activeAgentStore;
        private SessionStateStore<Map<String, SuspendedInfo>> suspendedAgentStore;
        private SessionStateStore<DisambiguationState> disambiguationStore;
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
        public Builder globalSessionStore(GlobalSessionStore globalSessionStore) { this.globalSessionStore = globalSessionStore; return this; }
        public Builder activeAgentStore(SessionStateStore<ActiveAgentInfo> activeAgentStore) { this.activeAgentStore = activeAgentStore; return this; }
        public Builder suspendedAgentStore(SessionStateStore<Map<String, SuspendedInfo>> suspendedAgentStore) { this.suspendedAgentStore = suspendedAgentStore; return this; }
        public Builder disambiguationStore(SessionStateStore<DisambiguationState> disambiguationStore) { this.disambiguationStore = disambiguationStore; return this; }
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
            Objects.requireNonNull(activeAgentStore, "activeAgentStore is required");
            Objects.requireNonNull(suspendedAgentStore, "suspendedAgentStore is required");
            Objects.requireNonNull(disambiguationStore, "disambiguationStore is required");
            String routingPath = routingTemplatePath != null ? routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
            String intentionPath = intentionTemplatePath != null ? intentionTemplatePath : DEFAULT_INTENTION_TEMPLATE;
            TemplateUtils.warmUp(routingPath, () -> "");
            TemplateUtils.warmUp(intentionPath, () -> "");
            return new MultiSubAgentDomainService(this);
        }
    }

    // ==================== suspendedAgents管理 ====================

    private static final String SUSPENDED_NS = "suspended";

    private void suspendOwnAgent(String sessionId, String intent, String threadId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgentStore.get(SUSPENDED_NS + ":" + logTag, sessionId)
                .orElseGet(HashMap::new);

        if (sessionMap.containsKey(intent)) {
            log.debug("[{}] Suspended agent already exists: session={}, intent={}", logTag, sessionId, intent);
            return;
        }

        if (sessionMap.size() >= maxSuspendedDepth) {
            String oldestKey = sessionMap.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().getSuspendedAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestKey != null) {
                sessionMap.remove(oldestKey);
                log.warn("[{}] Exceeded max suspended depth, evicted oldest: {}", logTag, oldestKey);
            }
        }

        SuspendedInfo info = new SuspendedInfo(intent, threadId, Instant.now(),
                Instant.now().plusSeconds(suspendedExpireMinutes * 60));
        sessionMap.put(intent, info);
        suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
        log.debug("[{}] Suspended agent: session={}, intent={}, threadId={}", logTag, sessionId, intent, threadId);
    }

    private SuspendedInfo getOwnSuspendedAgent(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgentStore.get(SUSPENDED_NS + ":" + logTag, sessionId)
                .orElse(null);
        if (sessionMap == null) return null;
        SuspendedInfo info = sessionMap.get(intent);
        if (info != null && info.isExpired()) {
            sessionMap.remove(intent);
            suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
            return null;
        }
        return info;
    }

    private Map<String, SuspendedInfo> getAllOwnSuspended(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgentStore.get(SUSPENDED_NS + ":" + logTag, sessionId)
                .orElse(null);
        if (sessionMap == null) return Collections.emptyMap();
        sessionMap.entrySet().removeIf(e -> e.getValue().isExpired());
        if (sessionMap.isEmpty()) {
            suspendedAgentStore.remove(SUSPENDED_NS + ":" + logTag, sessionId);
            return Collections.emptyMap();
        }
        suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
        return Collections.unmodifiableMap(new HashMap<>(sessionMap));
    }

    private void resumeOwnAgent(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgentStore.get(SUSPENDED_NS + ":" + logTag, sessionId)
                .orElse(null);
        if (sessionMap != null) {
            sessionMap.remove(intent);
            if (sessionMap.isEmpty()) {
                suspendedAgentStore.remove(SUSPENDED_NS + ":" + logTag, sessionId);
            } else {
                suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
            }
            log.debug("[{}] Resumed agent: session={}, intent={}", logTag, sessionId, intent);
        }
    }

    private boolean hasOwnSuspendedAgents(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgentStore.get(SUSPENDED_NS + ":" + logTag, sessionId)
                .orElse(null);
        if (sessionMap == null || sessionMap.isEmpty()) return false;
        sessionMap.entrySet().removeIf(e -> e.getValue().isExpired());
        if (sessionMap.isEmpty()) {
            suspendedAgentStore.remove(SUSPENDED_NS + ":" + logTag, sessionId);
            return false;
        }
        suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
        return true;
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredSuspended() {
        String ns = SUSPENDED_NS + ":" + logTag;
        Map<String, Map<String, SuspendedInfo>> all = suspendedAgentStore.getAll(ns);
        for (var entry : all.entrySet()) {
            String sessionId = entry.getKey();
            Map<String, SuspendedInfo> sessionMap = entry.getValue();
            sessionMap.entrySet().removeIf(e -> {
                if (e.getValue().isExpired()) {
                    log.debug("[{}] Auto cleaned expired: session={}, intent={}", logTag, sessionId, e.getKey());
                    return true;
                }
                return false;
            });
            if (sessionMap.isEmpty()) {
                suspendedAgentStore.remove(ns, sessionId);
            } else {
                suspendedAgentStore.put(ns, sessionId, sessionMap);
            }
        }
        cleanupExpiredActiveAgents();
    }

    // ==================== 消歧管理 ====================

    private static final String DISAMBIG_NS = "disambig";

    private boolean isInDisambiguation(String sessionId) {
        return disambiguationStore.containsKey(DISAMBIG_NS + ":" + logTag, sessionId);
    }

    private void setDisambiguationState(String sessionId, DisambiguationState state) {
        disambiguationStore.put(DISAMBIG_NS + ":" + logTag, sessionId, state);
    }

    private DisambiguationState getDisambiguationState(String sessionId) {
        return disambiguationStore.get(DISAMBIG_NS + ":" + logTag, sessionId).orElse(null);
    }

    private void clearDisambiguationState(String sessionId) {
        disambiguationStore.remove(DISAMBIG_NS + ":" + logTag, sessionId);
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
        clearOwnActiveAgent(sessionId);
        suspendedAgentStore.remove(SUSPENDED_NS + ":" + logTag, sessionId);
        disambiguationStore.remove(DISAMBIG_NS + ":" + logTag, sessionId);
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
