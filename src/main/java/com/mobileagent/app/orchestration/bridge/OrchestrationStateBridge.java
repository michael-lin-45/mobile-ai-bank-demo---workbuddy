package com.mobileagent.app.orchestration.bridge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.mobileagent.app.orchestration.OrchestrationStateService;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import com.mobileagent.app.orchestration.model.OrchestrationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrchestrationStateBridge {
    private final OrchestrationStateService stateService;

    public OrchestrationStateBridge(OrchestrationStateService stateService) {
        this.stateService = stateService;
    }

    /** Sync from OverAllState to OrchestrationState (after graph execution) */
    public void syncFromGraph(String sessionId, OverAllState graphState) {
        OrchestrationStateService.OrchestrationState view = stateService.getOrCheckExpired(sessionId);
        if (view == null) return;

        // Single direction: OverAllState → OrchestrationState
        String orchStatus = graphState.value(OrchestrationStateKeys.ORCH_STATUS, "");
        if (orchStatus != null && !orchStatus.isEmpty()) {
            view.setStatus(mapStatus(orchStatus));
        }
        view.setWaitingQuestion(graphState.<String>value(OrchestrationStateKeys.WAITING_QUESTION).orElse(null));

        stateService.saveState(sessionId, view);
    }

    private OrchestrationStatus mapStatus(String status) {
        try {
            return OrchestrationStatus.valueOf(status);
        } catch (Exception e) {
            return OrchestrationStatus.EXECUTING; // default
        }
    }
}
