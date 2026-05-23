package com.mobileagent.app.domain;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.rewriter.ContextRewriter;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.execution.GraphExecutionService;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转账L1 Service - 极简路由: 只有FOLLOW_UP和SWITCH_NEW
 *
 * 设计(自管状态版):
 * - 自管 transferActiveThreads: 本领域独立的activeThread，不与其它领域共享
 * - 无suspendedAgents: 1-1关系，只有一个子智能体，无需挂起/恢复
 * - FOLLOW_UP + 自有activeThread → resumeGraph(注入accumulatedParams) ← 核心bug修复点
 * - FOLLOW_UP + 无activeThread → 上下文改写后降级为SWITCH_NEW
 * - SWITCH_NEW → 新建thread + TransferGraph
 * - 完成后clearOwnActiveThread
 * - 独立ChatMemory实例(只记录本领域消息)
 * - 取消由子workflow处理(cancelAwareExtractParams → cancelExecutionNode)
 *
 * 关键bug修复:
 * 之前用全局activeThread，用户从转账切到账单再切回时，转账的accumulatedParams丢失。
 * 现在转账自管activeThread，切走再回来时上下文还在。
 */
@Slf4j
@Service
public class TransferService {

    private static final String INTENT = "TRANSFER";
    private static final String DOMAIN_NAME = "转账";

    private final ContextRouter contextRouter;
    private final ContextRewriter contextRewriter;
    private final GraphExecutionService graphExecutionService;
    private final IntentRegistry intentRegistry;
    private final ChatMemory transferChatMemory;

    // ==================== 自管状态 ====================

    /** 转账领域自有的activeThread (per session) */
    private final Map<String, ActiveThreadInfo> transferActiveThreads = new ConcurrentHashMap<>();

    @Data
    public static class ActiveThreadInfo {
        private final String threadId;
        private final String intent;
        private final Instant createdAt;
        /** 累积的参数 (如transfer.receiver, transfer.amount等) */
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

    // ==================== 自管状态方法 ====================

    private ActiveThreadInfo getOwnActiveThread(String sessionId) {
        return transferActiveThreads.get(sessionId);
    }

    private void setOwnActiveThread(String sessionId, String threadId, String intent) {
        if (threadId == null || intent == null) {
            transferActiveThreads.remove(sessionId);
        } else {
            transferActiveThreads.put(sessionId, new ActiveThreadInfo(threadId, intent, Instant.now()));
        }
    }

    private void clearOwnActiveThread(String sessionId) {
        transferActiveThreads.remove(sessionId);
    }

    /** 清除会话所有状态(供BankController.clearSession调用) */
    public void clearSession(String sessionId) {
        transferActiveThreads.remove(sessionId);
    }

    /** 获取会话状态描述(供BankController.getState调用) */
    public String getSessionStateDescription(String sessionId) {
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        if (active != null) {
            return "当前活跃意图: " + active.getIntent() +
                    " (线程: " + active.getThreadId().substring(0, 8) + "...)" +
                    " 参数: " + active.getAccumulatedParams();
        }
        return "当前无活跃意图";
    }

    // ==================== 构造 ====================

    public TransferService(ContextRouter contextRouter,
                           ContextRewriter contextRewriter,
                           GraphExecutionService graphExecutionService,
                           IntentRegistry intentRegistry,
                           @org.springframework.beans.factory.annotation.Qualifier("transferChatMemory") ChatMemory transferChatMemory) {
        this.contextRouter = contextRouter;
        this.contextRewriter = contextRewriter;
        this.graphExecutionService = graphExecutionService;
        this.intentRegistry = intentRegistry;
        this.transferChatMemory = transferChatMemory;
    }

    // ==================== 主入口 ====================

