package com.mobileagent.app.router;

import com.mobileagent.app.data.RoutingResolution;
import com.mobileagent.app.data.RoutingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 意图决议器 - 封装Phase2(改写+识别) + 消歧 + 模糊匹配
 *
 * 核心设计:
 * - 一个方法(resolve)，一次调用，一个结果
 * - Controller不需要知道消歧细节，只看RoutingResolution.status
 * - 消歧1次追问，回答仍模糊则直接拒绝（手机银行用户不会反复给模糊答案）
 * - 不依赖AgentStateManager，消歧状态由WealthService自管，通过参数传入
 *
 * 置信度增强消歧:
 * - 不完全依赖LLM的is_ambiguous自标记，结合confidence量化判断
 * - 高置信度 + LLM标ambiguous → 信任LLM首选意图，不消歧
 * - 低置信度 + 未标ambiguous + 意图属于歧义组 → 补充触发消歧
 * - 阈值可配置: routing.confidence.disambiguation-threshold
 *
 * 职责边界:
 * - 负责: Phase2意图识别 + 消歧追问(1次) + 模糊匹配
 * - 不负责: 状态管理(由L1 Service负责) / Phase1路由类型判断(ContextRouter) / Graph执行(GraphExecutionEngine)
 */
@Slf4j
@Service
public class IntentResolver {

    private final IntentRouter intentRouter;
    private final IntentRegistry intentRegistry;

    /** 消歧置信度阈值 - confidence低于此值且意图属于歧义组时,补充触发消歧 */
    @Value("${routing.confidence.disambiguation-threshold:0.7}")
    private double disambiguationThreshold;

    /** 高置信度豁免阈值 - confidence高于此值时,即使LLM标ambiguous也信任首选意图 */
    @Value("${routing.confidence.high-confidence-bypass:0.85}")
    private double highConfidenceBypass;

    public IntentResolver(IntentRouter intentRouter,
                               IntentRegistry intentRegistry) {
        this.intentRouter = intentRouter;
        this.intentRegistry = intentRegistry;
    }

    // ==================== 公共入口 ====================

    private static final String DEFAULT_INTENTION_TEMPLATE = "prompts/l1-intention.st";

    /**
     * 路由决策 - 统一入口
     *
     * @param globalChatHistory 全局跨域对话历史(由L0传入,用于跨域指代消解,可为null)
     * @param domainIntentScopeList 本领域意图的范围描述(含intentType+scope)，供belongs_to_domain判断
     */
    public RoutingResolution resolve(String sessionId, String userInput, RoutingResult phase1Result,
                                      ChatMemory chatMemory,
                                      boolean inDisambiguation, String disambiguationGroupId,
                                      boolean hasSuspendedAgents,
                                      Map<String, ?> suspendedAgents,
                                      String intentionTemplatePath,
                                      String globalChatHistory,
                                      String domainIntentScopeList) {
        if (inDisambiguation) {
            // 消歧中取消: 返回CANCELLED，由WealthService清理消歧状态
            if (isCancelExpression(userInput)) {
                log.info("[IntentResolver] Cancel detected during disambiguation");
                return RoutingResolution.cancelled();
            }
            return handleDisambiguationAnswer(sessionId, userInput, phase1Result, chatMemory,
                    disambiguationGroupId, hasSuspendedAgents, suspendedAgents, intentionTemplatePath,
                    globalChatHistory, domainIntentScopeList);
        }
        return resolveNewIntention(sessionId, userInput, phase1Result, chatMemory,
                hasSuspendedAgents, suspendedAgents, intentionTemplatePath, globalChatHistory,
                domainIntentScopeList);
    }

    // ==================== 新意图识别 ====================

