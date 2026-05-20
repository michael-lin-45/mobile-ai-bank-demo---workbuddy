package com.mobileagent.app.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResolution;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.model.WorkflowOutput;
import com.mobileagent.app.service.GraphExecutionService;
import com.mobileagent.app.service.ContextRouter;
import com.mobileagent.app.service.RoutingService;
import com.mobileagent.app.state.AgentStateManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 银行主控制器 (Master) - 接收用户消息 → Phase1路由 → 路由决议 → 执行Graph
 *
 * 对话历史管理 (ChatMemory):
 * - 用户消息: Phase1+Phase2均读取完成后添加(避免与.messages(history)重复)
 * - 系统回复: 子Graph追问/完成结果/消歧追问,拿到结果后提取文本添加
 * - 路由JSON: 不进ChatMemory(对用户不可见)
 *
 * ChatMemory中的对话格式:
 *   USER: "我想转账"
 *   ASSISTANT: "请问您要转给谁？"
 *   USER: "张三"
 *   ASSISTANT: "请问您要转多少金额？"
 */
@Slf4j
@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final AgentStateManager stateManager;
    private final IntentRegistry intentRegistry;
    private final ContextRouter contextRouter;
    private final RoutingService routingService;
    private final GraphExecutionService graphExecutionService;
    private final ChatMemory chatMemory;

    public BankController(AgentStateManager stateManager,
                          IntentRegistry intentRegistry,
                          ContextRouter contextRouter,
                          RoutingService routingService,
                          GraphExecutionService graphExecutionService,
                          ChatMemory chatMemory) {
        this.stateManager = stateManager;
        this.intentRegistry = intentRegistry;
        this.contextRouter = contextRouter;
        this.routingService = routingService;
        this.graphExecutionService = graphExecutionService;
        this.chatMemory = chatMemory;
    }

    @PostConstruct
    public void init() {
        log.info("[BankController] Initialized with ChatMemory");
    }

    /**
     * 主聊天接口
     *
     * 流程:
 * 1. Phase1 (ContextRouter): 判断路由类型(带对话历史)
 * 2. 路由决议 → Phase2 (IntentionRouter也带对话历史)
     * 3. 记录用户消息到ChatMemory(Phase1+Phase2均已读取)
     * 4. 执行Graph
     * 5. 记录系统回复到ChatMemory(子Graph追问/完成结果)
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
            // ========== Phase 1: 意图类型判断 (带对话历史) ==========
            RoutingResult phase1 = contextRouter.route(sessionId, userInput, stateManager);
            log.info("[BankController] Phase1: routeType={}, confidence={}",
                    phase1.getRouteType(), phase1.getConfidence());

            // ========== CANCEL during disambiguation ==========
            if (phase1.isFollowUp() && stateManager.isInDisambiguation(sessionId)) {
                if (isCancelExpression(userInput)) {
                    log.info("[BankController] Cancel detected during disambiguation");
                    chatMemory.add(sessionId, new UserMessage(userInput));
                    WorkflowOutput cancelResult = handleCancel(sessionId);
                    recordSystemReply(sessionId, cancelResult);
                    return cancelResult;
                }
            }

            // ========== FOLLOW_UP + activeThread → 直接resume (消歧中除外) ==========
            if (phase1.isFollowUp() && !stateManager.isInDisambiguation(sessionId)) {
                AgentStateManager.ActiveThreadInfo activeThread = stateManager.getActiveThread(sessionId);
                if (activeThread != null) {
                    log.info("[BankController] FOLLOW_UP with activeThread: intent={}", activeThread.getIntent());
                    chatMemory.add(sessionId, new UserMessage(userInput));
                    WorkflowOutput resumeResult = graphExecutionService.resumeGraph(
                            activeThread.getIntent(), activeThread.getThreadId(), userInput, sessionId);
                    recordSystemReply(sessionId, resumeResult);
                    return resumeResult;
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

            // ========== 记录用户消息到ChatMemory (Phase1+Phase2均已读取,现在可以安全添加) ==========
            chatMemory.add(sessionId, new UserMessage(userInput));

            // ========== 根据决议执行Phase3 ==========
            WorkflowOutput output = switch (resolution.getStatus()) {
                case RESOLVED -> executeRoute(sessionId, resolution);
                case DISAMBIGUATION -> {
                    AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
                    if (active != null) {
                        stateManager.suspendAgent(sessionId, active.getIntent(), active.getThreadId());
                        stateManager.clearActiveThread(sessionId);
                        log.info("[BankController] Disambiguation: suspended activeThread intent={}", active.getIntent());
                    }
                    yield WorkflowOutput.disambiguation(resolution.getQuestion(), resolution.getCandidateIntents());
                }
                case REJECTED -> WorkflowOutput.completed(null, "不支持该功能");
            };

            // ========== 记录系统回复到ChatMemory ==========
            recordSystemReply(sessionId, output);
            return output;

        } catch (Exception e) {
            log.error("[BankController] Error processing chat", e);
            return WorkflowOutput.error("处理请求时出错: " + e.getMessage());
        }
    }

    // ==================== 对话历史管理 ====================

    /**
     * 记录系统回复到ChatMemory - 只存用户可见的文本,不存路由JSON
     *
     * 从WorkflowOutput提取用户可见的回复:
     * - INTERRUPTED: 子Graph追问 (question)
     * - COMPLETED: 完成结果 (content)
     * - DISAMBIGUATION: 消歧追问 (question)
     * - ERROR: 错误信息 (errorMessage)
     */
    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        if (output == null) return;

        String reply = extractReplyContent(output);
        if (reply != null && !reply.isEmpty()) {
            chatMemory.add(sessionId, new AssistantMessage(reply));
            log.debug("[BankController] Recorded system reply: session={}, status={}, reply={}",
                    sessionId, output.getStatus(), truncate(reply, 50));
        }
    }

    private String extractReplyContent(WorkflowOutput output) {
        return switch (output.getStatus()) {
            case "INTERRUPTED" -> output.getQuestion();
            case "COMPLETED" -> output.getContent();
            case "DISAMBIGUATION" -> output.getQuestion();
            case "ERROR" -> output.getErrorMessage();
            default -> null;
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== Phase3: 路由执行 ====================

    private WorkflowOutput executeRoute(String sessionId, RoutingResolution resolution) {
        return switch (resolution.getRouteType()) {
            case "RESUME" -> handleResume(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
            default -> handleSwitchNew(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
        };
    }

    private WorkflowOutput handleSwitchNew(String sessionId, String intent, String rewrittenInput) {
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

        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null && !currentActive.getThreadId().equals(threadId)) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
        }

        stateManager.resumeAgent(sessionId, intent);
        stateManager.setActiveThread(sessionId, threadId, intent);

        AgentStateManager.ActiveThreadInfo newActive = stateManager.getActiveThread(sessionId);
        if (newActive != null && suspendedInfo.getAccumulatedParams() != null) {
            newActive.setAccumulatedParams(suspendedInfo.getAccumulatedParams());
        }

        // 与FOLLOW_UP一致: 重新执行graph + 注入accumulatedParams + 处理用户输入
        // 而非readGraphInterruptState(只读旧checkpoint,丢弃用户输入)
        log.info("[BankController] RESUME with resumeGraph: intent={}, userInput={}", intent, userInput);
        return graphExecutionService.resumeGraph(intent, threadId, userInput, sessionId);
    }

    private WorkflowOutput handleCancel(String sessionId) {
        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);

        if (active == null && !stateManager.isInDisambiguation(sessionId)) {
            return WorkflowOutput.completed(null, "当前没有进行中的操作。有什么可以帮您的吗？");
        }

        if (active != null) {
            stateManager.clearDisambiguationState(sessionId);
            return graphExecutionService.cancelGraph(active.getIntent(), active.getThreadId(), sessionId);
        }

        stateManager.clearDisambiguationState(sessionId);
        return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
    }

    // ==================== 调试接口 ====================

    private boolean isCancelExpression(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        return trimmed.matches("^(取消|算了|不要了|不转了|不查了|不了|放弃|别转了|别查了|算了不问了|取消查询|取消转账)$")
            || trimmed.matches(".*(取消|算了|放弃|不要了|不想).*$") && trimmed.length() <= 8;
    }

    @PostMapping("/debug/route")
    public Map<String, Object> debugRoute(@RequestParam String sessionId,
                                            @RequestBody Map<String, String> req) {
        String userInput = req.get("message");
        Map<String, Object> result = new HashMap<>();
        try {
            RoutingResult phase1 = contextRouter.route(sessionId, userInput, stateManager);
            result.put("phase1", Map.of(
                    "routeType", phase1.getRouteType() != null ? phase1.getRouteType() : "null",
                    "confidence", phase1.getConfidence(),
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
        chatMemory.clear(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }
}
