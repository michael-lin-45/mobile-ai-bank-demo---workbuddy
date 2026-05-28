package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResolution;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.data.WorkflowStatus;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.router.IntentResolver;
import com.mobileagent.app.execution.GlobalSessionStore;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.util.TemplateUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多子智能体领域服务 - 1-N关系 (1个L1对应N个L2)
 *
 * 适用场景: 理财(WEALTH_CONSULT/WEALTH_INTERPRET), 活动, 贷款 等
 * 特点: 多个子意图之间可切换，需要suspendedAgents、消歧、Phase2意图识别
 *
 * 控制流 (5层决策):
 * 1. Phase1: ContextRouter (full模板) → FOLLOW / SWITCH / RESUME
 * 2. FOLLOW + activeAgent (非消歧中) → resumeGraph（官方模式二）
 * 3. FOLLOW + 无activeAgent + 有suspendedAgents → 降级走Phase2
 * 4. Phase2: IntentResolver → RoutingResolution (RESOLVED/DISAMBIGUATION/REJECTED/CANCELLED)
 * 5. RESOLVED → executeRoute (auto-upgrade + handleSwitchNew/handleResume)
 *
 * 官方模式二 + threadId独立架构:
 * - SuspendedInfo 保留 intent + threadId + 时间戳
 * - handleResume 用 SuspendedInfo 中的 threadId 恢复（不再用sessionId）
 * - handleSwitchNew 挂起当前 agent 时传递其 threadId
 *
 * Cancel:
 * - 不暴露cancel公共方法
 * - 子智能体执行中的取消: FOLLOW → resumeGraph → 子Graph的cancelAwareExtractParams检测
 * - 消歧中的取消: IntentResolver.isCancelExpression() → CANCELLED状态 → handle()中clearDisambiguationState
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

    // ==================== Multi独有状态 ====================

    /** 本领域自有的挂起意图 (per session, intent → SuspendedInfo) */
    private final Map<String, Map<String, SuspendedInfo>> suspendedAgents = new ConcurrentHashMap<>();

    /** 本领域自有的消歧状态 (per session) */
    private final Map<String, DisambiguationState> disambiguationStates = new ConcurrentHashMap<>();

    private final int maxSuspendedDepth;
    private final long suspendedExpireMinutes;

    // ==================== Multi独有数据类 ====================

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
        super(builder.domainName, builder.logTag, builder.chatMemory,
                builder.contextRouter, builder.graphExecutionEngine, builder.intentRegistry,
                builder.globalSessionStore, builder.activeAgentExpireMinutes);
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
        private ChatMemory chatMemory;
        private ContextRouter contextRouter;
        private IntentResolver intentResolver;
        private GraphExecutionEngine graphExecutionEngine;
        private IntentRegistry intentRegistry;
        private GlobalSessionStore globalSessionStore;
        private String routingTemplatePath;
        private String intentionTemplatePath;
        private String rejectedMessage;
        private List<IntentInfo> handledIntents;
        private int maxSuspendedDepth = 3;
        private long suspendedExpireMinutes = 20;
        private long activeAgentExpireMinutes = 20;

        public Builder domainName(String domainName) { this.domainName = domainName; return this; }
        public Builder logTag(String logTag) { this.logTag = logTag; return this; }
        public Builder chatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; return this; }
        public Builder contextRouter(ContextRouter contextRouter) { this.contextRouter = contextRouter; return this; }
        public Builder intentResolver(IntentResolver intentResolver) { this.intentResolver = intentResolver; return this; }
        public Builder graphExecutionEngine(GraphExecutionEngine graphExecutionEngine) { this.graphExecutionEngine = graphExecutionEngine; return this; }
        public Builder intentRegistry(IntentRegistry intentRegistry) { this.intentRegistry = intentRegistry; return this; }
        public Builder globalSessionStore(GlobalSessionStore globalSessionStore) { this.globalSessionStore = globalSessionStore; return this; }
        public Builder routingTemplatePath(String routingTemplatePath) { this.routingTemplatePath = routingTemplatePath; return this; }
        /** 意图识别模板路径(如"prompts/l1-intention.st")，默认使用通用模板 */
        public Builder intentionTemplatePath(String intentionTemplatePath) { this.intentionTemplatePath = intentionTemplatePath; return this; }
        public Builder rejectedMessage(String rejectedMessage) { this.rejectedMessage = rejectedMessage; return this; }
        /** 此领域服务处理的所有意图及其描述 */
        public Builder handledIntents(List<IntentInfo> handledIntents) { this.handledIntents = handledIntents; return this; }
        public Builder maxSuspendedDepth(int maxSuspendedDepth) { this.maxSuspendedDepth = maxSuspendedDepth; return this; }
        public Builder suspendedExpireMinutes(long suspendedExpireMinutes) { this.suspendedExpireMinutes = suspendedExpireMinutes; return this; }
        /** activeAgent过期时间(分钟)，默认20分钟，与suspendedExpireMinutes保持一致 */
        public Builder activeAgentExpireMinutes(long activeAgentExpireMinutes) { this.activeAgentExpireMinutes = activeAgentExpireMinutes; return this; }

        public MultiSubAgentDomainService build() {
            Objects.requireNonNull(domainName, "domainName is required");
            Objects.requireNonNull(logTag, "logTag is required");
            Objects.requireNonNull(chatMemory, "chatMemory is required");
            Objects.requireNonNull(contextRouter, "contextRouter is required");
            Objects.requireNonNull(intentResolver, "intentResolver is required");
            Objects.requireNonNull(graphExecutionEngine, "graphExecutionEngine is required");
            Objects.requireNonNull(intentRegistry, "intentRegistry is required");
            Objects.requireNonNull(globalSessionStore, "globalSessionStore is required");
            // 预热模板: 构造时加载到缓存,运行时零IO
            String routingPath = routingTemplatePath != null
                    ? routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
            String intentionPath = intentionTemplatePath != null
                    ? intentionTemplatePath : DEFAULT_INTENTION_TEMPLATE;
            TemplateUtils.warmUp(routingPath, () -> "");
            TemplateUtils.warmUp(intentionPath, () -> "");
            return new MultiSubAgentDomainService(this);
        }
    }

    // ==================== suspendedAgents管理 ====================

    private void suspendOwnAgent(String sessionId, String intent, String threadId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

        // 同一intent已挂起 → 无需重复挂起（checkpoint自动保留）
        if (sessionMap.containsKey(intent)) {
            log.debug("[{}] Suspended agent already exists: session={}, intent={}", logTag, sessionId, intent);
            return;
        }

        // 检查深度限制
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
        log.debug("[{}] Suspended agent: session={}, intent={}, threadId={}", logTag, sessionId, intent, threadId);
    }

    private SuspendedInfo getOwnSuspendedAgent(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap == null) return null;
        SuspendedInfo info = sessionMap.get(intent);
        if (info != null && info.getExpiresAt().isBefore(Instant.now())) {
            sessionMap.remove(intent);
            return null;
        }
        return info;
    }

    private Map<String, SuspendedInfo> getAllOwnSuspended(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap == null) return Collections.emptyMap();
        sessionMap.entrySet().removeIf(e -> e.getValue().getExpiresAt().isBefore(Instant.now()));
        return Collections.unmodifiableMap(sessionMap);
    }

    private void resumeOwnAgent(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap != null) {
            sessionMap.remove(intent);
            log.debug("[{}] Resumed agent: session={}, intent={}", logTag, sessionId, intent);
        }
    }

    private boolean hasOwnSuspendedAgents(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = suspendedAgents.get(sessionId);
        if (sessionMap == null || sessionMap.isEmpty()) return false;
        sessionMap.entrySet().removeIf(e -> e.getValue().getExpiresAt().isBefore(Instant.now()));
        return !sessionMap.isEmpty();
    }

    /** 定时清理过期的挂起记录和activeAgent, 每分钟执行一次 */
    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredSuspended() {
        Instant now = Instant.now();
        suspendedAgents.forEach((sessionId, sessionMap) -> {
            sessionMap.entrySet().removeIf(e -> {
                if (e.getValue().getExpiresAt().isBefore(now)) {
                    log.debug("[{}] Auto cleaned expired: session={}, intent={}", logTag, sessionId, e.getKey());
                    return true;
                }
                return false;
            });
            if (sessionMap.isEmpty()) {
                suspendedAgents.remove(sessionId);
            }
        });
        // 同时清理过期的activeAgent
        cleanupExpiredActiveAgents();
    }

    // ==================== 消歧管理 ====================

    private boolean isInDisambiguation(String sessionId) {
        return disambiguationStates.containsKey(sessionId);
    }

    private void setDisambiguationState(String sessionId, DisambiguationState state) {
        disambiguationStates.put(sessionId, state);
    }

    private DisambiguationState getDisambiguationState(String sessionId) {
        return disambiguationStates.get(sessionId);
    }

    private void clearDisambiguationState(String sessionId) {
        disambiguationStates.remove(sessionId);
    }

    // ==================== 状态描述(供外部调用) ====================

    /** 获取挂起意图列表描述(供ContextRouter prompt用) */
    public String getPendingAgentsDescription(String sessionId) {
        Map<String, SuspendedInfo> suspended = getAllOwnSuspended(sessionId);
        if (suspended.isEmpty()) return "无";
        return String.join(", ", suspended.keySet());
    }

    // ==================== 主入口 ====================

    @Override
    public WorkflowOutput handle(String sessionId, String userInput, String globalChatHistory) {
        log.info("[{}] Handling: sessionId={}, input={}", logTag, sessionId, userInput);

        try {
            String currentAgent = buildCurrentAgent(sessionId);
            String pendingAgents = getPendingAgentsDescription(sessionId);

            // ========== Phase 1: ContextRouter (FOLLOW/SWITCH/RESUME) ==========
            ActiveAgentInfo activeAgentForRouting = getOwnActiveAgent(sessionId);
            String lastQuestion = (activeAgentForRouting != null) ? activeAgentForRouting.getLastQuestion() : null;

            RoutingResult phase1 = contextRouter.route(sessionId, userInput,
                    currentAgent, pendingAgents,
                    routingTemplatePath, domainName, chatMemory, lastQuestion);
            log.info("[{}] Phase1: routeType={}, confidence={}", logTag, phase1.getRouteType(), phase1.getConfidence());

            // ========== FOLLOW + activeAgent → 直接resume (消歧中除外) ==========
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

            // ========== RESUME但无suspendedAgents → 降级为SWITCH ==========
            if ("RESUME".equals(phase1.getRouteType()) && !hasOwnSuspendedAgents(sessionId)) {
                log.info("[{}] RESUME but no suspended agents → fallback to SWITCH", logTag);
                phase1 = RoutingResult.builder()
                        .routeType("SWITCH").confidence(0.5)
                        .reasoning("RESUME但无挂起线程,降级为新意图").build();
            }

            // ========== 路由决策 (Phase2 + 消歧) ==========
            DisambiguationState disambigState = getDisambiguationState(sessionId);
            String disambigGroupId = disambigState != null ? disambigState.getGroupId() : null;
            String domainIntentScopeList = intentRegistry.getDomainIntentScopeDescription(
                    handledIntents.stream().map(IntentInfo::getIntentName).toList());

            RoutingResolution resolution = intentResolver.resolve(sessionId, userInput, phase1, chatMemory,
                    isInDisambiguation(sessionId), disambigGroupId,
                    hasOwnSuspendedAgents(sessionId), getAllOwnSuspended(sessionId),
                    intentionTemplatePath, globalChatHistory, domainIntentScopeList);

            log.info("[{}] Routing resolution: status={}, intent={}, outOfDomain={}, globalChatHistory=[{}]",
                    logTag, resolution.getStatus(), resolution.getIntentName(), resolution.isOutOfDomain(),
                    globalChatHistory != null && !globalChatHistory.isBlank() ? globalChatHistory.substring(0, Math.min(200, globalChatHistory.length())) + "..." : "(空)");

            // ========== REROUTE判断: 识别的意图不属于本域 ==========
            if (resolution.isResolved()) {
                String effectiveIntent = resolution.getIntentName();

                // 跨域意图: 意图不在本域handledIntents中
                if (!isOwnIntent(effectiveIntent)) {
                    log.info("[{}] Cross-domain intent detected: intent={} not in handledIntents, → REROUTE",
                            logTag, effectiveIntent);
                    return WorkflowOutput.reroute(effectiveIntent, null);
                }

                // outOfDomain: IntentRouter判断不属于本域scope
                if (resolution.isOutOfDomain()) {
                    log.info("[{}] Out-of-domain intent detected: intent={}, → REROUTE",
                            logTag, effectiveIntent);
                    return WorkflowOutput.reroute(effectiveIntent, null);
                }
            }

            // ========== Auto-upgrade保护: SWITCH但意图与activeAgent一致 → 降级FOLLOW ==========
            if (resolution.isResolved() && activeAgentForRouting != null
                    && !"RESUME".equals(resolution.getRouteType())) {
                String identifiedIntent = resolution.getIntentName();
                if (identifiedIntent != null && identifiedIntent.equals(activeAgentForRouting.getIntent())) {
                    log.info("[{}] Auto-upgrade SWITCH→FOLLOW: identifiedIntent={} matches activeAgent.intent={}",
                            logTag, identifiedIntent, activeAgentForRouting.getIntent());
                    addUserMessage(sessionId, userInput);
                    return resumeActiveAgent(sessionId, userInput, activeAgentForRouting);
                }
            }

            addUserMessage(sessionId, userInput);

            // ========== 根据决议执行 ==========
            WorkflowOutput output = switch (resolution.getStatus()) {
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
                    yield WorkflowOutput.disambiguation(resolution.getQuestion(), resolution.getCandidateIntents());
                }
                case REJECTED -> WorkflowOutput.completed(null, rejectedMessage);
                case CANCELLED -> {
                    clearDisambiguationState(sessionId);
                    yield WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
                }
            };

            recordSystemReply(sessionId, output);
            return output;

        } catch (Exception e) {
            log.error("[{}] Error handling message", logTag, e);
            return WorkflowOutput.error("处理" + domainName + "请求时出错: " + e.getMessage());
        }
    }

    // ==================== 路由执行 ====================

    private WorkflowOutput executeRoute(String sessionId, RoutingResolution resolution) {
        // 防御: 如果意图已suspended但路由判了SWITCH，自动升级为RESUME
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

    private WorkflowOutput handleSwitchNew(String sessionId, String intent, String rewrittenInput) {
        ActiveAgentInfo currentActive = getOwnActiveAgent(sessionId);
        if (currentActive != null) {
            suspendOwnAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[{}] Suspended current: intent={}, threadId={}", logTag, currentActive.getIntent(), currentActive.getThreadId());
        }
        return executeNewAgent(sessionId, intent, rewrittenInput);
    }

    /**
     * 恢复挂起的意图 — 用SuspendedInfo中存储的threadId恢复，注入全局OverAllState数据
     */
    private WorkflowOutput handleResume(String sessionId, String intent, String userInput) {
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

        // 获取graph并设置activeAgent（保留原threadId）
        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        setOwnActiveAgent(sessionId, intent, threadId);

        // 注入当前Session的全局OverAllState数据
        Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();

        // 用存储的threadId恢复，注入全局数据
        WorkflowOutput result = graphExecutionEngine.resumeGraph(graph, intent, userInput, threadId, globalStateData);

        saveL2Result(sessionId, result);

        if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
            clearOwnActiveAgent(sessionId);
        }

        return result;
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
        suspendedAgents.remove(sessionId);
        disambiguationStates.remove(sessionId);
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

    /** 判断意图是否属于本域处理范围 */
    private boolean isOwnIntent(String intentName) {
        if (intentName == null || "UNKNOWN".equalsIgnoreCase(intentName)) return false;
        return handledIntents.stream()
                .anyMatch(info -> info.getIntentName().equals(intentName));
    }
}
