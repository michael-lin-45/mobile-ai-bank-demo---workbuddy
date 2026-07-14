package com.mobileagent.app.data;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * LLM路由结果DTO - 统一承载Phase1(意图类型判断)和Phase2(改写+识别)的输出
 *
 * Phase1输出: routeType = FOLLOW / SWITCH / CANCEL
 * Phase2输出: intentName, rewrittenInput, confidence, ambiguous, candidateIntents, belongsToDomain
 *
 * RESUME不由LLM输出，由executeRoute根据suspendedAgents状态决定，因此本DTO不含RESUME相关字段。
 * 支持消歧: Phase2可能返回多个候选意图(candidateIntents),
 * 此时Controller进入消歧模式追问用户。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingResult {

    /** Phase1: 意图类型 FOLLOW / SWITCH / CANCEL */
    private String routeType;

    /** Phase2: 改写后的用户输入 */
    private String rewrittenInput;

    /** Phase2: 识别出的意图名称 (TRANSFER / BILL_QUERY / WEALTH / UNKNOWN) */
    private String intentName;

    /** Phase2: 候选意图列表(消歧场景,如[WEALTH_CONSULT, WEALTH_INTERPRET]) */
    private List<String> candidateIntents;

    /** Phase2: 是否歧义(有多个候选但无法确定) */
    private boolean ambiguous;

    /** 置信度 0.0~1.0 */
    private double confidence;

    /** 判断理由 */
    private String reasoning;

    /** Phase2: SubGraphRouter判断用户意图是否属于当前域的处理范围(REROUTE依据)，默认true(保守) */
    @Builder.Default
    private boolean belongsToDomain = true;

    public boolean isFollow() {
        return "FOLLOW".equals(routeType);
    }

    public boolean isSwitch() {
        return "SWITCH".equals(routeType);
    }

    public boolean isCancel() {
        return "CANCEL".equals(routeType);
    }

    public boolean isAmbiguous() {
        return ambiguous || (candidateIntents != null && candidateIntents.size() > 1);
    }
}
