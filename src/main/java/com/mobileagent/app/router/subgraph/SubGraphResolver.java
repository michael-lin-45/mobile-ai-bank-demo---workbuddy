package com.mobileagent.app.router.subgraph;

import com.mobileagent.app.data.RoutingResolution;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.router.registry.SubGraphRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图决议器 - 封装Phase2(改写+识别) + 消歧 + 模糊匹配
 *
 * 核心设计:
 * - 一个方法(resolve)，一次调用，一个结果
 * - Controller不需要知道消歧细节，只看RoutingResolution.status
 * - 消歧1次追问，回答仍模糊则直接拒绝
 * - 不依赖AgentStateManager，消歧状态由WealthService自管，通过参数传入
 *
 * RESUME决策不在本层: 本层只输出SWITCH，由executeRoute根据suspendedAgents状态决定是否resume。
 *
 * 置信度增强消歧:
 * - 不完全依赖LLM的is_ambiguous自标记，结合confidence量化判断
 * - 高置信度 + LLM标ambiguous → 信任LLM首选意图，不消歧
 * - 低置信度 + 未标ambiguous + 意图属于歧义组 → 补充触发消歧
 * - 阈值可配置: routing.confidence.disambiguation-threshold
 */
@Slf4j
@Service
public class SubGraphResolver {

    private final SubGraphRouter subGraphRouter;
    private final SubGraphRegistry subGraphRegistry;

    @Value("${routing.confidence.disambiguation-threshold:0.7}")
    private double disambiguationThreshold;

    @Value("${routing.confidence.high-confidence-bypass:0.85}")
    private double highConfidenceBypass;

    public SubGraphResolver(SubGraphRouter subGraphRouter,
                               SubGraphRegistry subGraphRegistry) {
        this.subGraphRouter = subGraphRouter;
        this.subGraphRegistry = subGraphRegistry;
    }

    private static final String DEFAULT_INTENTION_TEMPLATE = "prompts/l1-intention.st";

    /**
     * 路由决策 - 统一入口
     *
     * @param chatHistory 已格式化的对话历史字符串(由调用方从GlobalSessionContext.messages获取)
     * @param domainIntentScopeList 本领域意图的范围描述(含intentType+scope)，供belongs_to_domain判断
     */
    public RoutingResolution resolve(String sessionId, String userInput, RoutingResult phase1Result,
                                      String chatHistory,
                                      boolean inDisambiguation, String disambiguationGroupId,
                                      String intentRoutingTemplatePath,
                                      String domainIntentScopeList) {
        if (inDisambiguation) {
            if (isCancelExpression(userInput)) {
                log.info("[SubGraphResolver] Cancel detected during disambiguation");
                return RoutingResolution.cancelled();
            }
            return handleDisambiguationAnswer(sessionId, userInput, phase1Result, chatHistory,
                    disambiguationGroupId, intentRoutingTemplatePath,
                    domainIntentScopeList);
        }
        return resolveNewIntention(sessionId, userInput, phase1Result, chatHistory,
                intentRoutingTemplatePath, domainIntentScopeList);
    }

