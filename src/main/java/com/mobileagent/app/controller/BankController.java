package com.mobileagent.app.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResolution;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.model.WorkflowOutput;
import com.mobileagent.app.service.GraphExecutionService;
import com.mobileagent.app.service.IntentRouter;
import com.mobileagent.app.service.RoutingService;
import com.mobileagent.app.state.AgentStateManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 银行主控制器 (Master) - 接收用户消息 → Phase1路由 → 路由决议 → 执行Graph
 *
 * 职责边界 (重构后):
 * - Controller: Phase1路由 + CANCEL处理 + FOLLOW_UP快速路径 + 根据决议执行Phase3
 * - RoutingService: Phase2识别 + 消歧 + 模糊匹配 (Controller不关心细节)
 * - GraphExecutionService: Graph执行 + 中断检测 + 参数提取
 * - IntentRouter: Phase1确定性规则 + 4B模型判断
 */
@Slf4j
@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final AgentStateManager stateManager;
    private final IntentRegistry intentRegistry;
    private final IntentRouter intentRouter;
    private final RoutingService routingService;
    private final GraphExecutionService graphExecutionService;

    public BankController(AgentStateManager stateManager,
                          IntentRegistry intentRegistry,
                          IntentRouter intentRouter,
                          RoutingService routingService,
                          GraphExecutionService graphExecutionService) {
        this.stateManager = stateManager;
        this.intentRegistry = intentRegistry;
        this.intentRouter = intentRouter;
        this.routingService = routingService;
        this.graphExecutionService = graphExecutionService;
    }

    @PostConstruct
    public void init() {
        log.info("[BankController] Initialized with RoutingService");
    }

    /**
     * 主聊天接口
     *
     * 流程:
     * 1. Phase1 (IntentRouter): 判断路由类型 FOLLOW_UP/SWITCH_NEW/RESUME/CANCEL
     * 2. CANCEL → handleCancel
     * 3. FOLLOW_UP + activeThread (且不在消歧中) → 直接resume
     * 4. RoutingService.resolve(): Phase2 + 消歧 + 模糊匹配 → 路由决议
     * 5. 根据决议执行Phase3
     */
    @PostMapping("/chat")
    public WorkflowOutput chat(@RequestParam String sessionId,
                                @RequestBody Map<String, String> req) {
        String userInput = req.get("message");
        log.info("[BankController] >>> chat: sessionId={}, message={}", sessionId, userInput);

        if (userInput == null || userInput.isBlank()) {
            return WorkflowOutput.error("Message cannot be empty");
        }

        try {
            // ========== Phase 1: 意图类型判断 (4B) ==========
            RoutingResult phase1 = intentRouter.route(sessionId, userInput, stateManager);
            log.info("[BankController] Phase1: routeType={}, confidence={}",
                    phase1.getRouteType(), phase1.getConfidence());

            // ========== CANCEL ==========
            if ("CANCEL".equals(phase1.getRouteType())) {
                return handleCancel(sessionId);
            }

            // ========== FOLLOW_UP + activeThread → 直接resume (消歧中除外) ==========
            if (phase1.isFollowUp() && !stateManager.isInDisambiguation(sessionId)) {
                AgentStateManager.ActiveThreadInfo activeThread = stateManager.getActiveThread(sessionId);
                if (activeThread != null) {
                    log.info("[BankController] FOLLOW_UP with activeThread: intent={}", activeThread.getIntent());
                    return graphExecutionService.resumeGraph(activeThread.getIntent(), activeThread.getThreadId(), userInput, sessionId);
                }

                if (!stateManager.hasSuspendedAgents(sessionId)) {
                    log.info("[BankController] FOLLOW_UP but no context → fallback to SWITCH_NEW");
                    phase1 = RoutingResult.builder()
                            .routeType("SWITCH_NEW").confidence(0.5)
                            .reasoning("FOLLOW_UP但无活跃线程,降级为新意图").build();
                }
            }

            // ========== 路由决策 (Phase2 + 消歧 + 模糊匹配) ==========
            RoutingResolution resolution = routingService.resolve(sessionId, userInput, phase1);
            log.info("[BankController] Routing resolution: status={}, intent={}",
                    resolution.getStatus(), resolution.getIntentName());

            // ========== 根据决议执行Phase3 ==========
            return switch (resolution.getStatus()) {
                case RESOLVED -> executeRoute(sessionId, resolution);
                case DISAMBIGUATION -> WorkflowOutput.disambiguation(resolution.getQuestion(), resolution.getCandidateIntents());
                case REJECTED -> WorkflowOutput.completed(null, "不支持该功能");
            };

        } catch (Exception e) {
            log.error("[BankController] Error processing chat", e);
            return WorkflowOutput.error("处理请求时出错: " + e.getMessage());
        }
    }

    // ==================== Phase3: 路由执行 ====================

    /**
     * 执行路由 - 根据决议中的routeType分发
     */
    private WorkflowOutput executeRoute(String sessionId, RoutingResolution resolution) {
        return switch (resolution.getRouteType()) {
            case "RESUME" -> handleResume(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
            default -> handleSwitchNew(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
        };
    }

    private WorkflowOutput handleSwitchNew(String sessionId, String intent, String rewrittenInput) {
        // 挂起当前活跃线程
        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[BankController] Suspended current: intent={}", currentActive.getIntent());
        }

        CompiledGraph graph = intentRegistry.getGraph(intent);
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
            log.warn("[BankController] RESUME but no suspended thread for intent={}, fallback to SWITCH_NEW", intent);
            return handleSwitchNew(sessionId, intent, userInput);
        }

        String threadId = suspendedInfo.getThreadId();

        // 挂起当前活跃线程(如果不同)
        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null && !currentActive.getThreadId().equals(threadId)) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
        }

        // 从挂起表移除,设为活跃
        stateManager.resumeAgent(sessionId, intent);
        stateManager.setActiveThread(sessionId, threadId, intent);

        // 恢复累积参数
        AgentStateManager.ActiveThreadInfo newActive = stateManager.getActiveThread(sessionId);
        if (newActive != null && suspendedInfo.getAccumulatedParams() != null) {
            newActive.setAccumulatedParams(suspendedInfo.getAccumulatedParams());
        }

        // 读取中断时的提问,重新展示
        return graphExecutionService.readGraphInterruptState(intent, threadId, sessionId);
    }

    private WorkflowOutput handleCancel(String sessionId) {
        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);

        // 无活跃线程且不在消歧中 → 提示无操作
        if (active == null && !stateManager.isInDisambiguation(sessionId)) {
            return WorkflowOutput.completed(null, "当前没有进行中的操作。有什么可以帮您的吗？");
        }

        // 有活跃线程 → 通知子Graph自行清理
        if (active != null) {
            String intent = active.getIntent();
            // TRANSFER和BILL_QUERY支持_cancelSignal, 让Graph自行清理
            if ("TRANSFER".equals(intent) || "BILL_QUERY".equals(intent)) {
                stateManager.clearDisambiguationState(sessionId);
                return graphExecutionService.cancelGraph(intent, active.getThreadId(), sessionId);
            }
            // 其他意图: 直接清理(Controller层面取消)
            stateManager.completeAgent(sessionId, intent);
            stateManager.clearActiveThread(sessionId);
        }

        // 清除消歧状态
        stateManager.clearDisambiguationState(sessionId);
        return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
    }

    // ==================== 调试接口 ====================

    @PostMapping("/debug/route")
    public Map<String, Object> debugRoute(@RequestParam String sessionId,
                                            @RequestBody Map<String, String> req) {
        String userInput = req.get("message");
        Map<String, Object> result = new HashMap<>();
        try {
            RoutingResult phase1 = intentRouter.route(sessionId, userInput, stateManager);
            result.put("phase1", Map.of(
                    "routeType", phase1.getRouteType() != null ? phase1.getRouteType() : "null",
                    "confidence", phase1.getConfidence(),
                    "intentName", phase1.getIntentName() != null ? phase1.getIntentName() : "null",
                    "reasoning", phase1.getReasoning() != null ? phase1.getReasoning() : "null"
            ));

            RoutingResolution resolution = routingService.resolve(sessionId, userInput, phase1);
            result.put("resolution", Map.of(
                    "status", resolution.getStatus().name(),
                    "intentName", resolution.getIntentName() != null ? resolution.getIntentName() : "null",
                    "rewrittenInput", resolution.getRewrittenInput() != null ? resolution.getRewrittenInput() : "null",
                    "routeType", resolution.getRouteType() != null ? resolution.getRouteType() : "null",
                    "question", resolution.getQuestion() != null ? resolution.getQuestion() : "null",
                    "candidateIntents", resolution.getCandidateIntents() != null ? resolution.getCandidateIntents() : List.of()
            ));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/debug/prompt")
    public Map<String, String> debugPrompt(@RequestParam String sessionId,
                                             @RequestParam String message) {
        Map<String, String> result = new HashMap<>();
        try {
            String template = new String(
                    new org.springframework.core.io.ClassPathResource("prompts/l0-routing.st").getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String intentList = intentRegistry.getIntentListDescription();
            String sessionState = stateManager.getSessionStateDescription(sessionId);
            AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
            String currentAgent = active != null ? active.getIntent() : "无";
            String pendingAgents = stateManager.hasSuspendedAgents(sessionId)
                    ? String.join(", ", stateManager.getAllSuspended(sessionId).keySet())
                    : "无";

            String prompt = template
                    .replace("{intent_list}", intentList)
                    .replace("{message}", message)
                    .replace("{current_agent}", currentAgent)
                    .replace("{last_agent}", currentAgent)
                    .replace("{pending_agents}", pendingAgents)
                    .replace("{session_state}", sessionState);
            result.put("prompt", prompt);
            result.put("intentListLength", String.valueOf(intentList.length()));
            result.put("promptLength", String.valueOf(prompt.length()));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/test/chat")
    public WorkflowOutput testChat(@RequestParam String sessionId,
                                    @RequestParam String intent,
                                    @RequestBody Map<String, String> req) {
        String userInput = req.get("message");
        log.info("[BankController] >>> test chat: sessionId={}, intent={}, message={}", sessionId, intent, userInput);

        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        if (active != null && active.getIntent().equals(intent)) {
            return graphExecutionService.resumeGraph(intent, active.getThreadId(), userInput, sessionId);
        }

        String threadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, threadId, intent);
        return graphExecutionService.executeGraph(graph, intent, threadId, userInput, sessionId, null);
    }

    @GetMapping("/state")
    public Map<String, Object> getState(@RequestParam String sessionId) {
        Map<String, Object> result = new HashMap<>();
        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        if (active != null) {
            Map<String, Object> activeInfo = new HashMap<>();
            activeInfo.put("threadId", active.getThreadId());
            activeInfo.put("intent", active.getIntent());
            activeInfo.put("accumulatedParams", active.getAccumulatedParams());
            result.put("activeThread", activeInfo);
        }
        Map<String, AgentStateManager.SuspendedInfo> suspended = stateManager.getAllSuspended(sessionId);
        if (!suspended.isEmpty()) {
            result.put("suspendedAgents", suspended.entrySet().stream()
                    .map(e -> Map.of("intent", e.getKey(), "threadId", e.getValue().getThreadId()))
                    .toList());
        }
        result.put("registeredIntents", intentRegistry.getIntentNames());
        result.put("inDisambiguation", stateManager.isInDisambiguation(sessionId));
        return result;
    }

    @DeleteMapping("/session")
    public Map<String, String> clearSession(@RequestParam String sessionId) {
        stateManager.clearSession(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }
}
