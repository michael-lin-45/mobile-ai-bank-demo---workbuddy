package com.mobileagent.app.domain;

import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.data.RoutingResolution;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.execution.GraphExecutionService;
import com.mobileagent.app.router.IntentResolver;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 理财L1 Service - 自管状态版
 *
 * 设计(自管状态版):
 * - 自管 wealthActiveThreads: 本领域独立的activeThread，不与其它领域共享
 * - 自管 wealthSuspendedAgents: 推荐↔解读切换场景的挂起线程
 * - 自管 disambiguationStates: WEALTH_CONSULT vs WEALTH_INTERPRET消歧
 * - ContextRouter使用完整的FOLLOW_UP/SWITCH_NEW/RESUME三种路由
 * - 独立ChatMemory实例(只记录本领域消息)
 * - 取消由子workflow处理(cancelAwareExtractParams → cancelExecutionNode)
 * - @Scheduled定时清理过期suspendedAgents
 *
 * 理财领域的意图只包含:
 * - WEALTH_CONSULT: 理财咨询/推荐
 * - WEALTH_INTERPRET: 理财产品解读
 */
@Slf4j
@Service
public class WealthService {

    private static final String DOMAIN_NAME = "理财";

    private final ContextRouter contextRouter;
    private final IntentResolver intentResolver;
    private final GraphExecutionService graphExecutionService;
    private final IntentRegistry intentRegistry;
    private final ChatMemory wealthChatMemory;

    // ==================== 自管状态 ====================

    /** 理财领域自有的activeThread (per session) */
    private final Map<String, ActiveThreadInfo> wealthActiveThreads = new ConcurrentHashMap<>();

    /** 理财领域自有的挂起意图线程 (per session, intent → SuspendedInfo) */
    private final Map<String, Map<String, SuspendedInfo>> wealthSuspendedAgents = new ConcurrentHashMap<>();

    /** 理财领域自有的消歧状态 (per session) */
    private final Map<String, DisambiguationState> disambiguationStates = new ConcurrentHashMap<>();

    /** 最大挂起深度 */
    @Value("${session.pending-agents.max-depth:3}")
    private int maxSuspendedDepth;

    /** 挂起超时时间(分钟) */
    @Value("${session.pending-agents.expire-minutes:20}")
    private long suspendedExpireMinutes;

    // ==================== 自管数据类 ====================

    @Data
    public static class ActiveThreadInfo {
        private final String threadId;
        private final String intent;
        private final Instant createdAt;
        /** 累积的参数 */
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

    @Data
    public static class DisambiguationState {
        private final String groupId;

        public DisambiguationState(String groupId) {
            this.groupId = groupId;
        }
    }

    // ==================== 自管状态方法 ====================

    // --- 活跃线程 ---

    private ActiveThreadInfo getOwnActiveThread(String sessionId) {
        return wealthActiveThreads.get(sessionId);
    }

    private void setOwnActiveThread(String sessionId, String threadId, String intent) {
        if (threadId == null || intent == null) {
            wealthActiveThreads.remove(sessionId);
        } else {
            wealthActiveThreads.put(sessionId, new ActiveThreadInfo(threadId, intent, Instant.now()));
        }
    }

    private void clearOwnActiveThread(String sessionId) {
        wealthActiveThreads.remove(sessionId);
    }

    // --- 挂起管理 ---

    private void suspendOwnAgent(String sessionId, String intent, String threadId) {
        Map<String, SuspendedInfo> sessionMap = wealthSuspendedAgents.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

        // 同一intent已挂起 → 合并累积参数
        if (sessionMap.containsKey(intent)) {
            SuspendedInfo existing = sessionMap.get(intent);
            ActiveThreadInfo active = wealthActiveThreads.get(sessionId);
            if (active != null && active.getIntent().equals(intent)) {
                Map<String, Object> merged = new HashMap<>(active.getAccumulatedParams());
                existing.getAccumulatedParams().forEach(merged::putIfAbsent);
                existing.setAccumulatedParams(merged);
            }
            log.debug("[WealthService] Suspended agent already exists, merged params: session={}, intent={}", sessionId, intent);
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
                log.warn("[WealthService] Exceeded max suspended depth, evicted oldest: {}", oldestKey);
            }
        }

