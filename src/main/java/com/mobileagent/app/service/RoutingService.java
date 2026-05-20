package com.mobileagent.app.service;

import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResolution;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.state.AgentStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 置信度增强消歧:
 * - 不完全依赖LLM的is_ambiguous自标记，结合confidence量化判断
 * - 高置信度 + LLM标ambiguous → 信任LLM首选意图，不消歧（LLM自己都不确定才标记ambiguous）
 * - 低置信度 + 未标ambiguous + 意图属于歧义组 → 补充触发消歧（LLM不够自信）
 * - 阈值可配置: routing.confidence.disambiguation-threshold
 *
 * 职责边界:
 * - 负责: Phase2意图识别 + 消歧追问(1次) + 模糊匹配
 * - 不负责: Phase1路由类型判断(ContextRouter) / Graph执行(GraphExecutionService) / 线程管理(BankController)
 */
@Slf4j
@Service
public class RoutingService {

    private final IntentionRouter intentionRouter;
    private final IntentRegistry intentRegistry;
    private final AgentStateManager stateManager;

    /** 消歧置信度阈值 - confidence低于此值且意图属于歧义组时,补充触发消歧 */
    @Value("${routing.confidence.disambiguation-threshold:0.7}")
    private double disambiguationThreshold;

    /** 高置信度豁免阈值 - confidence高于此值时,即使LLM标ambiguous也信任首选意图 */
    @Value("${routing.confidence.high-confidence-bypass:0.85}")
    private double highConfidenceBypass;

