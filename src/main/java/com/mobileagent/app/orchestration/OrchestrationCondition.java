package com.mobileagent.app.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.mobileagent.app.orchestration.model.ConditionalAnswer;
import com.mobileagent.app.orchestration.model.OrchestrationProperties;
import com.mobileagent.app.orchestration.model.OrchestrationStep;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import com.mobileagent.app.orchestration.model.StepStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Condition check node — evaluates step conditions and determines next routing.
 *
 * <h3>Phase 2A Enhancement</h3>
 * <ul>
 *   <li>INTERRUPTED steps have priority over PENDING steps</li>
 *   <li>Dependency check: dependsOnStepIndex must be COMPLETED before step can execute</li>
 *   <li>Conditional answer evaluation (Phase 2D): when a step completes, check if any
 *       INTERRUPTED step has a conditionalAnswer depending on it</li>
 *   <li>AUTO_RESUME signal: when conditional answer condition is met, auto-resume without waiting</li>
 * </ul>
 *
 * <h3>Scoped Condition Evaluation</h3>
 * <p>Conditions are evaluated against ONLY the result of the depended-on step (via
 * {@code dependsOnStepIndex} or {@code ConditionalAnswer.dependsOnStepId}), not ALL step results.
 * This prevents false matches — e.g., transfer serial "TXN277519" (number 277519)
 * incorrectly satisfying "income > 100000" when actual income is only 15086.50.
 *
 * <p>The domain→outputKey mapping is derived from {@code orchestration.agents[]} in yml config,
 * NOT from naming conventions — consistent with the project's config-as-truth principle.
 */
@Slf4j
@Component
public class OrchestrationCondition {

    /** domain → outputKey mapping (e.g., "BILL" → "bill_result"), derived from yml config */
    private final Map<String, String> domainToOutputKey;
    private final ChatClient conditionChatClient;