        SuspendedInfo info = new SuspendedInfo(threadId, intent, Instant.now(),
                Instant.now().plusSeconds(suspendedExpireMinutes * 60));
        // 继承activeThread的累积参数
        ActiveThreadInfo active = wealthActiveThreads.get(sessionId);
        if (active != null && active.getIntent().equals(intent)) {
            info.setAccumulatedParams(active.getAccumulatedParams());
        }
        sessionMap.put(intent, info);
        log.debug("[WealthService] Suspended agent: session={}, intent={}, threadId={}", sessionId, intent, threadId);
    }

    private SuspendedInfo getOwnSuspendedThread(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = wealthSuspendedAgents.get(sessionId);
        if (sessionMap == null) return null;
        SuspendedInfo info = sessionMap.get(intent);
        if (info != null && info.getExpiresAt().isBefore(Instant.now())) {
            sessionMap.remove(intent);
            return null;
        }
        return info;
    }

    private Map<String, SuspendedInfo> getAllOwnSuspended(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = wealthSuspendedAgents.get(sessionId);
        if (sessionMap == null) return Collections.emptyMap();
        sessionMap.entrySet().removeIf(e -> e.getValue().getExpiresAt().isBefore(Instant.now()));
        return Collections.unmodifiableMap(sessionMap);
    }

    private void resumeOwnAgent(String sessionId, String intent) {
        Map<String, SuspendedInfo> sessionMap = wealthSuspendedAgents.get(sessionId);
        if (sessionMap != null) {
            sessionMap.remove(intent);
            log.debug("[WealthService] Resumed agent: session={}, intent={}", sessionId, intent);
        }
    }

    private boolean hasOwnSuspendedAgents(String sessionId) {
        Map<String, SuspendedInfo> sessionMap = wealthSuspendedAgents.get(sessionId);
        if (sessionMap == null || sessionMap.isEmpty()) return false;
        sessionMap.entrySet().removeIf(e -> e.getValue().getExpiresAt().isBefore(Instant.now()));
        return !sessionMap.isEmpty();
    }

    // --- 消歧管理 ---

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

    // --- 清理 ---

    /** 清除会话所有状态(供BankController.clearSession调用) */
    public void clearSession(String sessionId) {
        wealthActiveThreads.remove(sessionId);
        wealthSuspendedAgents.remove(sessionId);
        disambiguationStates.remove(sessionId);
    }