    /**
     * 识别新意图 - Phase2 + 置信度增强消歧 + 模糊匹配
     */
     private RoutingResolution resolveNewIntention(String sessionId, String userInput, RoutingResult phase1Result,
                                                    ChatMemory chatMemory,
                                                    boolean hasSuspendedAgents,
                                                    Map<String, ?> suspendedAgents,
                                                    String intentionTemplatePath,
                                                    String globalChatHistory,
                                                    String domainIntentScopeList) {
        // 构建状态字符串(用于IntentRouter的prompt)
        String currentAgent = phase1Result.getRouteType() != null ? phase1Result.getRouteType() : "无";
        String pendingAgents = hasSuspendedAgents
                ? String.join(", ", suspendedAgents.keySet())
                : "无";
        String sessionState = "Phase1路由: " + phase1Result.getRouteType();
        String disambigContext = "无";

        // Phase2: 上下文改写 + 意图识别
        RoutingResult phase2 = intentRouter.rewriteAndIdentify(sessionId, userInput, phase1Result,
                currentAgent, pendingAgents, sessionState, disambigContext,
                intentionTemplatePath, chatMemory, globalChatHistory, domainIntentScopeList);
        log.info("[IntentResolver] Phase2: intent={}, ambiguous={}, confidence={}, candidates={}",
                phase2.getIntentName(), phase2.isAmbiguous(), phase2.getConfidence(), phase2.getCandidateIntents());

        // 1. 置信度增强的消歧判断
        if (phase2.isAmbiguous() && phase2.getCandidateIntents() != null && !phase2.getCandidateIntents().isEmpty()) {
            // 1a. LLM标ambiguous + 低置信度 → 消歧
            if (phase2.getConfidence() < highConfidenceBypass) {
                String groupId = resolveGroupId(phase2.getGroupId(), phase2.getCandidateIntents());
                if (groupId != null && intentRegistry.getGroup(groupId) != null) {
                    log.info("[IntentResolver] Disambiguation: ambiguous + low confidence ({}) < bypass ({})",
                            phase2.getConfidence(), highConfidenceBypass);
                    return triggerDisambiguation(groupId, phase2);
                }
            }
            // 1b. LLM标ambiguous + 高置信度 → 信任首选意图,不消歧
            log.info("[IntentResolver] Ambiguous but high confidence ({}) >= bypass ({}), trusting top intent: {}",
                    phase2.getConfidence(), highConfidenceBypass, phase2.getIntentName());
        }

        // 1c. 非ambiguous + 低置信度 + 意图属于歧义组 → 补充消歧
        String effectiveIntent = phase2.getIntentName();
        if (!phase2.isAmbiguous() && effectiveIntent != null && phase2.getConfidence() < disambiguationThreshold) {
            IntentRegistry.IntentGroup group = intentRegistry.findGroupByIntent(effectiveIntent);
            if (group != null) {
                log.info("[IntentResolver] Supplemental disambiguation: low confidence ({}) < threshold ({}), intent={} belongs to group={}",
                        phase2.getConfidence(), disambiguationThreshold, effectiveIntent, group.getGroupId());
                return triggerDisambiguation(group.getGroupId(), phase2);
            }
        }

        // 2. belongsToDomain=false → REROUTE (优先于REJECTED判断)
        String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
        if (!phase2.isBelongsToDomain()) {
            log.info("[IntentResolver] Out-of-domain detected by IntentRouter: intent={}, belongsToDomain=false → REROUTE",
                    effectiveIntent);
            return RoutingResolution.outOfDomain(effectiveIntent, rewrittenInput);
        }

        // 3. 完全无法识别 → 拒绝
        if (effectiveIntent == null || "UNKNOWN".equalsIgnoreCase(effectiveIntent)) {
            log.info("[IntentResolver] Intent completely unidentifiable");
            return RoutingResolution.rejected();
        }

        // 4. 识别到意图组名(如WEALTH) → 消歧
        if (intentRegistry.isGroupName(effectiveIntent)) {
            IntentRegistry.IntentGroup group = intentRegistry.getGroup(effectiveIntent);
            if (group != null) {
                return triggerDisambiguation(effectiveIntent, phase2);
            }
        }

        // 5. 意图不在注册表 → 模糊匹配
        if (!intentRegistry.hasIntent(effectiveIntent)) {
            log.warn("[IntentResolver] Unknown intent: {}, attempting fuzzy match", effectiveIntent);
            effectiveIntent = intentRegistry.fuzzyMatchIntent(effectiveIntent, userInput);
            if (effectiveIntent == null) {
                return RoutingResolution.rejected();
            }
            // 模糊匹配到组名 → 消歧
            if (intentRegistry.isGroupName(effectiveIntent)) {
                IntentRegistry.IntentGroup group = intentRegistry.getGroup(effectiveIntent);
                if (group != null) {
                    return triggerDisambiguation(effectiveIntent, phase2);
                }
            }
            log.info("[IntentResolver] Fuzzy matched to: {}", effectiveIntent);
        }

        // 6. 意图明确且属于本域 → RESOLVED
        String routeType = resolveRouteType(phase1Result, phase2, hasSuspendedAgents, suspendedAgents);
        return RoutingResolution.resolved(effectiveIntent, rewrittenInput, routeType);
    }