    public OrchestrationCondition(OrchestrationProperties orchestrationProperties,
                                   @Qualifier("domainChatClient") ChatClient conditionChatClient) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (OrchestrationProperties.AgentDefinition def : orchestrationProperties.getAgents()) {
            if (def.getDomain() != null && def.getOutputKey() != null) {
                mapping.put(def.getDomain(), def.getOutputKey());
            }
        }
        this.domainToOutputKey = mapping;
        this.conditionChatClient = conditionChatClient;
        log.info("[OrchestrationCondition] Domain→OutputKey mapping: {}", domainToOutputKey);
    }

    /**
     * Check conditions and determine next route.
     *
     * <p>Priority order (decomposeByIntent refactor — no INPUT_CLASSIFICATION):
     * <ol>
     *   <li>WAITING_QUESTION non-empty → mark step INTERRUPTED, WAITING_USER</li>
     *   <li>Mark current step COMPLETED</li>
     *   <li>Check conditional answers (Phase 2D) — if condition met → AUTO_RESUME</li>
     *   <li>Find next PENDING step (index order, no level) → execute</li>
     *   <li>Pop INTERRUPTED step (index descending LIFO) → re-ask</li>
     *   <li>Nothing found → DONE</li>
     * </ol>
     *
     * <p>Note: CANCEL/CANCEL_ALL is now handled by decomposeByIntent in OrchestrationAgent
     * (applies CANCEL operations to steps directly), not via INPUT_CLASSIFICATION here.
     */
    public Map<String, Object> checkAndRoute(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();

        // Clear one-shot signals from previous cycle — prevents stale routing.
        updates.put(OrchestrationStateKeys.AUTO_RESUME_STEP_ID, "");
        updates.put(OrchestrationStateKeys.AUTO_RESUME_ANSWER, "");

        // Read common state early
        int currentIdx = (int) state.value(OrchestrationStateKeys.CURRENT_STEP_INDEX).orElse(0);
        @SuppressWarnings("unchecked")
        List<?> stepsRaw = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());

        // 1. 检查 L2 子图 interrupt — 如果有等待问题，暂停编排
        //    Mark current step as INTERRUPTED — critical for NEW_INTENT flow to find it later
        String waitingQuestion = (String) state.value(OrchestrationStateKeys.WAITING_QUESTION).orElse("");
        String interruptionReason = (String) state.value(OrchestrationStateKeys.INTERRUPTION_REASON).orElse("");
        if (waitingQuestion != null && !waitingQuestion.isEmpty()) {
            log.info("[OrchestrationCondition] L2 interrupt detected: question={}, reason={}", waitingQuestion, interruptionReason);
            // Mark current step as INTERRUPTED so NEW_INTENT flow can find it
            OrchestrationStep currentStep = getStep(stepsRaw, currentIdx);
            if (currentStep != null) {
                currentStep.setStatus(StepStatus.INTERRUPTED);
                currentStep.setWaitingQuestion(waitingQuestion);
                // Save l2ThreadId from stepThreadMap to step — survives framework state resets
                @SuppressWarnings("unchecked")
                Map<String, String> stepThreadMap = (Map<String, String>) state.value(OrchestrationStateKeys.L2_STEP_THREAD_MAP).orElse(null);
                if (stepThreadMap != null && currentStep.getStepId() != null) {
                    String l2ThreadId = stepThreadMap.get(currentStep.getStepId());
                    if (l2ThreadId != null && !l2ThreadId.isEmpty()) {
                        currentStep.setL2ThreadId(l2ThreadId);
                        log.info("[OrchestrationCondition] Saved l2ThreadId={} to step stepId={}",
                            l2ThreadId, currentStep.getStepId());
                    }
                }
                updates.put(OrchestrationStateKeys.STEPS, stepsRaw);
                updates.put(OrchestrationStateKeys.WAITING_STEP_ID, currentStep.getStepId());
                log.info("[OrchestrationCondition] Marked step stepId={} as INTERRUPTED, saved waitingQuestion",
                    currentStep.getStepId());
            }
            updates.put(OrchestrationStateKeys.ORCH_STATUS, "WAITING_USER");
            return updates;
        }

        // 2. Collect step results from state for condition evaluation
        Map<String, Object> stepResults = collectStepResults(state);
        log.info("[OrchestrationCondition] stepResults available: keys={}", stepResults.keySet());

        // 3. Mark current step as COMPLETED or CANCELLED
        // If cancelSignal=true, L2GraphTool processed a cancel — mark CANCELLED, not COMPLETED
        OrchestrationStep currentStep = getStep(stepsRaw, currentIdx);
        if (currentStep != null && currentStep.getStatus() == StepStatus.RUNNING) {
            if (currentStep.isCancelSignal()) {
                currentStep.setStatus(StepStatus.CANCELLED);
                currentStep.setCancelReason("用户主动取消");
                currentStep.setCancelSignal(false);
                log.info("[OrchestrationCondition.checkAndRoute] Step {} (stepId={}) marked CANCELLED (cancelSignal processed)",
                    currentIdx, currentStep.getStepId());
            } else {
                currentStep.setStatus(StepStatus.COMPLETED);
                log.info("[OrchestrationCondition.checkAndRoute] Step {} (stepId={}) marked COMPLETED",
                    currentIdx, currentStep.getStepId());
            }
        }

        // 3.1 Collapse pure condition-evaluation steps: if a PENDING step has the same intent
        //     as its dependsOn step AND has a condition field, it's a redundant "condition evaluation"
        //     step produced by the planner — evaluate the condition directly and mark COMPLETED/SKIPPED.
        collapseRedundantConditionSteps(stepsRaw, stepResults);

        // 3.5 Phase 2D: Check conditional answers — evaluate after step completion
        if (currentStep != null) {
            OrchestrationStep conditionalStep = findConditionalAnswerTarget(stepsRaw, currentStep.getStepId(), stepResults);
            if (conditionalStep != null) {
                if (conditionalStep.getStatus() == StepStatus.CANCELLED) {
                    // Condition not met + cancelIfNotMet → step already cancelled in findConditionalAnswerTarget
                    // Fall through to find next step (don't auto-resume, don't pop this cancelled step)
                    log.info("[OrchestrationCondition] Conditional cancel: stepId={} cancelled (condition not met)",
                        conditionalStep.getStepId());
                    updates.put(OrchestrationStateKeys.STEPS, stepsRaw);
                    // Continue to find next PENDING step (skip the cancelled one)
                    // Fall through to step 4 (popTopInterrupted) or step 5 (findNextPendingStep)
                } else {
                    // Condition met → AUTO_RESUME
                    ConditionalAnswer ca = conditionalStep.getConditionalAnswer();
                    log.info("[OrchestrationCondition] Conditional answer met for stepId={}, condition={}, answerIfMet={}",
                        conditionalStep.getStepId(), ca.getCondition(), ca.getAnswerIfMet());
                    // Set AUTO_RESUME signal
                    updates.put(OrchestrationStateKeys.AUTO_RESUME_STEP_ID, conditionalStep.getStepId());
                    updates.put(OrchestrationStateKeys.AUTO_RESUME_ANSWER, ca.getAnswerIfMet());
                    updates.put(OrchestrationStateKeys.LATEST_USER_INPUT, ca.getAnswerIfMet());
                    updates.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, conditionalStep.getIndex());
                    updates.put(OrchestrationStateKeys.WAITING_QUESTION, "");
                    updates.put(OrchestrationStateKeys.ORCH_STATUS, "EXECUTING");
                    // Clear the conditional answer (consumed)
                    conditionalStep.setConditionalAnswer(null);
                    conditionalStep.setStatus(StepStatus.RUNNING);
                    updates.put(OrchestrationStateKeys.STEPS, stepsRaw);
                    return updates;
                }
            }
        }

        // 4. Find next PENDING step (with dependency check) — PRIORITY OVER INTERRUPTED
        // New PENDING steps (from replan) must execute before old INTERRUPTED steps are re-asked.
        // This ensures the user's full request is processed before re-asking old questions.
        int nextIdx = findNextPendingStep(stepsRaw, 0, stepResults);

        // 6. Update state
        updates.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, nextIdx);
        updates.put(OrchestrationStateKeys.STEP_STATUS, StepStatus.COMPLETED.name());
        updates.put(OrchestrationStateKeys.STEPS, stepsRaw);

        if (nextIdx >= 0) {
            OrchestrationStep nextStep = getStep(stepsRaw, nextIdx);
            String nextAction = nextStep != null ? describeStep(nextStep) : "";
            updates.put(OrchestrationStateKeys.NEXT_ACTION, nextAction);
            updates.put(OrchestrationStateKeys.ORCH_STATUS, "EXECUTING");
            log.info("[OrchestrationCondition.checkAndRoute] Next step: index={} stepId={} domain={}",
                nextIdx, nextStep != null ? nextStep.getStepId() : "none",
                nextStep != null ? nextStep.getDomain() : "none");
        } else {
            // No more PENDING steps — check for INTERRUPTED steps to re-ask
            OrchestrationStep interruptedStep = popTopInterrupted(stepsRaw);
            if (interruptedStep != null) {
                // Not superseded — re-ask the question
                log.info("[OrchestrationCondition] Pop INTERRUPTED step → re-ask: stepId={}, waitingQuestion={}",
                    interruptedStep.getStepId(), interruptedStep.getWaitingQuestion());
                updates.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, interruptedStep.getIndex());
                updates.put(OrchestrationStateKeys.WAITING_QUESTION,
                    interruptedStep.getWaitingQuestion() != null ? interruptedStep.getWaitingQuestion() : "");
                updates.put(OrchestrationStateKeys.ORCH_STATUS, "WAITING_USER");
                updates.put(OrchestrationStateKeys.WAITING_STEP_ID, interruptedStep.getStepId());
                updates.put(OrchestrationStateKeys.STEPS, stepsRaw);
                return updates;
            }

            updates.put(OrchestrationStateKeys.NEXT_ACTION, "无（全部完成）");
            updates.put(OrchestrationStateKeys.ORCH_STATUS, "DONE");
            log.info("[OrchestrationCondition.checkAndRoute] All steps completed");
        }

        return updates;
    }

    // ==================== Cancel Handling ====================

    // ==================== Phase 2D: Conditional Answer Evaluation ====================

    /**
     * Find an INTERRUPTED step whose conditionalAnswer depends on the just-completed stepId,
     * and evaluate the condition.
     *
     * <p>Supports comma-separated dependsOnStepId in ConditionalAnswer (e.g., "s-0,s-1").
     * Only evaluates when ALL depended-on steps have completed — prevents premature
     * condition evaluation before all required data is available.
     *
     * <p>Returns:
     * <ul>
     *   <li>Condition met → step (for AUTO_RESUME)</li>
     *   <li>Condition not met + cancelIfNotMet → step with status CANCELLED</li>
     *   <li>Condition not met + !cancelIfNotMet → null (fall through to popTopInterrupted)</li>
     * </ul>
     */
    private OrchestrationStep findConditionalAnswerTarget(List<?> steps, String completedStepId,
                                                            Map<String, Object> stepResults) {
        for (Object obj : steps) {
            if (!(obj instanceof OrchestrationStep step)) continue;
            if (step.getStatus() != StepStatus.INTERRUPTED) continue;
            ConditionalAnswer ca = step.getConditionalAnswer();
            if (ca == null) continue;

            // Check if completedStepId is one of the depended-on steps
            String dependsOnStepIds = ca.getDependsOnStepId();
            if (dependsOnStepIds == null || dependsOnStepIds.isEmpty()) continue;
            if (!isDependedOnBy(completedStepId, dependsOnStepIds)) continue;

            // Check if ALL depended-on steps have completed
            if (!areAllDependenciesMet(dependsOnStepIds, steps)) {
                log.info("[OrchestrationCondition] Conditional answer not all dependencies met yet: stepId={}, dependsOn={}, completed={}",
                    step.getStepId(), dependsOnStepIds, completedStepId);
                continue;
            }

            // All dependencies met — evaluate condition with scoped results
            boolean conditionMet = evaluateConditionalAnswerCondition(ca, steps, stepResults);
            log.info("[OrchestrationCondition] Conditional answer evaluation: stepId={}, condition='{}', result={}, cancelIfNotMet={}",
                step.getStepId(), ca.getCondition(), conditionMet, ca.isCancelIfNotMet());

            if (conditionMet) {
                return step; // Condition met → auto-resume
            } else if (ca.isCancelIfNotMet()) {
                // Condition not met + "否则就算了" → cancel the step
                step.setStatus(StepStatus.CANCELLED);
                step.setCancelReason("条件不满足: " + ca.getCondition());
                step.setConditionalAnswer(null);
                log.info("[OrchestrationCondition] Condition not met + cancelIfNotMet, cancelled stepId={}, reason={}",
                    step.getStepId(), step.getCancelReason());
                return step; // Return cancelled step so checkAndRoute can handle it
            } else {
                // Condition not met, no cancel → clear conditional answer, fall through to popTopInterrupted
                log.info("[OrchestrationCondition] Condition not met, clearing conditional answer for stepId={}",
                    step.getStepId());
                step.setConditionalAnswer(null);
                return null;
            }
        }
        return null;
    }

    /**
     * Check if completedStepId is one of the comma-separated depended-on stepIds.
     */
    private boolean isDependedOnBy(String completedStepId, String dependsOnStepIds) {
        for (String depId : dependsOnStepIds.split("\\s*,\\s*")) {
            if (completedStepId.equals(depId.trim())) return true;
        }
        return false;
    }

    /**
     * Check if ALL depended-on steps (comma-separated) have COMPLETED status.
     */
    private boolean areAllDependenciesMet(String dependsOnStepIds, List<?> steps) {
        for (String depId : dependsOnStepIds.split("\\s*,\\s*")) {
            if (depId.isEmpty()) continue;
            OrchestrationStep depStep = findStepByIndexOrId(depId.trim(), steps);
            if (depStep == null || depStep.getStatus() != StepStatus.COMPLETED) return false;
        }
        return true;
    }

    // ==================== INTERRUPTED Step Stack Recovery (LIFO) ====================

    /**
     * Find the top INTERRUPTED step (highest index) — stack-based recovery (LIFO by index).
     * DecomposeByIntent refactor: no level system. Resume order: highest index first
     * (last inserted → first recovered).
     * Skips steps whose conditionalAnswer is pending evaluation (handled by Phase 2D).
     */
    private OrchestrationStep popTopInterrupted(List<?> steps) {
        OrchestrationStep top = null;
        for (Object obj : steps) {
            if (!(obj instanceof OrchestrationStep step)) continue;
            if (step.getStatus() != StepStatus.INTERRUPTED) continue;
            if (step.getConditionalAnswer() != null) {
                continue;
            }
            if (top == null || step.getIndex() > top.getIndex()) {
                top = step;
            }
        }
        return top;
    }

    // ==================== Redundant Condition Step Collapse ====================

    /**
     * Collapse redundant "condition evaluation" steps produced by the planner.
     *
     * <p>When the planner creates a separate step like "判断收入是否>10000" (same intent as
     * the data-source step, with a condition field), that step would be routed to L2 again,
     * producing duplicate output. This method detects such steps and resolves them inline:
     * evaluate the condition directly and mark COMPLETED or SKIPPED — no L2 call needed.
     *
     * <p>Detection rule: step has a condition AND its dependsOn step has the same intent.
     */
    private void collapseRedundantConditionSteps(List<?> steps, Map<String, Object> stepResults) {
        for (Object obj : steps) {
            if (!(obj instanceof OrchestrationStep step)) continue;
            if (step.getStatus() != StepStatus.PENDING) continue;
            if (step.getCondition() == null || step.getCondition().isEmpty()) continue;
            if (step.getDependsOnStepIndex() == null || step.getDependsOnStepIndex().isEmpty()) continue;

            // Check if the depended-on step has the same intent
            String[] depIds = step.getDependsOnStepIndex().split("\\s*,\\s*");
            boolean sameIntentAsDep = false;
            for (String depId : depIds) {
                if (depId.isEmpty()) continue;
                OrchestrationStep depStep = findStepByIndexOrId(depId, steps);
                if (depStep != null && step.getIntent() != null && step.getIntent().equals(depStep.getIntent())) {
                    sameIntentAsDep = true;
                    break;
                }
            }

            if (sameIntentAsDep) {
                boolean met = evaluateStepCondition(step, steps, stepResults);
                step.setStatus(met ? StepStatus.COMPLETED : StepStatus.SKIPPED);
                if (!met) step.setCancelReason("条件不满足: " + step.getCondition());
                log.info("[OrchestrationCondition] Collapsed redundant condition step: stepId={}, intent={}, condition='{}', result={}",
                    step.getStepId(), step.getIntent(), step.getCondition(), met ? "COMPLETED" : "SKIPPED");
            }
        }
    }

    // ==================== Dependency Check ====================

    /**
     * Check if a step's dependency is met.
     * Supports comma-separated dependsOnStepIndex (e.g., "1,2" or "s-0,s-1").
     * ALL dependencies must be COMPLETED for the step to proceed.
     * @return true if the step has no dependency or ALL its dependencies are COMPLETED
     */
    private boolean isDependencyMet(OrchestrationStep step, List<?> allSteps) {
        String dependsOn = step.getDependsOnStepIndex();
        if (dependsOn == null || dependsOn.isEmpty()) {
            return true; // No dependency
        }
        String[] depIdentifiers = dependsOn.split("\\s*,\\s*");
        for (String depId : depIdentifiers) {
            if (depId.isEmpty()) continue;
            OrchestrationStep depStep = findStepByIndexOrId(depId, allSteps);
            if (depStep == null || depStep.getStatus() != StepStatus.COMPLETED) {
                return false; // At least one dependency not met
            }
        }
        return true; // All dependencies met
    }

    // ==================== Scoped Condition Evaluation ====================

    /**
     * Evaluate a step's condition against scoped results.
     * Scopes to only the results from steps that the current step depends on
     * (via {@code dependsOnStepIndex}), preventing false matches from unrelated
     * step results (e.g., transfer serial "TXN277519" matching "income > 100000").
     *
     * @param step The step whose condition to evaluate
     * @param allSteps All orchestration steps
     * @param allResults All step results (will be scoped before evaluation)
     * @return true if condition is met or null/empty
     */
    private boolean evaluateStepCondition(OrchestrationStep step,
                                            List<?> allSteps, Map<String, Object> allResults) {
        Map<String, Object> scopedResults = scopeResultsForDependency(
            step.getDependsOnStepIndex(), allSteps, allResults);
        return evaluateCondition(step.getCondition(), scopedResults);
    }

    /**
     * Evaluate a ConditionalAnswer's condition against scoped results.
     * Scopes to only the results from the step(s) that the answer depends on
     * (via {@code ConditionalAnswer.dependsOnStepId}).
     */
    private boolean evaluateConditionalAnswerCondition(ConditionalAnswer ca,
                                                         List<?> allSteps, Map<String, Object> allResults) {
        Map<String, Object> scopedResults = scopeResultsForDependency(
            ca.getDependsOnStepId(), allSteps, allResults);
        return evaluateCondition(ca.getCondition(), scopedResults);
    }

    /**
     * Scope step results to only the depended-on step(s)' results.
     *
     * <p>Supports comma-separated dependency identifiers (e.g., "1,2" or "s-0,s-1"),
     * enabling conditions that depend on multiple steps' results.
     *
     * <p>Behavior:
     * <ul>
     *   <li>dependsOn is null/empty → return allResults (backward compatible)</li>
     *   <li>dependsOn is single value → scope to that step's result</li>
     *   <li>dependsOn is comma-separated → scope to all listed steps' results</li>
     *   <li>depended-on step not found or no outputKey mapping → return allResults (safe fallback)</li>
     * </ul>
     *
     * <p>Domain→outputKey mapping is from yml config ({@code orchestration.agents[].domain + .outputKey}),
     * NOT from naming conventions — consistent with the project's config-as-truth principle.
     */
    private Map<String, Object> scopeResultsForDependency(String dependsOn,
                                                            List<?> allSteps, Map<String, Object> allResults) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return allResults; // No dependency — evaluate against all results
        }

        // Parse comma-separated dependency identifiers
        String[] depIdentifiers = dependsOn.split("\\s*,\\s*");
        Map<String, Object> scopedResults = new LinkedHashMap<>();
        boolean anyResolved = false;

        for (String depId : depIdentifiers) {
            if (depId.isEmpty()) continue;

            OrchestrationStep depStep = findStepByIndexOrId(depId, allSteps);
            if (depStep == null) {
                log.warn("[OrchestrationCondition] Cannot find depended-on step: {}, skipping", depId);
                continue;
            }

            String outputKey = domainToOutputKey.get(depStep.getDomain());
            if (outputKey == null) {
                log.warn("[OrchestrationCondition] No outputKey mapping for domain={}, skipping step {}",
                    depStep.getDomain(), depStep.getStepId());
                continue;
            }

            // Add the result and its _text variant
            if (allResults.containsKey(outputKey)) {
                scopedResults.put(outputKey, allResults.get(outputKey));
                anyResolved = true;
            }
            String textKey = outputKey + "_text";
            if (allResults.containsKey(textKey)) {
                scopedResults.put(textKey, allResults.get(textKey));
            }
        }

        if (!anyResolved) {
            log.warn("[OrchestrationCondition.scopeResultsForDependency] No results resolved for dependsOn='{}', falling back to all results", dependsOn);
            return allResults; // Safe fallback — no scoping if nothing resolved
        }

        log.info("[OrchestrationCondition.scopeResultsForDependency] Scoped results: dependsOn='{}' → scopedKeys={}", dependsOn, scopedResults.keySet());
        return scopedResults;
    }

    /**
     * Find a step by index (numeric string like "1") or stepId (like "s-0").
     */
    private OrchestrationStep findStepByIndexOrId(String indexOrId, List<?> steps) {
        // Try as numeric index first
        try {
            int idx = Integer.parseInt(indexOrId);
            return getStep(steps, idx);
        } catch (NumberFormatException e) {
            // Not a number — search by stepId
            for (Object obj : steps) {
                if (obj instanceof OrchestrationStep step && indexOrId.equals(step.getStepId())) {
                    return step;
                }
            }
            return null;
        }
    }

    // ==================== Condition Evaluation (core logic unchanged) ====================

    /**
     * Evaluate a step's condition string (e.g. "余额>50000")
     * Code-first approach: try pattern matching first, LLM fallback later.
     *
     * <p>IMPORTANT: Callers should use {@link #evaluateStepCondition} or
     * {@link #evaluateConditionalAnswerCondition} which scope results to
     * depended-on steps. Calling this directly with all stepResults may
     * produce false matches from unrelated step results.
     *
     * <p>Evaluation strategy:
     * 1. Fast path: try code-based evaluation for simple numeric comparisons (zero latency)
     * 2. LLM fallback: for complex/semantic conditions that code can't handle, call lightweight LLM
     *    (only triggered when condition exists, ~300-500ms latency)
     */
    public boolean evaluateCondition(String condition, Map<String, Object> stepResults) {
        if (condition == null || condition.isEmpty()) return true;

        // 1. Fast path: try code-based evaluation for simple numeric comparisons
        Boolean codeResult = tryCodeEvaluation(condition, stepResults);
        if (codeResult != null) {
            log.info("[OrchestrationCondition.evaluateCondition] Code path: condition='{}' → {}", condition, codeResult);
            return codeResult;
        }

        // 2. LLM fallback: for complex/semantic conditions
        log.info("[OrchestrationCondition.evaluateCondition] Code path cannot evaluate '{}', falling back to LLM", condition);
        boolean llmResult = evaluateWithLLM(condition, stepResults);
        log.info("[OrchestrationCondition.evaluateCondition] LLM path: condition='{}' → {}", condition, llmResult);
        return llmResult;
    }

    /**
     * Try code-based evaluation for simple numeric comparisons.
     * Returns null if condition is too complex for code (semantic, range, etc.).
     */
    private Boolean tryCodeEvaluation(String condition, Map<String, Object> stepResults) {
        try {
            String trimmed = condition.trim();

            // Normalize Chinese operators to symbolic form
            String normalized = trimmed
                .replaceAll("大于等于|不小于|≥", ">=")
                .replaceAll("小于等于|不超过|不大于|≤", "<=")
                .replaceAll("大于|超过|高于|多于", ">")
                .replaceAll("小于|低于|少于|不足", "<")
                .replaceAll("等于|相等|一样", "==");

            // Simple single comparison: field op number
            // Only handle the simplest case — if it has AND/且/并且/、 connectors, fall through to LLM
            if (normalized.contains("且") || normalized.contains("并且") || normalized.contains(" AND ")
                    || (normalized.contains("、") && normalized.chars().filter(c -> c == '>' || c == '<' || c == '=').count() > 1)) {
                // Complex condition — let LLM handle it
                return null;
            }

            // Order matters: check >= before >, <= before <, == last
            if (normalized.contains(">=")) {
                String[] parts = normalized.split(">=");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim().replaceAll("[^0-9.]", ""));
                    return compareStepResults(stepResults, threshold, (val, thresh) -> val >= thresh);
                }
            }

            if (normalized.contains("<=")) {
                String[] parts = normalized.split("<=");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim().replaceAll("[^0-9.]", ""));
                    return compareStepResults(stepResults, threshold, (val, thresh) -> val <= thresh);
                }
            }

            if (normalized.contains(">")) {
                String[] parts = normalized.split(">");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim().replaceAll("[^0-9.]", ""));
                    return compareStepResults(stepResults, threshold, (val, thresh) -> val > thresh);
                }
            }

            if (normalized.contains("<")) {
                String[] parts = normalized.split("<");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim().replaceAll("[^0-9.]", ""));
                    return compareStepResults(stepResults, threshold, (val, thresh) -> val < thresh);
                }
            }

            if (normalized.contains("==")) {
                String[] parts = normalized.split("==");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim().replaceAll("[^0-9.]", ""));
                    return compareStepResults(stepResults, threshold, (val, thresh) ->
                        Math.abs(val - thresh) < 0.01);
                }
            }

            // Not a simple numeric comparison — fall through to LLM
            return null;
        } catch (Exception e) {
            log.debug("[OrchestrationCondition.tryCodeEvaluation] Code evaluation failed for '{}': {}", condition, e.getMessage());
            return null;
        }
    }

    /**
     * Evaluate condition using lightweight LLM call.
     * Loads prompt from prompts/orch-condition-eval.st template, renders with condition + step results.
     * Only triggered when code fast-path cannot evaluate the condition (semantic/complex conditions).
     */
    private boolean evaluateWithLLM(String condition, Map<String, Object> stepResults) {
        try {
            // Build step results summary
            StringBuilder resultsText = new StringBuilder();
            for (Map.Entry<String, Object> entry : stepResults.entrySet()) {
                String val = extractTextValue(entry.getValue());
                if (val != null && !val.isEmpty()) {
                    // Truncate long results to keep prompt small
                    if (val.length() > 300) val = val.substring(0, 300) + "...";
                    resultsText.append("- ").append(entry.getKey()).append(": ").append(val).append("\n");
                }
            }

            // Load and render template
            String template = com.mobileagent.app.util.TemplateUtils.loadTemplate("prompts/orch-condition-eval.st");
            String prompt = template
                .replace("{condition}", condition)
                .replace("{step_results}", resultsText.length() > 0 ? resultsText.toString() : "（无结果）");

            String response = conditionChatClient.prompt()
                .user(prompt)
                .call()
                .content();

            if (response != null) {
                String trimmed = response.trim().toLowerCase();
                boolean result = trimmed.contains("true") && !trimmed.contains("false");
                log.info("[OrchestrationCondition.evaluateWithLLM] condition='{}', response='{}', result={}",
                    condition, trimmed, result);
                return result;
            }
        } catch (Exception e) {
            log.warn("[OrchestrationCondition.evaluateWithLLM] LLM evaluation failed for '{}': {}", condition, e.getMessage());
        }

        // LLM failed — default to false (safe: don't execute conditional step if we can't verify condition)
        return false;
    }

    /**
     * Extract text value from various result types (String, Message, Number, etc.)
     */
    private String extractTextValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof org.springframework.ai.chat.messages.Message msg) return msg.getText();
        return value.toString();
    }

    @FunctionalInterface
    private interface ThresholdComparator {
        boolean compare(double value, double threshold);
    }

    private boolean compareStepResults(Map<String, Object> stepResults, double threshold, ThresholdComparator comparator) {
        boolean anyNumberFound = false;

        for (Object val : stepResults.values()) {
            String text = null;

            if (val instanceof Number) {
                double d = ((Number) val).doubleValue();
                anyNumberFound = true;
                if (comparator.compare(d, threshold)) return true;
                continue;
            }

            if (val instanceof String) {
                text = (String) val;
            } else if (val instanceof org.springframework.ai.chat.messages.Message msg) {
                text = msg.getText();
            }

            if (text != null) {
                String normalized = text.replaceAll("(\\d),(\\d)", "$1$2");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\d]+\\.?[\\d]*").matcher(normalized);
                while (m.find()) {
                    try {
                        double d = Double.parseDouble(m.group());
                        anyNumberFound = true;
                        if (comparator.compare(d, threshold)) return true;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (!anyNumberFound) {
            log.info("[OrchestrationCondition.compareStepResults] No numbers found in step results, defaulting to false");
            return false;
        }

        return false;
    }

    // ==================== Step Results Collection ====================

    /**
     * Collect step results from state for condition evaluation.
     * Gathers both explicit STEP_RESULTS and agent outputKey results (bill_result, transfer_result, etc.).
     */
    private Map<String, Object> collectStepResults(OverAllState state) {
        Map<String, Object> stepResults = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> rawResults = (Map<String, Object>) state.value(OrchestrationStateKeys.STEP_RESULTS).orElse(Map.of());
        if (rawResults != null) stepResults.putAll(rawResults);

        // Also collect agent outputKey results (bill_result, transfer_result, etc.)
        for (String key : state.data().keySet()) {
            if (key.endsWith("_result") && !stepResults.containsKey(key)) {
                Object val = state.value(key).orElse(null);
                if (val != null) {
                    stepResults.put(key, val);
                    if (val instanceof org.springframework.ai.chat.messages.Message msg) {
                        stepResults.put(key + "_text", msg.getText());
                    }
                }
            }
        }
        return stepResults;
    }

    // ==================== Step Finding ====================

    /**
     * Find next PENDING step — simple index order (no level system).
     *
     * <p>DecomposeByIntent refactor: level system removed. Steps execute in index order.
     * <ul>
     *   <li>PENDING steps execute in ascending index order</li>
     *   <li>Dependency check: dependsOnStepIndex must be COMPLETED before step can execute</li>
     *   <li>Condition evaluation: scoped to depended-on step's result only (not ALL results)</li>
     *   <li>Condition not met → SKIPPED</li>
     * </ul>
     */
    private int findNextPendingStep(List<?> steps, int startIdx, Map<String, Object> stepResults) {
        for (int i = 0; i < steps.size(); i++) {
            OrchestrationStep step = getStep(steps, i);
            if (step == null) continue;
            if (step.getStatus() != StepStatus.PENDING && step.getStatus() != null) continue;
            if (!isDependencyMet(step, steps)) {
                log.info("[OrchestrationCondition.findNextPendingStep] Step {} (stepId={}) SKIPPED — dependency not met (dependsOnStepIndex={})",
                    i, step.getStepId(), step.getDependsOnStepIndex());
                continue;
            }
            if (evaluateStepCondition(step, steps, stepResults)) {
                log.info("[OrchestrationCondition.findNextPendingStep] Found PENDING step: index={} stepId={} domain={}",
                    i, step.getStepId(), step.getDomain());
                return i;
            } else {
                step.setStatus(StepStatus.SKIPPED);
                log.info("[OrchestrationCondition.findNextPendingStep] Step {} SKIPPED (condition not met: {})",
                    i, step.getCondition());
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private OrchestrationStep getStep(List<?> steps, int index) {
        if (steps == null || index < 0 || index >= steps.size()) return null;
        Object obj = steps.get(index);
        return obj instanceof OrchestrationStep ? (OrchestrationStep) obj : null;
    }

    private String describeStep(OrchestrationStep step) {
        return step != null ? step.getIntent() + "→" + step.getDescription() : "";
    }
}