    /** 获取会话状态描述(供BankController.getState调用) */
    public String getSessionStateDescription(String sessionId) {
        StringBuilder sb = new StringBuilder();
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        Map<String, SuspendedInfo> suspended = getAllOwnSuspended(sessionId);
        DisambiguationState disambiguation = getDisambiguationState(sessionId);

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

    /** 获取当前活跃意图名(供ContextRouter prompt用) */
    public String getCurrentAgentName(String sessionId) {
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        return active != null ? active.getIntent() : "无";
    }

    /** 获取挂起意图列表描述(供ContextRouter prompt用) */
    public String getPendingAgentsDescription(String sessionId) {
        Map<String, SuspendedInfo> suspended = getAllOwnSuspended(sessionId);
        if (suspended.isEmpty()) return "无";
        return String.join(", ", suspended.keySet());
    }

    /** 定时清理过期的挂起记录, 每分钟执行一次 */
    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredSuspended() {
        Instant now = Instant.now();
        wealthSuspendedAgents.forEach((sessionId, sessionMap) -> {
            sessionMap.entrySet().removeIf(e -> {
                if (e.getValue().getExpiresAt().isBefore(now)) {
                    log.debug("[WealthService] Auto cleaned expired: session={}, intent={}", sessionId, e.getKey());
                    return true;
                }
                return false;
            });
            if (sessionMap.isEmpty()) {
                wealthSuspendedAgents.remove(sessionId);
            }
        });
    }

    // ==================== 构造 ====================

    public WealthService(ContextRouter contextRouter,
                         IntentResolver intentResolver,
                         GraphExecutionService graphExecutionService,
                         IntentRegistry intentRegistry,
                         @org.springframework.beans.factory.annotation.Qualifier("wealthChatMemory") ChatMemory wealthChatMemory) {
        this.contextRouter = contextRouter;
        this.intentResolver = intentResolver;
        this.graphExecutionService = graphExecutionService;
        this.intentRegistry = intentRegistry;
        this.wealthChatMemory = wealthChatMemory;
    }

    // ==================== 主入口 ====================

    /**
     * 处理理财领域的消息
     */
    public WorkflowOutput handle(String sessionId, String userInput) {
        log.info("[WealthService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            // 获取本领域当前状态(用于ContextRouter的prompt)
            String currentAgent = getCurrentAgentName(sessionId);
            String pendingAgents = getPendingAgentsDescription(sessionId);

            // ========== Phase 1: ContextRouter (FOLLOW_UP/SWITCH_NEW/RESUME) ==========
            RoutingResult phase1 = contextRouter.route(sessionId, userInput,
                    currentAgent, pendingAgents,
                    "prompts/l1-routing.st", DOMAIN_NAME, wealthChatMemory);
            log.info("[WealthService] Phase1: routeType={}, confidence={}", phase1.getRouteType(), phase1.getConfidence());

            // ========== FOLLOW_UP + activeThread → 直接resume (消歧中除外) ==========
            if (phase1.isFollowUp() && !isInDisambiguation(sessionId)) {
                ActiveThreadInfo activeThread = getOwnActiveThread(sessionId);
                if (activeThread != null) {
                    log.info("[WealthService] FOLLOW_UP with own activeThread: intent={}, params={}",
                            activeThread.getIntent(), activeThread.getAccumulatedParams());
                    wealthChatMemory.add(sessionId, new UserMessage(userInput));

                    // 生成新threadId，恢复累积参数
                    String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    setOwnActiveThread(sessionId, newThreadId, activeThread.getIntent());

                    WorkflowOutput resumeResult = graphExecutionService.resumeGraph(
                            activeThread.getIntent(), newThreadId, userInput, sessionId,
                            activeThread.getAccumulatedParams());
                    saveAccumulatedParams(sessionId, resumeResult);
                    recordSystemReply(sessionId, resumeResult);
                    return resumeResult;
                }

                if (!hasOwnSuspendedAgents(sessionId)) {
                    log.info("[WealthService] FOLLOW_UP but no context → fallback to SWITCH_NEW");
                    phase1 = RoutingResult.builder()
                            .routeType("SWITCH_NEW").confidence(0.5)
                            .reasoning("FOLLOW_UP但无活跃线程,降级为新意图").build();
                }
            }

            // ========== 路由决策 (Phase2 + 消歧) ==========
            // 消歧状态由WealthService自管，传给IntentResolver使用
            DisambiguationState disambigState = getDisambiguationState(sessionId);
            String disambigGroupId = disambigState != null ? disambigState.getGroupId() : null;
            RoutingResolution resolution = intentResolver.resolve(sessionId, userInput, phase1, wealthChatMemory,
                    isInDisambiguation(sessionId), disambigGroupId,
                    hasOwnSuspendedAgents(sessionId), getAllOwnSuspended(sessionId));
            log.info("[WealthService] Routing resolution: status={}, intent={}",
                    resolution.getStatus(), resolution.getIntentName());

            // ========== 记录用户消息到领域ChatMemory ==========
            wealthChatMemory.add(sessionId, new UserMessage(userInput));

            // ========== 根据决议执行 ==========
            WorkflowOutput output = switch (resolution.getStatus()) {
                case RESOLVED -> executeRoute(sessionId, resolution);
                case DISAMBIGUATION -> {
                    ActiveThreadInfo active = getOwnActiveThread(sessionId);
                    if (active != null) {
                        suspendOwnAgent(sessionId, active.getIntent(), active.getThreadId());
                        clearOwnActiveThread(sessionId);
                        log.info("[WealthService] Disambiguation: suspended own activeThread intent={}", active.getIntent());
                    }
                    // 保存消歧状态到自管
                    String groupId = resolveGroupId(resolution);
                    if (groupId != null) {
                        setDisambiguationState(sessionId, new DisambiguationState(groupId));
                    }
                    yield WorkflowOutput.disambiguation(resolution.getQuestion(), resolution.getCandidateIntents());
                }
                case REJECTED -> WorkflowOutput.completed(null, "该理财功能暂不支持，目前仅支持理财咨询和理财产品解读");
                case CANCELLED -> {
                    clearDisambiguationState(sessionId);
                    yield WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
                }
            };

            recordSystemReply(sessionId, output);
            return output;

        } catch (Exception e) {
            log.error("[WealthService] Error handling message", e);
            return WorkflowOutput.error("处理理财请求时出错: " + e.getMessage());
        }
    }

    // ==================== 路由执行 ====================

    private WorkflowOutput executeRoute(String sessionId, RoutingResolution resolution) {
        // 防御: 如果意图已suspended但路由判了SWITCH_NEW，自动升级为RESUME
        if (!"RESUME".equals(resolution.getRouteType())
                && getOwnSuspendedThread(sessionId, resolution.getIntentName()) != null) {
            log.info("[WealthService] Auto-upgrade {}→RESUME for suspended intent={}",
                    resolution.getRouteType(), resolution.getIntentName());
            return handleResume(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
        }
        return switch (resolution.getRouteType()) {
            case "RESUME" -> handleResume(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
            default -> handleSwitchNew(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
        };
    }

    private WorkflowOutput handleSwitchNew(String sessionId, String intent, String rewrittenInput) {
        ActiveThreadInfo currentActive = getOwnActiveThread(sessionId);
        if (currentActive != null) {
            suspendOwnAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[WealthService] Suspended current: intent={}", currentActive.getIntent());
        }

        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        setOwnActiveThread(sessionId, newThreadId, intent);

        WorkflowOutput result = graphExecutionService.executeGraph(graph, intent, newThreadId, rewrittenInput, sessionId, null);
        saveAccumulatedParams(sessionId, result);

        if ("COMPLETED".equals(result.getStatus())) {
            clearOwnActiveThread(sessionId);
        }

        return result;
    }

    private WorkflowOutput handleResume(String sessionId, String intent, String userInput) {
        SuspendedInfo suspendedInfo = getOwnSuspendedThread(sessionId, intent);
        if (suspendedInfo == null) {
            log.warn("[WealthService] RESUME but no suspended thread for intent={}, fallback to SWITCH_NEW", intent);
            return handleSwitchNew(sessionId, intent, userInput);
        }

        String threadId = suspendedInfo.getThreadId();

        ActiveThreadInfo currentActive = getOwnActiveThread(sessionId);
        if (currentActive != null && !currentActive.getThreadId().equals(threadId)) {
            suspendOwnAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
        }

        resumeOwnAgent(sessionId, intent);
        setOwnActiveThread(sessionId, threadId, intent);

        // RESUME: 从suspendedInfo取累积参数
        Map<String, Object> suspendedParams = suspendedInfo.getAccumulatedParams();
        log.info("[WealthService] RESUME with suspendedParams: intent={}, params={}", intent, suspendedParams);

        // 生成新threadId，用累积参数重新执行graph
        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        setOwnActiveThread(sessionId, newThreadId, intent);

        WorkflowOutput result = graphExecutionService.resumeGraph(intent, newThreadId, userInput, sessionId, suspendedParams);
        saveAccumulatedParams(sessionId, result);

        if ("COMPLETED".equals(result.getStatus())) {
            clearOwnActiveThread(sessionId);
        }

        return result;
    }

    // ==================== accumulatedParams保存 ====================

    private void saveAccumulatedParams(String sessionId, WorkflowOutput output) {
        if (output == null || output.getAccumulatedParams() == null) return;
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        if (active != null && !output.getAccumulatedParams().isEmpty()) {
            active.setAccumulatedParams(output.getAccumulatedParams());
            log.info("[WealthService] Saved accumulated params: {}", output.getAccumulatedParams());
        }
    }

    // ==================== 工具方法 ====================

    /** 从RoutingResolution中提取groupId */
    private String resolveGroupId(RoutingResolution resolution) {
        // 尝试从候选意图推断groupId
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

    // ==================== ChatMemory管理 ====================

    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        ChatHistoryUtils.recordReply(wealthChatMemory, sessionId, output, "WealthService");
    }
}