    private RoutingResolution resolveNewIntention(String sessionId, String userInput, RoutingResult phase1Result,
                                                    String chatHistory,
                                                    String intentRoutingTemplatePath,
                                                    String domainIntentScopeList) {
        String currentAgent = phase1Result.getRouteType() != null ? phase1Result.getRouteType() : "无";

        RoutingResult phase2 = subGraphRouter.rewriteAndIdentify(sessionId, userInput, phase1Result,
                currentAgent, null, null,
                intentRoutingTemplatePath, chatHistory, domainIntentScopeList);
        log.info("[SubGraphResolver] Phase2: intent={}, ambiguous={}, confidence={}, candidates={}",
                phase2.getIntentName(), phase2.isAmbiguous(), phase2.getConfidence(), phase2.getCandidateIntents());

        if (phase2.isAmbiguous() && phase2.getCandidateIntents() != null && !phase2.getCandidateIntents().isEmpty()) {
            // 模型主动标了is_ambiguous=true且有候选意图 → 直接触发消歧，不信任confidence
            String groupId = resolveGroupId(phase2.getCandidateIntents());
            if (groupId != null && subGraphRegistry.getGroup(groupId) != null) {
                log.info("[SubGraphResolver] Disambiguation: model flagged ambiguous, candidates={}, confidence={} (bypass ignored)",
                        phase2.getCandidateIntents(), phase2.getConfidence());
                return triggerDisambiguation(groupId, phase2);
            }
            // 候选意图不属于任何已知消歧组 → 信任首选意图
            log.info("[SubGraphResolver] Ambiguous but candidates not in any known group, trusting top intent: {}",
                    phase2.getIntentName());
        }

        String effectiveIntent = phase2.getIntentName();
        if (!phase2.isAmbiguous() && effectiveIntent != null && phase2.getConfidence() < disambiguationThreshold) {
            SubGraphRegistry.IntentGroup group = subGraphRegistry.findGroupByIntent(effectiveIntent);
            if (group != null) {
                log.info("[SubGraphResolver] Supplemental disambiguation: low confidence ({}) < threshold ({}), intent={} belongs to group={}",
                        phase2.getConfidence(), disambiguationThreshold, effectiveIntent, group.getGroupId());
                return triggerDisambiguation(group.getGroupId(), phase2);
            }
        }

        String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
        if (!phase2.isBelongsToDomain()) {
            log.info("[SubGraphResolver] Out-of-domain detected by SubGraphRouter: intent={}, belongsToDomain=false → REROUTE",
                    effectiveIntent);
            return RoutingResolution.outOfDomain(effectiveIntent, rewrittenInput);
        }

        if (effectiveIntent == null || "UNKNOWN".equalsIgnoreCase(effectiveIntent)) {
            log.info("[SubGraphResolver] Intent completely unidentifiable");
            return RoutingResolution.rejected();
        }

        if (subGraphRegistry.isGroupName(effectiveIntent)) {
            SubGraphRegistry.IntentGroup group = subGraphRegistry.getGroup(effectiveIntent);
            if (group != null) {
                return triggerDisambiguation(effectiveIntent, phase2);
            }
        }

        if (!subGraphRegistry.hasIntent(effectiveIntent)) {
            log.warn("[SubGraphResolver] Unknown intent: {}, attempting fuzzy match", effectiveIntent);
            effectiveIntent = subGraphRegistry.fuzzyMatchIntent(effectiveIntent, userInput);
            if (effectiveIntent == null) {
                return RoutingResolution.rejected();
            }
            if (subGraphRegistry.isGroupName(effectiveIntent)) {
                SubGraphRegistry.IntentGroup group = subGraphRegistry.getGroup(effectiveIntent);
                if (group != null) {
                    return triggerDisambiguation(effectiveIntent, phase2);
                }
            }
            log.info("[SubGraphResolver] Fuzzy matched to: {}", effectiveIntent);
        }

        // RESUME由executeRoute根据suspendedAgents状态决定，本层统一输出SWITCH
        return RoutingResolution.resolved(effectiveIntent, rewrittenInput, "SWITCH");
    }

    private RoutingResolution triggerDisambiguation(String groupId, RoutingResult phase2) {
        SubGraphRegistry.IntentGroup group = subGraphRegistry.getGroup(groupId);
        if (group == null) {
            log.warn("[SubGraphResolver] Disambiguation failed: no group found for groupId={}", groupId);
            return RoutingResolution.rejected();
        }

        log.info("[SubGraphResolver] Entering disambiguation: groupId={}", groupId);

        return RoutingResolution.disambiguation(group.getDisambiguationQuestion(), group.getIntentNames());
    }

