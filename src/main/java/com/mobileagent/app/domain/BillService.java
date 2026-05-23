package com.mobileagent.app.domain;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.rewriter.ContextRewriter;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.execution.GraphExecutionService;
import com.mobileagent.app.manager.AgentStateManager;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 账单L1 Service - 极简路由: 只有FOLLOW_UP和SWITCH_NEW
 *
 * 设计:
 * - 无IntentionRouter, 无RoutingService, 无suspendedAgents
 * - FOLLOW_UP + 本领域activeThread → resumeGraph(注入accumulatedParams)
 * - FOLLOW_UP + 非本领域activeThread/无activeThread → 降级为SWITCH_NEW
 * - SWITCH_NEW → 新建thread + BillQueryGraph
 * - 完成后activeThread = null
 * - 独立ChatMemory实例(只记录本领域消息)
 * - 取消由子workflow处理(cancelAwareExtractParams → cancelExecutionNode)
 *
 * resumeGraph机制(与WealthService一致):
 * - 不使用SAA的resume()，而是重新执行graph + 注入accumulatedParams
 * - 原因: interruptBefore在resume后不会重新触发，导致多轮提问失败
 */
@Slf4j
@Service
public class BillService {

    private static final String INTENT = "BILL_QUERY";
    private static final String DOMAIN_NAME = "账单";

    private final ContextRouter contextRouter;
    private final ContextRewriter contextRewriter;
    private final GraphExecutionService graphExecutionService;
    private final IntentRegistry intentRegistry;
    private final AgentStateManager stateManager;
    private final ChatMemory billChatMemory;

    public BillService(ContextRouter contextRouter,
                       ContextRewriter contextRewriter,
                       GraphExecutionService graphExecutionService,
                       IntentRegistry intentRegistry,
                       AgentStateManager stateManager,
                       @org.springframework.beans.factory.annotation.Qualifier("billChatMemory") ChatMemory billChatMemory) {
        this.contextRouter = contextRouter;
        this.contextRewriter = contextRewriter;
        this.graphExecutionService = graphExecutionService;
        this.intentRegistry = intentRegistry;
        this.stateManager = stateManager;
        this.billChatMemory = billChatMemory;
    }

    /**
     * 处理账单领域的消息
     *
     * 流程(与WealthService对齐):
     * 1. Phase1: ContextRouter判断FOLLOW_UP/SWITCH_NEW
     * 2. FOLLOW_UP + 本领域activeThread → resumeGraph(与WealthService一致的resume机制)
     * 3. FOLLOW_UP + 无activeThread → 降级为SWITCH_NEW
     * 4. SWITCH_NEW → 新建thread + executeGraph
     * 5. 取消由子workflow处理(cancelAwareExtractParams检测取消意图)
     */
    public WorkflowOutput handle(String sessionId, String userInput) {
        log.info("[BillService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            // Phase1: ContextRouter判断FOLLOW_UP/SWITCH_NEW (简化模式)
            RoutingResult phase1 = contextRouter.route(sessionId, userInput, stateManager,
                    "prompts/l1-routing-simple.st", DOMAIN_NAME, billChatMemory);
            log.info("[BillService] Phase1: routeType={}, confidence={}", phase1.getRouteType(), phase1.getConfidence());

            // ========== FOLLOW_UP分支 ==========
            if (phase1.isFollowUp()) {
                AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);

                // ========== FOLLOW_UP + 本领域activeThread → resumeGraph ==========
                if (active != null && INTENT.equals(active.getIntent())) {
                    log.info("[BillService] FOLLOW_UP with same-domain activeThread: intent={}", active.getIntent());
                    billChatMemory.add(sessionId, new UserMessage(userInput));
                    // 使用resumeGraph: 不走SAA的resume()，而是重新执行graph+注入accumulatedParams
                    WorkflowOutput resumeResult = graphExecutionService.resumeGraph(
                            active.getIntent(), active.getThreadId(), userInput, sessionId);
                    recordSystemReply(sessionId, resumeResult);
                    return resumeResult;
                }

                // ========== FOLLOW_UP + 异领域activeThread → 挂起+降级SWITCH_NEW ==========
                if (active != null) {
                    log.info("[BillService] FOLLOW_UP but activeThread is different domain: activeIntent={}, thisDomain={} → suspend and SWITCH_NEW",
                            active.getIntent(), INTENT);
                    String rewrittenInput = contextRewriter.rewrite(sessionId, userInput, billChatMemory, DOMAIN_NAME);
                    billChatMemory.add(sessionId, new UserMessage(userInput));
                    return handleSwitchNew(sessionId, userInput, rewrittenInput);
                }

                // FOLLOW_UP但无activeThread → 上下文改写后降级为SWITCH_NEW
                log.info("[BillService] FOLLOW_UP but no activeThread → rewrite and fallback to SWITCH_NEW");
                String rewrittenInput = contextRewriter.rewrite(sessionId, userInput, billChatMemory, DOMAIN_NAME);
                billChatMemory.add(sessionId, new UserMessage(userInput));
                return handleSwitchNew(sessionId, userInput, rewrittenInput);
            }

            // ========== SWITCH_NEW (非FOLLOW_UP) ==========
            // 记录用户消息到领域ChatMemory
            billChatMemory.add(sessionId, new UserMessage(userInput));
            return handleSwitchNew(sessionId, userInput, userInput);

        } catch (Exception e) {
            log.error("[BillService] Error handling message", e);
            return WorkflowOutput.error("处理账单请求时出错: " + e.getMessage());
        }
    }

    private WorkflowOutput handleSwitchNew(String sessionId, String userInput, String rewrittenInput) {
        // 挂起当前活跃线程(无论属于哪个领域 — 其他领域的线程切换过来时需要挂起原线程)
        AgentStateManager.ActiveThreadInfo currentActive = stateManager.getActiveThread(sessionId);
        if (currentActive != null) {
            stateManager.suspendAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
            log.info("[BillService] Suspended current activeThread: intent={}", currentActive.getIntent());
        }

        CompiledGraph graph = intentRegistry.getGraph(INTENT);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + INTENT);
        }

        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, newThreadId, INTENT);

        return graphExecutionService.executeGraph(graph, INTENT, newThreadId, rewrittenInput, sessionId, null);
    }

    // ==================== ChatMemory管理 ====================

    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        ChatHistoryUtils.recordReply(billChatMemory, sessionId, output, "BillService");
    }
}
