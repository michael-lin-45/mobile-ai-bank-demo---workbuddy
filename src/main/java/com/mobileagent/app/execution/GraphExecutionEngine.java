package com.mobileagent.app.execution;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.observability.MetricsRegistry;
import com.mobileagent.app.observability.ObservabilityMetrics;
import com.mobileagent.app.router.registry.SubGraphRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Graph执行服务 - 封装官方模式二: interruptBefore + updateState + resume
 *
 * 核心设计:
 * - 统一返回 Flux<StreamChunk>，流式/非流式差异对上层透明
 * - 非流式Graph走已验证的 stream().blockLast() 路径，不冒险迁移
 * - 流式Graph走 graphResponseStream() 路径（Phase 2启用）
 * - GES是唯一读取 SubGraphRegistry.isStreamable() 的层
 *
 * 关键注意事项:
 * - getState() 必须用原始config（只有threadId），不能用updateState返回的updatedConfig
 * - updateState 第二参数（nodeId）传 null（interruptBefore模式下）
 * - stream 第二参数传 null 表示使用checkpoint中的state
 */
@Slf4j
@Service
public class GraphExecutionEngine {

    private final SubGraphRegistry subGraphRegistry;
    private final ObservabilityMetrics obsMetrics;
    private final MetricsRegistry metricsRegistry;

    public GraphExecutionEngine(SubGraphRegistry subGraphRegistry,
                                ObservabilityMetrics obsMetrics,
                                MetricsRegistry metricsRegistry) {
        this.subGraphRegistry = subGraphRegistry;
        this.obsMetrics = obsMetrics;
        this.metricsRegistry = metricsRegistry;
    }

    /** 生成包含 threadId 的 config */
    private RunnableConfig threadConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /**
     * 首次执行Graph — 统一返回 Flux<StreamChunk>
     *
     * GES闭环: 根据SubGraphRegistry.isStreamable()分流
     * - false → executeBlocking (已验证路径)
     * - true  → executeStreaming (Phase 2启用)
     */
    public Flux<StreamChunk> executeGraph(CompiledGraph graph, String intent,
                                           Map<String, Object> input, String threadId) {
        boolean streamable = subGraphRegistry.isStreamable(intent);
        if (!streamable) {
            return executeBlocking(graph, intent, input, threadId);
        }
        return executeStreaming(graph, intent, input, threadId);
    }

    /**
     * 恢复执行Graph — 统一返回 Flux<StreamChunk>
     */
    public Flux<StreamChunk> resumeGraph(CompiledGraph graph, String intent,
                                          String userInput, String threadId,
                                          Map<String, Object> globalStateData) {
        boolean streamable = subGraphRegistry.isStreamable(intent);
        if (!streamable) {
            return resumeBlocking(graph, intent, userInput, threadId, globalStateData);
        }
        return resumeStreaming(graph, intent, userInput, threadId, globalStateData);
    }

    /**
     * 取消 L2 子图 — 注入 _cancelSignal=true 后走 resume 流程
     *
     * <p>GES只做两件事：1) 设cancel信号 2) resume。用户原始输入照常传递。
     * L2子图（LLM模式）检测 _cancelSignal 走 cancelExecution；
     * L2子图（HTTP代理模式）将用户输入（含cancel语义）原样传给后端，由后端处理取消。
     *
     * @param graph     L2 子图
     * @param intent    子图意图名（用于日志和 isStreamable 判断）
     * @param threadId  L2 子图 threadId
     * @param userInput 用户原始输入
     * @return Flux<StreamChunk> 取消结果
     */
    public Flux<StreamChunk> cancelGraph(CompiledGraph graph, String intent, String threadId, String userInput) {
        // 先注入 _cancelSignal，再走 resume 流程
        try {
            RunnableConfig config = threadConfig(threadId);
            Map<String, Object> signalData = new HashMap<>();
            signalData.put("_cancelSignal", true);
            graph.updateState(config, signalData, null);
            log.info("[GraphExec] Cancel: injected _cancelSignal, intent={}, threadId={}", intent, threadId);
        } catch (Exception e) {
            log.error("[GraphExec] Cancel: failed to inject _cancelSignal, intent={}, threadId={}", intent, threadId, e);
        }
        // cancel信号已注入，走标准resume流程（userInput照常传递）
        return resumeGraph(graph, intent, userInput, threadId, null);
    }