    // ==================== 消歧处理 ====================

    /**
     * 触发消歧 - 返回DISAMBIGUATION，由WealthService保存disambiguationState
     */
    private RoutingResolution triggerDisambiguation(String groupId, RoutingResult phase2) {
        IntentRegistry.IntentGroup group = intentRegistry.getGroup(groupId);
        if (group == null) {
            log.warn("[IntentResolver] Disambiguation failed: no group found for groupId={}", groupId);
            return RoutingResolution.rejected();
        }

        log.info("[IntentResolver] Entering disambiguation: groupId={}", groupId);

        return RoutingResolution.disambiguation(group.getDisambiguationQuestion(), group.getIntentNames());
    }

    /**
     * 处理消歧回答 - 重新Phase2识别，1次追问后仍模糊则直接拒绝
     */
     private RoutingResolution handleDisambiguationAnswer(String sessionId, String userInput,
                                                              RoutingResult phase1Result,
                                                              ChatMemory chatMemory,
                                                              String disambiguationGroupId,
                                                              boolean hasSuspendedAgents,
                                                              Map<String, ?> suspendedAgents,
                                                              String intentionTemplatePath,
                                                              String globalChatHistory,
                                                              String domainIntentScopeList) {
        if (disambiguationGroupId == null) {
            return RoutingResolution.rejected();
        }

        IntentRegistry.IntentGroup group = intentRegistry.getGroup(disambiguationGroupId);
        if (group == null) {
            return RoutingResolution.rejected();
        }

        // 构建消歧上下文(用于IntentRouter)
        String currentAgent = "消歧模式";
        String pendingAgents = hasSuspendedAgents
                ? String.join(", ", suspendedAgents.keySet())
                : "无";
        String sessionState = "消歧中: 意图组=" + disambiguationGroupId;

        // 构建消歧上下文字符串
        StringBuilder sb = new StringBuilder();
        sb.append("系统追问: \"").append(group.getDisambiguationQuestion()).append("\"\n");
        sb.append("候选意图:\n");
        for (String candidateName : group.getIntentNames()) {
            IntentRegistry.IntentConfig config = intentRegistry.getConfig(candidateName);
            sb.append("  · ").append(candidateName);
            if (config != null) {
                sb.append(" (").append(config.getDescription()).append(")");
            }
            sb.append("\n");
        }
        sb.append("用户回答应映射到上述候选意图之一");
        String disambigContext = sb.toString();

        // 重新Phase2识别
        RoutingResult rePhase1 = RoutingResult.builder()
                .routeType("SWITCH_NEW").confidence(0.8).reasoning("消歧回答重新识别").build();
        RoutingResult phase2 = intentRouter.rewriteAndIdentify(sessionId, userInput, rePhase1,
                currentAgent, pendingAgents, sessionState, disambigContext,
                intentionTemplatePath, chatMemory, globalChatHistory, domainIntentScopeList);
        log.info("[IntentResolver] Disambiguation re-identify: intent={}, ambiguous={}, confidence={}",
                phase2.getIntentName(), phase2.isAmbiguous(), phase2.getConfidence());

        // 识别到组内具体意图 → RESOLVED
        String identifiedIntent = phase2.getIntentName();
        boolean isInGroupIntent = identifiedIntent != null
                && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && intentRegistry.hasIntent(identifiedIntent)
                && !intentRegistry.isGroupName(identifiedIntent)
                && group.getIntentNames().contains(identifiedIntent);

        if (isInGroupIntent) {
            log.info("[IntentResolver] Disambiguation resolved (in-group intent): intent={}, confidence={}",
                    identifiedIntent, phase2.getConfidence());

            String routeType = resolveRouteType(phase1Result, phase2, hasSuspendedAgents, suspendedAgents);
            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(identifiedIntent, rewrittenInput, routeType);
        }

        // 识别到非组内的已注册意图 → 也RESOLVED
        if (identifiedIntent != null
                && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && intentRegistry.hasIntent(identifiedIntent)
                && !intentRegistry.isGroupName(identifiedIntent)) {
            log.info("[IntentResolver] Disambiguation resolved (out-group intent): intent={}, confidence={}",
                    identifiedIntent, phase2.getConfidence());

            String routeType = resolveRouteType(phase1Result, phase2, hasSuspendedAgents, suspendedAgents);
            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(identifiedIntent, rewrittenInput, routeType);
        }

        // 识别到组名(仍模糊) → RESOLVED,选组内第一个意图作为默认
        if (identifiedIntent != null
                && intentRegistry.isGroupName(identifiedIntent)
                && identifiedIntent.equals(disambiguationGroupId)) {
            String defaultIntent = group.getIntentNames().get(0);
            log.info("[IntentResolver] Disambiguation resolved (group name, using first candidate): group={}, default={}",
                    identifiedIntent, defaultIntent);

            String routeType = resolveRouteType(phase1Result, phase2, hasSuspendedAgents, suspendedAgents);
            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(defaultIntent, rewrittenInput, routeType);
        }

        // 1次追问后仍无法识别 → 直接拒绝
        log.info("[IntentResolver] Disambiguation answer still ambiguous → rejected");
        return RoutingResolution.rejected();
    }

