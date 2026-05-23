package com.mobileagent.app.domain;

import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.data.RoutingResolution;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.execution.GraphExecutionService;
import com.mobileagent.app.router.IntentResolver;
import com.mobileagent.app.manager.AgentStateManager;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 理财L1 Service - 复用ContextRouter+IntentionRouter+RoutingService
 *
 * 设计:
 * - 复用95%的现有Phase1+Phase2+RoutingService逻辑
 * - ContextRouter使用完整的FOLLOW_UP/SWITCH_NEW/RESUME三种路由
 * - 有suspendedAgents(推荐↔解读切换场景)
 * - 有消歧(WEALTH_CONSULT vs WEALTH_INTERPRET)
 * - 独立ChatMemory实例(只记录本领域消息)
 * - 取消由子workflow处理(cancelAwareExtractParams → cancelExecutionNode)
 * - 消歧中取消由IntentResolver检测,返回CANCELLED状态
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
    private final AgentStateManager stateManager;
    private final ChatMemory wealthChatMemory;

    public WealthService(ContextRouter contextRouter,
                         IntentResolver intentResolver,
                         GraphExecutionService graphExecutionService,
                         IntentRegistry intentRegistry,
                         AgentStateManager stateManager,
                         @org.springframework.beans.factory.annotation.Qualifier("wealthChatMemory") ChatMemory wealthChatMemory) {
        this.contextRouter = contextRouter;
        this.intentResolver = intentResolver;
        this.graphExecutionService = graphExecutionService;
        this.intentRegistry = intentRegistry;
        this.stateManager = stateManager;
        this.wealthChatMemory = wealthChatMemory;
    }

    /**
     * 处理理财领域的消息
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @return 执行结果
     */
    public WorkflowOutput handle(String sessionId, String userInput) {
        log.info("[WealthService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            // ========== Phase 1: ContextRouter (FOLLOW_UP/SWITCH_NEW/RESUME) ==========
            RoutingResult phase1 = contextRouter.route(sessionId, userInput, stateManager,
                    "prompts/l1-routing.st", DOMAIN_NAME, wealthChatMemory);
            log.info("[WealthService] Phase1: routeType={}, confidence={}", phase1.getRouteType(), phase1.getConfidence());

            // ========== FOLLOW_UP + activeThread → 直接resume (消歧中除外) ==========
            if (phase1.isFollowUp() && !stateManager.isInDisambiguation(sessionId)) {
                AgentStateManager.ActiveThreadInfo activeThread = stateManager.getActiveThread(sessionId);
                if (activeThread != null) {
                    log.info("[WealthService] FOLLOW_UP with activeThread: intent={}", activeThread.getIntent());
                    wealthChatMemory.add(sessionId, new UserMessage(userInput));
                    WorkflowOutput resumeResult = graphExecutionService.resumeGraph(
                            activeThread.getIntent(), activeThread.getThreadId(), userInput, sessionId);
                    recordSystemReply(sessionId, resumeResult);
                    return resumeResult;
                }

                if (!stateManager.hasSuspendedAgents(sessionId)) {
                    log.info("[WealthService] FOLLOW_UP but no context → fallback to SWITCH_NEW");
                    phase1 = RoutingResult.builder()
                            .routeType("SWITCH_NEW").confidence(0.5)
                            .reasoning("FOLLOW_UP但无活跃线程,降级为新意图").build();
                }
            }

            // ========== 路由决策 (Phase2 + 消歧) ==========
            RoutingResolution resolution = intentResolver.resolve(sessionId, userInput, phase1, wealthChatMemory);
            log.info("[WealthService] Routing resolution: status={}, intent={}",
                    resolution.getStatus(), resolution.getIntentName());

            // ========== 记录用户消息到领域ChatMemory ==========
            wealthChatMemory.add(sessionId, new UserMessage(userInput));

            // ========== 根据决议执行 ==========
            WorkflowOutput output = switch (resolution.getStatus()) {
                case RESOLVED -> executeRoute(sessionId, resolution);
                case DISAMBIGUATION -> {
                    AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
                    if (active != null) {
                        stateManager.suspendAgent(sessionId, active.getIntent(), active.getThreadId());
                        stateManager.clearActiveThread(sessionId);
                        log.info("[WealthService] Disambiguation: suspended activeThread intent={}", active.getIntent());
                    }
                    yield WorkflowOutput.disambiguation(resolution.getQuestion(), resolution.getCandidateIntents());
                }
                case REJECTED -> WorkflowOutput.completed(null, "该理财功能暂不支持，目前仅支持理财咨询和理财产品解读");
                case CANCELLED -> WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
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
        // 场景: "继续推荐理财"语义上是恢复，但模型可能误判为SWITCH_NEW
        if (!"RESUME".equals(resolution.getRouteType())
                && stateManager.getSuspendedThread(sessionId, resolution.getIntentName()) != null) {
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
        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[WealthService] Suspended current: intent={}", currentActive.getIntent());
        }

        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, newThreadId, intent);

        return graphExecutionService.executeGraph(graph, intent, newThreadId, rewrittenInput, sessionId, null);
    }

    private WorkflowOutput handleResume(String sessionId, String intent, String userInput) {
        AgentStateManager.SuspendedInfo suspendedInfo = stateManager.getSuspendedThread(sessionId, intent);
        if (suspendedInfo == null) {
            log.warn("[WealthService] RESUME but no suspended thread for intent={}, fallback to SWITCH_NEW", intent);
            return handleSwitchNew(sessionId, intent, userInput);
        }

        String threadId = suspendedInfo.getThreadId();

        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null && !currentActive.getThreadId().equals(threadId)) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
        }

        stateManager.resumeAgent(sessionId, intent);
        stateManager.setActiveThread(sessionId, threadId, intent);

        // RESUME: 从suspendedInfo取累积参数，不从activeThread取
        Map<String, Object> suspendedParams = suspendedInfo.getAccumulatedParams();
        log.info("[WealthService] RESUME with suspendedParams: intent={}, params={}", intent, suspendedParams);

        return graphExecutionService.resumeGraph(intent, threadId, userInput, sessionId, suspendedParams);
    }

    // ==================== ChatMemory管理 ====================

    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        ChatHistoryUtils.recordReply(wealthChatMemory, sessionId, output, "WealthService");
    }
}
