package com.mobileagent.app.orchestration;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.data.SubGraphProperties;
import com.mobileagent.app.domain.DomainHandler;
import com.mobileagent.app.domain.L1ContextSnapshot;
import com.mobileagent.app.orchestration.bridge.OrchestrationStateBridge;
import com.mobileagent.app.orchestration.model.ConditionalAnswer;
import com.mobileagent.app.orchestration.model.OrchestrationProperties;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.orchestration.model.OrchestrationStatus;
import com.mobileagent.app.orchestration.model.OrchestrationStep;
import com.mobileagent.app.orchestration.model.StepStatus;
import com.mobileagent.app.router.domain.DomainRouter;
import com.mobileagent.app.router.registry.DomainServiceRegistry;
import com.mobileagent.app.util.JsonParseUtils;
import com.mobileagent.app.util.TemplateUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;

/**
 * 编排Agent — 多意图编排的入口点
 *
 * <p>实现 {@link DomainHandler}，作为L0域路由的目标之一。
 * 管理编排生命周期的四种状态转换：
 * <ul>
 *   <li>新编排：PLANNING → 调用 StateGraph 生成计划并执行</li>
 *   <li>恢复编排：WAITING_USER → decomposeByIntent 拆解后恢复 StateGraph</li>
 *   <li>挂起编排：SUSPENDED → 用户意图切换，启动新编排</li>
 *   <li>执行中：EXECUTING → 用户输入入队等待</li>
 * </ul>
 *
 * <h3>decomposeByIntent Refactor</h3>
 * <p>WAITING_USER 状态下的输入处理采用4级fallback链：
 * <ol>
 *   <li>Level 1 (0ms): classifyInput 快速旁路 — 纯数字/确认词 → ANSWER</li>
 *   <li>Level 2 (LLM ~500ms): decomposeByIntent — 主路径，LLM拆解输入为操作列表</li>
 *   <li>Level 3 (LLM ~2-5s): replan fallback — 清空orch_plan, ORCH_STATUS=REPLANNING</li>
 *   <li>Level 4 (0ms): 终端fallback — 全量输入作为ANSWER传给当前中断步骤</li>
 * </ol>
 */
@Slf4j
@Component
public class OrchestrationAgent implements DomainHandler {

    private final CompiledGraph orchestrationGraph;
    private final OrchestrationStateService stateService;
    private final OrchestrationRelevance relevance;
    private final OrchestrationStateBridge stateBridge;
    private final StepResultSinkRegistry sinkRegistry;
    private final DomainRouter domainRouter;
    private final ChatClient decomposeChatClient;
    private final SubGraphProperties subGraphProperties;
    private final ObjectMapper objectMapper;
    private final DomainServiceRegistry domainServiceRegistry;
    private final GlobalSessionStateStore globalSessionStore;
    private final OrchestrationProperties orchestrationProperties;

    public OrchestrationAgent(CompiledGraph orchestrationGraph,
                               OrchestrationStateService stateService,
                               OrchestrationRelevance relevance,
                               OrchestrationStateBridge stateBridge,
                               StepResultSinkRegistry sinkRegistry,
                               DomainRouter domainRouter,
                               @Qualifier("domainChatClient") ChatClient decomposeChatClient,
                               SubGraphProperties subGraphProperties,
                               ObjectMapper objectMapper,
                               DomainServiceRegistry domainServiceRegistry,
                               GlobalSessionStateStore globalSessionStore,
                               OrchestrationProperties orchestrationProperties) {
        this.orchestrationGraph = orchestrationGraph;
        this.stateService = stateService;
        this.relevance = relevance;
        this.stateBridge = stateBridge;
        this.sinkRegistry = sinkRegistry;
        this.domainRouter = domainRouter;
        this.decomposeChatClient = decomposeChatClient;
        this.subGraphProperties = subGraphProperties;
        this.objectMapper = objectMapper;
        this.domainServiceRegistry = domainServiceRegistry;
        this.globalSessionStore = globalSessionStore;
        this.orchestrationProperties = orchestrationProperties;
    }

    // ==================== DomainHandler 实现 ====================

    @Override
    public Flux<StreamChunk> handle(String sessionId, String userInput) {
        OrchestrationStateService.OrchestrationState state = stateService.getOrCheckExpired(sessionId);

        // EXECUTING: 当前步骤正在执行，将用户输入入队等待
        if (state != null && state.getStatus() == OrchestrationStatus.EXECUTING) {
            stateService.enqueuePendingInput(sessionId, userInput);
            log.info("[OrchestrationAgent.handle] Session={} EXECUTING, enqueued pending input", sessionId);
            return Flux.just(StreamChunk.chunk("ORCHESTRATION",
                "收到您的消息，当前步骤完成后将处理。"));
        }

        // WAITING_USER: 恢复编排 — decomposeByIntent 4级fallback链
        if (state != null && state.getStatus() == OrchestrationStatus.WAITING_USER) {
            log.info("[OrchestrationAgent.handle] Session={} WAITING_USER, decomposing input", sessionId);
            return handleWaitingUser(sessionId, userInput, state);
        }

        // SUSPENDED: 挂起的编排，用户输入是新意图，启动新编排
        if (state != null && state.getStatus() == OrchestrationStatus.SUSPENDED) {
            log.info("[OrchestrationAgent.handle] Session={} SUSPENDED, starting new orchestration", sessionId);
            return startOrchestration(sessionId, userInput);
        }

        // 无状态或已过期：启动新编排
        return startOrchestration(sessionId, userInput);
    }

    @Override
    public String getDomainName() {
        return "编排";
    }

    // ==================== WAITING_USER: decomposeByIntent 4级fallback链 ====================

    /**
     * WAITING_USER 状态下的处理 — decomposeByIntent 4级fallback链
     *
     * <p>4级fallback设计确保任何用户输入都能被处理：
     * <ol>
     *   <li>Level 1 (0ms): classifyInput 快速旁路 — 纯数字/确认词 → 直接ANSWER恢复</li>
     *   <li>Level 2 (LLM ~500ms): decomposeByIntent — LLM拆解为操作列表，直接操作步骤状态</li>
     *   <li>Level 3 (LLM ~2-5s): replan fallback — 清空orch_plan, ORCH_STATUS=REPLANNING, 重新规划</li>
     *   <li>Level 4 (0ms): 终端fallback — 全量输入作为ANSWER传给当前中断步骤</li>
     * </ol>
     */
    private Flux<StreamChunk> handleWaitingUser(String sessionId, String userInput,
                                                   OrchestrationStateService.OrchestrationState extState) {
        String waitingQuestion = extState.getWaitingQuestion();
        String graphThreadId = extState.getGraphThreadId();
        if (graphThreadId == null || graphThreadId.isEmpty()) {
            log.warn("[OrchestrationAgent] No graphThreadId in WAITING_USER, falling back to startOrchestration");
            return startOrchestration(sessionId, userInput);
        }

        // === Level 1: Fast bypass (0ms) — pure numbers/confirmations → ANSWER ===
        OrchestrationRelevance.ClassificationResult classification =
            relevance.classifyInput(userInput, waitingQuestion);
        if (classification.getType() == OrchestrationRelevance.InputClassification.ANSWER) {
            log.info("[OrchestrationAgent.handleWaitingUser] Level 1: ANSWER fast bypass for input='{}'", userInput);
            Map<String, Object> resumeData = new LinkedHashMap<>();
            resumeData.put("messages", List.of(new UserMessage(userInput)));
            resumeData.put(OrchestrationStateKeys.LATEST_USER_INPUT, userInput);
            resumeData.put(OrchestrationStateKeys.WAITING_QUESTION, "");
            return resumeGraph(sessionId, graphThreadId, extState, userInput, resumeData);
        }

        // === Level 2: decomposeByIntent (LLM ~500ms) — main path ===
        DecomposeResult decomposeResult = null;
        try {
            decomposeResult = decomposeByIntent(graphThreadId, userInput);
        } catch (Exception e) {
            log.warn("[OrchestrationAgent.handleWaitingUser] Level 2 decomposeByIntent exception: {}", e.getMessage());
        }

        if (decomposeResult != null && !decomposeResult.isNeedsReplan()) {
            log.info("[OrchestrationAgent.handleWaitingUser] Level 2: applying {} decompose operations",
                decomposeResult.getOperations().size());
            try {
                applyDecomposeOperations(graphThreadId, userInput, decomposeResult);
                // State already updated via updateState — resume with null resumeData
                return resumeGraph(sessionId, graphThreadId, extState, userInput, null);
            } catch (Exception e) {
                log.warn("[OrchestrationAgent.handleWaitingUser] Level 2 applyDecomposeOperations failed: {}, falling to Level 3",
                    e.getMessage(), e);
            }
        }

        // === Level 3: replan fallback (LLM ~2-5s) ===
        // decomposeByIntent failed OR returned needsReplan=true
        log.info("[OrchestrationAgent.handleWaitingUser] Level 3: replan fallback");
        try {
            Map<String, Object> replanData = buildReplanData(graphThreadId, userInput);
            return resumeGraph(sessionId, graphThreadId, extState, userInput, replanData);
        } catch (Exception e) {
            log.warn("[OrchestrationAgent.handleWaitingUser] Level 3 buildReplanData failed: {}, falling to Level 4",
                e.getMessage());
        }

        // === Level 4: Terminal fallback (0ms) — pass full input as ANSWER ===
        log.info("[OrchestrationAgent.handleWaitingUser] Level 4: terminal fallback (pass as ANSWER)");
        Map<String, Object> fallbackData = new LinkedHashMap<>();
        fallbackData.put("messages", List.of(new UserMessage(userInput)));
        fallbackData.put(OrchestrationStateKeys.LATEST_USER_INPUT, userInput);
        fallbackData.put(OrchestrationStateKeys.WAITING_QUESTION, "");
        return resumeGraph(sessionId, graphThreadId, extState, userInput, fallbackData);
    }

