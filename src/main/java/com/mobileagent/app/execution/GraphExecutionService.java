package com.mobileagent.app.execution;

import com.alibaba.cloud.ai.graph.*;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.data.WorkflowStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Graph执行服务 - 只负责执行Graph和提取结果，不管理状态
 *
 * 核心变化:
 * - 不再依赖AgentStateManager
 * - accumulatedParams通过WorkflowOutput返回，由L1 Service保存到自己的activeThread
 * - L1 Service负责所有状态管理(activeThread/suspendedAgent/completeAgent)
 *
 * 方法职责:
 * - executeGraph: 执行graph + 提取结果(accumulatedParams包含在返回值中)
 * - resumeGraph: 用accumulatedParams重新执行graph(由L1传入accumulatedParams)
 * - cancelGraph: 注入_cancelSignal让子Graph自行清理并终止(由L1传入accumulatedParams)
 * - extractAccumulatedParams: 从graph state提取已收集的参数(供L1使用)
 */
@Slf4j
@Service
public class GraphExecutionService {

    private final IntentRegistry intentRegistry;

    public GraphExecutionService(IntentRegistry intentRegistry) {
        this.intentRegistry = intentRegistry;
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
     * @return 执行结果 (COMPLETED / INTERRUPTED / ERROR)，accumulatedParams包含在返回值中
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
            return WorkflowOutput.error("执行出错: " + e.getMessage());
        }
    }

    /**
     * 恢复执行Graph - 注入累积参数+用户输入,重新执行
     *
     * 不使用SAA的resume()机制,而是每次都重新执行graph。
     * 原因: interruptBefore在resume后不会重新触发,导致多轮提问失败。
     *
     * @param intent 意图名称
     * @param newThreadId 新的线程ID(由L1 Service生成)
     * @param userInput 用户输入
     * @param sessionId 会话ID
     * @param accumulatedParams 已收集的参数(由L1 Service从自己的activeThread获取)
     * @return 执行结果
     */
    public WorkflowOutput resumeGraph(String intent, String newThreadId, String userInput,
                                      String sessionId, Map<String, Object> accumulatedParams) {
        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        log.info("[GraphExec] Resume graph (new execution): intent={}, newThread={}, params={}, userInput={}",
                intent, newThreadId, accumulatedParams, userInput);

        return executeGraph(graph, intent, newThreadId, userInput, sessionId, accumulatedParams);
    }

    /**
     * 检查Graph执行结果 - 区分正常完成和中断，提取accumulatedParams放入返回值
     *
     * 中断来源:
     * 1. interruptBefore机制: next()非空且非__END__ → 首次/重新执行时触发
     * 2. ask→END条件路由: graph结束但_question非空 → 兜底(不应触发)
     *
     * 注意: 此方法不再管理activeThread/suspendedAgent，状态由L1 Service处理
     */
    public WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config,
                                            String intent, String threadId, String sessionId) {
        try {
            var snapshot = graph.getState(config);

            if (snapshot == null) {
                log.warn("[GraphExec] getState returned null");
                return WorkflowOutput.completed(intent, "操作完成(无状态)");
            }

            String nextNode = snapshot.next();
            OverAllState currentState = snapshot.state();
            String question = currentState != null
                    ? (String) currentState.value("_question").orElse("")
                    : "";

            log.info("[GraphExec] Graph result: nextNode={}, question={}", nextNode, question);

            // 提取accumulatedParams放入返回值(由L1 Service保存到自己的activeThread)
            Map<String, Object> params = currentState != null
                    ? extractAccumulatedParams(intent, currentState)
                    : Map.of();

            // 判断1: interruptBefore中断 (next()非空且非END)
            if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
                log.info("[GraphExec] Interrupted by interruptBefore: nextNode={}, question={}", nextNode, question);
                return WorkflowOutput.builder()
                        .status(WorkflowStatus.INTERRUPTED)
                        .intent(intent)
                        .threadId(threadId)
                        .question(question)
                        .accumulatedParams(params)
                        .build();
            }

            // 判断2: ask→END中断 (graph结束但有提问 - 兜底)
            if (question != null && !question.isEmpty()) {
                log.info("[GraphExec] Interrupted by ask→END: question={}", question);
                return WorkflowOutput.builder()
                        .status(WorkflowStatus.INTERRUPTED)
                        .intent(intent)
                        .threadId(threadId)
                        .question(question)
                        .accumulatedParams(params)
                        .build();
            }

            // 正常完成
            String content = currentState != null
                    ? (String) currentState.value("_outputContent").orElse("操作已完成")
                    : "操作已完成";

            log.info("[GraphExec] Graph completed: content={}", content);
            return WorkflowOutput.builder()
                    .status(WorkflowStatus.COMPLETED)
                    .intent(intent)
                    .content(content)
                    .accumulatedParams(params)
                    .build();

        } catch (Exception e) {
            log.error("[GraphExec] Failed to check graph result", e);
            return WorkflowOutput.completed(intent, "操作完成(状态检查失败)");
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
     * @param intent 意图名称
     * @param newThreadId 新线程ID(由L1 Service生成)
     * @param sessionId 会话ID
     * @param accumulatedParams 已收集的参数(由L1 Service传入)
     * @return 取消结果
     */
    public WorkflowOutput cancelGraph(String intent, String newThreadId, String sessionId,
                                      Map<String, Object> accumulatedParams) {
        CompiledGraph graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            log.warn("[GraphExec.cancelGraph] Graph not found for intent={}", intent);
            return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
        }

        try {
            // 构建输入: 注入取消信号 + 累积参数
            Map<String, Object> input = new HashMap<>();
            input.put("messages", "取消");
            input.put("_latestUserInput", "取消");
            input.put("_question", null);
            input.put("_cancelSignal", true);
            if (accumulatedParams != null && !accumulatedParams.isEmpty()) {
                input.putAll(accumulatedParams);
            }

            log.info("[GraphExec.cancelGraph] Cancel graph: intent={}, newThread={}, params={}",
                    intent, newThreadId, accumulatedParams);

            RunnableConfig config = RunnableConfig.builder().threadId(newThreadId).build();
            graph.stream(input, config).blockLast();

            log.info("[GraphExec.cancelGraph] Graph cancelled successfully: intent={}", intent);
            return WorkflowOutput.completed(intent, "好的,已取消当前操作。还有什么可以帮您的吗？");

        } catch (Exception e) {
            log.error("[GraphExec.cancelGraph] Cancel graph failed", e);
            return WorkflowOutput.completed(null, "好的,已取消当前操作。还有什么可以帮您的吗？");
        }
    }
}
