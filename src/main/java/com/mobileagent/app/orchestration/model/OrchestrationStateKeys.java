package com.mobileagent.app.orchestration.model;

/**
 * OverAllState key constants for the orchestration StateGraph.
 */
public final class OrchestrationStateKeys {
    private OrchestrationStateKeys() {}

    // Session identity
    public static final String SESSION_ID = "orch_sessionId";

    // Core orchestration state
    public static final String STEPS = "orch_steps";
    public static final String CURRENT_STEP_INDEX = "orch_currentStepIndex";
    public static final String STEP_RESULTS = "orch_stepResults";
    public static final String ORIGINAL_REQUEST = "orch_originalRequest";
    public static final String LATEST_USER_INPUT = "orch_latestUserInput";
    public static final String ORCH_STATUS = "orch_status";  // PLANNING / EXECUTING / WAITING_USER / REPLANNING / DONE
    public static final String WAITING_QUESTION = "orch_waitingQuestion";
    public static final String INTERRUPTION_REASON = "orch_interruptionReason";

    // Observability fields
    public static final String CURRENT_ACTION = "orch_currentAction";
    public static final String NEXT_ACTION = "orch_nextAction";
    public static final String STEP_STATUS = "orch_stepStatus";
    public static final String AGENT_PHASE = "orch_agentPhase";
    public static final String AGENT_NAME = "orch_agentName";
    public static final String CURRENT_TOOL_CALLS = "orch_currentToolCalls";

    // L2 execution status — written by L2GraphTool, read by conditionCheckNode
    public static final String STEP_L2_STATUS = "_stepL2Status";  // "COMPLETED" / "ERROR" / "INTERRUPTED"

    // stepId → threadId 映射
    public static final String L2_STEP_THREAD_MAP = "_l2_stepThreadMap";

    // 步骤级等待状态
    public static final String WAITING_STEP_ID = "orch_waitingStepId";
    public static final String WAITING_STEP_INDEX = "orch_waitingStepIndex";

    // 条件性回答自动恢复信号 (conditionCheckNode 设置, routeAfterCondition 消费, 下一轮 conditionCheckNode 清除)
    public static final String AUTO_RESUME_STEP_ID = "_autoResumeStepId";
    public static final String AUTO_RESUME_ANSWER = "_autoResumeAnswer";

    // L1 取消意图领养 — scanL1Interrupts 检测到取消信号时写入，StepPreparator 消费后清除
    // 类型: List<Map<String,String>> 每项含 intent/domain/threadId
    public static final String CANCELLED_L1_INTENTS = "orch_cancelledL1Intents";
}
