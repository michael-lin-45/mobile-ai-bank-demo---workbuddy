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

    /** 根据状态提取回复内容 */
    public String getReplyContent() {
        return switch (status) {
            case COMPLETED -> content;
            case INTERRUPTED, DISAMBIGUATION -> question;
            case ERROR -> errorMessage;
        };
    }

    /** 兼容旧代码: 获取状态的字符串值 */
    public String getStatusString() {
        return status != null ? status.getValue() : null;
    }
}