    // ==================== 非流式路径（与原逻辑完全一致） ====================

    private Flux<StreamChunk> executeBlocking(CompiledGraph graph, String intent,
                                               Map<String, Object> input, String threadId) {
        return Flux.defer(() -> {
            try {
                RunnableConfig config = threadConfig(threadId);
                input.put("_threadId", threadId);
                log.info("[GraphExec] Blocking execute: intent={}, threadId={}", intent, threadId);

                long startMs = System.currentTimeMillis();
                var timerSample = obsMetrics.startWorkflowTimer();
                graph.stream(input, config).blockLast();
                obsMetrics.stopWorkflowTimer(timerSample);
                long durationMs = System.currentTimeMillis() - startMs;
                // 埋点：子图执行耗时（带 intent/graph tag）
                obsMetrics.recordWorkflowDuration(intent, intent, durationMs);

                WorkflowOutput output = checkGraphResult(graph, config, intent);
                if (output.getStatus() == com.mobileagent.app.data.WorkflowStatus.INTERRUPTED) {
                    obsMetrics.recordWorkflowInterrupt();
                    obsMetrics.recordWorkflowInterrupt(intent, "interruptBefore");
                    // T-J P7：人工中断 → 待审批 +1（deepflux.workflow.pending_approval）
                    metricsRegistry.addUpDown("workflow.pending_approval", +1L, "intent", intent);
                }
                if (output.getStatus() == com.mobileagent.app.data.WorkflowStatus.COMPLETED) {
                    obsMetrics.recordBusinessSuccess();
                } else {
                    obsMetrics.recordBusinessFail();
                }
                return Flux.just(StreamChunk.fromWorkflowOutput(output));
            } catch (Exception e) {
                log.error("[GraphExec] Blocking execute failed", e);
                obsMetrics.recordBusinessFail();
                return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
            }
        });
    }

    private Flux<StreamChunk> resumeBlocking(CompiledGraph graph, String intent,
                                              String userInput, String threadId,
                                              Map<String, Object> globalStateData) {
        return Flux.defer(() -> {
            try {
                RunnableConfig config = threadConfig(threadId);
                log.info("[GraphExec] Blocking resume: intent={}, threadId={}", intent, threadId);

                Map<String, Object> updateData = new HashMap<>();
                updateData.put("_threadId", threadId);
                if (globalStateData != null && !globalStateData.isEmpty()) {
                    updateData.put("_globalStateData", globalStateData);
                }
                updateData.put("_latestUserInput", userInput);

                long startMs = System.currentTimeMillis();
                var timerSample = obsMetrics.startWorkflowTimer();
                RunnableConfig updatedConfig = graph.updateState(config, updateData, null);
                graph.stream(null, updatedConfig).blockLast();
                obsMetrics.stopWorkflowTimer(timerSample);
                long durationMs = System.currentTimeMillis() - startMs;
                // 埋点：子图执行耗时（resume 路径）
                obsMetrics.recordWorkflowDuration(intent, intent, durationMs);

                WorkflowOutput output = checkGraphResult(graph, config, intent);
                if (output.getStatus() == com.mobileagent.app.data.WorkflowStatus.INTERRUPTED) {
                    obsMetrics.recordWorkflowInterrupt();
                    obsMetrics.recordWorkflowInterrupt(intent, "interruptBefore");
                    // T-J P7：resume 仍中断（再次挂起）→ 待审批 +1
                    metricsRegistry.addUpDown("workflow.pending_approval", +1L, "intent", intent);
                } else if (output.getStatus() == com.mobileagent.app.data.WorkflowStatus.COMPLETED) {
                    // T-J P7：审批完成后图执行完成 → 待审批 -1
                    metricsRegistry.addUpDown("workflow.pending_approval", -1L, "intent", intent);
                }
                return Flux.just(StreamChunk.fromWorkflowOutput(output));
            } catch (Exception e) {
                log.error("[GraphExec] Blocking resume failed", e);
                return Flux.just(StreamChunk.error("恢复执行出错: " + e.getMessage()));
            }
        });
    }

    // ==================== 流式路径（Phase 2启用，Phase 1为骨架） ====================

