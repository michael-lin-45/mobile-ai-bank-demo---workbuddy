package com.mobileagent.app.data;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 路由决议 - IntentResolver的返回类型
 *
 * 三种状态:
 * - RESOLVED:      意图明确，可以执行Phase3
 * - DISAMBIGUATION: 意图模糊，需要追问用户
 * - REJECTED:      无法识别，返回"不支持该功能"
 *
 * 设计原则:
 * - Controller只看status，不需要知道消歧细节
 * - 未来IntentResolver内部实现可以替换为Graph，接口不变
 */
@Data
@Builder
public class RoutingResolution {

    /** 决议状态 */
    private RoutingStatus status;

    /** 识别的意图 (RESOLVED时非null) */
    private String intentName;

    /** 改写后的输入 (RESOLVED时非null) */
    private String rewrittenInput;

    /** 路由类型 SWITCH_NEW/RESUME (RESOLVED时非null) */
    private String routeType;

    /** 追问内容 (DISAMBIGUATION时非null) */
    private String question;

    /** 候选意图 (DISAMBIGUATION时非null) */
    private List<String> candidateIntents;

    /** 决议状态枚举 */
    public enum RoutingStatus {
        /** 意图明确，可以执行 */
        RESOLVED,
        /** 意图模糊，需要追问 */
        DISAMBIGUATION,
        /** 无法识别，拒绝 */
        REJECTED
    }

    // ==================== 工厂方法 ====================

    public static RoutingResolution resolved(String intentName, String rewrittenInput, String routeType) {
        return RoutingResolution.builder()
                .status(RoutingStatus.RESOLVED)
                .intentName(intentName)
                .rewrittenInput(rewrittenInput)
                .routeType(routeType)
                .build();
    }

    public static RoutingResolution disambiguation(String question, List<String> candidateIntents) {
        return RoutingResolution.builder()
                .status(RoutingStatus.DISAMBIGUATION)
                .question(question)
                .candidateIntents(candidateIntents)
                .build();
    }

    public static RoutingResolution rejected() {
        return RoutingResolution.builder()
                .status(RoutingStatus.REJECTED)
                .build();
    }

    // ==================== 便捷判断 ====================

    public boolean isResolved() {
        return status == RoutingStatus.RESOLVED;
    }

    public boolean isDisambiguation() {
        return status == RoutingStatus.DISAMBIGUATION;
    }

    public boolean isRejected() {
        return status == RoutingStatus.REJECTED;
    }
}
