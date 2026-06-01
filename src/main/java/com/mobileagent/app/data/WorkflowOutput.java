package com.mobileagent.app.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Graph执行结果DTO - Controller返回给前端的统一响应格式
 *
 * 状态类型: 见 {@link WorkflowStatus}
 *
 * 改造后角色: 仅作为 Accept: application/json 的视图适配器
 * SSE模式不使用此类，直接序列化StreamChunk
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

    /** 错误信息 (ERROR时) */
    private String errorMessage;

    /** 候选意图列表 (DISAMBIGUATION时) */
    private List<String> candidateIntents;

    /** REROUTE时建议的目标意图 — L1→L0内部信号，不暴露给前端 */
    @JsonIgnore
    private String rerouteIntent;

    /** REROUTE时建议的目标域 — L1→L0内部信号，不暴露给前端 */
    @JsonIgnore
    private String rerouteHint;

    public static WorkflowOutput completed(String intent, String content) {
        return WorkflowOutput.builder()
                .status(WorkflowStatus.COMPLETED)
                .intent(intent)
                .content(content)
                .build();
    }

    public static WorkflowOutput interrupted(String intent, String question) {
        return WorkflowOutput.builder()
                .status(WorkflowStatus.INTERRUPTED)
                .intent(intent)
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

    /** 根据状态提取回复内容 — 仅内部使用（ChatMemory写入），不序列化给前端 */
    @JsonIgnore
    public String getReplyContent() {
        return switch (status) {
            case COMPLETED -> content;
            case INTERRUPTED, DISAMBIGUATION -> question;
            case ERROR -> errorMessage;
            case REROUTE -> null; // REROUTE不对用户可见，由BankController内部处理
        };
    }

}
