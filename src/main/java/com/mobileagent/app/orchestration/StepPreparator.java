package com.mobileagent.app.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.domain.DomainHandler;
import com.mobileagent.app.domain.L1ContextSnapshot;
import com.mobileagent.app.orchestration.model.OrchestrationStep;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import com.mobileagent.app.orchestration.model.StepStatus;
import com.mobileagent.app.router.registry.DomainServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Step preparation — injects rewrittenInput into messages for ReactAgent consumption.
 * Core operation: take the current OrchestrationStep, build a user message from
 * its rewrittenInput, and update all observability fields in OverAllState.
 *
 * <h3>Plan Parsing (v4.3)</h3>
 * <p>On first call, if STEPS is empty but orch_plan has content,
 * parse orch_plan JSON into List&lt;OrchestrationStep&gt; and write to STEPS.
 * This solves the chicken-and-egg problem: planNode routing checks orch_plan,
 * but STEPS (needed by domain agents) is only populated here.
 */
@Slf4j
@Component
public class StepPreparator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DomainServiceRegistry domainServiceRegistry;

    public StepPreparator(DomainServiceRegistry domainServiceRegistry) {
        this.domainServiceRegistry = domainServiceRegistry;
    }

    /**
     * Prepare step input for ReactAgent node.
     * Core operation: inject rewrittenInput as user message into messages.
     * Also update observability fields.
     *
     * <p>On first call, parses orch_plan into STEPS if STEPS is empty.
     *
     * @param state Current OverAllState
     * @return Map of state updates to merge
     */
    public Map<String, Object> prepareStep(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();

        String orchStatus = state.value(OrchestrationStateKeys.ORCH_STATUS, "");
        @SuppressWarnings("unchecked")
        List<?> stepsRaw = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());

        // ===== 0. REPLANNING merge: planNode just re-planned (simplified — no level, no REPLAN_* signals) =====
        if ("REPLANNING".equals(orchStatus) && state.value("orch_plan").isPresent()) {
            List<OrchestrationStep> newPlannedSteps = parsePlanIntoSteps(state);
            if (newPlannedSteps != null && !newPlannedSteps.isEmpty()) {
                List<Object> merged = new ArrayList<>(stepsRaw);
                String firstNewStepId = null;
                // Build mapping: original plan index → new stepId (for dependsOnStepIndex remapping)
                Map<Integer, String> origIndexToNewStepId = new LinkedHashMap<>();

                for (int i = 0; i < newPlannedSteps.size(); i++) {
                    OrchestrationStep ps = newPlannedSteps.get(i);
                    ps.setIndex(merged.size());
                    String newStepId = "s-ins-" + Integer.toHexString((int)(System.nanoTime() & 0xFFFFFF)) + "-" + i;
                    ps.setStepId(newStepId);
                    ps.setStatus(StepStatus.PENDING);
                    if (i == 0) firstNewStepId = newStepId;
                    origIndexToNewStepId.put(i, newStepId);
                    // Remap dependsOnStepIndex from original index to new stepId
                    // Supports comma-separated dependsOnStepIndex (e.g., "1,2")
                    String dependsOn = ps.getDependsOnStepIndex();
                    if (dependsOn != null && !dependsOn.isEmpty()) {
                        String[] depParts = dependsOn.split("\\s*,\\s*");
                        List<String> remappedParts = new ArrayList<>();
                        for (String depPart : depParts) {
                            try {
                                int origDepIdx = Integer.parseInt(depPart);
                                String depStepId = origIndexToNewStepId.get(origDepIdx);
                                if (depStepId != null) {
                                    remappedParts.add(depStepId);
                                } else {
                                    remappedParts.add(depPart); // keep as-is if not found
                                }
                            } catch (NumberFormatException e) {
                                remappedParts.add(depPart); // already a stepId
                            }
                        }
                        String remapped = String.join(",", remappedParts);
                        ps.setDependsOnStepIndex(remapped);
                        log.info("[StepPreparator] Remapped dependsOnStepIndex: {} → {}", dependsOn, remapped);
                    }
                    // Skip same-intent old PENDING steps (simple cleanup)
                    for (Object obj : merged) {
                        if (obj instanceof OrchestrationStep oldStep
                                && oldStep.getStatus() == StepStatus.PENDING
                                && oldStep.getIntent() != null
                                && oldStep.getIntent().equals(ps.getIntent())) {
                            oldStep.setStatus(StepStatus.SKIPPED);
                            oldStep.setCancelReason("被新计划替代");
                            log.info("[StepPreparator] SKIPPED old PENDING step: stepId={} intent={}", oldStep.getStepId(), oldStep.getIntent());
                        }
                    }
                    merged.add(ps);
                    log.info("[StepPreparator] Merged replan step: stepId={}, index={}, domain={}, intent={}, dependsOn={}",
                        ps.getStepId(), ps.getIndex(), ps.getDomain(), ps.getIntent(), ps.getDependsOnStepIndex());
                }

                // Prepare the first new step for execution
                OrchestrationStep firstStep = newPlannedSteps.get(0);
                firstStep.setStatus(StepStatus.RUNNING);
                String rewrittenInput = firstStep.getRewrittenInput();
                if (rewrittenInput == null || rewrittenInput.isEmpty()) {
                    rewrittenInput = firstStep.getDescription();
                }

                // L1上下文领养 — step执行前，collect该domain的L1上下文，传threadId实现resume
                String sessionId = state.value(OrchestrationStateKeys.SESSION_ID, "");
                String enrichedInput = enrichWithL1Context(state, sessionId, firstStep.getDomain(), firstStep, rewrittenInput, updates);

                updates.put(OrchestrationStateKeys.STEPS, merged);
                updates.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, firstStep.getIndex());
                updates.put(OrchestrationStateKeys.ORCH_STATUS, "EXECUTING");
                updates.put(OrchestrationStateKeys.WAITING_STEP_ID, ""); // clear so L2GraphTool uses CURRENT_STEP_INDEX
                updates.put("messages", List.of(new UserMessage(enrichedInput)));
                updates.put(OrchestrationStateKeys.CURRENT_ACTION, describeStep(firstStep));
                updates.put(OrchestrationStateKeys.STEP_STATUS, StepStatus.RUNNING.name());
                updates.put(OrchestrationStateKeys.AGENT_NAME, firstStep.getDomain().toLowerCase() + "_agent");
                updates.put(OrchestrationStateKeys.AGENT_PHASE, "REASONING");

                log.info("[StepPreparator] REPLANNING prepared first step: stepId={}, index={}, domain={}, intent={}",
                    firstStep.getStepId(), firstStep.getIndex(), firstStep.getDomain(), firstStep.getIntent());

                return updates;
            } else {
                log.warn("[StepPreparator] REPLANNING but planNode produced no steps — aborting");
                updates.put(OrchestrationStateKeys.ORCH_STATUS, "DONE");
                return updates;
            }
        }

        // ===== 1. Parse plan into STEPS on first call =====
        if ((stepsRaw == null || stepsRaw.isEmpty()) && state.value("orch_plan").isPresent()) {
            List<OrchestrationStep> parsedSteps = parsePlanIntoSteps(state);
            if (parsedSteps != null && !parsedSteps.isEmpty()) {
                updates.put(OrchestrationStateKeys.STEPS, parsedSteps);
                stepsRaw = parsedSteps;
                log.info("[StepPreparator] Parsed {} steps from orch_plan", parsedSteps.size());
            } else {
                log.warn("[StepPreparator] orch_plan present but failed to parse steps — aborting");
                updates.put(OrchestrationStateKeys.ORCH_STATUS, "DONE");
                return updates;
            }
        }

        // ===== 1.5 Process cancelled L1 intents (deferred from startOrchestration) =====
        // startOrchestration() detected cancel signals and stored cancelled L1 intents in graph state.
        // Process them here: set cancelSignal=true on matching steps (L2GraphTool will cancel the L2 subgraph
        // via _cancelSignal → cancelExecution), release L1 ActiveAgentInfo, then clear the key.
        // Steps stay PENDING (not CANCELLED) so L2GraphTool can execute the cancel flow.
        @SuppressWarnings("unchecked")
        List<Map<String, String>> cancelledL1Raw = (List<Map<String, String>>)
            state.value(OrchestrationStateKeys.CANCELLED_L1_INTENTS).orElse(null);
        if (cancelledL1Raw != null && !cancelledL1Raw.isEmpty()) {
            processCancelledL1Intents(state, cancelledL1Raw, stepsRaw, updates);
            updates.put(OrchestrationStateKeys.CANCELLED_L1_INTENTS, List.of()); // clear after processing
            if (!updates.containsKey(OrchestrationStateKeys.STEPS)) {
                updates.put(OrchestrationStateKeys.STEPS, stepsRaw);
            }
        }

        // ===== 2. Get current step =====
        int currentIndex = (int) state.value(OrchestrationStateKeys.CURRENT_STEP_INDEX).orElse(0);
        OrchestrationStep step = getStepAtIndex(stepsRaw, currentIndex);
        if (step == null) {
            log.warn("[StepPreparator.prepareStep] No step at index {}, cannot prepare", currentIndex);
            return Map.of(OrchestrationStateKeys.ORCH_STATUS, "DONE");
        }

        // ===== 3. Inject rewrittenInput as user message =====
        // ReactAgent expects Message objects in messages, NOT HashMap
        // decomposeByIntent refactor: rewrittenInput is already the complete, enriched input
        // — no LATEST_USER_INPUT append (that was the old classifyInput pattern)
        String rewrittenInput = step.getRewrittenInput();
        if (rewrittenInput == null || rewrittenInput.isEmpty()) {
            rewrittenInput = step.getDescription(); // fallback
        }

        // Enrich with context from COMPLETED steps of same intent (cross-step context inheritance)
        rewrittenInput = enrichWithCompletedStepContext(stepsRaw, step, rewrittenInput);

        // L1上下文领养 — step执行前，collect该domain的L1上下文，传threadId实现resume
        String sessionId = state.value(OrchestrationStateKeys.SESSION_ID, "");
        String enrichedInput = enrichWithL1Context(state, sessionId, step.getDomain(), step, rewrittenInput, updates);

        updates.put("messages", List.of(new UserMessage(enrichedInput)));

        // ===== 4. Update observability fields =====
        updates.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, step.getIndex());
        updates.put(OrchestrationStateKeys.CURRENT_ACTION, describeStep(step));
        updates.put(OrchestrationStateKeys.STEP_STATUS, StepStatus.RUNNING.name());
        updates.put(OrchestrationStateKeys.AGENT_NAME, step.getDomain().toLowerCase() + "_agent");
        updates.put(OrchestrationStateKeys.AGENT_PHASE, "REASONING");
        updates.put(OrchestrationStateKeys.NEXT_ACTION, describeNextStep(stepsRaw, step));
        updates.put(OrchestrationStateKeys.ORCH_STATUS, "EXECUTING");

        // 5. Mark step as RUNNING
        step.setStatus(StepStatus.RUNNING);

        log.info("[StepPreparator.prepareStep] Prepared step {}/{}: domain={} intent={} action={}",
            step.getIndex(), stepsRaw.size(), step.getDomain(), step.getIntent(),
            describeStep(step));

        return updates;
    }

    // ==================== Plan Parsing ====================

    /**
     * Parse orch_plan (Planner LLM JSON output) into List&lt;OrchestrationStep&gt;.
     *
     * <p>Handles:
     * <ul>
     *   <li>Markdown code fences (```json ... ```)</li>
     *   <li>Root object with "steps" array</li>
     *   <li>Bare array of steps</li>
     *   <li>orch_plan value may be String or other object type</li>
     * </ul>
     */
    private List<OrchestrationStep> parsePlanIntoSteps(OverAllState state) {
        try {
            Object planObj = state.value("orch_plan").orElse(null);
            if (planObj == null) return null;

            // ReactAgent outputKey writes AssistantMessage, not String
            String planJson;
            if (planObj instanceof Message msg) {
                planJson = msg.getText();
                log.info("[StepPreparator] orch_plan is Message type, extracted text length={}", planJson != null ? planJson.length() : 0);
            } else {
                planJson = planObj.toString();
                log.info("[StepPreparator] orch_plan is {} type, toString length={}", planObj.getClass().getSimpleName(), planJson.length());
            }

            if (planJson == null || planJson.isEmpty()) {
                log.warn("[StepPreparator] orch_plan text content is empty");
                return null;
            }

            // Strip markdown code fences if present
            planJson = stripMarkdownFence(planJson);

            JsonNode root = OBJECT_MAPPER.readTree(planJson);

            // Root may be { "steps": [...], "reasoning": "..." } or bare [...]
            JsonNode stepsNode = root.has("steps") ? root.get("steps") : root;

            if (!stepsNode.isArray()) {
                log.warn("[StepPreparator] orch_plan does not contain a steps array, got: {}",
                    stepsNode.getNodeType());
                return null;
            }

            List<OrchestrationStep> steps = new ArrayList<>();
            for (JsonNode stepNode : stepsNode) {
                OrchestrationStep step = new OrchestrationStep();
                step.setIndex(stepNode.path("index").asInt(0));
                step.setDomain(stepNode.path("domain").asText(""));
                step.setIntent(stepNode.path("intent").asText(""));
                step.setDescription(stepNode.path("description").asText(""));
                step.setRewrittenInput(stepNode.path("rewrittenInput").asText(
                    stepNode.path("description").asText("")));  // fallback to description
                // condition: null if absent or explicitly null
                JsonNode condNode = stepNode.get("condition");
                step.setCondition((condNode == null || condNode.isNull() || condNode.asText("").isEmpty())
                    ? null : condNode.asText());
                step.setStatus(StepStatus.PENDING);

                // Phase 2A: Generate stable stepId (s-0, s-1, s-2...)
                step.setStepId("s-" + step.getIndex());

                // Phase 2A: Parse dependsOnStepIndex (Planner may output it)
                JsonNode dependsNode = stepNode.get("dependsOnStepIndex");
                step.setDependsOnStepIndex((dependsNode == null || dependsNode.isNull() || dependsNode.asText("").isEmpty())
                    ? null : dependsNode.asText());

                steps.add(step);

                log.info("[StepPreparator] Parsed step: index={} stepId={} domain={} intent={} condition={} dependsOn={}",
                    step.getIndex(), step.getStepId(), step.getDomain(), step.getIntent(),
                    step.getCondition(), step.getDependsOnStepIndex());
            }

            return steps;
        } catch (Exception e) {
            log.error("[StepPreparator] Failed to parse orch_plan into steps", e);
            return null;
        }
    }

    private String stripMarkdownFence(String json) {
        String trimmed = json.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBacktick = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        return trimmed;
    }

    // ==================== L1 Context Adoption (Step-Level) ====================

    /**
     * Step级L1上下文领养 — step执行前，collect该domain的L1上下文，传递L1 threadId实现resume
     *
     * <p>核心设计：L2 不感知调用者是 L1 还是 L2GraphTool，所以 L1 的 checkpoint 可以被 L2GraphTool resume。
     *
     * <p>三件事：
     * <ol>
     *   <li>enrichedInput: 将L1上下文拼入rewrittenInput（保留原有逻辑）</li>
     *   <li>threadId传递: 将L1的threadId设置到step.l2ThreadId + L2_STEP_THREAD_MAP，让L2GraphTool resume而非new</li>
     *   <li>不abort checkpoint: 不调releaseL1Resources()，保留L2 checkpoint供resume。L1的activeAgent自然过期，编排期间L1不会被调用</li>
     * </ol>
     *
     * @param state           当前OverAllState
     * @param sessionId       session ID
     * @param stepDomain      当前step的domain（如"WEALTH"）
     * @param step            当前OrchestrationStep（需要设置l2ThreadId）
     * @param rewrittenInput  PlannerAgent生成的初步rewrittenInput
     * @param updates         状态更新Map（需要写入L2_STEP_THREAD_MAP）
     * @return enriched rewrittenInput（可能等于原rewrittenInput）
     */
    private String enrichWithL1Context(OverAllState state, String sessionId, String stepDomain,
                                        OrchestrationStep step, String rewrittenInput,
                                        Map<String, Object> updates) {
        if (sessionId == null || sessionId.isEmpty() || stepDomain == null || stepDomain.isEmpty()) {
            return rewrittenInput;
        }

        // L1上下文领养（如有）
        try {
            DomainHandler handler = domainServiceRegistry.getHandler(stepDomain);
            if (handler != null) {
                L1ContextSnapshot snapshot = handler.collectL1Context(sessionId);
                if (snapshot != null && !snapshot.isEmpty()) {
                    // === 1. enrichedInput: 保留原有的文本拼装逻辑 ===
                    StringBuilder enriched = new StringBuilder(rewrittenInput);
                    enriched.append("\n\n【L1上下文 — 请将以下信息与上方任务合并改写后再执行】");

                    String l1ThreadId = null; // 只领养与step intent匹配的threadId

                    for (L1ContextSnapshot.ActiveContext ac : snapshot.activeContexts()) {
                        enriched.append("\n- 域: ").append(ac.domain())
                                .append(", 意图: ").append(ac.intent());
                        // 用户交互历史
                        if (ac.userMessages() != null && !ac.userMessages().isEmpty()) {
                            enriched.append(", 用户输入: ");
                            for (String msg : ac.userMessages()) {
                                enriched.append("\"").append(msg).append("\" ");
                            }
                        }
                        // 中断时的提问
                        if (ac.lastQuestion() != null && !ac.lastQuestion().isEmpty()) {
                            enriched.append(", 中断提问: \"").append(ac.lastQuestion()).append("\"");
                        }
                        // 只领养与step intent匹配的threadId — 不同intent的checkpoint属于不同子图，不能cross-resume
                        if (l1ThreadId == null && ac.threadId() != null && !ac.threadId().isEmpty()
                                && ac.intent() != null && ac.intent().equals(step.getIntent())) {
                            l1ThreadId = ac.threadId();
                        }
                    }
                    for (L1ContextSnapshot.SuspendedContext sc : snapshot.suspendedContexts()) {
                        enriched.append("\n- 域: ").append(sc.domain())
                                .append(", 挂起意图: ").append(sc.intent());
                    }

                    // === 2. threadId传递: 设置step.l2ThreadId + L2_STEP_THREAD_MAP ===
                    if (l1ThreadId != null && step != null) {
                        step.setL2ThreadId(l1ThreadId);
                        log.info("[StepPreparator] L1 threadId inherited: stepId={}, l2ThreadId={}", step.getStepId(), l1ThreadId);

                        // 写入L2_STEP_THREAD_MAP，让L2GraphTool能通过stepThreadMap找到threadId
                        @SuppressWarnings("unchecked")
                        Map<String, String> stepThreadMap = (Map<String, String>) updates.getOrDefault(
                            OrchestrationStateKeys.L2_STEP_THREAD_MAP,
                            state.value(OrchestrationStateKeys.L2_STEP_THREAD_MAP).orElse(new LinkedHashMap<>()));
                        if (!(stepThreadMap instanceof LinkedHashMap)) {
                            // 防止不可变map
                            Map<String, String> mutable = new LinkedHashMap<>(stepThreadMap);
                            stepThreadMap = mutable;
                        }
                        stepThreadMap.put(step.getStepId(), l1ThreadId);
                        updates.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, stepThreadMap);
                        log.info("[StepPreparator] L1 threadId written to L2_STEP_THREAD_MAP: stepId={} → threadId={}", step.getStepId(), l1ThreadId);
                    }

                    // === 3. Release L1 ownership — clear ActiveAgentInfo so domain no longer claims ownership ===
                    // L2 checkpoint is preserved so L2GraphTool can resume it.
                    // For cancel scenarios, L2GraphTool will set _cancelSignal to let L2 subgraph cancel cleanly,
                    // then GES auto-clears checkpoint on completion.
                    List<String> adoptedIntents = snapshot.activeContexts().stream()
                        .map(L1ContextSnapshot.ActiveContext::intent)
                        .toList();
                    handler.releaseL1Resources(sessionId, adoptedIntents);

                    log.info("[StepPreparator] L1 context adopted (resume mode): domain={}, adoptedIntents={}, l1ThreadId={}, ActiveAgentInfo released (checkpoint preserved)",
                        stepDomain, adoptedIntents, l1ThreadId);
                    log.info("[StepPreparator] enrichWithL1Context: domain={}, rewrittenInput=[{}], enrichedResult=[{}]", stepDomain, rewrittenInput, enriched);

                    return enriched.toString();
                }
            }
        } catch (Exception e) {
            log.warn("[StepPreparator] L1 context adoption failed for domain={}: {}", stepDomain, e.getMessage());
        }

        return rewrittenInput;
    }

    // ==================== Cancel L1 Intent Processing ====================

    /**
     * Process cancelled L1 intents — set cancelSignal=true on matching steps + release L1 ActiveAgentInfo.
     *
     * <p>Called once from prepareStep() after plan parsing. Clears CANCELLED_L1_INTENTS from state
     * after processing to ensure idempotency.
     *
     * <p>Design: steps with cancelSignal=true stay PENDING (not CANCELLED), so L2GraphTool
     * can execute the cancel flow: detect cancelSignal → call GES.cancelGraph() →
     * inject _cancelSignal=true into L2 subgraph → subgraph's cancelAwareExtractParams/cancelAwareAsk
     * detects it → routes to cancelExecution → clean termination → GES auto-clears checkpoint.
     *
     * <p>Unified operation: same releaseL1Resources for both cancel and answer scenarios —
     * clear ActiveAgentInfo, preserve L2 checkpoint (GES handles cleanup).
     */
    private void processCancelledL1Intents(OverAllState state, List<Map<String, String>> cancelledL1Raw,
                                            List<?> stepsRaw, Map<String, Object> updates) {
        String sessionId = state.value(OrchestrationStateKeys.SESSION_ID, "");

        // Collect cancelled intent names and group by domain
        List<String> cancelledIntentNames = new ArrayList<>();
        Map<String, List<String>> domainToCancelledIntents = new LinkedHashMap<>();

        for (Map<String, String> ci : cancelledL1Raw) {
            String intent = ci.get("intent");
            String domain = ci.get("domain");
            String threadId = ci.get("threadId");
            if (intent != null && !intent.isEmpty()) {
                cancelledIntentNames.add(intent);
                domainToCancelledIntents.computeIfAbsent(domain, k -> new ArrayList<>()).add(intent);
            }
        }

        // 1. Set cancelSignal=true on matching steps + set l2ThreadId from cancelled L1 data
        for (Object obj : stepsRaw) {
            if (obj instanceof OrchestrationStep step) {
                if (cancelledIntentNames.contains(step.getIntent())
                        && step.getStatus() != StepStatus.COMPLETED
                        && step.getStatus() != StepStatus.SKIPPED) {
                    step.setCancelSignal(true);
                    step.setRewrittenInput("用户取消该操作");
                    // Set l2ThreadId from cancelled L1 data so L2GraphTool can find the checkpoint to cancel
                    for (Map<String, String> ci : cancelledL1Raw) {
                        if (step.getIntent().equals(ci.get("intent")) && ci.get("threadId") != null) {
                            step.setL2ThreadId(ci.get("threadId"));
                            // Also write to L2_STEP_THREAD_MAP
                            @SuppressWarnings("unchecked")
                            Map<String, String> stepThreadMap = (Map<String, String>) updates.getOrDefault(
                                OrchestrationStateKeys.L2_STEP_THREAD_MAP,
                                state.value(OrchestrationStateKeys.L2_STEP_THREAD_MAP).orElse(new LinkedHashMap<>()));
                            if (!(stepThreadMap instanceof LinkedHashMap)) {
                                stepThreadMap = new LinkedHashMap<>(stepThreadMap);
                            }
                            stepThreadMap.put(step.getStepId(), ci.get("threadId"));
                            updates.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, stepThreadMap);
                            break;
                        }
                    }
                    log.info("[StepPreparator] Set cancelSignal on step: stepId={}, intent={}, l2ThreadId={}",
                        step.getStepId(), step.getIntent(), step.getL2ThreadId());
                }
            }
        }

        // 2. Release L1 ActiveAgentInfo for cancelled intents (clear info, preserve checkpoint)
        for (Map.Entry<String, List<String>> entry : domainToCancelledIntents.entrySet()) {
            String domain = entry.getKey();
            List<String> intents = entry.getValue();
            try {
                DomainHandler handler = domainServiceRegistry.getHandler(domain);
                if (handler != null) {
                    handler.releaseL1Resources(sessionId, intents);
                    log.info("[StepPreparator] Released L1 ActiveAgentInfo for cancelled intents: domain={}, intents={}",
                        domain, intents);
                }
            } catch (Exception e) {
                log.warn("[StepPreparator] Failed to release L1 resources for domain={}: {}", domain, e.getMessage());
            }
        }

        log.info("[StepPreparator] Processed {} cancelled L1 intents (cancelSignal set, ActiveAgentInfo released)",
            cancelledL1Raw.size());
    }

    // ==================== Helpers ====================

    private String describeStep(OrchestrationStep step) {
        return step.getIntent() + "→" + step.getDescription();
    }

    private String describeNextStep(List<?> steps, OrchestrationStep current) {
        int nextIdx = current.getIndex() + 1;
        OrchestrationStep next = getStepAtIndex(steps, nextIdx);
        if (next != null) {
            return next.getIntent() + "→" + next.getDescription();
        }
        return "无（最后一步）";
    }

    @SuppressWarnings("unchecked")
    private OrchestrationStep getStepAtIndex(List<?> steps, int index) {
        if (steps == null || index < 0 || index >= steps.size()) return null;
        Object obj = steps.get(index);
        if (obj instanceof OrchestrationStep) return (OrchestrationStep) obj;
        // PlannerAgent may output steps as LinkedHashMap (from JSON deserialization)
        // Handle this gracefully — return null, caller should handle
        return null;
    }

    /**
     * Enrich rewrittenInput with context from COMPLETED steps of the same intent.
     * When a new INSERT step's content references a previous step ("再转1000"),
     * the L2 agent needs to see the full context from the completed step
     * (e.g., "我妈" as the recipient).
     *
     * Only appends context if the current step is not COMPLETED and has a
     * matching COMPLETED step in the step list.
     */
    private String enrichWithCompletedStepContext(List<?> steps, OrchestrationStep currentStep, String rewrittenInput) {
        if (steps == null || currentStep == null || currentStep.getIntent() == null) {
            return rewrittenInput;
        }
        if (currentStep.getStatus() == StepStatus.COMPLETED) {
            return rewrittenInput;
        }

        // Find COMPLETED steps with same intent
        List<String> completedInputs = new ArrayList<>();
        for (Object obj : steps) {
            if (obj instanceof OrchestrationStep s) {
                if (s.getStepId().equals(currentStep.getStepId())) continue; // skip self
                if (currentStep.getIntent().equals(s.getIntent())
                        && s.getStatus() == StepStatus.COMPLETED
                        && s.getRewrittenInput() != null && !s.getRewrittenInput().isEmpty()) {
                    completedInputs.add(s.getRewrittenInput());
                }
            }
        }

        if (completedInputs.isEmpty()) {
            return rewrittenInput;
        }

        StringBuilder enriched = new StringBuilder(rewrittenInput);
        enriched.append("\n\n【同意图已完成步骤上下文 — 请结合以下信息消除歧义后执行】");
        for (int i = 0; i < completedInputs.size(); i++) {
            enriched.append("\n- 已完成步骤").append(i + 1).append(": ").append(completedInputs.get(i));
        }
        log.info("[StepPreparator] enrichWithCompletedStepContext: intent={}, rewrittenInput=[{}], completedSteps={}",
            currentStep.getIntent(), rewrittenInput, completedInputs.size());
        return enriched.toString();
    }
}