    // ==================== 工具方法 ====================

    /**
     * 解析路由类型 - 综合Phase1、Phase2和挂起状态的判断
     *
     * RESUME判定优先级:
     * 1. Phase2细化路由类型 (LLM明确判断)
     * 2. Phase1已经是RESUME
     * 3. 识别到的意图在suspendedAgents中 → RESUME (状态兜底,防止LLM漏判)
     * 4. Phase1是FOLLOW_UP但无活跃线程 → SWITCH_NEW
     * 5. 兜底: Phase1的routeType
     */
    private String resolveRouteType(RoutingResult phase1Result, RoutingResult phase2,
                                     boolean hasSuspendedAgents, Map<String, ?> suspendedAgents) {
        // Phase2细化路由类型优先
        if (phase2.getRefinedRouteType() != null) {
            return phase2.getRefinedRouteType();
        }
        // Phase1已经是RESUME
        if (phase1Result.isResume()) {
            return "RESUME";
        }
        // 状态兜底: 识别到的意图在suspendedAgents中 → RESUME
        String effectiveIntent = phase2.getIntentName();
        if (effectiveIntent != null && !"UNKNOWN".equalsIgnoreCase(effectiveIntent) && hasSuspendedAgents) {
            if (suspendedAgents.containsKey(effectiveIntent)) {
                log.info("[IntentResolver] State-based RESUME override: intent={} is in suspendedAgents (phase1 was {})",
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

    /**
     * 判断是否为取消表达 - 用于消歧中检测用户取消
     */
    private boolean isCancelExpression(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        // 匹配: 取消词出现在句首，后面可以跟任意内容
        // "算了" / "算了，不看了" / "取消" / "不要了，算了" → 都算取消
        return trimmed.matches("^(取消|算了|不要了|不了|放弃|不看了|不想了|不弄了).*$");
    }
}
