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

    /** IntentRouter判断不属于本域 (REROUTE依据) */
    private boolean outOfDomain;

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
        REJECTED,
        /** 用户取消当前操作(如消歧中取消) */
        CANCELLED,
        /** 意图不属于本域，需要L0重新路由 */
        REROUTE
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

    public static RoutingResolution cancelled() {
        return RoutingResolution.builder()
                .status(RoutingStatus.CANCELLED)
                .build();
    }

    /** 意图不属于本域，需要重新路由 */
    public static RoutingResolution reroute(String intentName, String targetDomain) {
        return RoutingResolution.builder()
                .status(RoutingStatus.REROUTE)
                .intentName(intentName)
                .routeType(targetDomain != null ? "REROUTE_" + targetDomain : "REROUTE")
                .build();
    }

    /** 意图明确但不属于本域 */
    public static RoutingResolution outOfDomain(String intentName, String rewrittenInput) {
        return RoutingResolution.builder()
                .status(RoutingStatus.RESOLVED)
                .intentName(intentName)
                .rewrittenInput(rewrittenInput)
                .outOfDomain(true)
                .routeType("SWITCH_NEW")
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

    public boolean isCancelled() {
        return status == RoutingStatus.CANCELLED;
    }

    public boolean isReroute() {
        return status == RoutingStatus.REROUTE;
    }

    public boolean isOutOfDomain() {
        return outOfDomain;
    }
}
