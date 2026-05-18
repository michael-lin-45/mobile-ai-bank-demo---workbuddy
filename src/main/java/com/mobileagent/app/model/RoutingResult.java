package com.mobileagent.app.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * LLM路由结果DTO - 统一承载Phase1(意图类型判断)和Phase2(改写+识别)的输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingResult {

    /** Phase1: 意图类型 FOLLOW_UP / SWITCH_NEW / RESUME */
    private String routeType;

    /** Phase2: 改写后的用户输入 */
    private String rewrittenInput;

    /** Phase2: 识别出的意图名称 (TRANSFER / BILL_QUERY / UNKNOWN) */
    private String intentName;

    /** Phase2: 路由类型(细化) SWITCH_NEW / RESUME */
    private String refinedRouteType;

    /** Phase2: RESUME时指定恢复目标意图 */
    private String resumeTarget;

    /** 置信度 0.0~1.0 */
    private double confidence;

    /** 判断理由 */
    private String reasoning;

    public boolean isFollowUp() {
        return "FOLLOW_UP".equals(routeType);
    }

    public boolean isSwitchNew() {
        return "SWITCH_NEW".equals(routeType) || "SWITCH_NEW".equals(refinedRouteType);
    }

    public boolean isResume() {
        return "RESUME".equals(routeType) || "RESUME".equals(refinedRouteType);
    }
}
