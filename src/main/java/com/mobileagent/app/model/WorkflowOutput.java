package com.mobileagent.app.model;

import lombok.Builder;
import lombok.Data;

/**
 * Graph执行结果DTO - Controller返回给前端的统一响应格式
 */
@Data
@Builder
public class WorkflowOutput {

    /** 状态: COMPLETED / INTERRUPTED / ERROR */
    private String status;

    /** 输出内容 (COMPLETED时) */
    private String content;

    /** 提问内容 (INTERRUPTED时) */
    private String question;

    /** 当前意图 */
    private String intent;

    /** 线程ID */
    private String threadId;

    /** 错误信息 (ERROR时) */
    private String errorMessage;

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

    public static WorkflowOutput error(String errorMessage) {
        return WorkflowOutput.builder()
                .status("ERROR")
                .errorMessage(errorMessage)
                .build();
    }
}
