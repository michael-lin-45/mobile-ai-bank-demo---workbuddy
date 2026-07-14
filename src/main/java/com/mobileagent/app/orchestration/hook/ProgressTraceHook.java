package com.mobileagent.app.orchestration.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
public class ProgressTraceHook extends AgentHook {

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String agentName = this.getAgentName();
        int msgCount = state.value("messages")
            .map(m -> ((List<?>) m).size())
            .orElse(0);

        log.info("[ProgressTraceHook] {} 开始推理, messages={}", agentName, msgCount);

        return CompletableFuture.completedFuture(Map.of(
            OrchestrationStateKeys.AGENT_PHASE, "REASONING",
            OrchestrationStateKeys.AGENT_NAME, agentName
        ));
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        String agentName = this.getAgentName();
        List<?> messages = state.value("messages").map(m -> (List<?>) m).orElse(List.of());
        Object lastMsg = messages.isEmpty() ? null : messages.get(messages.size() - 1);

        if (lastMsg instanceof AssistantMessage am && am.hasToolCalls()) {
            String toolNames = am.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::name)
                .collect(Collectors.joining(", "));
            log.info("[ProgressTraceHook] {} 决定调用Tool: {}", agentName, toolNames);
            return CompletableFuture.completedFuture(Map.of(
                OrchestrationStateKeys.AGENT_PHASE, "TOOL_CALLING",
                OrchestrationStateKeys.CURRENT_TOOL_CALLS, toolNames
            ));
        }

        log.info("[ProgressTraceHook] {} 推理完成，无Tool调用", agentName);
        return CompletableFuture.completedFuture(Map.of(
            OrchestrationStateKeys.AGENT_PHASE, "COMPLETED"
        ));
    }

    @Override
    public String getName() { return "PROGRESS_TRACE"; }

    @Override
    public List<JumpTo> canJumpTo() { return List.of(); }
}