    public RoutingService(IntentionRouter intentionRouter,
                          IntentRegistry intentRegistry,
                          AgentStateManager stateManager) {
        this.intentionRouter = intentionRouter;
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
     * 识别新意图 - Phase2 + 置信度增强消歧 + 模糊匹配
     *
     * 决策链 (置信度增强):
     * 1. Phase2识别 → 量化消歧判断:
     *    a. ambiguous + 低置信度 → 消歧 (LLM自己不确定)
     *    b. ambiguous + 高置信度(≥bypass) → 信任首选意图,不消歧
     *    c. 非ambiguous + 低置信度(<threshold) + 属于歧义组 → 补充消歧
     *    d. 非ambiguous + 高置信度 → 直接RESOLVED
     * 2. UNKNOWN? → 拒绝
     * 3. isGroupName? → 消歧
     * 4. !hasIntent? → fuzzyMatch → 可能消歧或拒绝
     * 5. hasIntent? → RESOLVED
     */
    private RoutingResolution resolveNewIntent(String sessionId, String userInput, RoutingResult phase1Result) {
        // Phase2: 上下文改写 + 意图识别
        RoutingResult phase2 = intentionRouter.rewriteAndIdentify(sessionId, userInput, phase1Result, stateManager);
        log.info("[RoutingService] Phase2: intent={}, ambiguous={}, confidence={}, candidates={}",
                phase2.getIntentName(), phase2.isAmbiguous(), phase2.getConfidence(), phase2.getCandidateIntents());

        // 1. 置信度增强的消歧判断
        if (phase2.isAmbiguous() && phase2.getCandidateIntents() != null && !phase2.getCandidateIntents().isEmpty()) {
            // 1a. LLM标ambiguous + 低置信度 → 消歧
            if (phase2.getConfidence() < highConfidenceBypass) {
                String groupId = resolveGroupId(phase2.getGroupId(), phase2.getCandidateIntents());
                if (groupId != null && intentRegistry.getGroup(groupId) != null) {
                    log.info("[RoutingService] Disambiguation: ambiguous + low confidence ({}) < bypass ({})",
                            phase2.getConfidence(), highConfidenceBypass);
                    return triggerDisambiguation(sessionId, groupId, phase2);
                }
            }
            // 1b. LLM标ambiguous + 高置信度 → 信任首选意图,不消歧
            log.info("[RoutingService] Ambiguous but high confidence ({}) >= bypass ({}), trusting top intent: {}",
                    phase2.getConfidence(), highConfidenceBypass, phase2.getIntentName());
        }

        // 1c. 非ambiguous + 低置信度 + 意图属于歧义组 → 补充消歧
        String effectiveIntent = phase2.getIntentName();
        if (!phase2.isAmbiguous() && effectiveIntent != null && phase2.getConfidence() < disambiguationThreshold) {
            IntentRegistry.IntentGroup group = intentRegistry.findGroupByIntent(effectiveIntent);
            if (group != null) {
                log.info("[RoutingService] Supplemental disambiguation: low confidence ({}) < threshold ({}), intent={} belongs to group={}",
                        phase2.getConfidence(), disambiguationThreshold, effectiveIntent, group.getGroupId());
                return triggerDisambiguation(sessionId, group.getGroupId(), phase2);
            }
        }

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
        String routeType = resolveRouteType(sessionId, phase1Result, phase2);
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
        RoutingResult phase2 = intentionRouter.rewriteAndIdentify(sessionId, userInput, rePhase1, stateManager);
        log.info("[RoutingService] Disambiguation re-identify: intent={}, ambiguous={}, confidence={}",
                phase2.getIntentName(), phase2.isAmbiguous(), phase2.getConfidence());

        // 识别到组内具体意图 → RESOLVED (消歧上下文已强,不再要求高置信度)
        // 消歧回答场景: 用户回答的是"咨询"/"解读"这类短答案,只要映射到组内意图即可
        String identifiedIntent = phase2.getIntentName();
        boolean isInGroupIntent = identifiedIntent != null
                && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && intentRegistry.hasIntent(identifiedIntent)
                && !intentRegistry.isGroupName(identifiedIntent)
                && group.getIntentNames().contains(identifiedIntent);

        if (isInGroupIntent) {
            stateManager.clearDisambiguationState(sessionId);
            log.info("[RoutingService] Disambiguation resolved (in-group intent): intent={}, confidence={}",
                    identifiedIntent, phase2.getConfidence());

            String routeType = resolveRouteType(sessionId, phase1Result, phase2);
            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(identifiedIntent, rewrittenInput, routeType);
        }

        // 识别到非组内的已注册意图 → 也RESOLVED (用户可能回答了不同的意图)
        if (identifiedIntent != null
                && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && intentRegistry.hasIntent(identifiedIntent)
                && !intentRegistry.isGroupName(identifiedIntent)) {
            stateManager.clearDisambiguationState(sessionId);
            log.info("[RoutingService] Disambiguation resolved (out-group intent): intent={}, confidence={}",
                    identifiedIntent, phase2.getConfidence());

            String routeType = resolveRouteType(sessionId, phase1Result, phase2);
            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(identifiedIntent, rewrittenInput, routeType);
        }

        // 识别到组名(仍模糊) → RESOLVED,选组内第一个意图作为默认 (消歧1次后不过度追问)
        if (identifiedIntent != null
                && intentRegistry.isGroupName(identifiedIntent)
                && identifiedIntent.equals(groupId)) {
            String defaultIntent = group.getIntentNames().get(0);
            stateManager.clearDisambiguationState(sessionId);
            log.info("[RoutingService] Disambiguation resolved (group name, using first candidate): group={}, default={}",
                    identifiedIntent, defaultIntent);

            String routeType = resolveRouteType(sessionId, phase1Result, phase2);
            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(defaultIntent, rewrittenInput, routeType);
        }

        // 1次追问后仍无法识别 → 直接拒绝
        stateManager.clearDisambiguationState(sessionId);
        log.info("[RoutingService] Disambiguation answer still ambiguous → rejected");
        return RoutingResolution.rejected();
    }

    // ==================== 工具方法 ====================

    /**
     * 解析路由类型 - 综合Phase1、Phase2和状态管理器的判断
     *
     * RESUME判定优先级:
     * 1. Phase2细化路由类型 (LLM明确判断)
     * 2. Phase1已经是RESUME
     * 3. 识别到的意图在suspendedAgents中 → RESUME (状态兜底,防止LLM漏判)
     * 4. Phase1是FOLLOW_UP但无活跃线程 → SWITCH_NEW
     * 5. 兜底: Phase1的routeType
     */
    private String resolveRouteType(String sessionId, RoutingResult phase1Result, RoutingResult phase2) {
        // Phase2细化路由类型优先
        if (phase2.getRefinedRouteType() != null) {
            return phase2.getRefinedRouteType();
        }
        // Phase1已经是RESUME
        if (phase1Result.isResume()) {
            return "RESUME";
        }
        // 状态兜底: 识别到的意图在suspendedAgents中 → RESUME
        // 场景: "转500吧" → phase1=SWITCH_NEW(LLM漏判), phase2识别intent=TRANSFER → TRANSFER在挂起列表 → RESUME
        String effectiveIntent = phase2.getIntentName();
        if (effectiveIntent != null && !"UNKNOWN".equalsIgnoreCase(effectiveIntent)) {
            AgentStateManager.SuspendedInfo suspended = stateManager.getSuspendedThread(sessionId, effectiveIntent);
            if (suspended != null) {
                log.info("[RoutingService] State-based RESUME override: intent={} is in suspendedAgents (phase1 was {})",
                        effectiveIntent, phase1Result.getRouteType());
                return "RESUME";
            }
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
