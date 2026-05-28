package com.mobileagent.app.execution;

import com.alibaba.cloud.ai.graph.*;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.data.WorkflowStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Graph执行服务 - 封装官方模式二: interruptBefore + updateState + resume
 *
 * 核心设计:
 * - 每次新执行(executeGraph)由调用方传入独立threadId，不再用sessionId作为threadId
 * - 这样同一会话下不同次执行有独立Checkpoint，避免历史脏数据自动合并
 * - executeGraph: 首次执行，stream(input, config)，input含全局OverAllState数据
 * - resumeGraph: updateState(globalStateData) + stream(null, updatedConfig)，globalStateData含全局OverAllState数据
 *
 * 关键注意事项:
 * - getState() 必须用原始config（只有threadId），不能用updateState返回的updatedConfig
 * - updateState 第二参数（nodeId）传 null（interruptBefore模式下）
 * - stream 第二参数传 null 表示使用checkpoint中的state
 */
@Slf4j
@Service
public class GraphExecutionEngine {

    /** 生成包含 threadId 的 config */
    private RunnableConfig threadConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /**
     * 首次执行Graph — 新意图或跨域切换后的首次执行
     *
     * @param graph 目标CompiledGraph
     * @param intent 意图名称
     * @param input 输入数据Map（由调用方构建，含messages、跨域数据等）
     * @param threadId 线程ID（每次新执行唯一，不再用sessionId）
     */
    public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                       Map<String, Object> input, String threadId) {
        try {
            RunnableConfig config = threadConfig(threadId);

            log.info("[GraphExec] Executing graph: intent={}, threadId={}, inputKeys={}", intent, threadId, input.keySet());

            graph.stream(input, config).blockLast();
            return checkGraphResult(graph, config, intent);

        } catch (Exception e) {
            log.error("[GraphExec] Graph execution failed", e);
            return WorkflowOutput.error("执行出错: " + e.getMessage());
        }
    }

    /**
     * 恢复执行Graph — 官方模式二: updateState + stream(null, updatedConfig)
     *
     * 流程:
     * 1. config = threadConfig(threadId) — 用存储的threadId读取该Graph的checkpoint
     * 2. updateState(config, {_globalStateData: globalStateData, _latestUserInput: userInput}, null)
     * 3. stream(null, updatedConfig) — 从中断点恢复执行
     * 4. checkGraphResult(graph, config, intent) — 用原始config读最新checkpoint
     *
     * @param graph 目标CompiledGraph
     * @param intent 意图名称
     * @param userInput 用户输入
     * @param threadId 线程ID（从ActiveAgentInfo/SuspendedInfo中取出）
     * @param globalStateData 全局OverAllState数据（用 _globalStateData 一个key包住注入checkpoint）
     */
    public WorkflowOutput resumeGraph(CompiledGraph graph, String intent,
                                      String userInput, String threadId,
                                      Map<String, Object> globalStateData) {
        try {
            RunnableConfig config = threadConfig(threadId);

            log.info("[GraphExec] Resume graph: intent={}, threadId={}, userInput={}", intent, threadId, userInput);

            // Step 1: updateState 注入全局数据(整体) + 用户输入
            Map<String, Object> updateData = new HashMap<>();
            if (globalStateData != null && !globalStateData.isEmpty()) {
                updateData.put("_globalStateData", globalStateData);
            }
            updateData.put("_latestUserInput", userInput);

            RunnableConfig updatedConfig = graph.updateState(config, updateData, null);

            // Step 2: stream(null, updatedConfig) 从中断点恢复
            graph.stream(null, updatedConfig).blockLast();

            // Step 3: 用原始 config 读最新 checkpoint（不是 updatedConfig！）
            return checkGraphResult(graph, config, intent);

        } catch (Exception e) {
            log.error("[GraphExec] Resume graph failed", e);
            return WorkflowOutput.error("恢复执行出错: " + e.getMessage());
        }
    }

    /**
     * 检查Graph执行结果 — 区分正常完成和中断
     *
     * 中断来源:
     * 1. interruptBefore机制: next()非空且非__END__
     * 2. ask→END条件路由: graph结束但_question非空（兜底，不应触发）
     *
     * 注意: 不再提取accumulatedParams，checkpoint自动保留完整OverAllState
     */
    private WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config,
                                             String intent) {
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

            // 判断1: interruptBefore中断 (next()非空且非END)
            if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
                log.info("[GraphExec] Interrupted by interruptBefore: nextNode={}, question={}", nextNode, question);
                return WorkflowOutput.interrupted(intent, question);
            }

            // 判断2: ask→END中断 (graph结束但有提问 - 兜底)
            if (question != null && !question.isEmpty()) {
                log.info("[GraphExec] Interrupted by ask→END: question={}", question);
                return WorkflowOutput.interrupted(intent, question);
            }

            // 正常完成
            String content = currentState != null
                    ? (String) currentState.value("_outputContent").orElse("操作已完成")
                    : "操作已完成";

            log.info("[GraphExec] Graph completed: content={}", content);
            return WorkflowOutput.completed(intent, content);

        } catch (Exception e) {
            log.error("[GraphExec] Failed to check graph result", e);
            return WorkflowOutput.completed(intent, "操作完成(状态检查失败)");
        }
    }
}
