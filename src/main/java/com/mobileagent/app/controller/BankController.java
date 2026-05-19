package com.mobileagent.app.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.model.WorkflowOutput;
import com.mobileagent.app.service.ContextRewriter;
import com.mobileagent.app.service.GraphExecutionService;
import com.mobileagent.app.service.IntentRouter;
import com.mobileagent.app.state.AgentStateManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 银行主控制器 (Master) - 接收用户消息 → 3阶段LLM路由 → 执行Graph → 处理中断/完成
 *
 * 3阶段路由:
 * Phase1: 4B模型判断意图类型 (FOLLOW_UP / SWITCH_NEW / RESUME)
 * Phase2: 8B模型上下文改写+意图识别 (仅SWITCH_NEW/RESUME/fallback需要)
 * Phase3: 路由执行
 *
 * 职责边界:
 * - Controller: 路由决策 + 消歧决策 + 协调Service
 * - GraphExecutionService: Graph执行 + 中断检测 + 参数提取
 * - IntentRegistry: 意图注册 + 模糊匹配
 * - IntentRouter/ContextRewriter: LLM路由判断
 */
@Slf4j
@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final AgentStateManager stateManager;
    private final IntentRegistry intentRegistry;
    private final IntentRouter intentRouter;
    private final ContextRewriter contextRewriter;
    private final GraphExecutionService graphExecutionService;

    public BankController(AgentStateManager stateManager,
                          IntentRegistry intentRegistry,
                          IntentRouter intentRouter,
                          ContextRewriter contextRewriter,
                          GraphExecutionService graphExecutionService) {
        this.stateManager = stateManager;
        this.intentRegistry = intentRegistry;
        this.intentRouter = intentRouter;
        this.contextRewriter = contextRewriter;
        this.graphExecutionService = graphExecutionService;
    }

    @PostConstruct
    public void init() {
        log.info("[BankController] Initialized");
    }

    /**
     * 主聊天接口
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
            // ========== 消歧模式处理 ==========
            if (stateManager.isInDisambiguation(sessionId)) {
                return handleDisambiguationAnswer(sessionId, userInput);
            }

            // ========== Phase 1: 意图类型判断 (4B) ==========
            RoutingResult phase1 = intentRouter.route(sessionId, userInput, stateManager);
            log.info("[BankController] Phase1: routeType={}, confidence={}",
                    phase1.getRouteType(), phase1.getConfidence());

            if ("CANCEL".equals(phase1.getRouteType())) {
                return handleCancel(sessionId);
            }

            // ========== FOLLOW_UP + activeThread → 直接resume ==========
            if (phase1.isFollowUp()) {
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

            // ========== Phase 2: 上下文改写+意图识别 (8B) ==========
            RoutingResult phase2 = contextRewriter.rewriteAndIdentify(sessionId, userInput, phase1, stateManager);
            log.info("[BankController] Phase2: intent={}, ambiguous={}, candidates={}",
                    phase2.getIntentName(), phase2.isAmbiguous(), phase2.getCandidateIntents());

            // ========== 消歧检查 ==========
            if (phase2.isAmbiguous() && phase2.getCandidateIntents() != null && !phase2.getCandidateIntents().isEmpty()) {
                String groupId = phase2.getGroupId();
                if (groupId == null) groupId = inferGroupId(phase2.getCandidateIntents());
                if (groupId != null && intentRegistry.getGroup(groupId) != null) {
                    return handleDisambiguationNeeded(sessionId, phase2, userInput);
                }
                log.info("[BankController] Ambiguous but no matching group, falling through with first candidate");
            }

            // ========== 完全无法识别 → 不支持 ==========
            String effectiveIntent = phase2.getIntentName();
            if ("UNKNOWN".equalsIgnoreCase(effectiveIntent) || effectiveIntent == null) {
                log.info("[BankController] Intent completely unidentifiable");
                return WorkflowOutput.completed(null, "不支持该功能");
            }

            // ========== 意图组名 → 消歧 ==========
            if (intentRegistry.isGroupName(effectiveIntent)) {
                IntentRegistry.IntentGroup group = intentRegistry.getGroup(effectiveIntent);
                if (group != null) {
                    RoutingResult disambigResult = RoutingResult.builder()
                            .routeType(phase1.getRouteType()).intentName(effectiveIntent)
                            .groupId(effectiveIntent).candidateIntents(group.getIntentNames())
                            .ambiguous(true).rewrittenInput(phase2.getRewrittenInput())
                            .confidence(phase2.getConfidence()).build();
                    return handleDisambiguationNeeded(sessionId, disambigResult, userInput);
                }
            }

            // ========== 模糊匹配 ==========
            if (!intentRegistry.hasIntent(effectiveIntent)) {
                log.warn("[BankController] Unknown intent: {}, attempting fuzzy match", effectiveIntent);
                effectiveIntent = intentRegistry.fuzzyMatchIntent(effectiveIntent, userInput);
                if (effectiveIntent == null) {
                    return WorkflowOutput.completed(null, "不支持该功能");
                }
                // 模糊匹配到组名 → 消歧
                if (intentRegistry.isGroupName(effectiveIntent)) {
                    IntentRegistry.IntentGroup group = intentRegistry.getGroup(effectiveIntent);
                    if (group != null) {
                        RoutingResult disambigResult = RoutingResult.builder()
                                .routeType(phase1.getRouteType()).intentName(effectiveIntent)
                                .groupId(effectiveIntent).candidateIntents(group.getIntentNames())
                                .ambiguous(true).rewrittenInput(phase2.getRewrittenInput())
                                .confidence(phase2.getConfidence()).build();
                        return handleDisambiguationNeeded(sessionId, disambigResult, userInput);
                    }
                }
                log.info("[BankController] Fuzzy matched to: {}", effectiveIntent);
            }

            // ========== Phase 3: 路由执行 ==========
            String routeType = phase2.getRefinedRouteType() != null ? phase2.getRefinedRouteType() : phase1.getRouteType();
            if (phase1.isFollowUp() && phase2.getRefinedRouteType() == null) {
                routeType = "SWITCH_NEW";
            }

            return switch (routeType) {
                case "SWITCH_NEW" -> handleSwitchNew(sessionId, effectiveIntent,
                        phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput);
                case "RESUME" -> handleResume(sessionId, effectiveIntent, userInput);
                default -> WorkflowOutput.error("Unknown route type: " + routeType);
            };

        } catch (Exception e) {
            log.error("[BankController] Error processing chat", e);
            return WorkflowOutput.error("处理请求时出错: " + e.getMessage());
        }
    }

    // ==================== 消歧处理 ====================

    private WorkflowOutput handleDisambiguationNeeded(String sessionId, RoutingResult phase2Result, String userInput) {
        String groupId = phase2Result.getGroupId();
        if (groupId == null && phase2Result.getCandidateIntents() != null && !phase2Result.getCandidateIntents().isEmpty()) {
            groupId = inferGroupId(phase2Result.getCandidateIntents());
        }

        IntentRegistry.IntentGroup group = groupId != null ? intentRegistry.getGroup(groupId) : null;
        if (group == null) {
            log.warn("[BankController] Disambiguation failed: no group found for candidates={}", phase2Result.getCandidateIntents());
            return WorkflowOutput.completed(null, "不支持该功能");
        }

        stateManager.setDisambiguationState(sessionId, new AgentStateManager.DisambiguationState(groupId, userInput));
        log.info("[BankController] Entering disambiguation: groupId={}, attempt=1", groupId);
        return WorkflowOutput.disambiguation(group.getDisambiguationQuestion(), group.getIntentNames());
    }

    private WorkflowOutput handleDisambiguationAnswer(String sessionId, String userInput) {
        AgentStateManager.DisambiguationState disambigState = stateManager.getDisambiguationState(sessionId);
        if (disambigState == null) {
            stateManager.clearDisambiguationState(sessionId);
            return WorkflowOutput.error("消歧状态异常");
        }

        String groupId = disambigState.getGroupId();
        IntentRegistry.IntentGroup group = intentRegistry.getGroup(groupId);
        if (group == null) {
            stateManager.clearDisambiguationState(sessionId);
            return WorkflowOutput.completed(null, "不支持该功能");
        }

        // 重新走Phase2识别
        RoutingResult phase1Result = RoutingResult.builder()
                .routeType("SWITCH_NEW").confidence(0.8).reasoning("消歧回答重新识别").build();
        RoutingResult phase2 = contextRewriter.rewriteAndIdentify(sessionId, userInput, phase1Result, stateManager);
        log.info("[BankController] Disambiguation re-identify: intent={}, ambiguous={}",
                phase2.getIntentName(), phase2.isAmbiguous());

        String identifiedIntent = phase2.getIntentName();
        if (identifiedIntent != null && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && intentRegistry.hasIntent(identifiedIntent) && !intentRegistry.isGroupName(identifiedIntent)) {
            stateManager.clearDisambiguationState(sessionId);
            log.info("[BankController] Disambiguation resolved: intent={}", identifiedIntent);
            return handleSwitchNew(sessionId, identifiedIntent,
                    phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput);
        }

        // 仍然模糊 → 增加尝试次数
        disambigState = stateManager.incrementDisambiguationAttempt(sessionId);
        int attempt = disambigState.getAttemptCount();

        if (attempt >= 3) {
            stateManager.clearDisambiguationState(sessionId);
            log.info("[BankController] Disambiguation max attempts ({}) → 不支持该功能", attempt);
            return WorkflowOutput.completed(null, "不支持该功能");
        }

        log.info("[BankController] Disambiguation attempt {}: still ambiguous, asking again", attempt);
        return WorkflowOutput.disambiguation(group.getDisambiguationQuestion(), group.getIntentNames());
    }

    private String inferGroupId(java.util.List<String> candidateIntents) {
        for (String candidate : candidateIntents) {
            IntentRegistry.IntentGroup group = intentRegistry.findGroupByIntent(candidate);
            if (group != null) return group.getGroupId();
        }
        return null;
    }

    // ==================== 路由执行 ====================

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
        if (active != null) {
            stateManager.completeAgent(sessionId, active.getIntent());
        }
        stateManager.clearActiveThread(sessionId);
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

            RoutingResult phase2 = contextRewriter.rewriteAndIdentify(sessionId, userInput, phase1, stateManager);
            result.put("phase2", Map.of(
                    "intentName", phase2.getIntentName() != null ? phase2.getIntentName() : "null",
                    "rewrittenInput", phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : "null",
                    "refinedRouteType", phase2.getRefinedRouteType() != null ? phase2.getRefinedRouteType() : "null",
                    "ambiguous", phase2.isAmbiguous(),
                    "candidateIntents", phase2.getCandidateIntents() != null ? phase2.getCandidateIntents() : List.of(),
                    "groupId", phase2.getGroupId() != null ? phase2.getGroupId() : "null",
                    "confidence", phase2.getConfidence()
            ));

            result.put("isGroupName", phase2.getIntentName() != null && intentRegistry.isGroupName(phase2.getIntentName()));
            result.put("hasIntent", phase2.getIntentName() != null && intentRegistry.hasIntent(phase2.getIntentName()));
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
        return result;
    }

    @DeleteMapping("/session")
    public Map<String, String> clearSession(@RequestParam String sessionId) {
        stateManager.clearSession(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }
}
