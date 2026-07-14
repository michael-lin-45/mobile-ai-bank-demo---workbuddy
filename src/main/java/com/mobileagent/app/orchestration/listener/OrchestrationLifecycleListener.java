package com.mobileagent.app.orchestration.listener;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class OrchestrationLifecycleListener implements GraphLifecycleListener {

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long timestamp) {
        log.info("[OrchestrationLifecycleListener] BEFORE node={} step={} threadId={}",
            nodeId,
            state.getOrDefault(OrchestrationStateKeys.CURRENT_STEP_INDEX, "?"),
            config.threadId().orElse("unknown"));
    }

    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long timestamp) {
        log.info("[OrchestrationLifecycleListener] AFTER node={} status={} threadId={}",
            nodeId,
            state.getOrDefault(OrchestrationStateKeys.STEP_STATUS, "UNKNOWN"),
            config.threadId().orElse("unknown"));
    }

    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable error, RunnableConfig config) {
        log.error("[OrchestrationLifecycleListener] ERROR node={} error={}", nodeId, error.getMessage(), error);
    }

    @Override
    public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
        log.info("[OrchestrationLifecycleListener] COMPLETE node={}", nodeId);
    }
}
