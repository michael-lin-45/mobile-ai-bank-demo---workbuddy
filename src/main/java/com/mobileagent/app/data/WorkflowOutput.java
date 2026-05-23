package com.mobileagent.app.data;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Graph执行结果DTO - Controller返回给前端的统一响应格式
 *
 * 状态类型:
 * - COMPLETED: 操作完成
 * - INTERRUPTED: 子智能体需要用户补充参数
 * - DISAMBIGUATION: 意图消歧,需要用户明确意图
 * - ERROR: 错误
 */
@Data
@Builder
public class WorkflowOutput {

    /** 状态: COMPLETED / INTERRUPTED / DISAMBIGUATION / ERROR */
    private String status;

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

    public static WorkflowOutput completed(String intent, String content) {
        return WorkflowOutput.builder()
                .status("COMPLETED")
                .intent(intent)
                .content(content)
                .build();
    }

    public static WorkflowOutput interrupted(String intent, String threadId, String question) {
        return WorkflowOutput.builder()
                .status("INTERRUPTED")
                .intent(intent)
                .threadId(threadId)
                .question(question)
                .build();
    }

    /** 意图消歧 - 需要用户明确具体意图 */
    public static WorkflowOutput disambiguation(String question, List<String> candidates) {
        return WorkflowOutput.builder()
                .status("DISAMBIGUATION")
                .question(question)
                .candidateIntents(candidates)
                .build();
    }

    public static WorkflowOutput error(String errorMessage) {
        return WorkflowOutput.builder()
                .status("ERROR")
                .errorMessage(errorMessage)
                .build();
    }
}