    private RoutingResolution handleDisambiguationAnswer(String sessionId, String userInput,
                                                              RoutingResult phase1Result,
                                                              String chatHistory,
                                                              String disambiguationGroupId,
                                                              String intentRoutingTemplatePath,
                                                              String domainIntentScopeList) {
        if (disambiguationGroupId == null) {
            return RoutingResolution.rejected();
        }

        SubGraphRegistry.IntentGroup group = subGraphRegistry.getGroup(disambiguationGroupId);
        if (group == null) {
            return RoutingResolution.rejected();
        }

        String currentAgent = "消歧模式";

        StringBuilder sb = new StringBuilder();
        sb.append("系统追问: \"").append(group.getDisambiguationQuestion()).append("\"\n");
        sb.append("候选意图:\n");
        for (String candidateName : group.getIntentNames()) {
            SubGraphRegistry.IntentConfig config = subGraphRegistry.getConfig(candidateName);
            sb.append("  · ").append(candidateName);
            if (config != null) {
                sb.append(" (").append(config.getDescription()).append(")");
            }
            sb.append("\n");
        }
        sb.append("用户回答应映射到上述候选意图之一");
        String disambigContext = sb.toString();

        RoutingResult rePhase1 = RoutingResult.builder()
                .routeType("SWITCH").confidence(0.8).reasoning("消歧回答重新识别").build();
        RoutingResult phase2 = subGraphRouter.rewriteAndIdentify(sessionId, userInput, rePhase1,
                currentAgent, null, disambigContext,
                intentRoutingTemplatePath, chatHistory, domainIntentScopeList);
        log.info("[SubGraphResolver] Disambiguation re-identify: intent={}, ambiguous={}, confidence={}",
                phase2.getIntentName(), phase2.isAmbiguous(), phase2.getConfidence());

        String identifiedIntent = phase2.getIntentName();
        boolean isInGroupIntent = identifiedIntent != null
                && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && subGraphRegistry.hasIntent(identifiedIntent)
                && !subGraphRegistry.isGroupName(identifiedIntent)
                && group.getIntentNames().contains(identifiedIntent);

        if (isInGroupIntent) {
            log.info("[SubGraphResolver] Disambiguation resolved (in-group intent): intent={}, confidence={}",
                    identifiedIntent, phase2.getConfidence());

            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(identifiedIntent, rewrittenInput, "SWITCH");
        }

        if (identifiedIntent != null
                && !"UNKNOWN".equalsIgnoreCase(identifiedIntent)
                && subGraphRegistry.hasIntent(identifiedIntent)
                && !subGraphRegistry.isGroupName(identifiedIntent)) {
            log.info("[SubGraphResolver] Disambiguation resolved (out-group intent): intent={}, confidence={}",
                    identifiedIntent, phase2.getConfidence());

            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(identifiedIntent, rewrittenInput, "SWITCH");
        }

        if (identifiedIntent != null
                && subGraphRegistry.isGroupName(identifiedIntent)
                && identifiedIntent.equals(disambiguationGroupId)) {
            String defaultIntent = group.getIntentNames().get(0);
            log.info("[SubGraphResolver] Disambiguation resolved (group name, using first candidate): group={}, default={}",
                    identifiedIntent, defaultIntent);

            String rewrittenInput = phase2.getRewrittenInput() != null ? phase2.getRewrittenInput() : userInput;
            return RoutingResolution.resolved(defaultIntent, rewrittenInput, "SWITCH");
        }

        log.info("[SubGraphResolver] Disambiguation answer still ambiguous → rejected");
        return RoutingResolution.rejected();
    }

    private String resolveGroupId(List<String> candidateIntents) {
        if (candidateIntents != null && !candidateIntents.isEmpty()) {
            for (String candidate : candidateIntents) {
                SubGraphRegistry.IntentGroup group = subGraphRegistry.findGroupByIntent(candidate);
                if (group != null) {
                    return group.getGroupId();
                }
            }
        }
        return null;
    }

    private boolean isCancelExpression(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        return trimmed.matches("^(取消|算了|不要了|不了|放弃|不看了|不想了|不弄了).*$");
    }
}