    // ==================== Level 2: decomposeByIntent ====================

    /**
     * 调用LLM拆解用户输入为有序操作列表
     *
     * <p>使用 prompts/orch-answer-split.st 模板，传入：
     * <ul>
     *   <li>用户输入</li>
     *   <li>当前步骤列表（INTERRUPTED + PENDING，含intent/status/rewrittenInput/waitingQuestion）</li>
     *   <li>可用意图列表（从 routing.intents 读取）</li>
     * </ul>
     *
     * @return DecomposeResult，失败返回null（触发Level 3 fallback）
     */
    private DecomposeResult decomposeByIntent(String graphThreadId, String userInput) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(graphThreadId).build();
            var snapshot = orchestrationGraph.getState(config);
            if (snapshot == null || snapshot.state() == null) {
                log.warn("[OrchestrationAgent.decomposeByIntent] No graph state for threadId={}", graphThreadId);
                return null;
            }
            OverAllState graphState = snapshot.state();

            // 1. Read current steps (INTERRUPTED + PENDING, with intent/status/rewrittenInput/waitingQuestion)
            List<?> stepsRaw = (List<?>) graphState.value(OrchestrationStateKeys.STEPS).orElse(List.of());
            int currentIdx = (int) graphState.value(OrchestrationStateKeys.CURRENT_STEP_INDEX).orElse(0);
            String waitingQuestion = (String) graphState.value(OrchestrationStateKeys.WAITING_QUESTION).orElse("");

            StringBuilder stepList = new StringBuilder();
            for (int i = 0; i < stepsRaw.size(); i++) {
                Object obj = stepsRaw.get(i);
                if (obj instanceof OrchestrationStep step) {
                    stepList.append("- index=").append(i)
                        .append(", stepId=").append(step.getStepId())
                        .append(", intent=").append(step.getIntent())
                        .append(", domain=").append(step.getDomain())
                        .append(", status=").append(step.getStatus())
                        .append(", rewrittenInput=").append(step.getRewrittenInput());
                    if (i == currentIdx) {
                        stepList.append(" [当前中断步骤]");
                        if (waitingQuestion != null && !waitingQuestion.isEmpty()) {
                            stepList.append(", waitingQuestion=").append(waitingQuestion);
                        }
                    }
                    stepList.append("\n");
                }
            }

            // 2. Build intent list from routing.intents (SubGraphProperties)
            StringBuilder intentList = new StringBuilder();
            for (SubGraphProperties.SubGraphConfigProps intent : subGraphProperties.getIntents()) {
                intentList.append("- intent: ").append(intent.getName())
                    .append(", domain: ").append(intent.getDomain())
                    .append(", description: ").append(intent.getDescription())
                    .append(", type: ").append(intent.getIntentType() != null ? intent.getIntentType() : "UNKNOWN")
                    .append("\n");
            }

            // 3. Read prompt template from prompts/orch-answer-split.st
            String template = TemplateUtils.loadTemplate("prompts/orch-answer-split.st");
            String prompt = template
                .replace("{user_input}", userInput != null ? userInput : "")
                .replace("{step_list}", stepList.toString())
                .replace("{intent_list}", intentList.toString());

            // 4. Call ChatClient with rendered prompt
            long startMs = System.currentTimeMillis();
            String content = decomposeChatClient.prompt()
                .user(prompt)
                .call()
                .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[OrchestrationAgent.decomposeByIntent] LLM call completed in {}ms", elapsedMs);
            log.debug("[OrchestrationAgent.decomposeByIntent] LLM raw response: {}", content);

            if (content == null || content.isBlank()) {
                log.warn("[OrchestrationAgent.decomposeByIntent] Empty LLM response");
                return null;
            }

            // 5. Parse JSON response into DecomposeResult
            String json = JsonParseUtils.extractJson(content);
            var rootNode = objectMapper.readTree(json);

            DecomposeResult result = new DecomposeResult();
            if (rootNode.has("needsReplan") && rootNode.get("needsReplan").asBoolean()) {
                result.setNeedsReplan(true);
                log.info("[OrchestrationAgent.decomposeByIntent] LLM requested REPLAN");
                return result;
            }

            if (rootNode.has("operations")) {
                for (var opNode : rootNode.get("operations")) {
                    DecomposeOperation op = new DecomposeOperation();
                    op.setType(opNode.has("type") && !opNode.get("type").isNull() ? opNode.get("type").asText() : "");
                    op.setIntent(opNode.has("intent") && !opNode.get("intent").isNull() ? opNode.get("intent").asText() : null);
                    op.setContent(opNode.has("content") && !opNode.get("content").isNull() ? opNode.get("content").asText() : null);
                    op.setCondition(opNode.has("condition") && !opNode.get("condition").isNull() ? opNode.get("condition").asText() : null);
                    op.setDependsOnIntent(opNode.has("dependsOnIntent") && !opNode.get("dependsOnIntent").isNull() ? opNode.get("dependsOnIntent").asText() : null);
                    op.setAnswerIfNotMet(opNode.has("answerIfNotMet") && !opNode.get("answerIfNotMet").isNull() ? opNode.get("answerIfNotMet").asText() : null);
                    op.setOrder(opNode.has("order") && !opNode.get("order").isNull() ? opNode.get("order").asInt() : 1);
                    result.getOperations().add(op);
                }
            }

            if (result.getOperations().isEmpty()) {
                log.warn("[OrchestrationAgent.decomposeByIntent] No operations in LLM response");
                return null;
            }

