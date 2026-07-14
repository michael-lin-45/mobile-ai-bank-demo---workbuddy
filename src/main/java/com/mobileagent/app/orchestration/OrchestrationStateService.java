package com.mobileagent.app.orchestration;

import com.mobileagent.app.orchestration.model.OrchestrationStatus;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排状态管理服务 — Phase 1: ConcurrentHashMap in-memory
 * 管理OrchestrationState（编排层外部视图，非OverAllState）
 */
@Slf4j
@Component
public class OrchestrationStateService {

    private final ConcurrentHashMap<String, OrchestrationState> store = new ConcurrentHashMap<>();

    public OrchestrationState get(String sessionId) {
        return store.get(sessionId);
    }

    public OrchestrationState getOrCheckExpired(String sessionId) {
        OrchestrationState state = store.get(sessionId);
        if (state == null) return null;
        // Check expiry (5 minutes)
        if (state.isExpired()) {
            store.remove(sessionId);
            return null;
        }
        return state;
    }

    public void saveState(String sessionId, OrchestrationState state) {
        state.setLastActiveTime(System.currentTimeMillis());
        store.put(sessionId, state);
    }

    public void enqueuePendingInput(String sessionId, String userInput) {
        OrchestrationState state = store.get(sessionId);
        if (state != null) {
            state.getPendingInputs().add(userInput);
        }
    }

    /**
     * 编排状态 — OverAllState的外部视图
     * OverAllState是唯一真源，此对象用于handle()等外部API判断路由
     *
     * <p>只保留有读取者的字段：status, waitingQuestion, graphThreadId, pendingInputs, lastActiveTime
     */
    @Data
    public static class OrchestrationState {
        private OrchestrationStatus status = OrchestrationStatus.PLANNING;
        private String waitingQuestion;
        private String graphThreadId; // 编排图的threadId，用于resume

        // Pending input queue (for EXECUTING state interruptions)
        private LinkedList<String> pendingInputs = new LinkedList<>();

        // Expiry
        private long lastActiveTime = System.currentTimeMillis();
        private static final long EXPIRY_MS = 5 * 60 * 1000; // 5 minutes

        public boolean isExpired() {
            return System.currentTimeMillis() - lastActiveTime > EXPIRY_MS;
        }
    }
}
