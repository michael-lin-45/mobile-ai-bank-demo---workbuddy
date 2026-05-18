package com.mobileagent.app.controller;

import com.alibaba.cloud.ai.graph.*;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.model.WorkflowOutput;
import com.mobileagent.app.service.ContextRewriter;
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
 * 关键API模式(interruptBefore模式):
 * - 首次执行: graph.stream(input, config) → 流在interruptBefore节点前停止
 * - 中断检测: getState(config).getNext() 非空 = 被中断
 * - 恢复执行: updateState(config, userInputMap, null) → stream(null, updatedConfig)
 */
@Slf4j
@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final AgentStateManager stateManager;
    private final IntentRegistry intentRegistry;
    private final IntentRouter intentRouter;
    private final ContextRewriter contextRewriter;

    public BankController(AgentStateManager stateManager,
                          IntentRegistry intentRegistry,
                          IntentRouter intentRouter,
                          ContextRewriter contextRewriter) {
        this.stateManager = stateManager;
        this.intentRegistry = intentRegistry;
        this.intentRouter = intentRouter;
        this.contextRewriter = contextRewriter;
    }

    @PostConstruct
    public void init() {
        // 绑定Graph Bean到IntentRegistry
        log.info("[BankController] Initialized");
    }

    /**
     * 主聊天接口
     *
     * @param sessionId 会话ID (用于区分不同用户/会话)
     * @param req 请求体 { "message": "用户输入" }
     * @return WorkflowOutput
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
            log.info("[BankController] Phase1 result: routeType={}, confidence={}",
                    phase1.getRouteType(), phase1.getConfidence());

            // 处理CANCEL
            if ("CANCEL".equals(phase1.getRouteType())) {
                return handleCancel(sessionId);
            }

            // ========== Phase 1 → Phase 3 直达: FOLLOW_UP + activeThread存在 ==========
            if (phase1.isFollowUp()) {
                AgentStateManager.ActiveThreadInfo activeThread = stateManager.getActiveThread(sessionId);
                if (activeThread != null) {
                    // 直接resume,不需要改写
                    log.info("[BankController] FOLLOW_UP with activeThread: threadId={}, intent={}",
                            activeThread.getThreadId(), activeThread.getIntent());
                    return resumeGraph(activeThread.getIntent(), activeThread.getThreadId(), userInput, sessionId);
                }

                // FOLLOW_UP但activeThread为空 → 检查是否有挂起的agents
                if (!stateManager.hasSuspendedAgents(sessionId)) {
                    // 完全没有上下文, fallback到SWITCH_NEW
                    log.info("[BankController] FOLLOW_UP but no activeThread and no suspendedAgents → fallback to SWITCH_NEW");
                    phase1 = RoutingResult.builder()
                            .routeType("SWITCH_NEW")
                            .confidence(0.5)
                            .reasoning("FOLLOW_UP但无活跃线程,降级为新意图")
                            .build();
                } else {
                    // 有挂起的agents,走Phase2识别应该恢复哪个
                    log.info("[BankController] FOLLOW_UP with suspendedAgents → proceed to Phase2");
                    // phase1保持FOLLOW_UP,Phase2会判断
                }
            }

            // ========== Phase 2: 上下文改写+意图识别 (8B) ==========
            RoutingResult phase2 = contextRewriter.rewriteAndIdentify(sessionId, userInput, phase1, stateManager);
            log.info("[BankController] Phase2 result: intent={}, refinedRouteType={}, rewritten={}",
                    phase2.getIntentName(), phase2.getRefinedRouteType(), phase2.getRewrittenInput());

            String effectiveIntent = phase2.getIntentName();

            // 验证意图是否已注册
            if (!intentRegistry.hasIntent(effectiveIntent)) {
                log.warn("[BankController] Unknown intent: {}, attempting fuzzy match, userInput={}", effectiveIntent, userInput);
                effectiveIntent = fuzzyMatchIntent(effectiveIntent, userInput);
                if (effectiveIntent == null) {
                    return WorkflowOutput.error("Unrecognized intent: " + phase2.getIntentName());
                }
                log.info("[BankController] Fuzzy matched to: {}", effectiveIntent);
            }

            // ========== Phase 3: 路由执行 ==========
            String routeType = phase2.getRefinedRouteType() != null ? phase2.getRefinedRouteType() : phase1.getRouteType();
            if (phase1.isFollowUp() && phase2.getRefinedRouteType() == null) {
                // FOLLOW_UP fallback到SWITCH_NEW
                routeType = "SWITCH_NEW";
            }

            return switch (routeType) {
                case "SWITCH_NEW" -> handleSwitchNew(sessionId, effectiveIntent, phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput);
                case "RESUME" -> handleResume(sessionId, effectiveIntent, userInput);
                default -> WorkflowOutput.error("Unknown route type: " + routeType);
            };

        } catch (Exception e) {
            log.error("[BankController] Error processing chat", e);
            return WorkflowOutput.error("处理请求时出错: " + e.getMessage());
        }
    }

    // ==================== 路由执行方法 ====================

    /**
     * SWITCH_NEW: 挂起当前任务 + 创建新线程 + 启动新Graph
     */
    private WorkflowOutput handleSwitchNew(String sessionId, String intent, String rewrittenInput) {
        // 1. 如果当前有活跃线程,先挂起
        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[BankController] Suspended current: intent={}, threadId={}",
                    currentActive.getIntent(), currentActive.getThreadId());
        }

        // 2. 获取目标Graph
        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        // 3. 创建新线程
        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, newThreadId, intent);

        // 4. 执行Graph
        return executeGraph(graph, intent, newThreadId, rewrittenInput, sessionId, null);
    }

    /**
     * RESUME: 从suspendedAgents中恢复挂起的线程
     *
     * 设计: RESUME只是恢复上下文(将挂起线程重新设为activeThread)，
     * 不执行graph。用户下次回答时，FOLLOW_UP会触发resumeGraph重新执行。
     */
    private WorkflowOutput handleResume(String sessionId, String intent, String userInput) {
        // 1. 查找挂起线程
        AgentStateManager.SuspendedInfo suspendedInfo = stateManager.getSuspendedThread(sessionId, intent);
        if (suspendedInfo == null) {
            log.warn("[BankController] RESUME but no suspended thread for intent={}, falling back to SWITCH_NEW", intent);
            return handleSwitchNew(sessionId, intent, userInput);
        }

        String threadId = suspendedInfo.getThreadId();

        // 2. 如果当前有活跃线程且不同,先挂起
        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null && !currentActive.getThreadId().equals(threadId)) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[BankController] Suspended current active: intent={}", currentActive.getIntent());
        }

        // 3. 从挂起表移除,设为活跃
        stateManager.resumeAgent(sessionId, intent);
        stateManager.setActiveThread(sessionId, threadId, intent);

        // 恢复累积参数
        AgentStateManager.ActiveThreadInfo newActive = stateManager.getActiveThread(sessionId);
        if (newActive != null && suspendedInfo.getAccumulatedParams() != null) {
            newActive.setAccumulatedParams(suspendedInfo.getAccumulatedParams());
        }

        // 4. 读取graph中断时的提问,重新展示给用户
        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            var snapshot = graph.getState(config);

            if (snapshot != null) {
                String nextNode = snapshot.next();
                OverAllState currentState = snapshot.state();
                String question = currentState != null
                        ? (String) currentState.value("_question").orElse("")
                        : "";

                if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
                    log.info("[BankController] RESUME restored context: intent={}, nextNode={}, question={}", intent, nextNode, question);
                    return WorkflowOutput.interrupted(intent, threadId, question);
                }
            }

            // graph已完成(不应该发生),返回之前的提问(从累积参数推断)
            stateManager.completeAgent(sessionId, intent);
            return WorkflowOutput.completed(intent, "该任务已完成");

        } catch (Exception e) {
            log.error("[BankController] Failed to read graph state for RESUME", e);
            return WorkflowOutput.error("恢复上下文出错: " + e.getMessage());
        }
    }

    /**
     * CANCEL: 取消当前任务
     */
    private WorkflowOutput handleCancel(String sessionId) {
        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        if (active != null) {
            stateManager.completeAgent(sessionId, active.getIntent());
        }
        stateManager.clearActiveThread(sessionId);
        return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
    }

    // ==================== Graph执行方法 ====================

    /**
     * 执行Graph - 始终创建新的执行,注入累积参数+用户输入
     *
     * 核心设计: 不依赖interruptBefore的resume机制(框架限制:resume后不重新触发interruptBefore)
     * 而是每次都重新执行graph,但注入已收集的参数,让paramRouter跳过已满足的参数。
     *
     * 流程:
     * 1. 注入accumulatedParams + _latestUserInput
     * 2. graph执行,interruptBefore在需要提问的节点前停止
     * 3. 如果被中断: 存储已收集参数,返回提问
     * 4. 如果完成: 返回结果
     */
    private WorkflowOutput executeGraph(CompiledGraph graph, String intent, String threadId,
                                         String userInput, String sessionId,
                                         Map<String, Object> accumulatedParams) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

            Map<String, Object> input = new HashMap<>();
            input.put("messages", userInput);
            input.put("_latestUserInput", userInput);
            input.put("_question", null);

            // 注入累积参数(让paramRouter跳过已满足的参数)
            if (accumulatedParams != null && !accumulatedParams.isEmpty()) {
                input.putAll(accumulatedParams);
                log.info("[BankController] Injecting accumulated params: {}", accumulatedParams);
            }

            log.info("[BankController] Executing graph: intent={}, threadId={}, input={}", intent, threadId, userInput);

            graph.stream(input, config).blockLast();
            return checkGraphResult(graph, config, intent, threadId, sessionId);

        } catch (Exception e) {
            log.error("[BankController] Graph execution failed", e);
            stateManager.clearActiveThread(sessionId);
            return WorkflowOutput.error("执行出错: " + e.getMessage());
        }
    }

    /**
     * 恢复执行Graph - 注入累积参数+用户输入,重新执行
     *
     * 不使用SAA的resume()机制,而是每次都重新执行graph。
     * 原因: interruptBefore在resume后不会重新触发,导致多轮提问失败。
     */
    private WorkflowOutput resumeGraph(String intent, String threadId, String userInput, String sessionId) {
        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        // 获取累积参数
        Map<String, Object> accumulatedParams = new HashMap<>();
        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        if (active != null && active.getIntent().equals(intent)) {
            accumulatedParams.putAll(active.getAccumulatedParams());
        }

        // 生成新threadId(每次重新执行)
        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, newThreadId, intent);
        // 恢复累积参数到新的activeThread
        AgentStateManager.ActiveThreadInfo newActive = stateManager.getActiveThread(sessionId);
        if (newActive != null) {
            newActive.setAccumulatedParams(accumulatedParams);
        }

        log.info("[BankController] Resume graph (new execution): intent={}, oldThread={}, newThread={}, userInput={}, params={}",
                intent, threadId, newThreadId, userInput, accumulatedParams);

        return executeGraph(graph, intent, newThreadId, userInput, sessionId, accumulatedParams);
    }

    /**
     * 检查Graph执行结果 - 区分正常完成和中断
     *
     * 中断来源:
     * 1. interruptBefore机制: next()非空且非__END__ → 首次/重新执行时触发
     * 2. ask→END条件路由: graph结束但_question非空 → 兜底(不应触发)
     */
    private WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config,
                                             String intent, String threadId, String sessionId) {
        try {
            var snapshot = graph.getState(config);

            if (snapshot == null) {
                log.warn("[BankController] getState returned null");
                stateManager.completeAgent(sessionId, intent);
                return WorkflowOutput.completed(intent, "操作完成(无状态)");
            }

            String nextNode = snapshot.next();
            OverAllState currentState = snapshot.state();
            String question = currentState != null
                    ? (String) currentState.value("_question").orElse("")
                    : "";

            log.info("[BankController] Graph result: nextNode={}, question={}", nextNode, question);

            // 保存已收集的参数到activeThread
            AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
            if (active != null && currentState != null) {
                Map<String, Object> params = extractAccumulatedParams(intent, currentState);
                active.setAccumulatedParams(params);
                log.info("[BankController] Saved accumulated params: {}", params);
            }

            // 判断1: interruptBefore中断 (next()非空且非END)
            if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
                stateManager.suspendAgent(sessionId, intent, threadId);
                stateManager.setActiveThread(sessionId, threadId, intent);
                // 恢复累积参数
                if (active != null) {
                    AgentStateManager.ActiveThreadInfo restoredActive = stateManager.getActiveThread(sessionId);
                    if (restoredActive != null) {
                        restoredActive.setAccumulatedParams(active.getAccumulatedParams());
                    }
                }
                log.info("[BankController] Interrupted by interruptBefore: nextNode={}, question={}", nextNode, question);
                return WorkflowOutput.interrupted(intent, threadId, question);
            }

            // 判断2: ask→END中断 (graph结束但有提问 - 兜底)
            if (question != null && !question.isEmpty()) {
                stateManager.suspendAgent(sessionId, intent, threadId);
                stateManager.setActiveThread(sessionId, threadId, intent);
                if (active != null) {
                    AgentStateManager.ActiveThreadInfo restoredActive = stateManager.getActiveThread(sessionId);
                    if (restoredActive != null) {
                        restoredActive.setAccumulatedParams(active.getAccumulatedParams());
                    }
                }
                log.info("[BankController] Interrupted by ask→END: question={}", question);
                return WorkflowOutput.interrupted(intent, threadId, question);
            }

            // 正常完成
            String content = currentState != null
                    ? (String) currentState.value("_outputContent").orElse("操作已完成")
                    : "操作已完成";

            stateManager.completeAgent(sessionId, intent);
            log.info("[BankController] Graph completed: content={}", content);
            return WorkflowOutput.completed(intent, content);

        } catch (Exception e) {
            log.error("[BankController] Failed to check graph result", e);
            stateManager.completeAgent(sessionId, intent);
            return WorkflowOutput.completed(intent, "操作完成(状态检查失败)");
        }
    }

    /** 从graph state中提取已收集的参数 */
    private Map<String, Object> extractAccumulatedParams(String intent, OverAllState state) {
        Map<String, Object> params = new HashMap<>();
        String prefix = switch (intent) {
            case "TRANSFER" -> "transfer.";
            case "BILL_QUERY" -> "bill.";
            default -> "";
        };

        for (String key : state.data().keySet()) {
            if (key.startsWith(prefix)) {
                Object value = state.value(key).orElse(null);
                if (value != null && !value.toString().isEmpty()) {
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    // ==================== 辅助方法 ====================

    /** 模糊匹配意图 - 当LLM返回的intent不在注册表中时尝试匹配 */
    private String fuzzyMatchIntent(String intent, String userInput) {
        if (intent == null) return guessIntentFromInput(userInput);
        String upper = intent.toUpperCase().replace("-", "_").replace(" ", "_");
        for (String registeredIntent : intentRegistry.getIntentNames()) {
            if (upper.contains(registeredIntent) || registeredIntent.contains(upper)) {
                return registeredIntent;
            }
        }
        // 基于关键词的简单匹配
        if (upper.contains("TRANSFER") || upper.contains("转账")) return "TRANSFER";
        if (upper.contains("BILL") || upper.contains("账单")) return "BILL_QUERY";
        // LLM返回UNKNOWN时,从用户输入推断
        return guessIntentFromInput(userInput);
    }

    /** 从用户输入关键词推断意图 */
    private String guessIntentFromInput(String userInput) {
        if (userInput == null) return null;
        String lower = userInput.toLowerCase();
        if (lower.contains("转账") || lower.contains("转钱") || lower.contains("汇款") || lower.contains("transfer")) return "TRANSFER";
        if (lower.contains("账单") || lower.contains("明细") || lower.contains("消费") || lower.contains("支出") || lower.contains("bill")) return "BILL_QUERY";
        return null;
    }

    /** 直接测试接口 - 跳过LLM路由直接执行指定意图 */
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
            // 已有同意图的活跃线程 → resume
            return resumeGraph(intent, active.getThreadId(), userInput, sessionId);
        }

        String threadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, threadId, intent);
        return executeGraph(graph, intent, threadId, userInput, sessionId, null);
    }

    /** 获取会话状态 (调试用) */
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

    /** 清除会话 (调试用) */
    @DeleteMapping("/session")
    public Map<String, String> clearSession(@RequestParam String sessionId) {
        stateManager.clearSession(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }
}
