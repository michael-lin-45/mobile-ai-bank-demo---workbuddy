package com.mobileagent.app.service;

import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResolution;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.state.AgentStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 路由服务 - 封装Phase2(改写+识别) + 消歧 + 模糊匹配
 *
 * 核心设计:
 * - 一个方法(resolve)，一次调用，一个结果
 * - Controller不需要知道消歧细节，只看RoutingResolution.status
 * - 消歧1次追问，回答仍模糊则直接拒绝（手机银行用户不会反复给模糊答案）
 *
 * 职责边界:
 * - 负责: Phase2意图识别 + 消歧追问(1次) + 模糊匹配
 * - 不负责: Phase1路由类型判断(IntentRouter) / Graph执行(GraphExecutionService) / 线程管理(BankController)
 */
@Slf4j
@Service
public class RoutingService {

    private final ContextRewriter contextRewriter;
    private final IntentRegistry intentRegistry;
    private final AgentStateManager stateManager;

    public RoutingService(ContextRewriter contextRewriter,
                          IntentRegistry intentRegistry,
                          AgentStateManager stateManager) {
        this.contextRewriter = contextRewriter;
        this.intentRegistry = intentRegistry;
        this.stateManager = stateManager;
    }

    // ==================== 公共入口 ====================

    /**
     * 路由决策 - 统一入口
     *
     * 如果当前在消歧中，处理消歧回答。
     * 如果不在消歧中，执行Phase2 + 消歧检查 + 模糊匹配。
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param phase1Result Phase1的路由结果
     * @return 路由决议 (RESOLVED / DISAMBIGUATION / REJECTED)
     */
    public RoutingResolution resolve(String sessionId, String userInput, RoutingResult phase1Result) {
        if (stateManager.isInDisambiguation(sessionId)) {
            return handleDisambiguationAnswer(sessionId, userInput, phase1Result);
        }
        return resolveNewIntent(sessionId, userInput, phase1Result);
    }

    // ==================== 新意图识别 ====================

    /**
     * 识别新意图 - Phase2 + 消歧检查 + 模糊匹配
     *
     * 决策链:
     * 1. Phase2识别 → ambiguous? → 消歧
     * 2. UNKNOWN? → 拒绝
     * 3. isGroupName? → 消歧
     * 4. !hasIntent? → fuzzyMatch → 可能消歧或拒绝
     * 5. hasIntent? → RESOLVED
     */
    private RoutingResolution resolveNewIntent(String sessionId, String userInput, RoutingResult phase1Result) {
        // Phase2: 上下文改写 + 意图识别
        RoutingResult phase2 = contextRewriter.rewriteAndIdentify(sessionId, userInput, phase1Result, stateManager);
        log.info("[RoutingService] Phase2: intent={}, ambiguous={}, candidates={}",
                phase2.getIntentName(), phase2.isAmbiguous(), phase2.getCandidateIntents());

        // 1. LLM明确标记为ambiguous + 有候选意图 → 消歧
        if (phase2.isAmbiguous() && phase2.getCandidateIntents() != null && !phase2.getCandidateIntents().isEmpty()) {
            String groupId = resolveGroupId(phase2.getGroupId(), phase2.getCandidateIntents());
            if (groupId != null && intentRegistry.getGroup(groupId) != null) {
                return triggerDisambiguation(sessionId, groupId, phase2);
            }
            log.info("[RoutingService] Ambiguous but no matching group, falling through with first candidate");
        }

        String effectiveIntent = phase2.getIntentName();

        // 2. 完全无法识别 → 拒绝
        if (effectiveIntent == null || "UNKNOWN".equalsIgnoreCase(effectiveIntent)) {
            log.info("[RoutingService] Intent completely unidentifiable");
            return RoutingResolution.rejected();
        }

        // 3. 识别到意图组名(如WEALTH) → 消歧
        if (intentRegistry.isGroupName(effectiveIntent)) {
            IntentRegistry.IntentGroup group = intentRegistry.getGroup(effectiveIntent);
            if (group != null) {
                return triggerDisambiguation(sessionId, effectiveIntent, phase2);
            }
        }

        // 4. 意图不在注册表 → 模糊匹配
        if (!intentRegistry.hasIntent(effectiveIntent)) {
            log.warn("[RoutingService] Unknown intent: {}, attempting fuzzy match", effectiveIntent);
            effectiveIntent = intentRegistry.fuzzyMatchIntent(effectiveIntent, userInput);
            if (effectiveIntent == null) {
                return RoutingResolution.rejected();
            }
            // 模糊匹配到组名 → 消歧
            if (intentRegistry.isGroupName(effectiveIntent)) {
                IntentRegistry.IntentGroup group = intentRegistry.getGroup(effectiveIntent);
                if (group != null) {
                    return triggerDisambiguation(sessionId, effectiveIntent, phase2);
                }
            }
            log.info("[RoutingService] Fuzzy matched to: {}", effectiveIntent);
        }

        // 5. 意图明确 → RESOLVED
        String routeType = resolveRouteType(phase1Result, phase2);
        String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
        return RoutingResolution.resolved(effectiveIntent, rewrittenInput, routeType);
    }