    private Flux<StreamChunk> executeStreaming(CompiledGraph graph, String intent,
                                                Map<String, Object> input, String threadId) {
        try {
            RunnableConfig config = threadConfig(threadId);
            input.put("_threadId", threadId);
            log.info("[GraphExec] Streaming execute: intent={}, threadId={}", intent, threadId);

            long startMs = System.currentTimeMillis();

            return graph.graphResponseStream(input, config)
                .flatMap(graphResponse -> {
                    StreamChunk chunk = mapStreamingOutput(graphResponse, intent);
                    return chunk != null ? Flux.just(chunk) : Flux.empty();
                })
                .concatWith(Flux.defer(() -> {
                    long durationMs = System.currentTimeMillis() - startMs;
                    obsMetrics.recordWorkflowDuration(intent, intent, durationMs);
                    StreamChunk terminal = buildStreamingTerminalChunk(graph, config, intent);
                    return Flux.just(terminal);
                }))
                .onErrorResume(e -> {
                    log.error("[GraphExec] Streaming execute failed", e);
                    obsMetrics.recordBusinessFail();
                    return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
                });
        } catch (Exception e) {
            log.error("[GraphExec] Streaming execute setup failed", e);
            obsMetrics.recordBusinessFail();
            return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
        }
    }

    private Flux<StreamChunk> resumeStreaming(CompiledGraph graph, String intent,
                                               String userInput, String threadId,
                                               Map<String, Object> globalStateData) {
        try {
                RunnableConfig config = threadConfig(threadId);
                log.info("[GraphExec] Streaming resume: intent={}, threadId={}", intent, threadId);

                Map<String, Object> updateData = new HashMap<>();
                updateData.put("_threadId", threadId);
            if (globalStateData != null && !globalStateData.isEmpty()) {
                updateData.put("_globalStateData", globalStateData);
            }
            updateData.put("_latestUserInput", userInput);
            RunnableConfig updatedConfig = graph.updateState(config, updateData, null);

            long startMs = System.currentTimeMillis();

            return graph.graphResponseStream((Map<String, Object>) null, updatedConfig)
                .flatMap(graphResponse -> {
                    StreamChunk chunk = mapStreamingOutput(graphResponse, intent);
                    return chunk != null ? Flux.just(chunk) : Flux.empty();
                })
                .concatWith(Flux.defer(() -> {
                    long durationMs = System.currentTimeMillis() - startMs;
                    obsMetrics.recordWorkflowDuration(intent, intent, durationMs);
                    StreamChunk terminal = buildStreamingTerminalChunk(graph, config, intent);
                    return Flux.just(terminal);
                }))
                .onErrorResume(e -> {
                    log.error("[GraphExec] Streaming resume failed", e);
                    obsMetrics.recordBusinessFail();
                    return Flux.just(StreamChunk.error("恢复执行出错: " + e.getMessage()));
                });
        } catch (Exception e) {
            log.error("[GraphExec] Streaming resume setup failed", e);
            obsMetrics.recordBusinessFail();
            return Flux.just(StreamChunk.error("恢复执行出错: " + e.getMessage()));
        }
    }

    /**
     * 从GraphResponse提取StreamingOutput → StreamChunk
     *
     * GraphResponse两种形态:
     * - GraphResponse.of(data): output=completedFuture(data), resultValue=null, isDone=false
     *   → 流式chunk走这条路径，必须通过getOutput().join()取值
     * - GraphResponse.done(resultValue): output=null, resultValue有值, isDone=true
     *   → 终结信号走这条路径，通过resultValue()取值
     *
     * 只有 AGENT_MODEL_STREAMING / GRAPH_NODE_STREAMING 产生CHUNK
     * 其他类型(中间节点NodeOutput、FINISHED、TOOL、HOOK)一律过滤
     */
    private StreamChunk mapStreamingOutput(GraphResponse<NodeOutput> graphResponse,
                                           String intent) {
        Object value = extractValue(graphResponse);
        if (value == null) return null;

        if (value instanceof StreamingOutput<?> streaming) {
            OutputType outputType = streaming.getOutputType();
            if (outputType == OutputType.AGENT_MODEL_STREAMING
                    || outputType == OutputType.GRAPH_NODE_STREAMING) {
                String chunk = streaming.chunk();
                if (chunk != null && !chunk.isEmpty()) {
                    return StreamChunk.chunk(intent, chunk);
                }
            }
            // FINISHED / TOOL / HOOK → 过滤
        }
        return null; // 普通NodeOutput（中间节点）→ 过滤
    }

