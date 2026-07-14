package com.mobileagent.app.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.mobileagent.app.orchestration.model.OrchestrationStep;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Summary helper — builds the final summary from all step results.
 * Provides the data that SummaryAgent will use to generate a user-facing summary.
 */
@Slf4j
@Component
public class OrchestrationSummary {

    /**
     * Build summary context from orchestration state for SummaryAgent.
     * This provides the data that SummaryAgent will use to generate a user-facing summary.
     *
     * @param state Current OverAllState containing all step results
     * @return Map with "messages" containing the summary prompt
     */
    public Map<String, Object> buildSummaryPrompt(OverAllState state) {
        String originalRequest = (String) state.value(OrchestrationStateKeys.ORIGINAL_REQUEST).orElse("");
        @SuppressWarnings("unchecked")
        List<?> stepsRaw = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("请汇总以下多意图请求的执行结果：\n\n");
        sb.append("用户原始请求：").append(originalRequest).append("\n\n");

        for (Object obj : stepsRaw) {
            if (obj instanceof OrchestrationStep step) {
                sb.append(String.format("步骤%d [%s-%s]: %s → 状态: %s",
                    step.getIndex(),
                    step.getDomain(),
                    step.getIntent(),
                    step.getDescription(),
                    step.getStatus()));
                if (step.getCancelReason() != null) {
                    sb.append(" (原因: ").append(step.getCancelReason()).append(")");
                }
                // For SKIPPED steps with condition, explain why they were skipped
                if (step.getStatus() == com.mobileagent.app.orchestration.model.StepStatus.SKIPPED
                        && step.getCondition() != null && !step.getCondition().isEmpty()) {
                    sb.append(" (跳过原因: 用户设定的条件「")
                      .append(step.getCondition())
                      .append("」未满足，因此未执行此步骤。必须如实告知用户是因为该条件未满足，不得编造其他理由如权限不足、系统限制等)");
                }
                sb.append("\n");
            }
        }

        sb.append("\n请用简洁自然的语言向用户汇报执行结果。");

        log.info("[OrchestrationSummary.buildSummaryPrompt] Summary prompt:\n{}", sb.toString());

        return Map.of("messages", List.of(new UserMessage(sb.toString())));
    }
}
