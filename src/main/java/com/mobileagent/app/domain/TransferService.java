package com.mobileagent.app.domain;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.model.WorkflowOutput;
import com.mobileagent.app.service.ContextRouter;
import com.mobileagent.app.service.GraphExecutionService;
import com.mobileagent.app.state.AgentStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 转账L1 Service - 极简路由: 只有FOLLOW_UP和SWITCH_NEW
 *
 * 设计:
 * - 无IntentionRouter, 无RoutingService, 无suspendedAgents
 * - FOLLOW_UP + 本领域activeThread → resumeGraph(注入accumulatedParams)
 * - FOLLOW_UP + 非本领域activeThread/无activeThread → 降级为SWITCH_NEW
 * - SWITCH_NEW → 新建thread + TransferGraph
 * - 完成后activeThread = null
 * - 独立ChatMemory实例(只记录本领域消息)
 *
 * resumeGraph机制(与WealthService一致):
 * - 不使用SAA的resume()，而是重新执行graph + 注入accumulatedParams
 * - 原因: interruptBefore在resume后不会重新触发，导致多轮提问失败
 */
@Slf4j
@Service
public class TransferService {

    private static final String INTENT = "TRANSFER";
    private static final String DOMAIN_NAME = "转账";

    private final ContextRouter contextRouter;
    private final GraphExecutionService graphExecutionService;
    private final IntentRegistry intentRegistry;
    private final AgentStateManager stateManager;
    private final ChatMemory transferChatMemory;

    public TransferService(ContextRouter contextRouter,
                           GraphExecutionService graphExecutionService,
                           IntentRegistry intentRegistry,
                           AgentStateManager stateManager,
                           @org.springframework.beans.factory.annotation.Qualifier("transferChatMemory") ChatMemory transferChatMemory) {
        this.contextRouter = contextRouter;
        this.graphExecutionService = graphExecutionService;
        this.intentRegistry = intentRegistry;
        this.stateManager = stateManager;
        this.transferChatMemory = transferChatMemory;
    }

    /**
     * 处理转账领域的消息
     *
     * 流程(与WealthService对齐):
     * 1. Phase1: ContextRouter判断FOLLOW_UP/SWITCH_NEW
     * 2. FOLLOW_UP + 本领域activeThread → resumeGraph(与WealthService一致的resume机制)
     * 3. FOLLOW_UP + 无activeThread → 降级为SWITCH_NEW
     * 4. SWITCH_NEW → 新建thread + executeGraph
     * 5. CANCEL: 只在FOLLOW_UP分支中处理(纯取消表达)
     */
    public WorkflowOutput handle(String sessionId, String userInput) {
        log.info("[TransferService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            // Phase1: ContextRouter判断FOLLOW_UP/SWITCH_NEW (简化模式)
            RoutingResult phase1 = contextRouter.route(sessionId, userInput, stateManager,
                    "prompts/l1-routing-simple.st", DOMAIN_NAME, transferChatMemory);
            log.info("[TransferService] Phase1: routeType={}, confidence={}", phase1.getRouteType(), phase1.getConfidence());

            // ========== CANCEL检测: 只在FOLLOW_UP + 本领域activeThread时处理 ==========
            if (phase1.isFollowUp()) {
                AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
                if (active != null && INTENT.equals(active.getIntent()) && isCancelExpression(userInput)) {
                    log.info("[TransferService] Cancel detected during FOLLOW_UP");
                    transferChatMemory.add(sessionId, new UserMessage(userInput));
                    WorkflowOutput cancelResult = graphExecutionService.cancelGraph(INTENT, active.getThreadId(), sessionId);
                    recordSystemReply(sessionId, cancelResult);
                    return cancelResult;
                }

                // ========== FOLLOW_UP + 本领域activeThread → resumeGraph ==========
                if (active != null) {
                    log.info("[TransferService] FOLLOW_UP with activeThread: intent={}", active.getIntent());
                    transferChatMemory.add(sessionId, new UserMessage(userInput));
                    // 使用resumeGraph: 不走SAA的resume()，而是重新执行graph+注入accumulatedParams
                    WorkflowOutput resumeResult = graphExecutionService.resumeGraph(
                            active.getIntent(), active.getThreadId(), userInput, sessionId);
                    recordSystemReply(sessionId, resumeResult);
                    return resumeResult;
                }

                // FOLLOW_UP但无activeThread → 降级为SWITCH_NEW
                log.info("[TransferService] FOLLOW_UP but no activeThread → fallback to SWITCH_NEW");
            }

            // ========== SWITCH_NEW (或FOLLOW_UP降级) ==========
            // 记录用户消息到领域ChatMemory
            transferChatMemory.add(sessionId, new UserMessage(userInput));
            return handleSwitchNew(sessionId, userInput);

        } catch (Exception e) {
            log.error("[TransferService] Error handling message", e);
            return WorkflowOutput.error("处理转账请求时出错: " + e.getMessage());
        }
    }

    private WorkflowOutput handleSwitchNew(String sessionId, String userInput) {
        // 挂起当前活跃线程(无论属于哪个领域 — 其他领域的线程切换过来时需要挂起原线程)
        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[TransferService] Suspended current activeThread: intent={}", currentActive.getIntent());
        }

        CompiledGraph graph = intentRegistry.getGraph(INTENT);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + INTENT);
        }

        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, newThreadId, INTENT);

        return graphExecutionService.executeGraph(graph, INTENT, newThreadId, userInput, sessionId, null);
    }

    // ==================== ChatMemory管理 ====================

    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        if (output == null) return;
        String reply = extractReplyContent(output);
        if (reply != null && !reply.isEmpty()) {
            transferChatMemory.add(sessionId, new AssistantMessage(reply));
            log.debug("[TransferService] Recorded system reply: session={}, reply={}", sessionId, truncate(reply, 50));
        }
    }

    private String extractReplyContent(WorkflowOutput output) {
        return switch (output.getStatus()) {
            case "INTERRUPTED" -> output.getQuestion();
            case "COMPLETED" -> output.getContent();
            case "ERROR" -> output.getErrorMessage();
            default -> null;
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private boolean isCancelExpression(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        // 只匹配纯取消表达，不含新意图。"算了X"/"不转了查账单"等由L0判断路由到新领域
        return trimmed.matches("^(取消|算了|不要了|不转了|不了|放弃|别转了|取消转账)$");
    }
}