    /**
     * 从GraphResponse提取值 — 兼容of()和done()两种形态
     *
     * of(data):  output != null → getOutput().join() 取值
     * done(val): output == null → resultValue() 取值
     */
    private Object extractValue(GraphResponse<NodeOutput> graphResponse) {
        try {
            if (graphResponse.getOutput() != null && !graphResponse.getOutput().isCompletedExceptionally()) {
                return graphResponse.getOutput().join();
            }
            if (graphResponse.resultValue().isPresent()) {
                return graphResponse.resultValue().get();
            }
        } catch (Exception e) {
            log.debug("[GraphExec] extractValue failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 构建流式路径的终结chunk
     *
     * 流式COMPLETE不带content（前端已通过CHUNK获得所有文本）
     * ChatHistory的完整文本由BankController.AssistantAccumulator从累积器获取
     */
    private StreamChunk buildStreamingTerminalChunk(CompiledGraph graph, RunnableConfig config,
                                                     String intent) {
        try {
            var snapshot = graph.getState(config);
            if (snapshot == null) {
                return StreamChunk.streamingDone(intent);
            }

            String nextNode = snapshot.next();
            OverAllState state = snapshot.state();
            String question = state != null ? (String) state.value("_question").orElse("") : "";

            // interruptBefore中断 — 带question
            if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
                obsMetrics.recordWorkflowInterrupt(intent, nextNode);
                return StreamChunk.interrupted(intent, question);
            }
            // ask→END中断 — 带question
            if (question != null && !question.isEmpty()) {
                obsMetrics.recordWorkflowInterrupt(intent, "askEnd");
                return StreamChunk.interrupted(intent, question);
            }

            // 正常完成 — 不带content，只是结束信号
            clearCheckpoint(graph, config, intent);
            return StreamChunk.streamingDone(intent);

        } catch (Exception e) {
            log.error("[GraphExec] Failed to build streaming terminal chunk", e);
            return StreamChunk.streamingDone(intent);
        }
    }

    // ==================== 原有方法保留 ====================

    /**
     * 清理已完成Graph的checkpoint — 防止OverAllState垃圾堆积
     *
     * 只在Graph正常完成后调用(INTERRUPTED不能清理,需要保留给resume)。
     * 通过 CompiledGraph.compileConfig.checkpointSaver().release(config) 释放该threadId下的所有checkpoint。
     */
    private void clearCheckpoint(CompiledGraph graph, RunnableConfig config, String intent) {
        try {
            var saverOpt = graph.compileConfig.checkpointSaver();
            if (saverOpt.isPresent()) {
                var tag = saverOpt.get().release(config);
                log.info("[GraphExec] Checkpoint cleared: intent={}, threadId={}, checkpointsRemoved={}",
                        intent, tag.threadId(), tag.checkpoints().size());
            }
        } catch (Exception e) {
            // 清理失败不影响主流程,只记录警告
            log.warn("[GraphExec] Failed to clear checkpoint: intent={}, error={}", intent, e.getMessage());
        }
    }

    /**
     * 检查Graph执行结果 — 区分正常完成和中断（非流式路径复用）
     *
     * 中断来源:
     * 1. interruptBefore机制: next()非空且非__END__
     * 2. ask→END条件路由: graph结束但_question非空（兜底，不应触发）
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
                obsMetrics.recordWorkflowInterrupt(intent, nextNode);
                return WorkflowOutput.interrupted(intent, question);
            }

            // 判断2: ask→END中断 (graph结束但有提问 - 兜底)
            if (question != null && !question.isEmpty()) {
                log.info("[GraphExec] Interrupted by ask→END: question={}", question);
                obsMetrics.recordWorkflowInterrupt(intent, "askEnd");
                return WorkflowOutput.interrupted(intent, question);
            }

            // 正常完成 — 清理checkpoint,防止OverAllState垃圾堆积
            String content = currentState != null
                    ? (String) currentState.value("_outputContent").orElse("操作已完成")
                    : "操作已完成";

            log.info("[GraphExec] Graph completed: content={}", content);
            clearCheckpoint(graph, config, intent);

            return WorkflowOutput.completed(intent, content);

        } catch (Exception e) {
            log.error("[GraphExec] Failed to check graph result", e);
            return WorkflowOutput.completed(intent, "操作完成(状态检查失败)");
        }
    }
}