    // ==================== 消歧处理 ====================

    /**
     * 触发消歧 - 保存groupId，返回DISAMBIGUATION
     */
    private RoutingResolution triggerDisambiguation(String sessionId, String groupId, RoutingResult phase2) {
        IntentRegistry.IntentGroup group = intentRegistry.getGroup(groupId);
        if (group == null) {
            log.warn("[RoutingService] Disambiguation failed: no group found for groupId={}", groupId);
            return RoutingResolution.rejected();
        }

        stateManager.setDisambiguationState(sessionId,
                new AgentStateManager.DisambiguationState(groupId));
        log.info("[RoutingService] Entering disambiguation: groupId={}", groupId);

        return RoutingResolution.disambiguation(group.getDisambiguationQuestion(), group.getIntentNames());
    }

    /**
     * 处理消歧回答 - 重新Phase2识别，1次追问后仍模糊则直接拒绝
     *
     * 决策链:
     * 1. 重新识别 → 如果明确 → RESOLVED
     * 2. 仍然模糊 → REJECTED (不做多轮追问)
     */
    private RoutingResolution handleDisambiguationAnswer(String sessionId, String userInput,
                                                           RoutingResult phase1Result) {
        AgentStateManager.DisambiguationState disambigState = stateManager.getDisambiguationState(sessionId);
        if (disambigState == null) {
            stateManager.clearDisambiguationState(sessionId);
            return RoutingResolution.rejected();
        }

        String groupId = disambigState.getGroupId();
        IntentRegistry.IntentGroup group = intentRegistry.getGroup(groupId);
        if (group == null) {
            stateManager.clearDisambiguationState(sessionId);
            return RoutingResolution.rejected();
        }

        // 重新Phase2识别
        RoutingResult rePhase1 = RoutingResult.builder()
                .routeType("SWITCH_NEW").confidence(0.8).reasoning("消歧回答重新识别").build();
        RoutingResult phase2 = contextRewriter.rewriteAndIdentify(sessionId, userInput, rePhase1, stateManager);
        log.info("[RoutingService] Disambiguation re-identify: intent={}, ambiguous={}",
                phase2.getIntentName(), phase2.isAmbiguous());

        // 识别到明确的已注册意图 → 解决
        String identifiedIntent = phase2.getIntentName();
        if (identifiedIntent != null
                && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && intentRegistry.hasIntent(identifiedIntent)
                && !intentRegistry.isGroupName(identifiedIntent)) {
            stateManager.clearDisambiguationState(sessionId);
            log.info("[RoutingService] Disambiguation resolved: intent={}", identifiedIntent);

            String routeType = resolveRouteType(phase1Result, phase2);
            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(identifiedIntent, rewrittenInput, routeType);
        }

        // 1次追问后仍模糊 → 直接拒绝
        stateManager.clearDisambiguationState(sessionId);
        log.info("[RoutingService] Disambiguation answer still ambiguous → rejected");
        return RoutingResolution.rejected();
    }

    // ==================== 工具方法 ====================

    /**
     * 解析路由类型 - 综合Phase1和Phase2的判断
     */
    private String resolveRouteType(RoutingResult phase1Result, RoutingResult phase2) {
        // Phase2细化路由类型优先
        if (phase2.getRefinedRouteType() != null) {
            return phase2.getRefinedRouteType();
        }
        // Phase1已经是RESUME
        if (phase1Result.isResume()) {
            return "RESUME";
        }
        // Phase1是FOLLOW_UP但无活跃线程 → 降级为SWITCH_NEW
        if (phase1Result.isFollowUp()) {
            return "SWITCH_NEW";
        }
        return phase1Result.getRouteType() != null ? phase1Result.getRouteType() : "SWITCH_NEW";
    }

    /**
     * 解析组ID - 优先用Phase2返回的groupId，否则从候选意图推断
     */
    private String resolveGroupId(String phase2GroupId, List<String> candidateIntents) {
        if (phase2GroupId != null) {
            return phase2GroupId;
        }
        if (candidateIntents != null && !candidateIntents.isEmpty()) {
            for (String candidate : candidateIntents) {
                IntentRegistry.IntentGroup group = intentRegistry.findGroupByIntent(candidate);
                if (group != null) {
                    return group.getGroupId();
                }
            }
        }
        return null;
    }
}
