package com.mobileagent.app.orchestration;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Quick bypass for WAITING_USER input — decomposeByIntent refactor.
 *
 * <p>Only retains Level 1 fast bypass (0ms, deterministic):
 * <ul>
 *   <li>Pure number/with unit ("500", "500元", "300股") → ANSWER</li>
 *   <li>Confirmation words ("确认", "好的", "是的") → ANSWER</li>
 * </ul>
 *
 * <p>All other inputs are handled by decomposeByIntent (OrchestrationAgent Level 2).
 * The old LLM classifyWithLLM (ABCD classification) has been removed.
 *
 * <p>Cancel detection is also removed — decomposeByIntent handles CANCEL/CANCEL_ALL
 * as operation types in its output.
 */
@Slf4j
@Component
public class OrchestrationRelevance {

    private static final Set<String> CONFIRM_WORDS = Set.of(
        "确认", "是", "对", "好", "同意", "继续", "没错", "嗯", "是的"
    );

    // ==================== Classification Result ====================

    public enum InputClassification {
        ANSWER,       // Pure number / confirmation — fast bypass
        COMPLEX       // Everything else — needs decomposeByIntent
    }

    @Data
    public static class ClassificationResult {
        private InputClassification type;

        public static ClassificationResult answer() {
            ClassificationResult r = new ClassificationResult();
            r.setType(InputClassification.ANSWER);
            return r;
        }

        public static ClassificationResult complex() {
            ClassificationResult r = new ClassificationResult();
            r.setType(InputClassification.COMPLEX);
            return r;
        }
    }

    // ==================== Main Classification API ====================

    /**
     * Quick bypass check — only deterministic rules, no LLM.
     * Returns ANSWER for pure numbers/confirmations, COMPLEX for everything else.
     */
    public ClassificationResult classifyInput(String userInput, String waitingQuestion) {
        if (userInput == null || userInput.isBlank()) {
            return ClassificationResult.answer();
        }
        String trimmed = userInput.trim();

        // 1. Confirmation words → ANSWER
        if (CONFIRM_WORDS.contains(trimmed)) {
            log.info("[OrchestrationRelevance] ANSWER (confirm word): '{}'", trimmed);
            return ClassificationResult.answer();
        }

        // 2. Pure number → likely answering an amount question
        if (trimmed.matches("^\\d+(\\.\\d+)?$")) {
            log.info("[OrchestrationRelevance] ANSWER (pure number): '{}'", trimmed);
            return ClassificationResult.answer();
        }

        // 3. Number with unit (e.g. "5000元", "300股") → likely an answer
        if (trimmed.matches("^\\d+(\\.\\d+)?[元块万股份]?$")) {
            log.info("[OrchestrationRelevance] ANSWER (number+unit): '{}'", trimmed);
            return ClassificationResult.answer();
        }

        // Everything else → COMPLEX, needs decomposeByIntent
        log.info("[OrchestrationRelevance] COMPLEX (needs decomposeByIntent): '{}'", trimmed);
        return ClassificationResult.complex();
    }
}
