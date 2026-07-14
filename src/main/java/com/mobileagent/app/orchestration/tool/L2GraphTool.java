package com.mobileagent.app.orchestration.tool;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tool.StateAwareToolCallback;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.orchestration.StepResultSinkRegistry;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import com.mobileagent.app.orchestration.model.OrchestrationStep;
import com.mobileagent.app.orchestration.model.StepStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * L2GraphTool — L2 subgraph tool (v6.0)
 *
 * <p>L2GraphTool maintains resume/new logic, delegates graph execution to GES.
 * <ul>
 *   <li>New: generate threadId -> GES.executeGraph() -> save threadId if interrupted</li>
 *   <li>Resume: read saved threadId -> GES.resumeGraph() -> clear threadId if completed</li>
 *   <li>Uses user's raw answer (not domain agent's rephrased version) for resume</li>
 * </ul>
 */
@Slf4j
public class L2GraphTool implements StateAwareToolCallback {

    private static final String DEFAULT_INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "input": {
              "type": "string",
              "description": "用户输入或指令"
            }
          },
          "required": ["input"]
        }
        """;

    private final CompiledGraph l2Graph;
    private final GraphExecutionEngine graphExecutionEngine;
    private final AtomicReference<CompiledGraph> orchestrationGraphRef;
    private final String toolName;
    private final String toolDescription;
    private final ToolDefinition toolDefinition;
    private final com.mobileagent.app.orchestration.StepResultSinkRegistry sinkRegistry;

    /** Cache: traceId:stepId → question, prevents ReactAgent infinite loop on INTERRUPTED steps.
     *  Input-aware: only hits when the SAME input is repeated (ReactAgent loop).
     *  Different input (user provided new answer) clears cache and executes normally. */
    private final ConcurrentHashMap<String, String> interruptCache = new ConcurrentHashMap<>();
    /** Cache: traceId:stepId → l2ThreadId, for resume after user answers */
    private final ConcurrentHashMap<String, String> interruptThreadIdCache = new ConcurrentHashMap<>();
    /** Cache: traceId:stepId → input that caused the interrupt, for input-aware cache hit */
    private final ConcurrentHashMap<String, String> interruptInputCache = new ConcurrentHashMap<>();

    public L2GraphTool(CompiledGraph l2Graph, GraphExecutionEngine graphExecutionEngine,
                       AtomicReference<CompiledGraph> orchestrationGraphRef,
                       String toolName, String toolDescription,
                       com.mobileagent.app.orchestration.StepResultSinkRegistry sinkRegistry) {
        this.l2Graph = l2Graph;
        this.graphExecutionEngine = graphExecutionEngine;
        this.orchestrationGraphRef = orchestrationGraphRef;
        this.toolName = toolName;
        this.toolDescription = toolDescription;
        this.sinkRegistry = sinkRegistry;
        this.toolDefinition = DefaultToolDefinition.builder()
            .name(toolName).description(toolDescription).inputSchema(DEFAULT_INPUT_SCHEMA).build();
    }

    // ===== ToolCallback interface =====

    @Override
    public ToolDefinition getToolDefinition() { return toolDefinition; }

    @Override
    public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long startTime = System.currentTimeMillis();
        String traceId = extractTraceId(toolContext);
        // [DEBUG-DUP] Stack trace to identify caller
        String caller = Thread.currentThread().getStackTrace()[2].getClassName() + "." + Thread.currentThread().getStackTrace()[2].getMethodName();
        log.info("[L2GraphTool][DEBUG-DUP] call() START | tool={} | traceId={} | stepId={} | caller={} | resume={} | input={}",
            toolName, traceId, readCurrentStepId(toolContext), caller, isResumeHint(toolContext),
            toolInput != null && toolInput.length() > 100 ? toolInput.substring(0, 100) + "..." : toolInput);

        try {
            // 1. Parse input
            Map<String, Object> inputs = parseToolInput(toolInput);
            String actualInput = (String) inputs.getOrDefault("user_input", "");

            // 2. Determine current stepId — read from orchestration graph state
            String currentStepId = readCurrentStepId(toolContext);
            log.info("[L2GraphTool] Current stepId={} | tool={}", currentStepId, toolName);

            // 3.1 Cancel step check — if step has cancelSignal=true, cancel the L2 subgraph via GES
            if (currentStepId != null && !currentStepId.isEmpty() && isCancelStep(toolContext, currentStepId)) {
                log.info("[L2GraphTool] Cancel step detected: stepId={}, calling GES cancelGraph", currentStepId);
                return executeCancelStep(toolContext, currentStepId, traceId, actualInput);
            }

            // 3.5 Interrupt cache check — input-aware: only hits when ReactAgent repeats the SAME input.
            // If input changed (user provided new answer after graph resume), cache is cleared and normal execution proceeds.
            String cacheKey = traceId + ":" + currentStepId;
            if (currentStepId != null && !currentStepId.isEmpty() && interruptCache.containsKey(cacheKey)) {
                String cachedInput = interruptInputCache.get(cacheKey);
                if (actualInput != null && actualInput.equals(cachedInput)) {
                    // Same input — ReactAgent loop, return cached result without re-executing
                    // Return empty string so ReactAgent stops calling this tool (empty tool result
                    // = no actionable content → agent exits tool loop). The WAITING_QUESTION is already
                    // written into state below, so conditionCheckNode will pick it up and route to WAITING_USER.
                    String cachedQuestion = interruptCache.get(cacheKey);
                    String cachedThreadId = interruptThreadIdCache.get(cacheKey);
                    log.info("[L2GraphTool] Step already INTERRUPTED (same input, cached): stepId={}, question={}",
                        currentStepId, cachedQuestion);
                    Map<String, Object> cachedExtraState = ToolContextHelper.getStateForUpdate(toolContext).orElse(null);
                    if (cachedExtraState != null) {
                        cachedExtraState.put(OrchestrationStateKeys.WAITING_QUESTION, cachedQuestion);
                        cachedExtraState.put(OrchestrationStateKeys.INTERRUPTION_REASON, "L2_INTERRUPT");
                        cachedExtraState.put(OrchestrationStateKeys.ORCH_STATUS, "WAITING_USER");
                        if (currentStepId != null && !currentStepId.isEmpty() && cachedThreadId != null) {
                            @SuppressWarnings("unchecked")
                            Map<String, String> stm = (Map<String, String>) cachedExtraState.getOrDefault(
                                OrchestrationStateKeys.L2_STEP_THREAD_MAP, new LinkedHashMap<>());
                            stm.put(currentStepId, cachedThreadId);
                            cachedExtraState.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, stm);
                            cachedExtraState.put(OrchestrationStateKeys.WAITING_STEP_ID, currentStepId);
                        }
                    }
                    return "";
                } else {
                    // Different input — user provided new answer, clear cache and execute normally
                    log.info("[L2GraphTool] Input changed for cached step, clearing cache: stepId={} cachedInput='{}' newInput='{}'",
                        currentStepId, cachedInput, actualInput);
                    interruptCache.remove(cacheKey);
                    interruptThreadIdCache.remove(cacheKey);
                    interruptInputCache.remove(cacheKey);
                }
            }

            // 4. Resume or new? — Check L2_STEP_THREAD_MAP, then step's l2ThreadId, then legacy fallback
            Map<String, String> stepThreadMap = readStepThreadMap(toolContext);
            String pendingL2ThreadId = null;

            if (currentStepId != null && !currentStepId.isEmpty() && stepThreadMap != null) {
                pendingL2ThreadId = stepThreadMap.get(currentStepId);
            }

            // Fallback 1: check step's l2ThreadId field (survives framework state resets)
            if ((pendingL2ThreadId == null || pendingL2ThreadId.isEmpty()) && currentStepId != null) {
                String stepL2ThreadId = readL2ThreadIdFromStep(toolContext, currentStepId);
                if (stepL2ThreadId != null && !stepL2ThreadId.isEmpty()) {
                    pendingL2ThreadId = stepL2ThreadId;
                    log.info("[L2GraphTool] Using step's l2ThreadId as fallback: stepId={}, threadId={}", currentStepId, stepL2ThreadId);
                }
            }

            // NOTE: Legacy L2_PENDING_THREAD_ID fallback removed intentionally.
            // It was a global single-value key that caused wrong-threadId resume when
            // a new interruption step (e.g. transfer at level 2) found no entry in
            // stepThreadMap or step.l2ThreadId, but the legacy key still held a
            // threadId from a DIFFERENT step's subgraph (e.g. wealth_consult).
            // The two-level fallback (stepThreadMap → step.l2ThreadId) is sufficient
            // and step-scoped, preventing cross-step threadId leakage.

            boolean isResume = pendingL2ThreadId != null && !pendingL2ThreadId.isEmpty();
            String l2ThreadId;
            reactor.core.publisher.Flux<StreamChunk> resultFlux;

            if (isResume) {
                l2ThreadId = pendingL2ThreadId;
                // Use actualInput (ReactAgent's focused tool call) for resume instead of LATEST_USER_INPUT.
                // LATEST_USER_INPUT may contain info for multiple steps (e.g., "给笑阿姨，再买朝朝盈股"),
                // which can confuse the L2 graph's extractParams and cause wrong parameter extraction.
                // actualInput is already focused by the ReactAgent on the current step's intent.
                String resumeInput = (actualInput != null && !actualInput.isEmpty()) ? actualInput : readUserRawInput(toolContext, actualInput);
                log.info("[L2GraphTool] RESUMING via GES | tool={} | stepId={} | threadId={} | userInput={} | source={}",
                    toolName, currentStepId, l2ThreadId, resumeInput,
                    (actualInput != null && !actualInput.isEmpty()) ? "actualInput" : "LATEST_USER_INPUT");
                clearPendingInExtraState(toolContext, currentStepId);
                resultFlux = graphExecutionEngine.resumeGraph(l2Graph, toolName.toUpperCase(), resumeInput, l2ThreadId, null);
            } else {
                l2ThreadId = traceId + "-" + toolName + "-" + (currentStepId != null ? currentStepId : "anon")
                    + "-" + System.currentTimeMillis();
                log.info("[L2GraphTool] NEW via GES | tool={} | stepId={} | threadId={}", toolName, currentStepId, l2ThreadId);
                Map<String, Object> gesInput = new HashMap<>();
                gesInput.put("messages", actualInput);
                gesInput.put("_latestUserInput", actualInput);
                gesInput.put("_question", null);
                resultFlux = graphExecutionEngine.executeGraph(l2Graph, toolName.toUpperCase(), gesInput, l2ThreadId);
            }

            // 5. Single-subscription: forward non-terminal chunks to sink + capture terminal for return
            //    Reference: L1 pattern — GES returns Flux<StreamChunk> directly, no blocking.
            //    Here we must block (tool callback contract returns String), but we bridge
            //    non-terminal chunks to the orchestration sink BEFORE blocking completes,
            //    so SSE sees streaming output in real-time.
            Sinks.Many<StreamChunk> sink = (sinkRegistry != null && traceId != null)
                ? sinkRegistry.getSink(traceId) : null;
            log.info("[L2GraphTool] Sink lookup: traceId={}, sinkFound={}, isResume={}, stepId={}",
                traceId, sink != null, isResume, currentStepId);

            if (sink != null) {
                // Streamable path: single subscribe, forward non-terminal to sink, capture terminal
                java.util.concurrent.atomic.AtomicReference<WorkflowOutput> terminalRef = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<StreamChunk> terminalChunkRef = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                resultFlux.subscribe(
                    chunk -> {
                        log.info("[L2GraphTool] Chunk received: tool={} type={} intent={} contentLen={} isTerminal={} replyContentLen={}",
                            toolName, chunk.getType(), chunk.getIntent(),
                            chunk.getContent() != null ? chunk.getContent().length() : 0,
                            chunk.isTerminal(),
                            chunk.getReplyContent() != null ? chunk.getReplyContent().length() : 0);
                        if (chunk.isTerminal()) {
                            terminalRef.set(chunk.toWorkflowOutput());
                            // [DEBUG-DUP] Track terminal chunk for duplicate detection
                            StreamChunk prev = terminalChunkRef.getAndSet(chunk);
                            if (prev != null) {
                                log.warn("[L2GraphTool][DEBUG-DUP] Multiple terminal chunks! tool={} stepId={} prev_type={} curr_type={}",
                                    toolName, currentStepId, prev.getType(), chunk.getType());
                            }
                            // Non-streamable L2 (e.g. TRANSFER) produces only a terminal COMPLETE with content.
                            // Forward as-is (COMPLETE type) so SSE shows step result with correct intent.
                            // Streamable L2's terminal COMPLETE has no content (streamingDone), so filtered out.
                            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                                var emitResult = sink.tryEmitNext(chunk);
                                log.info("[L2GraphTool][DEBUG-DUP] Sink emit OK: tool={} stepId={} type={} intent={} emitResult={} contentLen={}",
                                    toolName, currentStepId, chunk.getType(), chunk.getIntent(), emitResult,
                                    chunk.getContent() != null ? chunk.getContent().length() : 0);
                            } else {
                                log.warn("[L2GraphTool] Terminal chunk NOT forwarded to sink (empty content): tool={} stepId={} type={} intent={} content='{}' replyContent='{}'",
                                    toolName, currentStepId, chunk.getType(), chunk.getIntent(),
                                    chunk.getContent(), chunk.getReplyContent());
                            }
                        } else if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                            // Streamable L2 streaming chunks — forward as-is
                            var emitResult = sink.tryEmitNext(chunk);
                            if (emitResult.isFailure()) {
                                log.warn("[L2GraphTool] sink.tryEmitNext FAILED for non-terminal: {} tool={} type={}", emitResult, toolName, chunk.getType());
                            }
                        }
                    },
                    err -> {
                        log.debug("[L2GraphTool] L2 stream error: {}", err.getMessage());
                        latch.countDown();
                    },
                    () -> {
                        log.info("[L2GraphTool] L2 flux completed for traceId={}", traceId);
                        latch.countDown();
                    }
                );
                log.info("[L2GraphTool] Subscribed to resultFlux with sink forwarding for traceId={}", traceId);

                // Block until flux completes (same thread model as before, just single subscription)
                latch.await();
                WorkflowOutput output = terminalRef.get();

                // Process result
                String result = processOutput(output, toolContext, l2ThreadId, currentStepId);
                updateInterruptCache(cacheKey, output, l2ThreadId, currentStepId, actualInput);
                log.info("[L2GraphTool] call() END | tool={} | elapsed={}ms | resume={} | stepId={}",
                    toolName, System.currentTimeMillis() - startTime, isResume, currentStepId);
                return result;
            } else {
                // Non-streamable path (no sink): simple block, same as before
                log.warn("[L2GraphTool] NO SINK for traceId={} — L2 result will NOT be bridged to SSE! tool={} stepId={}",
                    traceId, toolName, currentStepId);
                WorkflowOutput output = resultFlux
                    .filter(StreamChunk::isTerminal)
                    .last()
                    .map(StreamChunk::toWorkflowOutput)
                    .block();

                String result = processOutput(output, toolContext, l2ThreadId, currentStepId);
                updateInterruptCache(cacheKey, output, l2ThreadId, currentStepId, actualInput);
                log.info("[L2GraphTool] call() END (no-sink) | tool={} | elapsed={}ms | resume={} | stepId={}",
                    toolName, System.currentTimeMillis() - startTime, isResume, currentStepId);
                return result;
            }

        } catch (Exception e) {
            log.error("[L2GraphTool] call() FAILED | tool={} | error={}", toolName, e.getMessage(), e);
            return "工具调用失败: " + e.getMessage();
        }
    }

    private void updateInterruptCache(String cacheKey, WorkflowOutput output, String l2ThreadId, String stepId, String input) {
        if (output == null || stepId == null || cacheKey == null) return;
        if (output.getStatus() == com.mobileagent.app.data.WorkflowStatus.INTERRUPTED) {
            String question = output.getQuestion() != null ? output.getQuestion() : "";
            interruptCache.put(cacheKey, question);
            interruptThreadIdCache.put(cacheKey, l2ThreadId);
            interruptInputCache.put(cacheKey, input != null ? input : "");
            log.info("[L2GraphTool] Cached interrupt: key={}, question={}, threadId={}, input='{}'", cacheKey, question, l2ThreadId, input);
        } else if (output.getStatus() == com.mobileagent.app.data.WorkflowStatus.COMPLETED) {
            interruptCache.remove(cacheKey);
            interruptThreadIdCache.remove(cacheKey);
            interruptInputCache.remove(cacheKey);
            log.info("[L2GraphTool] Cleared interrupt cache: key={} (step completed)", cacheKey);
        }
    }

    private String processOutput(WorkflowOutput output, ToolContext toolContext, String l2ThreadId, String stepId) {
        if (output == null) return "子图执行完成（无结果）";

        Map<String, Object> extraState = ToolContextHelper.getStateForUpdate(toolContext).orElse(null);

        if (output.getStatus() == com.mobileagent.app.data.WorkflowStatus.INTERRUPTED) {
            String question = output.getQuestion() != null ? output.getQuestion() : "请提供更多信息以继续操作";
            log.info("[L2GraphTool] L2 INTERRUPT: question={}, l2ThreadId={}, stepId={}", question, l2ThreadId, stepId);
            if (extraState != null) {
                extraState.put(OrchestrationStateKeys.WAITING_QUESTION, question);
                extraState.put(OrchestrationStateKeys.INTERRUPTION_REASON, "L2_INTERRUPT");
                extraState.put(OrchestrationStateKeys.ORCH_STATUS, "WAITING_USER");

                // Phase 2A: Store threadId in L2_STEP_THREAD_MAP (keyed by stepId)
                if (stepId != null && !stepId.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> stepThreadMap = (Map<String, String>) extraState.getOrDefault(
                        OrchestrationStateKeys.L2_STEP_THREAD_MAP, new LinkedHashMap<>());
                    stepThreadMap.put(stepId, l2ThreadId);
                    extraState.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, stepThreadMap);
                    extraState.put(OrchestrationStateKeys.WAITING_STEP_ID, stepId);
                    log.info("[L2GraphTool] Saved stepId={} → threadId={} in L2_STEP_THREAD_MAP", stepId, l2ThreadId);
                }

                // NOTE: No longer writing to L2_PENDING_THREAD_ID (legacy global key).
                // stepThreadMap (keyed by stepId) is the authoritative source.
                // The legacy global key caused wrong-threadId resume for new steps.
            }
            return "需要用户输入: " + question;
        }

        // Normal completion
        if (extraState != null) {
            // Phase 2A: Remove threadId from L2_STEP_THREAD_MAP (step completed)
            if (stepId != null && !stepId.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, String> stepThreadMap = (Map<String, String>) extraState.getOrDefault(
                    OrchestrationStateKeys.L2_STEP_THREAD_MAP, new LinkedHashMap<>());
                String removed = stepThreadMap.remove(stepId);
                extraState.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, stepThreadMap);
                if (removed != null) {
                    log.info("[L2GraphTool] Removed stepId={} from L2_STEP_THREAD_MAP (completed)", stepId);
                }
            }
            // NOTE: No longer clearing L2_PENDING_THREAD_ID (legacy global key removed).
            // stepThreadMap removal above is sufficient.
            extraState.put(OrchestrationStateKeys.WAITING_QUESTION, "");
            extraState.put(OrchestrationStateKeys.INTERRUPTION_REASON, "");
        }
        String content = output.getContent();
        return (content != null && !content.isEmpty()) ? content : "子图执行完成";
    }

    private void clearPendingInExtraState(ToolContext toolContext, String stepId) {
        ToolContextHelper.getStateForUpdate(toolContext).ifPresent(es -> {
            // NOTE: No longer clearing L2_PENDING_THREAD_ID (legacy global key removed).
            // stepThreadMap removal below is sufficient.
            if (stepId != null && !stepId.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, String> stepThreadMap = (Map<String, String>) es.getOrDefault(
                    OrchestrationStateKeys.L2_STEP_THREAD_MAP, new LinkedHashMap<>());
                stepThreadMap.remove(stepId);
                es.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, stepThreadMap);
            }
        });
    }

    private Map<String, Object> parseToolInput(String toolInput) {
        Map<String, Object> inputs = new HashMap<>();
        String actualInput = toolInput;
        try {
            if (toolInput != null && toolInput.startsWith("{")) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> parsed = mapper.readValue(toolInput,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                if (parsed.containsKey("input")) actualInput = String.valueOf(parsed.get("input"));
                inputs.putAll(parsed);
            }
        } catch (Exception e) {
            log.debug("[L2GraphTool] parseToolInput: non-JSON | tool={}", toolName);
        }
        inputs.put("user_input", actualInput);
        inputs.put("_latestUserInput", actualInput);
        inputs.put("messages", List.of(new UserMessage(actualInput)));
        log.info("[L2GraphTool] parseToolInput: actualInput={}", actualInput);
        return inputs;
    }

    private String extractTraceId(ToolContext toolContext) {
        if (toolContext == null) return "unknown";
        return ToolContextHelper.getConfig(toolContext).flatMap(RunnableConfig::threadId).orElse("no-thread");
    }

    private String extractDomainFromToolName() {
        if (toolName == null || !toolName.contains("_")) return toolName != null ? toolName.toUpperCase() : "UNKNOWN";
        return toolName.substring(0, toolName.indexOf("_")).toUpperCase();
    }

    private String readUserRawInput(ToolContext toolContext, String fallback) {
        if (orchestrationGraphRef == null || orchestrationGraphRef.get() == null) return fallback;
        try {
            String threadId = ToolContextHelper.getConfig(toolContext).flatMap(RunnableConfig::threadId).orElse(null);
            if (threadId == null || threadId.isEmpty()) return fallback;
            var snapshot = orchestrationGraphRef.get().getState(RunnableConfig.builder().threadId(threadId).build());
            if (snapshot == null || snapshot.state() == null) return fallback;
            Object val = snapshot.state().value(OrchestrationStateKeys.LATEST_USER_INPUT).orElse(null);
            if (val != null && !val.toString().isEmpty()) {
                log.info("[L2GraphTool] User raw input from orchestration state: {}", val);
                return val.toString();
            }
        } catch (Exception e) {
            log.warn("[L2GraphTool] Failed to read user raw input: {}", e.getMessage());
        }
        return fallback;
    }

    // ===== Phase 2A: stepId → threadId management =====

    /**
     * Read current stepId from orchestration graph state.
     * First tries WAITING_STEP_ID (set on interrupt), then falls back to
     * finding the step at CURRENT_STEP_INDEX.
     */
    private String readCurrentStepId(ToolContext toolContext) {
        if (orchestrationGraphRef == null || orchestrationGraphRef.get() == null) return null;
        try {
            String threadId = ToolContextHelper.getConfig(toolContext).flatMap(RunnableConfig::threadId).orElse(null);
            if (threadId == null || threadId.isEmpty()) return null;
            var snapshot = orchestrationGraphRef.get().getState(RunnableConfig.builder().threadId(threadId).build());
            if (snapshot == null || snapshot.state() == null) return null;
            var state = snapshot.state();

            // Priority 1: WAITING_STEP_ID (set when L2 interrupts)
            String waitingStepId = state.value(OrchestrationStateKeys.WAITING_STEP_ID, "");
            if (waitingStepId != null && !waitingStepId.isEmpty()) {
                return waitingStepId;
            }

            // Priority 2: Find step at CURRENT_STEP_INDEX
            int currentIdx = state.value(OrchestrationStateKeys.CURRENT_STEP_INDEX, -1);
            if (currentIdx >= 0) {
                List<?> steps = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());
                if (steps != null && currentIdx < steps.size()) {
                    Object stepObj = steps.get(currentIdx);
                    if (stepObj instanceof OrchestrationStep step) {
                        return step.getStepId();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[L2GraphTool] Failed to read current stepId: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Read L2_STEP_THREAD_MAP from orchestration graph state.
     */

    /**
     * Read l2ThreadId from the OrchestrationStep itself — fallback when stepThreadMap is lost.
     * Returns l2ThreadId for INTERRUPTED or RUNNING steps that have a saved l2ThreadId.
     * RUNNING is included because StepPreparator changes INTERRUPTED→RUNNING before L2GraphTool runs.
     * COMPLETED/PENDING steps are excluded: COMPLETED doesn't need resume, PENDING was never interrupted.
     */
    private String readL2ThreadIdFromStep(ToolContext toolContext, String stepId) {
        if (orchestrationGraphRef == null || orchestrationGraphRef.get() == null || stepId == null) return null;
        try {
            String threadId = ToolContextHelper.getConfig(toolContext).flatMap(RunnableConfig::threadId).orElse(null);
            if (threadId == null || threadId.isEmpty()) return null;
            var snapshot = orchestrationGraphRef.get().getState(RunnableConfig.builder().threadId(threadId).build());
            if (snapshot == null || snapshot.state() == null) return null;
            var state = snapshot.state();
            List<?> steps = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());
            if (steps != null) {
                for (Object obj : steps) {
                    if (obj instanceof OrchestrationStep step && stepId.equals(step.getStepId())) {
                        if (step.getStatus() == StepStatus.INTERRUPTED || step.getStatus() == StepStatus.RUNNING) {
                            String tid = step.getL2ThreadId();
                            if (tid != null && !tid.isEmpty()) {
                                return tid;
                            }
                        }
                        log.debug("[L2GraphTool] Skipping l2ThreadId for stepId={}: status={} (not INTERRUPTED/RUNNING or no l2ThreadId)",
                            stepId, step.getStatus());
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[L2GraphTool] Failed to read l2ThreadId from step: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, String> readStepThreadMap(ToolContext toolContext) {
        if (orchestrationGraphRef == null || orchestrationGraphRef.get() == null) return null;
        try {
            String threadId = ToolContextHelper.getConfig(toolContext).flatMap(RunnableConfig::threadId).orElse(null);
            if (threadId == null || threadId.isEmpty()) return null;
            var snapshot = orchestrationGraphRef.get().getState(RunnableConfig.builder().threadId(threadId).build());
            if (snapshot == null || snapshot.state() == null) return null;
            Object rawMap = snapshot.state().value(OrchestrationStateKeys.L2_STEP_THREAD_MAP).orElse(null);
            if (rawMap instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawMap).entrySet()) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[L2GraphTool] Failed to read stepThreadMap: {}", e.getMessage());
        }
        return null;
    }


    // ===== Cancel step handling =====

    /**
     * Check if the current step has cancelSignal=true — set by either:
     * 1. StepPreparator.processCancelledL1Intents() for L1-adopted cancel intents
     * 2. OrchestrationAgent.applyDecomposeOperations() for orchestration-decomposed cancel intents
     *
     * Unified cancel: doesn't care about step origin, only checks cancelSignal boolean.
     */
    private boolean isCancelStep(ToolContext toolContext, String stepId) {
        if (orchestrationGraphRef == null || orchestrationGraphRef.get() == null || stepId == null) return false;
        try {
            String threadId = ToolContextHelper.getConfig(toolContext).flatMap(RunnableConfig::threadId).orElse(null);
            if (threadId == null || threadId.isEmpty()) return false;
            var snapshot = orchestrationGraphRef.get().getState(RunnableConfig.builder().threadId(threadId).build());
            if (snapshot == null || snapshot.state() == null) return false;
            List<?> steps = (List<?>) snapshot.state().value(OrchestrationStateKeys.STEPS).orElse(List.of());
            for (Object obj : steps) {
                if (obj instanceof OrchestrationStep step && stepId.equals(step.getStepId())) {
                    return step.isCancelSignal();
                }
            }
        } catch (Exception e) {
            log.warn("[L2GraphTool] Failed to check cancelSignal: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Execute cancel step — unified for both L1-adopted and orchestration-decomposed cancel.
     * Inject _cancelSignal=true into L2 subgraph and resume, letting the subgraph's
     * cancelAwareExtractParams/cancelAwareAsk detect it and route to cancelExecution
     * for clean termination. GES auto-clears checkpoint on completion.
     *
     * <p>Cancel unification: whether the step was adopted from L1 or decomposed by orchestration,
     * the cancel flow is identical — cancelSignal → GES.cancelGraph() → _cancelSignal → cancelExecution → END.
     */
    private String executeCancelStep(ToolContext toolContext, String stepId, String traceId, String actualInput) {
        // Find the L2 threadId for this step (from stepThreadMap or step.l2ThreadId)
        Map<String, String> stepThreadMap = readStepThreadMap(toolContext);
        String l2ThreadId = null;

        if (stepThreadMap != null && stepId != null) {
            l2ThreadId = stepThreadMap.get(stepId);
        }

        // Fallback: check step's l2ThreadId field
        if ((l2ThreadId == null || l2ThreadId.isEmpty()) && stepId != null) {
            l2ThreadId = readL2ThreadIdFromStep(toolContext, stepId);
        }

        if (l2ThreadId == null || l2ThreadId.isEmpty()) {
            // No L2 checkpoint to cancel — step has no subgraph running
            log.info("[L2GraphTool] Cancel step has no L2 threadId, returning cancelled directly: stepId={}", stepId);
            Map<String, Object> extraState = ToolContextHelper.getStateForUpdate(toolContext).orElse(null);
            if (extraState != null) {
                extraState.put(OrchestrationStateKeys.WAITING_QUESTION, "");
                extraState.put(OrchestrationStateKeys.INTERRUPTION_REASON, "");
            }
            return "操作已取消";
        }

        // Call GES cancelGraph — inject _cancelSignal + resume with user input
        String intent = extractDomainFromToolName();
        Flux<StreamChunk> resultFlux = graphExecutionEngine.cancelGraph(l2Graph, intent, l2ThreadId, actualInput);

        // Single-subscription: forward non-terminal chunks to sink + capture terminal
        Sinks.Many<StreamChunk> sink = (sinkRegistry != null && traceId != null)
            ? sinkRegistry.getSink(traceId) : null;

        if (sink != null) {
            java.util.concurrent.atomic.AtomicReference<StreamChunk> terminalChunkRef = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            resultFlux.subscribe(
                chunk -> {
                    if (chunk.isTerminal()) {
                        terminalChunkRef.set(chunk);
                        if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                            sink.tryEmitNext(chunk);
                        }
                    } else if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                        sink.tryEmitNext(chunk);
                    }
                },
                err -> {
                    log.debug("[L2GraphTool] Cancel stream error: {}", err.getMessage());
                    latch.countDown();
                },
                latch::countDown
            );

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            StreamChunk terminal = terminalChunkRef.get();
            String result = (terminal != null && terminal.getContent() != null && !terminal.getContent().isEmpty())
                ? terminal.getContent() : "操作已取消";

            // Clean up stepThreadMap entry and clear WAITING state
            Map<String, Object> extraState = ToolContextHelper.getStateForUpdate(toolContext).orElse(null);
            if (extraState != null) {
                if (stepId != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> stm = (Map<String, String>) extraState.getOrDefault(
                        OrchestrationStateKeys.L2_STEP_THREAD_MAP, new LinkedHashMap<>());
                    stm.remove(stepId);
                    extraState.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, stm);
                }
                extraState.put(OrchestrationStateKeys.WAITING_QUESTION, "");
                extraState.put(OrchestrationStateKeys.INTERRUPTION_REASON, "");
            }

            log.info("[L2GraphTool] Cancel step completed: stepId={}, result={}", stepId, result);
            return result;
        } else {
            // No sink — simple block
            try {
                WorkflowOutput output = resultFlux
                    .filter(StreamChunk::isTerminal)
                    .last()
                    .map(StreamChunk::toWorkflowOutput)
                    .block();
                String result = (output != null && output.getContent() != null && !output.getContent().isEmpty())
                    ? output.getContent() : "操作已取消";
                log.info("[L2GraphTool] Cancel step completed (no-sink): stepId={}, result={}", stepId, result);
                return result;
            } catch (Exception e) {
                log.warn("[L2GraphTool] Cancel step failed: stepId={}, error={}", stepId, e.getMessage());
                return "操作已取消";
            }
        }
    }


    // [DEBUG-DUP] Helper for logging resume hint
    private boolean isResumeHint(ToolContext toolContext) {
        try {
            String currentStepId = readCurrentStepId(toolContext);
            if (currentStepId == null) return false;
            Map<String, String> stepThreadMap = readStepThreadMap(toolContext);
            if (stepThreadMap != null && stepThreadMap.containsKey(currentStepId)) return true;
            String l2ThreadId = readL2ThreadIdFromStep(toolContext, currentStepId);
            return l2ThreadId != null && !l2ThreadId.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
    @Override
    public String toString() { return "L2GraphTool{name='" + toolName + "', via GES}"; }
}
