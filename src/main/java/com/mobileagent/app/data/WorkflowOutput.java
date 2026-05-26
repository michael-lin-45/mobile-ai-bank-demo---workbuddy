package com.mobileagent.app.data;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Graph执行结果DTO - Controller返回给前端的统一响应格式
 *
 * 状态类型: 见 {@link WorkflowStatus}
 */
@Data
@Builder
public class WorkflowOutput {

    /** 状态 */
    private WorkflowStatus status;

    /** 输出内容 (COMPLETED时) */
    private String content;

    /** 提问内容 (INTERRUPTED/DISAMBIGUATION时) */
    private String question;

    /** 当前意图 */
    private String intent;

    /** 线程ID */
    private String threadId;

    /** 错误信息 (ERROR时) */
    private String errorMessage;

    /** 候选意图列表 (DISAMBIGUATION时) */
    private List<String> candidateIntents;

    /** 从Graph state中提取的已收集参数 (由L1 Service保存到自己的activeThread) */
    private Map<String, Object> accumulatedParams;

    /** REROUTE时建议的目标意图(如"TRANSFER")，可为null */
    private String rerouteIntent;

    /** REROUTE时建议的目标域(如"TRANSFER")，可为null */
    private String rerouteHint;

    public static WorkflowOutput completed(String intent, String content) {
        return WorkflowOutput.builder()
                .status(WorkflowStatus.COMPLETED)
                .intent(intent)
                .content(content)
                .build();
    }

    public static WorkflowOutput interrupted(String intent, String threadId, String question) {
        return WorkflowOutput.builder()
                .status(WorkflowStatus.INTERRUPTED)
                .intent(intent)
                .threadId(threadId)
                .question(question)
                .build();
    }

    /** 意图消歧 - 需要用户明确具体意图 */
    public static WorkflowOutput disambiguation(String question, List<String> candidates) {
        return WorkflowOutput.builder()
                .status(WorkflowStatus.DISAMBIGUATION)
                .question(question)
                .candidateIntents(candidates)
                .build();
    }

    public static WorkflowOutput error(String errorMessage) {
        return WorkflowOutput.builder()
                .status(WorkflowStatus.ERROR)
                .errorMessage(errorMessage)
                .build();
    }

    /** L1无法处理，需要L0重新路由 */
    public static WorkflowOutput reroute(String rerouteIntent, String rerouteHint) {
        return WorkflowOutput.builder()
                .status(WorkflowStatus.REROUTE)
                .rerouteIntent(rerouteIntent)
                .rerouteHint(rerouteHint)
                .build();
    }

    /** 根据状态提取回复内容 */
    public String getReplyContent() {
        return switch (status) {
            case COMPLETED -> content;
            case INTERRUPTED, DISAMBIGUATION -> question;
            case ERROR -> errorMessage;
            case REROUTE -> null; // REROUTE不对用户可见，由BankController内部处理
        };
    }

}
