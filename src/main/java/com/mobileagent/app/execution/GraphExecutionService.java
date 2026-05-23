package com.mobileagent.app.execution;

import com.alibaba.cloud.ai.graph.*;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.manager.AgentStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Graph执行服务 - 封装Graph的执行、中断检测、参数提取逻辑
 *
 * 核心设计:
 * - 不依赖interruptBefore的resume机制(框架Bug#4519)
 * - 每次resume都重新执行graph,注入accumulatedParams让paramRouter跳过已收集的参数
 *
 * 方法职责:
 * - executeGraph: 执行graph + 检查结果(完成/中断)
 * - resumeGraph: 重新执行graph(注入累积参数)
 * - cancelGraph: 注入取消信号让子Graph自行清理
 * - prepareReExecution: 准备重新执行的上下文(提取累积参数+生成新threadId+恢复参数)
 * - extractAccumulatedParams: 从graph state提取已收集的参数
 */
@Slf4j
@Service
public class GraphExecutionService {

    private final IntentRegistry intentRegistry;
    private final AgentStateManager stateManager;

    public GraphExecutionService(IntentRegistry intentRegistry, AgentStateManager stateManager) {
        this.intentRegistry = intentRegistry;
        this.stateManager = stateManager;
    }

    /**
     * 执行Graph - 始终创建新的执行,注入累积参数+用户输入
     *
     * @param graph 目标Graph
     * @param intent 意图名称
     * @param threadId 线程ID
     * @param userInput 用户输入
     * @param sessionId 会话ID
     * @param accumulatedParams 已收集的参数(可能为null)
     * @return 执行结果 (COMPLETED / INTERRUPTED / ERROR)
     */
    public WorkflowOutput executeGraph(CompiledGraph graph, String intent, String threadId,
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
                log.info("[GraphExec] Injecting accumulated params: {}", accumulatedParams);
            }

            log.info("[GraphExec] Executing graph: intent={}, threadId={}, input={}", intent, threadId, userInput);

            graph.stream(input, config).blockLast();
            return checkGraphResult(graph, config, intent, threadId, sessionId);

        } catch (Exception e) {
            log.error("[GraphExec] Graph execution failed", e);
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
    public WorkflowOutput resumeGraph(String intent, String threadId, String userInput, String sessionId) {
        Map<String, Object> accumulatedParams = getAccumulatedParamsFromActive(sessionId, intent);
        return resumeGraph(intent, threadId, userInput, sessionId, accumulatedParams);
    }

    /**
     * 恢复执行Graph - 使用指定的累积参数(从suspendedInfo传入)
     */
    public WorkflowOutput resumeGraph(String intent, String threadId, String userInput,
                                      String sessionId, Map<String, Object> accumulatedParams) {
        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        // 重新执行准备: 生成新threadId + 恢复累积参数
        String newThreadId = prepareReExecution(sessionId, intent, accumulatedParams);

        log.info("[GraphExec] Resume graph (new execution): intent={}, oldThread={}, newThread={}, params={}, userInput={}",
                intent, threadId, newThreadId, accumulatedParams, userInput);

        return executeGraph(graph, intent, newThreadId, userInput, sessionId, accumulatedParams);
    }

    /**
     * 检查Graph执行结果 - 区分正常完成和中断
     *
     * 中断来源:
     * 1. interruptBefore机制: next()非空且非__END__ → 首次/重新执行时触发
     * 2. ask→END条件路由: graph结束但_question非空 → 兜底(不应触发)
     */
    public WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config,
                                            String intent, String threadId, String sessionId) {
        try {
            var snapshot = graph.getState(config);

            if (snapshot == null) {
                log.warn("[GraphExec] getState returned null");
                stateManager.completeAgent(sessionId, intent);
                return WorkflowOutput.completed(intent, "操作完成(无状态)");
            }

            String nextNode = snapshot.next();
            OverAllState currentState = snapshot.state();
            String question = currentState != null
                    ? (String) currentState.value("_question").orElse("")
                    : "";

            log.info("[GraphExec] Graph result: nextNode={}, question={}", nextNode, question);

            // 保存已收集的参数到activeThread
            AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
            if (active != null && currentState != null) {
                Map<String, Object> params = extractAccumulatedParams(intent, currentState);
                active.setAccumulatedParams(params);
                log.info("[GraphExec] Saved accumulated params: {}", params);
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
                log.info("[GraphExec] Interrupted by interruptBefore: nextNode={}, question={}", nextNode, question);
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
                log.info("[GraphExec] Interrupted by ask→END: question={}", question);
                return WorkflowOutput.interrupted(intent, threadId, question);
            }

            // 正常完成
            String content = currentState != null
                    ? (String) currentState.value("_outputContent").orElse("操作已完成")
                    : "操作已完成";

            stateManager.completeAgent(sessionId, intent);
            log.info("[GraphExec] Graph completed: content={}", content);
            return WorkflowOutput.completed(intent, content);

        } catch (Exception e) {
            log.error("[GraphExec] Failed to check graph result", e);
            stateManager.completeAgent(sessionId, intent);
            return WorkflowOutput.completed(intent, "操作完成(状态检查失败)");
        }
    }

    /**
     * 读取Graph中断状态(不执行) - 用于RESUME时恢复上下文
     */
    public WorkflowOutput readGraphInterruptState(String intent, String threadId, String sessionId) {
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
                    log.info("[GraphExec] RESUME restored context: intent={}, nextNode={}, question={}", intent, nextNode, question);
                    return WorkflowOutput.interrupted(intent, threadId, question);
                }
            }

            // graph已完成
            stateManager.completeAgent(sessionId, intent);
            return WorkflowOutput.completed(intent, "该任务已完成");

        } catch (Exception e) {
            log.error("[GraphExec] Failed to read graph state for RESUME", e);
            return WorkflowOutput.error("恢复上下文出错: " + e.getMessage());
        }
    }

    /** 从graph state中提取已收集的参数 */
    public Map<String, Object> extractAccumulatedParams(String intent, OverAllState state) {
        Map<String, Object> params = new HashMap<>();
        String prefix = switch (intent) {
            case "TRANSFER" -> "transfer.";
            case "BILL_QUERY" -> "bill.";
            case "WEALTH_CONSULT" -> "wealthConsult.";
            case "WEALTH_INTERPRET" -> "wealthInterpret.";
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

    /**
     * 取消Graph执行 - 注入_cancelSignal让子Graph自行清理并终止
     *
     * 流程: 重新执行Graph(注入_cancelSignal=true + 已收集参数)
     *       → extractParams跳过LLM → paramRouter路由到cancelExecution → END
     *
     * @param intent 意图名称
     * @param threadId 当前活跃线程ID(用于提取累积参数)
     * @param sessionId 会话ID
     * @return 取消结果
     */
    public WorkflowOutput cancelGraph(String intent, String threadId, String sessionId) {
        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            log.warn("[GraphExec.cancelGraph] Graph not found for intent={}", intent);
            stateManager.completeAgent(sessionId, intent);
            stateManager.clearActiveThread(sessionId);
            return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
        }

        try {
            // 先保存累积参数(在setActiveThread覆盖前)
            Map<String, Object> accumulatedParams = getAccumulatedParamsFromActive(sessionId, intent);
            // 重新执行准备: 生成新threadId + 恢复累积参数
            String newThreadId = prepareReExecution(sessionId, intent, accumulatedParams);

            // 构建输入: 注入取消信号 + 累积参数
            Map<String, Object> input = new HashMap<>();
            input.put("messages", "取消");
            input.put("_latestUserInput", "取消");
            input.put("_question", null);
            input.put("_cancelSignal", true);
            if (!accumulatedParams.isEmpty()) {
                input.putAll(accumulatedParams);
            }

            log.info("[GraphExec.cancelGraph] Cancel graph: intent={}, oldThread={}, newThread={}, params={}",
                    intent, threadId, newThreadId, accumulatedParams);

            RunnableConfig config = RunnableConfig.builder().threadId(newThreadId).build();
            graph.stream(input, config).blockLast();

            // 清理状态
            stateManager.completeAgent(sessionId, intent);
            stateManager.clearActiveThread(sessionId);

            log.info("[GraphExec.cancelGraph] Graph cancelled successfully: intent={}", intent);
            return WorkflowOutput.completed(intent, "好的,已取消当前操作。还有什么可以帮您的吗？");

        } catch (Exception e) {
            log.error("[GraphExec.cancelGraph] Cancel graph failed", e);
            // 即使失败也要清理状态
            stateManager.completeAgent(sessionId, intent);
            stateManager.clearActiveThread(sessionId);
            return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
        }
    }

    // ==================== 公共辅助方法 ====================

    /**
     * 准备重新执行 - 生成新threadId + 恢复累积参数到新activeThread
     *
     * resumeGraph和cancelGraph共享的逻辑:
     * 1. 生成新的threadId(因为每次都重新执行graph)
     * 2. 设置新activeThread
     * 3. 恢复累积参数到新activeThread
     *
     * 注意: 调用此方法前必须先从旧activeThread提取accumulatedParams,
     *       因为setActiveThread会覆盖旧的activeThread引用
     *
     * @param sessionId 会话ID
     * @param intent 意图名称
     * @param accumulatedParams 要恢复的累积参数
     * @return 新的threadId
     */
    private String prepareReExecution(String sessionId, String intent, Map<String, Object> accumulatedParams) {
        String newThreadId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        stateManager.setActiveThread(sessionId, newThreadId, intent);
        // 恢复累积参数到新的activeThread
        AgentStateManager.ActiveThreadInfo newActive = stateManager.getActiveThread(sessionId);
        if (newActive != null && accumulatedParams != null && !accumulatedParams.isEmpty()) {
            newActive.setAccumulatedParams(accumulatedParams);
        }
        return newThreadId;
    }

    /**
     * 从当前activeThread获取累积参数
     */
    private Map<String, Object> getAccumulatedParamsFromActive(String sessionId, String intent) {
        Map<String, Object> params = new HashMap<>();
        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        if (active != null && active.getIntent().equals(intent)) {
            params.putAll(active.getAccumulatedParams());
        }
        return params;
    }
}