            log.info("[OrchestrationAgent.decomposeByIntent] Parsed {} operations", result.getOperations().size());
            for (DecomposeOperation op : result.getOperations()) {
                log.info("[OrchestrationAgent.decomposeByIntent]   op: type={}, intent={}, order={}, content={}",
                    op.getType(), op.getIntent(), op.getOrder(),
                    op.getContent() != null && op.getContent().length() > 80 ? op.getContent().substring(0, 80) + "..." : op.getContent());
            }
            return result;
        } catch (Exception e) {
            log.warn("[OrchestrationAgent.decomposeByIntent] Failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Level 2: applyDecomposeOperations ====================

    /**
     * 将decomposeByIntent的操作列表应用到图状态
     *
     * <p>两遍处理：
     * <ol>
     *   <li>Pass 1: 创建所有INSERT步骤（生成stepId），应用CANCEL/CANCEL_ALL</li>
     *   <li>Pass 2: 解析dependsOnIntent → dependsOnStepId，应用ANSWER/MERGE/CONDITION</li>
     * </ol>
     *
     * <p>操作详情：
     * <ul>
     *   <li>ANSWER order=1: 设LATEST_USER_INPUT=content, CURRENT_STEP_INDEX=INTERRUPTED步骤的index</li>
     *   <li>ANSWER order>1: 在INTERRUPTED步骤上设ConditionalAnswer</li>
     *   <li>MERGE: 找PENDING步骤中intent匹配的，更新rewrittenInput</li>
     *   <li>INSERT: 从yml intent查找创建新OrchestrationStep，插入INTERRUPTED步骤之后</li>
     *   <li>CANCEL: 找intent匹配的步骤，有l2ThreadId→cancelSignal=true(归一化)，无→CANCELLED</li>
     *   <li>CANCEL_ALL: 所有非COMPLETED/SKIPPED步骤，有l2ThreadId→cancelSignal=true，无→CANCELLED</li>
     *   <li>CONDITION: 同ANSWER + 在INTERRUPTED步骤上设condition</li>
     * </ul>
     *
     * <p>应用完所有操作后，调用 orchestrationGraph.updateState() 修改图状态。
     */
    private void applyDecomposeOperations(String graphThreadId, String userInput, DecomposeResult result) throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId(graphThreadId).build();
        var snapshot = orchestrationGraph.getState(config);
        if (snapshot == null || snapshot.state() == null) {
            throw new IllegalStateException("No graph state for applyDecomposeOperations");
        }
        OverAllState graphState = snapshot.state();

        // Get current steps as mutable list
        @SuppressWarnings("unchecked")
        List<Object> stepsMutable = new ArrayList<>((List<?>) graphState.value(OrchestrationStateKeys.STEPS).orElse(List.of()));
        int currentIdx = (int) graphState.value(OrchestrationStateKeys.CURRENT_STEP_INDEX).orElse(0);
        String waitingQuestion = (String) graphState.value(OrchestrationStateKeys.WAITING_QUESTION).orElse("");

        // Find INTERRUPTED step
        OrchestrationStep interruptedStep = null;
        if (currentIdx >= 0 && currentIdx < stepsMutable.size()) {
            Object obj = stepsMutable.get(currentIdx);
            if (obj instanceof OrchestrationStep step) {
                interruptedStep = step;
                if (waitingQuestion != null && !waitingQuestion.isEmpty()) {
                    step.setWaitingQuestion(waitingQuestion);
                }
                step.setStatus(StepStatus.INTERRUPTED);
            }
        }

        // Sort operations by order
        List<DecomposeOperation> ops = new ArrayList<>(result.getOperations());
        ops.sort(Comparator.comparingInt(o -> o.getOrder() != 0 ? o.getOrder() : Integer.MAX_VALUE));

        // Get current L2_STEP_THREAD_MAP for cancel unification check
        @SuppressWarnings("unchecked")
        Map<String, String> existingStepThreadMap = (Map<String, String>) graphState
            .value(OrchestrationStateKeys.L2_STEP_THREAD_MAP).orElse(new LinkedHashMap<>());

        // === Pass 1: Create INSERT steps, apply CANCEL/CANCEL_ALL ===
        // Map: intent → newly inserted stepId (for dependsOnIntent resolution in Pass 2)
        Map<String, String> insertedIntentToStepId = new LinkedHashMap<>();
        List<DecomposeOperation> insertOps = new ArrayList<>();

        for (DecomposeOperation op : ops) {
            String type = op.getType();
            if (type == null) continue;

            switch (type) {
                case "CANCEL_ALL":
                    // Cancel all non-COMPLETED/SKIPPED steps — unified: has l2ThreadId → cancelSignal, else CANCELLED
                    for (Object o : stepsMutable) {
                        if (o instanceof OrchestrationStep s) {
                            if (s.getStatus() != StepStatus.COMPLETED && s.getStatus() != StepStatus.SKIPPED) {
                                String threadId = existingStepThreadMap.get(s.getStepId());
                                if (threadId != null && !threadId.isEmpty()) {
                                    // L2 subgraph active — set cancelSignal so L2GraphTool cancels it cleanly
                                    s.setCancelSignal(true);
                                    s.setRewrittenInput("用户取消该操作");
                                    s.setL2ThreadId(threadId);
                                    // Keep status as-is (INTERRUPTED/PENDING) so L2GraphTool can pick it up
                                    log.info("[OrchestrationAgent.applyDecomposeOperations] CANCEL_ALL cancelSignal: stepId={} intent={} l2ThreadId={}",
                                        s.getStepId(), s.getIntent(), threadId);
                                } else {
                                    // No L2 subgraph — mark CANCELLED directly, will be skipped
                                    s.setStatus(StepStatus.CANCELLED);
                                    s.setCancelReason("用户主动取消");
                                    log.info("[OrchestrationAgent.applyDecomposeOperations] CANCEL_ALL cancelled: stepId={} intent={}",
                                        s.getStepId(), s.getIntent());
                                }
                            }
                        }
                    }
                    break;
                case "CANCEL":
                    // Cancel a specific step by intent — find the matching step, not just interruptedStep.
                    if (op.getIntent() != null) {
                        // Find the step matching op.getIntent()
                        OrchestrationStep cancelTarget = null;
                        for (Object o : stepsMutable) {
                            if (o instanceof OrchestrationStep s
                                    && op.getIntent().equals(s.getIntent())
                                    && s.getStatus() != StepStatus.COMPLETED
                                    && s.getStatus() != StepStatus.SKIPPED
                                    && s.getStatus() != StepStatus.CANCELLED) {
                                cancelTarget = s;
                                break;
                            }
                        }
                        if (cancelTarget != null) {
                            String threadId = existingStepThreadMap.get(cancelTarget.getStepId());
                            if (threadId != null && !threadId.isEmpty()) {
                                // L2 subgraph active — set cancelSignal so L2GraphTool cancels it cleanly
                                cancelTarget.setCancelSignal(true);
                                cancelTarget.setRewrittenInput("用户取消该操作");
                                cancelTarget.setL2ThreadId(threadId);
                                log.info("[OrchestrationAgent.applyDecomposeOperations] CANCEL cancelSignal: stepId={} intent={} l2ThreadId={}",
                                    cancelTarget.getStepId(), cancelTarget.getIntent(), threadId);
                            } else {
                                // No L2 subgraph — mark CANCELLED directly, will be skipped
                                cancelTarget.setStatus(StepStatus.CANCELLED);
                                cancelTarget.setCancelReason("用户主动取消");
                                log.info("[OrchestrationAgent.applyDecomposeOperations] CANCEL cancelled: stepId={} intent={}",
                                    cancelTarget.getStepId(), cancelTarget.getIntent());
                            }
                        } else {
                            log.warn("[OrchestrationAgent.applyDecomposeOperations] CANCEL: no active step found for intent={}", op.getIntent());
                        }
                    } else if (interruptedStep != null) {
                        // No intent specified — cancel the interrupted step (backward compatible)
                        String threadId = existingStepThreadMap.get(interruptedStep.getStepId());
                        if (threadId != null && !threadId.isEmpty()) {
                            interruptedStep.setCancelSignal(true);
                            interruptedStep.setRewrittenInput("用户取消该操作");
                            interruptedStep.setL2ThreadId(threadId);
                            log.info("[OrchestrationAgent.applyDecomposeOperations] CANCEL cancelSignal (no intent): stepId={} intent={} l2ThreadId={}",
                                interruptedStep.getStepId(), interruptedStep.getIntent(), threadId);
                        } else {
                            interruptedStep.setStatus(StepStatus.CANCELLED);
                            interruptedStep.setCancelReason("用户主动取消");
                            log.info("[OrchestrationAgent.applyDecomposeOperations] CANCEL cancelled (no intent): stepId={} intent={}",
                                interruptedStep.getStepId(), interruptedStep.getIntent());
                        }
                    }
                    break;
                case "INSERT":
                    // Check if intent already exists as INTERRUPTED or PENDING step
                    // If so, convert to ANSWER (INTERRUPTED) or MERGE (PENDING) instead of creating duplicate
                    String existingIntent = op.getIntent();
                    boolean converted = false;
                    if (existingIntent != null) {
                        for (Object o : stepsMutable) {
                            if (o instanceof OrchestrationStep s) {
                                if (existingIntent.equals(s.getIntent())) {
                                    if (s.getStatus() == StepStatus.INTERRUPTED && interruptedStep != null
                                            && s.getStepId().equals(interruptedStep.getStepId())) {
                                        // Same intent as interrupted step → convert to ANSWER
                                        op.setType("ANSWER");
                                        converted = true;
                                        log.info("[OrchestrationAgent.applyDecomposeOperations] INSERT→ANSWER conversion: intent={} matches interrupted step",
                                            existingIntent);
                                        break;
                                    } else if (s.getStatus() == StepStatus.PENDING) {
                                        // Same intent as PENDING step → convert to MERGE
                                        op.setType("MERGE");
                                        converted = true;
                                        log.info("[OrchestrationAgent.applyDecomposeOperations] INSERT→MERGE conversion: intent={} matches pending step",
                                            existingIntent);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (!converted) {
                        insertOps.add(op);
                    }
                    break;
                default:
                    break;
            }
        }

        // Insert INSERT steps after INTERRUPTED step, before existing PENDING steps
        insertOps.sort(Comparator.comparingInt(o -> o.getOrder() != 0 ? o.getOrder() : Integer.MAX_VALUE));
        int insertPos = currentIdx + 1; // after INTERRUPTED step
        int firstInsertIndex = -1;
        String lastInsertedStepIdForAutoResume = null; // for ConditionalAnswer dependsOn
        for (DecomposeOperation op : insertOps) {
            OrchestrationStep newStep = createStepFromIntent(op.getIntent(), op.getContent(), interruptedStep);
            if (newStep == null) {
                log.warn("[OrchestrationAgent.applyDecomposeOperations] Cannot create step for intent={}", op.getIntent());
                continue;
            }
            stepsMutable.add(insertPos, newStep);
            if (firstInsertIndex < 0) firstInsertIndex = insertPos;
            insertedIntentToStepId.put(op.getIntent(), newStep.getStepId());
            lastInsertedStepIdForAutoResume = newStep.getStepId();
            insertPos++;
            log.info("[OrchestrationAgent.applyDecomposeOperations] INSERT step: stepId={}, intent={}, content={}",
                newStep.getStepId(), newStep.getIntent(), newStep.getRewrittenInput());
        }

        // Re-index all steps
        for (int i = 0; i < stepsMutable.size(); i++) {
            Object obj = stepsMutable.get(i);
            if (obj instanceof OrchestrationStep s) {
                s.setIndex(i);
            }
        }

        // === Pass 2: Apply ANSWER, MERGE, CONDITION (resolve dependsOnIntent → dependsOnStepId) ===
        String answerContent = null;  // for ANSWER order=1
        boolean hasImmediateAnswer = false;

        // === INSERT auto-resume: when user inserts new intent during INTERRUPTED step,
        // the new intent executes first, then the INTERRUPTED step auto-resumes.
        // This matches replan semantics: "先做新的，再回到原来的"。
        // Set ConditionalAnswer (unconditional) on INTERRUPTED step, dependsOn last INSERT step.
        // When INSERT completes, conditionCheckNode Phase 2D detects this and auto-resumes INTERRUPTED,
        // ensuring INTERRUPTED step resumes before other PENDING steps (e.g. TRANSFER).
        if (interruptedStep != null && !insertOps.isEmpty() && !hasImmediateAnswer
                && interruptedStep.getConditionalAnswer() == null) {
            ConditionalAnswer autoResume = new ConditionalAnswer();
            autoResume.setCondition(null); // unconditional — always resume after INSERT completes
            autoResume.setAnswerIfMet(interruptedStep.getRewrittenInput()); // keep original input
            autoResume.setCancelIfNotMet(false);
            autoResume.setDependsOnStepId(lastInsertedStepIdForAutoResume);
            interruptedStep.setConditionalAnswer(autoResume);
            log.info("[OrchestrationAgent.applyDecomposeOperations] Auto-resume ConditionalAnswer set on INTERRUPTED step: stepId={}, dependsOnStepId={} — INSERT completes → INTERRUPTED auto-resumes",
                interruptedStep.getStepId(), lastInsertedStepIdForAutoResume);
        }

        for (DecomposeOperation op : ops) {
            String type = op.getType();
            if (type == null) continue;

            switch (type) {
                case "ANSWER":
                case "CONDITION":
                    // Validate: ANSWER intent must match the interrupted step's intent
                    if (interruptedStep != null && op.getIntent() != null
                            && !op.getIntent().equals(interruptedStep.getIntent())) {
                        log.warn("[OrchestrationAgent.applyDecomposeOperations] ANSWER intent={} does not match interrupted step intent={}, skipping",
                            op.getIntent(), interruptedStep.getIntent());
                        break;
                    }
                    if (op.getOrder() <= 1) {
                        // Immediate answer — set LATEST_USER_INPUT=content, CURRENT_STEP_INDEX=INTERRUPTED step's index
                        answerContent = op.getContent();
                        hasImmediateAnswer = true;
                        // Update INTERRUPTED step's rewrittenInput with answer content
                        // (StepPreparator reads rewrittenInput, not LATEST_USER_INPUT)
                        if (interruptedStep != null && op.getContent() != null) {
                            interruptedStep.setRewrittenInput(op.getContent());
                        }
                        // For CONDITION, also set ConditionalAnswer on INTERRUPTED step
                        if ("CONDITION".equals(type) && interruptedStep != null) {
                            ConditionalAnswer ca = new ConditionalAnswer();
                            ca.setCondition(op.getCondition());
                            ca.setAnswerIfMet(op.getContent());
                            ca.setCancelIfNotMet(false);
                            String depStepId = resolveDependsOnStepId(op.getDependsOnIntent(),
                                insertedIntentToStepId, stepsMutable);
                            ca.setDependsOnStepId(depStepId);
                            interruptedStep.setConditionalAnswer(ca);
                            log.info("[OrchestrationAgent.applyDecomposeOperations] Set ConditionalAnswer on stepId={}: condition={}, answerIfMet={}, dependsOnStepId={}",
                                interruptedStep.getStepId(), ca.getCondition(), ca.getAnswerIfMet(), ca.getDependsOnStepId());
                        }
                    } else {
                        // Delayed answer (order>1)
                        if (interruptedStep != null) {
                            // Always use ConditionalAnswer mechanism — even for unconditional answers.
                            // condition=null means condition is always met (evaluateCondition returns true).
                            // dependsOnStepId points to the last INSERT step, so the answer is
                            // applied when that step completes (PENDING steps run before INTERRUPTED).
                            ConditionalAnswer ca = new ConditionalAnswer();
                            ca.setCondition(op.getCondition());  // null for unconditional → always met
                            ca.setAnswerIfMet(op.getContent());
                            ca.setCancelIfNotMet(false);
                            // Resolve dependsOnStepId: use op's dependsOnIntent if provided,
                            // otherwise default to the LAST inserted step
                            String depStepId = resolveDependsOnStepId(op.getDependsOnIntent(),
                                insertedIntentToStepId, stepsMutable);
                            if (depStepId == null && !insertedIntentToStepId.isEmpty()) {
                                // No explicit dependsOnIntent — depend on last INSERT step
                                String lastInsertedStepId = insertedIntentToStepId.values().stream()
                                    .reduce((first, second) -> second).orElse(null);
                                depStepId = lastInsertedStepId;
                            }
                            ca.setDependsOnStepId(depStepId);
                            interruptedStep.setConditionalAnswer(ca);
                            // Also update rewrittenInput so StepPreparator has the answer
                            if (op.getContent() != null) {
                                interruptedStep.setRewrittenInput(op.getContent());
                            }
                            log.info("[OrchestrationAgent.applyDecomposeOperations] Delayed answer (condition={}): stepId={}, answerIfMet={}, dependsOnStepId={}",
                                op.getCondition(), interruptedStep.getStepId(), ca.getAnswerIfMet(), ca.getDependsOnStepId());
                        }
                    }
                    break;
                case "MERGE":
                    // Find PENDING/INTERRUPTED step with matching intent, update rewrittenInput with content
                    // Also preserve condition/dependsOn/answerIfNotMet if present (e.g. from INSERT→MERGE conversion)
                    if (op.getIntent() != null) {
                        for (Object o : stepsMutable) {
                            if (o instanceof OrchestrationStep s) {
                                if (op.getIntent().equals(s.getIntent())
                                        && (s.getStatus() == StepStatus.PENDING || s.getStatus() == StepStatus.INTERRUPTED)) {
                                    // Merge rewrittenInput: if op.content is a partial update,
                                    // combine with existing input; otherwise use op.content as-is
                                    String existing = s.getRewrittenInput() != null ? s.getRewrittenInput() : "";
                                    String merged;
                                    if (op.getContent() != null && !op.getContent().isBlank()) {
                                        // If existing input has product/context info that op.content lacks,
                                        // prepend existing context to avoid losing info
                                        if (!existing.isEmpty() && !op.getContent().contains(existing)
                                                && !existing.contains(op.getContent())) {
                                            // Semantic merge: existing provides context, op provides update
                                            // e.g. existing="买入一些朝朝盈" + content="买入600股" → "买600股朝朝盈"
                                            // We trust the LLM's content to be the merged result if it's rich enough;
                                            // otherwise, concatenate with context preservation
                                            merged = existing + "，" + op.getContent();
                                        } else {
                                            merged = op.getContent();
                                        }
                                    } else {
                                        merged = existing;
                                    }
                                    s.setRewrittenInput(merged);
                                    log.info("[OrchestrationAgent.applyDecomposeOperations] MERGE stepId={}: rewrittenInput='{}' → '{}'",
                                        s.getStepId(), existing, merged);

                                    // Preserve condition/dependsOn if present (from INSERT→MERGE conversion)
                                    if (op.getCondition() != null && !op.getCondition().isBlank()) {
                                        s.setCondition(op.getCondition());
                                        String depStepId = resolveDependsOnStepId(op.getDependsOnIntent(),
                                            insertedIntentToStepId, stepsMutable);
                                        if (depStepId != null) {
                                            s.setDependsOnStepIndex(depStepId);
                                        }
                                        log.info("[OrchestrationAgent.applyDecomposeOperations] MERGE condition on stepId={}: condition={}, dependsOnStepId={}",
                                            s.getStepId(), op.getCondition(), depStepId);
                                    }
                                    // Preserve answerIfNotMet if present — store as ConditionalAnswer on the step
                                    if (op.getCondition() != null && !op.getCondition().isBlank()
                                            && op.getAnswerIfNotMet() != null && !op.getAnswerIfNotMet().isBlank()) {
                                        ConditionalAnswer ca = new ConditionalAnswer();
                                        ca.setCondition(op.getCondition());
                                        ca.setAnswerIfMet(merged);
                                        ca.setCancelIfNotMet(true); // condition not met → skip this step
                                        String depStepId = resolveDependsOnStepId(op.getDependsOnIntent(),
                                            insertedIntentToStepId, stepsMutable);
                                        ca.setDependsOnStepId(depStepId);
                                        s.setConditionalAnswer(ca);
                                        log.info("[OrchestrationAgent.applyDecomposeOperations] MERGE ConditionalAnswer on stepId={}: condition={}, answerIfMet={}, cancelIfNotMet=true, dependsOnStepId={}",
                                            s.getStepId(), ca.getCondition(), ca.getAnswerIfMet(), ca.getDependsOnStepId());
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        // === Re-sort PENDING steps after INTERRUPTED step based on LLM's order field ===
        // When decompose produces a mix of INSERT (new steps at currentIdx+1) and MERGE (existing steps at original position),
        // the list index may not reflect the user's intended execution order.
        // E.g. MERGE(PURCHASE, order=2) stays at index 3, INSERT(INTERPRET, order=3) inserted at index 2
        //      → INTERPRET executes before PURCHASE, but user said PURCHASE first.
        // Fix: re-sort PENDING steps after INTERRUPTED step by the LLM's `order` field.
        Map<String, Integer> stepOrderMap = new LinkedHashMap<>();
        for (DecomposeOperation op : ops) {
            if (op.getOrder() > 0 && op.getType() != null) {
                switch (op.getType()) {
                    case "INSERT":
                        String insertStepId = insertedIntentToStepId.get(op.getIntent());
                        if (insertStepId != null) {
                            stepOrderMap.put(insertStepId, op.getOrder());
                        }
                        break;
                    case "MERGE":
                        if (op.getIntent() != null) {
                            for (Object o : stepsMutable) {
                                if (o instanceof OrchestrationStep s
                                        && op.getIntent().equals(s.getIntent())
                                        && (s.getStatus() == StepStatus.PENDING || s.getStatus() == StepStatus.INTERRUPTED)) {
                                    stepOrderMap.put(s.getStepId(), op.getOrder());
                                    break;
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        if (!stepOrderMap.isEmpty() && currentIdx < stepsMutable.size() - 1) {
            List<Object> beforeCurrent = new ArrayList<>(stepsMutable.subList(0, currentIdx + 1));
            List<Object> afterCurrent = new ArrayList<>(stepsMutable.subList(currentIdx + 1, stepsMutable.size()));

            afterCurrent.sort((a, b) -> {
                OrchestrationStep sa = a instanceof OrchestrationStep ? (OrchestrationStep) a : null;
                OrchestrationStep sb = b instanceof OrchestrationStep ? (OrchestrationStep) b : null;
                if (sa == null || sb == null) return 0;
                Integer orderA = stepOrderMap.get(sa.getStepId());
                Integer orderB = stepOrderMap.get(sb.getStepId());
                if (orderA != null && orderB != null) return orderA.compareTo(orderB);
                // At least one step not in this decompose batch — keep current relative position
                return Integer.compare(sa.getIndex(), sb.getIndex());
            });

            stepsMutable.clear();
            stepsMutable.addAll(beforeCurrent);
            stepsMutable.addAll(afterCurrent);

            // Re-index all steps after re-sort
            for (int i = 0; i < stepsMutable.size(); i++) {
                Object obj = stepsMutable.get(i);
                if (obj instanceof OrchestrationStep s) {
                    s.setIndex(i);
                }
            }

            // Update firstInsertIndex: it may have moved after re-sort
            if (firstInsertIndex >= 0) {
                for (int i = currentIdx + 1; i < stepsMutable.size(); i++) {
                    Object obj = stepsMutable.get(i);
                    if (obj instanceof OrchestrationStep s && insertedIntentToStepId.containsValue(s.getStepId())) {
                        firstInsertIndex = i;
                        break;
                    }
                }
            }

            log.info("[OrchestrationAgent.applyDecomposeOperations] Re-sorted steps by user order: stepOrderMap={}", stepOrderMap);
        }

        // === Sanity check: did any operation actually take effect? ===
        // If all operations referenced unknown intents or matched nothing,
        // no state change happened — throw to trigger Level 3 replan.
        boolean anyApplied = hasImmediateAnswer || firstInsertIndex >= 0;
        boolean hasCancelSignalStep = false;
        if (!anyApplied) {
            // Check if CANCEL/CANCEL_ALL or MERGE actually modified anything
            for (Object o : stepsMutable) {
                if (o instanceof OrchestrationStep s) {
                    if (s.getStatus() == StepStatus.CANCELLED
                            || s.isCancelSignal()
                            || (s.getConditionalAnswer() != null && s.getStatus() == StepStatus.INTERRUPTED)) {
                        anyApplied = true;
                        if (s.isCancelSignal()) {
                            hasCancelSignalStep = true;
                        }
                    }
                }
            }
        } else {
            // Also check for cancelSignal steps when ANSWER/INSERT exist
            for (Object o : stepsMutable) {
                if (o instanceof OrchestrationStep s && s.isCancelSignal()) {
                    hasCancelSignalStep = true;
                    break;
                }
            }
        }
        if (!anyApplied) {
            throw new IllegalStateException(
                "No decompose operations could be applied (all intents unknown or no matching steps)");
        }

        // === Update graph state ===
        Map<String, Object> updateData = new LinkedHashMap<>();
        updateData.put(OrchestrationStateKeys.STEPS, stepsMutable);

        // Find first cancelSignal step index (for routing when no ANSWER/INSERT)
        // Skip COMPLETED/SKIPPED/CANCELLED steps — their cancelSignal is stale (already processed by L2GraphTool)
        int firstCancelSignalIndex = -1;
        String firstCancelSignalStepId = null;
        for (Object o : stepsMutable) {
            if (o instanceof OrchestrationStep s && s.isCancelSignal()
                    && s.getStatus() != StepStatus.COMPLETED
                    && s.getStatus() != StepStatus.SKIPPED
                    && s.getStatus() != StepStatus.CANCELLED) {
                firstCancelSignalIndex = s.getIndex();
                firstCancelSignalStepId = s.getStepId();
                break;
            }
        }

        if (hasImmediateAnswer) {
            // ANSWER order=1: resume INTERRUPTED step with answer content
            updateData.put(OrchestrationStateKeys.LATEST_USER_INPUT, answerContent);
            updateData.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, currentIdx);
            updateData.put(OrchestrationStateKeys.WAITING_QUESTION, "");
            updateData.put(OrchestrationStateKeys.WAITING_STEP_ID, "");
            updateData.put("messages", List.of(new UserMessage(answerContent)));
        } else if (firstInsertIndex >= 0) {
            // No immediate answer but INSERTs exist — execute first INSERT step
            // MUST clear WAITING_STEP_ID: new INSERT steps have no L2 subgraph to resume.
            // Without this, L2GraphTool reads old WAITING_STEP_ID (e.g. cancelled WEALTH_PURCHASE's s-1),
            // finds the old L2 threadId, and tries to resume a wrong subgraph with wrong intent → ERROR.
            updateData.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, firstInsertIndex);
            updateData.put(OrchestrationStateKeys.WAITING_QUESTION, "");
            updateData.put(OrchestrationStateKeys.WAITING_STEP_ID, "");
            updateData.put(OrchestrationStateKeys.LATEST_USER_INPUT, userInput);
            updateData.put("messages", List.of(new UserMessage(userInput)));
        } else if (firstCancelSignalIndex >= 0) {
            // No ANSWER/INSERT but cancelSignal steps exist — route to cancel step so L2GraphTool can
            // execute executeCancelStep() → GES.cancelGraph() → clean L2 termination
            updateData.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, firstCancelSignalIndex);
            updateData.put(OrchestrationStateKeys.WAITING_QUESTION, "");
            updateData.put(OrchestrationStateKeys.WAITING_STEP_ID, "");
            updateData.put(OrchestrationStateKeys.LATEST_USER_INPUT, userInput);
            updateData.put("messages", List.of(new UserMessage(userInput)));
            log.info("[OrchestrationAgent.applyDecomposeOperations] Routing to cancelSignal step: index={}, stepId={}",
                firstCancelSignalIndex, firstCancelSignalStepId);
        } else {
            // No ANSWER, no INSERT, no cancelSignal (e.g. MERGE-only, pure CANCEL of PENDING) — clear WAITING_QUESTION, let graph route
            updateData.put(OrchestrationStateKeys.WAITING_QUESTION, "");
            updateData.put(OrchestrationStateKeys.WAITING_STEP_ID, "");
            updateData.put(OrchestrationStateKeys.LATEST_USER_INPUT, userInput);
            updateData.put("messages", List.of(new UserMessage(userInput)));
        }

        // Clean up: remove threadId entries for truly CANCELLED steps (no L2 to cancel, won't resume)
        // But KEEP threadId entries for cancelSignal steps — L2GraphTool needs them to call GES.cancelGraph()
        @SuppressWarnings("unchecked")
        Map<String, String> stepThreadMap = (Map<String, String>) graphState.value(OrchestrationStateKeys.L2_STEP_THREAD_MAP).orElse(null);
        if (stepThreadMap != null) {
            Set<String> cancelledStepIds = new LinkedHashSet<>();
            for (Object o : stepsMutable) {
                if (o instanceof OrchestrationStep s && s.getStatus() == StepStatus.CANCELLED) {
                    // Only remove truly CANCELLED steps (no active L2 subgraph)
                    // cancelSignal steps keep their threadId for L2GraphTool cancel flow
                    cancelledStepIds.add(s.getStepId());
                }
            }
            if (!cancelledStepIds.isEmpty()) {
                // Must create a mutable copy for removal, then put into updateData
                Map<String, String> mutableMap = new LinkedHashMap<>(stepThreadMap);
                mutableMap.keySet().removeAll(cancelledStepIds);
                updateData.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, mutableMap);
                log.info("[OrchestrationAgent.applyDecomposeOperations] Cleaned {} cancelled stepIds from L2_STEP_THREAD_MAP",
                    cancelledStepIds.size());
            }
        }

        orchestrationGraph.updateState(config, updateData, null);
        log.info("[OrchestrationAgent.applyDecomposeOperations] Updated graph state: steps={}, hasImmediateAnswer={}, firstInsertIndex={}, currentIdx={}",
            stepsMutable.size(), hasImmediateAnswer, firstInsertIndex, currentIdx);
    }

    /**
     * Resolve dependsOnIntent to a concrete stepId.
     * Supports comma-separated intents (e.g., "BILL_QUERY,WEALTH_CONSULT") →
     * comma-separated stepIds (e.g., "s-0,s-1").
     *
     * Checks newly inserted steps first, then existing steps with matching intent.
     */
    private String resolveDependsOnStepId(String dependsOnIntent,
                                            Map<String, String> insertedIntentToStepId,
                                            List<Object> stepsMutable) {
        if (dependsOnIntent == null || dependsOnIntent.isEmpty()) return null;

        // Single intent — fast path
        if (!dependsOnIntent.contains(",")) {
            return resolveSingleDependsOnIntent(dependsOnIntent, insertedIntentToStepId, stepsMutable);
        }

        // Comma-separated intents — resolve each and join
        String[] intents = dependsOnIntent.split("\\s*,\\s*");
        List<String> resolvedStepIds = new ArrayList<>();
        for (String intent : intents) {
            if (intent.isEmpty()) continue;
            String stepId = resolveSingleDependsOnIntent(intent, insertedIntentToStepId, stepsMutable);
            if (stepId != null) {
                resolvedStepIds.add(stepId);
            } else {
                log.warn("[OrchestrationAgent.resolveDependsOnStepId] Cannot resolve intent: {}", intent);
            }
        }
        return resolvedStepIds.isEmpty() ? null : String.join(",", resolvedStepIds);
    }

    /**
     * Resolve a single intent to its stepId.
     */
    private String resolveSingleDependsOnIntent(String intent,
                                                  Map<String, String> insertedIntentToStepId,
                                                  List<Object> stepsMutable) {
        // Check newly inserted steps first
        String stepId = insertedIntentToStepId.get(intent);
        if (stepId != null) return stepId;

        // Check existing steps
        for (Object o : stepsMutable) {
            if (o instanceof OrchestrationStep s) {
                if (intent.equals(s.getIntent())) {
                    return s.getStepId();
                }
            }
        }
        return null;
    }

    /**
     * Create a new OrchestrationStep from yml intent lookup.
     * Looks up intent in routing.intents (SubGraphProperties) to get domain/description.
     */
    private OrchestrationStep createStepFromIntent(String intentName, String content,
                                                     OrchestrationStep interruptedStep) {
        if (intentName == null || intentName.isEmpty()) return null;

        SubGraphProperties.SubGraphConfigProps intentConfig = null;
        for (SubGraphProperties.SubGraphConfigProps intent : subGraphProperties.getIntents()) {
            if (intentName.equals(intent.getName())) {
                intentConfig = intent;
                break;
            }
        }
        if (intentConfig == null) {
            log.warn("[OrchestrationAgent.createStepFromIntent] Intent not found in routing.intents: {}", intentName);
            return null;
        }

        OrchestrationStep step = new OrchestrationStep();
        step.setStepId("s-ins-" + Integer.toHexString((int)(System.nanoTime() & 0xFFFFFF)));
        step.setDomain(intentConfig.getDomain());
        step.setIntent(intentConfig.getName());
        step.setDescription(intentConfig.getDescription());
        step.setRewrittenInput(content);
        step.setStatus(StepStatus.PENDING);
        return step;
    }

    // ==================== Level 3: buildReplanData ====================

    /**
     * 构建replan fallback数据
     *
     * <p>清空orch_plan，设ORCH_STATUS=REPLANNING，清WAITING_QUESTION/AUTO_RESUME_*，
     * 设messages为原始请求+新输入。
     */
    private Map<String, Object> buildReplanData(String graphThreadId, String userInput) {
        // Read original request from graph state
        String originalRequest = "";
        try {
            RunnableConfig cfg = RunnableConfig.builder().threadId(graphThreadId).build();
            var snap = orchestrationGraph.getState(cfg);
            if (snap != null && snap.state() != null) {
                originalRequest = snap.state().value(OrchestrationStateKeys.ORIGINAL_REQUEST, "");
            }
        } catch (Exception e) {
            log.debug("[OrchestrationAgent.buildReplanData] Failed to read ORIGINAL_REQUEST: {}", e.getMessage());
        }

        String replanInput;
        if (originalRequest != null && !originalRequest.isEmpty() && !originalRequest.equals(userInput)) {
            replanInput = "原始请求: " + originalRequest + "\n新请求: " + userInput;
        } else {
            replanInput = userInput;
        }

        Map<String, Object> replanData = new LinkedHashMap<>();
        replanData.put("messages", List.of(new UserMessage(replanInput)));
        replanData.put(OrchestrationStateKeys.LATEST_USER_INPUT, replanInput);
        replanData.put(OrchestrationStateKeys.WAITING_QUESTION, "");
        replanData.put(OrchestrationStateKeys.AUTO_RESUME_STEP_ID, "");
        replanData.put(OrchestrationStateKeys.AUTO_RESUME_ANSWER, "");
        replanData.put(OrchestrationStateKeys.ORCH_STATUS, "REPLANNING");
        replanData.put("orch_plan", ""); // clear plan to force planNode to re-plan

        log.info("[OrchestrationAgent.buildReplanData] Built replan data: replanInput='{}'", replanInput);
        return replanData;
    }

    // ==================== resumeGraph helper ====================

    /**
     * Common graph resume pattern — re-register sink, call graphResponseStream,
     * merge with sink flux, extractFinalResult.
     *
     * @param resumeData state updates to inject on resume (null = state already updated via updateState)
     */
    private Flux<StreamChunk> resumeGraph(String sessionId, String graphThreadId,
                                            OrchestrationStateService.OrchestrationState extState,
                                            String userInput, Map<String, Object> resumeData) {
        // Update external state
        extState.setStatus(OrchestrationStatus.EXECUTING);
        stateService.saveState(sessionId, extState);

        RunnableConfig config = RunnableConfig.builder().threadId(graphThreadId).build();

        // Re-register sink for this threadId (was unregistered after previous interrupt completed)
        if (sinkRegistry.getSink(graphThreadId) == null) {
            sinkRegistry.register(graphThreadId);
        }

        Flux<StreamChunk> graphFlux = orchestrationGraph.graphResponseStream(resumeData, config)
            .mapNotNull(response -> handleGraphResponse(response, sessionId))
            .doOnComplete(() -> {
                Sinks.Many<StreamChunk> sink = sinkRegistry.getSink(graphThreadId);
                if (sink != null) sink.tryEmitComplete();
                log.info("[OrchestrationAgent] Graph stream completed (resume), sink completed for threadId={}", graphThreadId);
            })
            .doOnError(e -> {
                Sinks.Many<StreamChunk> sink = sinkRegistry.getSink(graphThreadId);
                if (sink != null) sink.tryEmitComplete();
            });

        return Flux.merge(graphFlux, sinkRegistry.getFlux(graphThreadId))
            .concatWith(Flux.defer(() -> {
                StreamChunk finalChunk = extractFinalResult(sessionId, graphThreadId);
                sinkRegistry.unregister(graphThreadId);
                return finalChunk != null ? Flux.just(finalChunk) : Flux.empty();
            }));
    }

    // ==================== 编排生命周期 ====================

    /**
     * 启动新编排 — 创建状态、构建输入、调用 StateGraph
     *
     * <p>L1上下文领养流程（adopt-l1-context=true时）：
     * <ol>
     *   <li>scan: 遍历 DomainServiceRegistry → handler.collectL1Context()</li>
     *   <li>classify: 用 DomainRouter.findDomainsInInput() 判定继承/不继承</li>
     *   <li>enrich: 继承的上下文拼入 PlannerAgent 的 ORIGINAL_REQUEST</li>
     *   <li>release: 只释放继承的L1资源，不继承的保留（用户可resume）</li>
     * </ol>
     */
    private Flux<StreamChunk> startOrchestration(String sessionId, String userInput) {
        log.info("[OrchestrationAgent.startOrchestration] Session={}, input={}", sessionId, userInput);

        // === L1 Context Scan: collect ALL interrupted L1 subgraphs across all domains ===
        // Pass userInput for cancel signal detection — deferred release: cancelled L1 intents
        // are NOT immediately released; instead stored in graph state for StepPreparator to handle
        L1InterruptScanResult l1ScanResult = scanL1Interrupts(sessionId, userInput);

        // 创建外部状态视图
        OrchestrationStateService.OrchestrationState state = new OrchestrationStateService.OrchestrationState();
        state.setStatus(OrchestrationStatus.PLANNING);
        stateService.saveState(sessionId, state);

        // 构建 StateGraph 输入 — userInput 给 PlannerAgent
        // If L1 interrupts exist, append summary so PlannerAgent knows which intents are already interrupted
        String plannerInput = userInput;
        String l1InterruptSummary = l1ScanResult.getSummary();
        if (l1InterruptSummary != null && !l1InterruptSummary.isEmpty()) {
            plannerInput = userInput + "\n\n" + l1InterruptSummary;
            log.info("[OrchestrationAgent.startOrchestration] Injected L1 interrupt context into PlannerAgent input");
        }

        // Inject conversation history from GlobalSessionContext so PlannerAgent can resolve
        // references like "刚才推荐的" — needs to know what was recommended in previous turns
        int orchMaxMessages = orchestrationProperties.getGlobal().getOrchMaxMessages();
        String conversationHistory = globalSessionStore.getOrCreate(sessionId)
            .formatRecentItems(orchMaxMessages);
        if (conversationHistory != null && !"(无历史对话)".equals(conversationHistory)) {
            plannerInput = plannerInput + "\n\n【对话历史】\n" + conversationHistory;
            log.info("[OrchestrationAgent.startOrchestration] Injected {} messages of conversation history into PlannerAgent input",
                orchMaxMessages);
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("messages", List.of(new UserMessage(plannerInput)));
        inputs.put(OrchestrationStateKeys.ORIGINAL_REQUEST, plannerInput);
        inputs.put(OrchestrationStateKeys.LATEST_USER_INPUT, userInput);
        inputs.put(OrchestrationStateKeys.ORCH_STATUS, "PLANNING");
        inputs.put(OrchestrationStateKeys.SESSION_ID, sessionId);

        // Store cancelled L1 intents in graph state inputs — StepPreparator will consume them
        // to mark matching steps as CANCELLED and release L1 resources (abort checkpoint + clear ActiveAgentInfo)
        if (!l1ScanResult.getCancelledL1Intents().isEmpty()) {
            List<Map<String, String>> cancelledList = l1ScanResult.getCancelledL1Intents().stream()
                .map(c -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("intent", c.getIntent());
                    m.put("domain", c.getDomain());
                    m.put("threadId", c.getThreadId());
                    return m;
                })
                .toList();
            inputs.put(OrchestrationStateKeys.CANCELLED_L1_INTENTS, cancelledList);
            log.info("[OrchestrationAgent.startOrchestration] Stored {} cancelled L1 intents in graph state inputs",
                cancelledList.size());
        }

        // 每次新编排用唯一 threadId
        String threadId = sessionId + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[OrchestrationAgent.startOrchestration] Using fresh threadId={}", threadId);

        state.setGraphThreadId(threadId);
        stateService.saveState(sessionId, state);

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        // Register sink for L2 streaming chunks
        Sinks.Many<StreamChunk> sink = sinkRegistry.register(threadId);

        // Main graph stream — when it completes, also complete the sink to unblock mergeWith
        Flux<StreamChunk> graphFlux = orchestrationGraph.graphResponseStream(inputs, config)
            .mapNotNull(response -> handleGraphResponse(response, sessionId))
            .doOnComplete(() -> {
                sink.tryEmitComplete();
                log.info("[OrchestrationAgent] Graph stream completed, sink completed for threadId={}", threadId);
            })
            .doOnError(e -> {
                sink.tryEmitComplete();
            });

        return Flux.merge(graphFlux, sinkRegistry.getFlux(threadId))
            .concatWith(Flux.defer(() -> {
                StreamChunk finalChunk = extractFinalResult(sessionId, threadId);
                sinkRegistry.unregister(threadId);
                return finalChunk != null ? Flux.just(finalChunk) : Flux.empty();
            }));
    }

    // ==================== L1 Interrupt Scan ====================

    /**
     * 扫描所有 domain handler 的 L1 中断上下文，构建摘要供 PlannerAgent 感知。
     *
     * <p>遍历 DomainServiceRegistry 中所有 handler，调用 collectL1Context(sessionId)，
     * 汇总所有 ActiveContext（含 domain, intent, lastQuestion, userMessages），
     * 生成结构化摘要字符串注入 PlannerAgent 输入。
     *
     * @return L1 中断摘要字符串，无中断时返回 null
     */
    /**
     * 扫描所有 domain handler 的 L1 中断上下文，构建摘要供 PlannerAgent 感知。
     *
     * <p>遍历 DomainServiceRegistry 中所有 handler，调用 collectL1Context(sessionId)，
     * 汇总所有 ActiveContext（含 domain, intent, lastQuestion, userMessages），
     * 生成结构化摘要字符串注入 PlannerAgent 输入。
     *
     * <p>取消信号检测：如果 userInput 包含取消词（算了/取消/不要了/不X了/放弃），
     * 则认为用户要放弃所有当前中断的 L1 意图，立即释放资源并排除出摘要。
     * Planner 只会看到未被取消的 L1 中断（应领养的意图）。
     *
     * @param sessionId session ID
     * @param userInput 用户当前输入（用于取消信号检测），null时不检测取消
     * @return L1 中断摘要字符串，无中断时返回 null
     */
    /**
     * L1 取消意图领养结果 — scanL1Interrupts 检测到取消信号时返回，
     * startOrchestration 将其存入 graph state，StepPreparator 消费后释放 L1 资源。
     */
    @Data
    static class CancelledL1Intent {
        private final String intent;
        private final String domain;
        private final String threadId;
    }

    /**
     * scanL1Interrupts 结果 — 同时返回摘要字符串和取消意图数据
     */
    @Data
    static class L1InterruptScanResult {
        private final String summary;
        private final List<CancelledL1Intent> cancelledL1Intents;

        L1InterruptScanResult(String summary, List<CancelledL1Intent> cancelledL1Intents) {
            this.summary = summary;
            this.cancelledL1Intents = cancelledL1Intents != null ? cancelledL1Intents : List.of();
        }
    }

    private L1InterruptScanResult scanL1Interrupts(String sessionId, String userInput) {
        boolean hasCancelSignal = detectCancelSignal(userInput);

        List<L1ContextSnapshot.ActiveContext> allInterrupts = new ArrayList<>();
        List<L1ContextSnapshot.ActiveContext> cancelledInterrupts = new ArrayList<>();

        for (DomainHandler handler : domainServiceRegistry.getAllHandlers()) {
            try {
                L1ContextSnapshot snapshot = handler.collectL1Context(sessionId);
                if (snapshot != null && !snapshot.isEmpty() && snapshot.activeContexts() != null) {
                    if (hasCancelSignal) {
                        cancelledInterrupts.addAll(snapshot.activeContexts());
                    } else {
                        allInterrupts.addAll(snapshot.activeContexts());
                    }
                }
            } catch (Exception e) {
                log.debug("[OrchestrationAgent.scanL1Interrupts] handler={} collectL1Context failed: {}",
                    handler.getDomainName(), e.getMessage());
            }
        }

        // 取消信号：领养 L1 取消意图，不立即释放资源
        // StepPreparator 会在解析 plan 后处理这些取消意图：
        //   1. 将匹配的步骤标记为 CANCELLED
        //   2. abort L2 checkpoint
        //   3. release L1 ActiveAgentInfo
        // 这保证了编排生命周期感知取消，摘要也能报告"已取消转账"
        if (!cancelledInterrupts.isEmpty()) {
            List<CancelledL1Intent> cancelledData = cancelledInterrupts.stream()
                .map(ac -> new CancelledL1Intent(ac.intent(), ac.domain(), ac.threadId()))
                .toList();

            // 构建领养摘要 — 告知 Planner 这些意图需要纳入计划但标记为取消
            StringBuilder summary = new StringBuilder();
            summary.append("【L1中断上下文 — 以下意图已被用户明确取消，请纳入计划，系统会自动取消这些步骤】");
            for (L1ContextSnapshot.ActiveContext ac : cancelledInterrupts) {
                summary.append("\n- 意图: ").append(ac.intent())
                       .append(", 域: ").append(ac.domain())
                       .append(" (用户取消，需领养后取消)");
            }
            summary.append("\n注意：被取消意图的 rewrittenInput 写'用户取消该操作'即可，系统会在步骤执行前自动标记为CANCELLED并释放L1资源。");

            log.info("[OrchestrationAgent.scanL1Interrupts] Adopting {} cancelled L1 interrupts (deferred release)",
                cancelledInterrupts.size());
            return new L1InterruptScanResult(summary.toString(), cancelledData);
        }

        if (allInterrupts.isEmpty()) {
            return new L1InterruptScanResult(null, null);
        }

        StringBuilder summary = new StringBuilder();
        summary.append("【L1中断上下文 — 以下意图已有L1子图中断，请结合用户话术按顺序编排，该领养的意图需纳入计划】");
        for (L1ContextSnapshot.ActiveContext ac : allInterrupts) {
            summary.append("\n- 意图: ").append(ac.intent());
            summary.append(", 域: ").append(ac.domain());
            if (ac.lastQuestion() != null && !ac.lastQuestion().isEmpty()) {
                summary.append(", 中断提问: \"").append(ac.lastQuestion()).append("\"");
            }
            if (ac.userMessages() != null && !ac.userMessages().isEmpty()) {
                summary.append(", 用户已输入: ");
                for (String msg : ac.userMessages()) {
                    summary.append("\"").append(msg).append("\" ");
                }
            }
        }
        summary.append("\n注意：被领养意图的 rewrittenInput 只需写用户本次输入中与该意图相关的内容，" +
            "中断提问和用户历史交互会在步骤执行前由系统自动合并补全。");

        log.info("[OrchestrationAgent.scanL1Interrupts] Found {} L1 interrupts across all domains", allInterrupts.size());
        return new L1InterruptScanResult(summary.toString(), null);
    }

    /**
     * 检测用户输入是否包含取消信号。
     * 取消词：算了/取消/不要了/不X了/不想了/不用了/放弃
     * 只有单独取消词（无新意图）时不触发——那种情况L0路由到最近活跃域，由L1处理取消。
     * 这里只检测"取消词存在"，是否同时有新意图由L0判断路由。
     */
    private boolean detectCancelSignal(String userInput) {
        if (userInput == null || userInput.isEmpty()) return false;
        String[] cancelWords = {"算了", "取消", "不要了", "不想了", "不用了", "放弃"};
        for (String word : cancelWords) {
            if (userInput.contains(word)) {
                log.info("[OrchestrationAgent.detectCancelSignal] Detected cancel signal '{}' in input", word);
                return true;
            }
        }
        // Pattern: 不X了 (e.g., 不转了, 不买了, 不查了)
        if (userInput.matches(".*不.{1,3}了.*")) {
            log.info("[OrchestrationAgent.detectCancelSignal] Detected cancel pattern '不X了' in input");
            return true;
        }
        return false;
    }

    // ==================== GraphResponse 处理 ====================

    private StreamChunk handleGraphResponse(GraphResponse<NodeOutput> response, String sessionId) {
        try {
            // Only sync state on node completions — do NOT produce artificial CHUNK events.
            // Real CHUNK output comes exclusively from L2 subgraph streaming via sink bridging.
            // Non-streamable L2 results appear in the final COMPLETE summary.
            Object rawValue = null;
            if (response.getOutput() != null && !response.getOutput().isCompletedExceptionally()) {
                rawValue = response.getOutput().join();
            } else if (response.resultValue().isPresent()) {
                rawValue = response.resultValue().get();
            }

            if (rawValue instanceof NodeOutput nodeOutput && !nodeOutput.isEND()) {
                syncState(sessionId);
                log.info("[OrchestrationAgent] Graph node completed: {}", nodeOutput.node());
            }
            return null;
        } catch (Exception e) {
            log.debug("[OrchestrationAgent.handleGraphResponse] Error", e);
            return null;
        }
    }

    /**
     * Flux 完成后提取最终结果 — 优先检测 INTERRUPTED + sink桥接上下文
     */
    private StreamChunk extractFinalResult(String sessionId, String threadId) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            var snapshot = orchestrationGraph.getState(config);
            if (snapshot != null) {
                OverAllState state = snapshot.state();
                if (state != null) {
                    // 优先检查 L2 子图 interrupt → 返回问题给用户
                    String waitingQuestion = extractTextFromState(state, OrchestrationStateKeys.WAITING_QUESTION);
                    if (waitingQuestion != null && !waitingQuestion.isEmpty()) {
                        log.info("[OrchestrationAgent] L2 interrupt: question={}", waitingQuestion);

                        // Phase 2A: Also save waitingQuestion to the INTERRUPTED step
                        saveWaitingQuestionToStep(state);

                        stateBridge.syncFromGraph(sessionId, state);

                        // Use step's domain as INTERRUPTED intent — same as L0/L1/L2 pattern
                        // No context prefix — step results already surfaced via COMPLETE chunks
                        // from L2GraphTool sink bridge; repeating them would duplicate content
                        String interruptIntent = extractStepDomain(state);
                        return StreamChunk.interrupted(interruptIntent, waitingQuestion);
                    }

                    // 正常完成
                    String content = extractTextFromState(state, "orch_outputContent");
                    log.info("[OrchestrationAgent] ====== extractFinalResult STATE DUMP ======");
                    log.info("[OrchestrationAgent] Final state keys: {}", state.data().keySet());
                    for (String key : state.data().keySet()) {
                        Object val = state.value(key).orElse(null);
                        String valStr = val != null ? val.toString() : "null";
                        if (valStr.length() > 500) valStr = valStr.substring(0, 500) + "...";
                        log.info("[OrchestrationAgent]   {} = {}", key, valStr);
                    }
                    log.info("[OrchestrationAgent] Final orch_outputContent: '{}'", content);
                    log.info("[OrchestrationAgent] orch_outputContent isNull={}, isEmpty={}",
                        content == null, content == null || content.isEmpty());
                    log.info("[OrchestrationAgent] ====== END STATE DUMP ======");

                    // Cleanup — all wrapped in try-catch, must never prevent returning content
                    try {
                        stateBridge.syncFromGraph(sessionId, state);
                    } catch (Exception syncEx) {
                        log.warn("[OrchestrationAgent] Failed to sync state for session={}: {}", sessionId, syncEx.getMessage());
                    }

                    // 编排DONE → 清空lastDomain，让L0路由恢复，保留的L1 agent可resume
                    try {
                        domainRouter.clearLastDomain(sessionId);
                        log.info("[OrchestrationAgent] Orchestration DONE, cleared lastDomain for session={}", sessionId);
                    } catch (Exception clearEx) {
                        log.warn("[OrchestrationAgent] Failed to clear lastDomain for session={}: {}", sessionId, clearEx.getMessage());
                    }

                    log.info("[OrchestrationAgent] Returning COMPLETE: contentLen={}", content != null ? content.length() : 0);
                    return StreamChunk.complete("ORCHESTRATION", content);
                }
            }
        } catch (Exception e) {
            log.error("[OrchestrationAgent.extractFinalResult] Error reading final state", e);
        }
        return StreamChunk.complete("ORCHESTRATION", "");
    }

    /**
     * Phase 2A: Save waitingQuestion to the current INTERRUPTED step.
     * This ensures the question is preserved when we come back to this step later.
     */
    private void saveWaitingQuestionToStep(OverAllState state) {
        try {
            int currentIdx = (int) state.value(OrchestrationStateKeys.CURRENT_STEP_INDEX).orElse(0);
            List<?> steps = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());
            String waitingQuestion = (String) state.value(OrchestrationStateKeys.WAITING_QUESTION).orElse("");

            if (steps != null && currentIdx >= 0 && currentIdx < steps.size()) {
                Object obj = steps.get(currentIdx);
                if (obj instanceof OrchestrationStep step && waitingQuestion != null && !waitingQuestion.isEmpty()) {
                    step.setWaitingQuestion(waitingQuestion);
                    log.info("[OrchestrationAgent] Saved waitingQuestion='{}' to step stepId={}",
                        waitingQuestion, step.getStepId());
                }
            }
        } catch (Exception e) {
            log.debug("[OrchestrationAgent.saveWaitingQuestionToStep] Error: {}", e.getMessage());
        }
    }

    /** Extract current step's domain for INTERRUPTED intent — matches L0/L1/L2 pattern */
    private String extractStepDomain(OverAllState state) {
        try {
            int currentIdx = (int) state.value(OrchestrationStateKeys.CURRENT_STEP_INDEX).orElse(0);
            List<?> steps = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());
            if (steps != null && currentIdx >= 0 && currentIdx < steps.size()) {
                Object obj = steps.get(currentIdx);
                if (obj instanceof OrchestrationStep step) {
                    String domain = step.getDomain();
                    return (domain != null && !domain.isEmpty()) ? domain : "ORCHESTRATION";
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "ORCHESTRATION";
    }

    private String extractTextFromState(OverAllState state, String key) {
        Object val = state.value(key).orElse(null);
        if (val == null) return "";
        if (val instanceof org.springframework.ai.chat.messages.Message msg) return msg.getText();
        return val.toString();
    }

    private void syncState(String sessionId) {
        try {
            OrchestrationStateService.OrchestrationState extState = stateService.get(sessionId);
            String graphThreadId = (extState != null) ? extState.getGraphThreadId() : null;
            if (graphThreadId == null || graphThreadId.isEmpty()) return;
            RunnableConfig config = RunnableConfig.builder().threadId(graphThreadId).build();
            var snapshot = orchestrationGraph.getState(config);
            if (snapshot != null) {
                OverAllState state = snapshot.state();
                if (state != null) stateBridge.syncFromGraph(sessionId, state);
            }
        } catch (Exception e) {
            log.debug("[OrchestrationAgent.syncState] Error", e);
        }
    }

    // ==================== decomposeByIntent 内部数据结构 ====================

    /**
     * decomposeByIntent LLM返回的拆解结果
     */
    @Data
    static class DecomposeResult {
        List<DecomposeOperation> operations = new ArrayList<>();
        boolean needsReplan = false;
    }

    /**
     * 单个拆解操作
     */
    @Data
    static class DecomposeOperation {
        String type;          // ANSWER / MERGE / INSERT / CANCEL / CANCEL_ALL / REPLAN / CONDITION
        String intent;
        String content;
        String condition;
        String dependsOnIntent;
        String answerIfNotMet;
        int order;
    }
}