    /**
     * 处理转账领域的消息
     *
     * 流程:
     * 1. Phase1: ContextRouter判断FOLLOW_UP/SWITCH_NEW
     * 2. FOLLOW_UP + 自有activeThread → resumeGraph(注入accumulatedParams) ← bug修复核心
     * 3. FOLLOW_UP + 无activeThread → 上下文改写后降级为SWITCH_NEW
     * 4. SWITCH_NEW → 新建thread + executeGraph
     * 5. 取消由子workflow处理(cancelAwareExtractParams检测取消意图)
     */
    public WorkflowOutput handle(String sessionId, String userInput) {
        log.info("[TransferService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            // 获取本领域当前状态(用于ContextRouter的prompt)
            ActiveThreadInfo ownActive = getOwnActiveThread(sessionId);
            String currentAgent = ownActive != null ? ownActive.getIntent() : "无";
            String pendingAgents = "无"; // 1-1无suspendedAgents

            // Phase1: ContextRouter判断FOLLOW_UP/SWITCH_NEW (简化模式)
            RoutingResult phase1 = contextRouter.route(sessionId, userInput,
                    currentAgent, pendingAgents,
                    "prompts/l1-routing-simple.st", DOMAIN_NAME, transferChatMemory);
            log.info("[TransferService] Phase1: routeType={}, confidence={}", phase1.getRouteType(), phase1.getConfidence());

            // ========== FOLLOW_UP分支 ==========
            if (phase1.isFollowUp()) {
                ActiveThreadInfo active = getOwnActiveThread(sessionId);

                // ========== FOLLOW_UP + 自有activeThread → resumeGraph (核心bug修复!) ==========
                if (active != null) {
                    log.info("[TransferService] FOLLOW_UP with own activeThread: intent={}, params={}",
                            active.getIntent(), active.getAccumulatedParams());
                    transferChatMemory.add(sessionId, new UserMessage(userInput));

                    // 生成新threadId，恢复累积参数
                    String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    setOwnActiveThread(sessionId, newThreadId, INTENT);

                    WorkflowOutput resumeResult = graphExecutionService.resumeGraph(
                            active.getIntent(), newThreadId, userInput, sessionId,
                            active.getAccumulatedParams());
                    // 保存accumulatedParams到自有activeThread
                    saveAccumulatedParams(sessionId, resumeResult);
                    recordSystemReply(sessionId, resumeResult);
                    return resumeResult;
                }

                // FOLLOW_UP但无activeThread → 上下文改写后降级为SWITCH_NEW
                log.info("[TransferService] FOLLOW_UP but no own activeThread → rewrite and fallback to SWITCH_NEW");
                String rewrittenInput = contextRewriter.rewrite(sessionId, userInput, transferChatMemory, DOMAIN_NAME);
                transferChatMemory.add(sessionId, new UserMessage(userInput));
                return handleSwitchNew(sessionId, userInput, rewrittenInput);
            }

            // ========== SWITCH_NEW (非FOLLOW_UP) ==========
            // 记录用户消息到领域ChatMemory
            transferChatMemory.add(sessionId, new UserMessage(userInput));
            return handleSwitchNew(sessionId, userInput, userInput);

        } catch (Exception e) {
            log.error("[TransferService] Error handling message", e);
            return WorkflowOutput.error("处理转账请求时出错: " + e.getMessage());
        }
    }

    // ==================== SWITCH_NEW执行 ====================

    private WorkflowOutput handleSwitchNew(String sessionId, String userInput, String rewrittenInput) {
        // 1-1无需suspend: 没有suspendedAgents，直接覆盖自有activeThread

        CompiledGraph graph = intentRegistry.getGraph(INTENT);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + INTENT);
        }

        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        setOwnActiveThread(sessionId, newThreadId, INTENT);

        WorkflowOutput result = graphExecutionService.executeGraph(graph, INTENT, newThreadId, rewrittenInput, sessionId, null);
        // 保存accumulatedParams到自有activeThread
        saveAccumulatedParams(sessionId, result);

        // 如果完成，清空activeThread
        if ("COMPLETED".equals(result.getStatus())) {
            clearOwnActiveThread(sessionId);
        }

        return result;
    }

    // ==================== accumulatedParams保存 ====================

    /**
     * 从WorkflowOutput中保存accumulatedParams到自有activeThread
     *
     * 这是状态自管的关键: GES返回accumulatedParams，L1 Service负责保存
     */
    private void saveAccumulatedParams(String sessionId, WorkflowOutput output) {
        if (output == null || output.getAccumulatedParams() == null) return;
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        if (active != null && !output.getAccumulatedParams().isEmpty()) {
            active.setAccumulatedParams(output.getAccumulatedParams());
            log.info("[TransferService] Saved accumulated params: {}", output.getAccumulatedParams());
        }
    }

    // ==================== 取消 ====================

    /**
     * 取消当前转账操作
     */
    public WorkflowOutput cancelTransfer(String sessionId) {
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        if (active == null) {
            return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
        }

        Map<String, Object> params = active.getAccumulatedParams();
        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        WorkflowOutput result = graphExecutionService.cancelGraph(INTENT, newThreadId, sessionId, params);
        clearOwnActiveThread(sessionId);
        return result;
    }

    // ==================== ChatMemory管理 ====================

    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        ChatHistoryUtils.recordReply(transferChatMemory, sessionId, output, "TransferService");
    }
}
