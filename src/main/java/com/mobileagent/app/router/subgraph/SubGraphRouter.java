package com.mobileagent.app.router.subgraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.observability.AgentSpanContext;
import com.mobileagent.app.observability.ObservabilityMetrics;
import com.mobileagent.app.router.registry.SubGraphRegistry;
import com.mobileagent.app.util.JsonParseUtils;
import com.mobileagent.app.util.TemplateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 意图识别+上下文改写服务 - Phase2: 使用LLM同时完成意图识别和上下文改写
 *
 * 设计:
 * - 意图识别是主要目的,上下文改写是为子Graph提参服务的手段
 * - 调用方传入已格式化的chatHistory字符串(统一历史,含本域+他域),本类不再自行读取ChatMemory
 * - 改写后的输入传递给子Graph,使子Graph参数提取更准确
 * - 不依赖AgentStateManager，由调用方传入状态字符串
 */
@Slf4j
@Service
public class SubGraphRouter {

    private final ChatClient chatClient;
    private final SubGraphRegistry subGraphRegistry;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics obsMetrics;

    public SubGraphRouter(@Qualifier("intentChatClient") ChatClient chatClient,
                           SubGraphRegistry subGraphRegistry,
                           ObjectMapper objectMapper,
                           ObservabilityMetrics obsMetrics) {
        this.chatClient = chatClient;
        this.subGraphRegistry = subGraphRegistry;
        this.objectMapper = objectMapper;
        this.obsMetrics = obsMetrics;
    }

    private static final String DEFAULT_TEMPLATE_PATH = "prompts/l1-intention.st";

    /**
     * Phase2: 意图识别 + 上下文改写
     *
     * @param sessionId 会话ID
     * @param userInput 用户原始输入
     * @param phase1Result Phase1的路由结果
     * @param currentAgent 当前活跃意图名称
     * @param sessionState 会话状态描述
     * @param disambigContext 消歧上下文
     * @param templatePath 提示词模板路径
     * @param chatHistory 已格式化的对话历史字符串(由调用方从GlobalSessionContext.messages获取)
     * @param domainIntentScopeList 本领域意图的范围描述
     * @return 包含意图名称、改写后输入、路由类型、归属判断的完整路由结果
     */
    public RoutingResult rewriteAndIdentify(String sessionId, String userInput,
                                             RoutingResult phase1Result,
                                             String currentAgent,
                                             String sessionState, String disambigContext,
                                             String templatePath,
                                             String chatHistory,
                                             String domainIntentScopeList) {
        try {
            String systemPrompt = buildIntentionSystemPrompt(userInput, phase1Result,
                    currentAgent, sessionState, disambigContext, chatHistory,
                    templatePath != null ? templatePath : DEFAULT_TEMPLATE_PATH,
                    domainIntentScopeList);

            long startMs = System.currentTimeMillis();
            // 托管模式：intentName 在 LLM 返回后才解析，需延迟回填到 span.intent
            AgentSpanContext ctx = AgentSpanContext.setWithHeldSpan("L1-LLM2", "SubGraphRouter", null, sessionId, null);
            String content;
            try {
                content = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userInput)
                        .call()
                        .content();
            } catch (Exception e) {
                // LLM 调用失败：ObsChatModel 已在异常路径自行 end span；此处回填兜底意图(沿用当前活跃意图)并清理
                ctx.commitIntent(currentAgent != null ? currentAgent : "UNKNOWN", java.util.Map.of());
                throw e;
            }
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[SubGraphRouter] LLM call completed in {}ms | sessionId={}, userInput={}", elapsedMs, sessionId, userInput);
            log.debug("[SubGraphRouter] LLM raw response: {}", content);

            RoutingResult result = parseRewriteResponse(content, phase1Result);
            log.info("[SubGraphRouter] Result: intent={}, routeType={}, rewritten={}, confidence={}",
                    result.getIntentName(), result.getRouteType(),
                    result.getRewrittenInput(), result.getConfidence());

            // 埋点：改写准确率轻量信号（实体守恒 + 代词检测）
            checkRewriteAccuracy(userInput, result);
            obsMetrics.recordRewriteTotal();
            // 埋点：L1 意图识别调用计数（解锁 P0-5/P1-2 backend agent_call:L1 聚合）
            obsMetrics.recordL1Call("intent_identify", result.getIntentName());

            // 回填真实 intentName 到 span.intent（托管模式结束 span）
            ctx.commitIntent(result.getIntentName(), java.util.Map.of());
            return result;

        } catch (Exception e) {
            String fbIntent = (currentAgent != null && !"无".equals(currentAgent)) ? currentAgent : "UNKNOWN";
            log.error("[SubGraphRouter] LLM call failed, degrading to current intent: {}", fbIntent, e);
            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .intentName(fbIntent)
                    .rewrittenInput(userInput)
                    .confidence(0.3)
                    .reasoning("LLM调用失败,降级沿用当前意图: " + fbIntent)
                    .build();
        } finally {
            // 兜底：若上方未成功 commit（如埋点/状态更新异常），仍结束 held span 并清理 ThreadLocal，防止 span 泄漏
            AgentSpanContext.clear();
        }
    }

    private String buildIntentionSystemPrompt(String userInput,
                                               RoutingResult phase1Result,
                                               String currentAgent,
                                               String sessionState, String disambigContext,
                                               String chatHistory,
                                               String templatePath,
                                               String domainIntentScopeList) {
        String template = loadTemplate(templatePath);
        String allIntentList = subGraphRegistry.getIntentListDescription();
        String scopeList = domainIntentScopeList != null ? domainIntentScopeList : allIntentList;

        // Disambiguation section: only inject when disambigContext has real content
        String disambigSection = "";
        if (disambigContext != null && !"无".equals(disambigContext) && !disambigContext.isBlank()) {
            disambigSection = "\n消歧回答处理:\n"
                + "- 用户正在回答消歧追问，简短回答是对候选意图的选择\n"
                + "- 必须将用户回答映射到候选意图之一\n"
                + "- 明确选择后不再标记is_ambiguous=true\n"
                + "- 回答与候选意图都不相关 → intent_name=UNKNOWN\n"
                + "\n消歧上下文:\n" + disambigContext;
        }

        return template
                .replace("{intent_scope_list}", scopeList)
                .replace("{message}", userInput)
                .replace("{chat_history}", chatHistory)
                .replace("{domain_name}", "")
                .replace("{disambig_section}", disambigSection);
    }

    private RoutingResult parseRewriteResponse(String content, RoutingResult phase1Result) {
        try {
            String json = JsonParseUtils.extractJson(content);
            var node = objectMapper.readTree(json);

            String intentName = node.has("intent_name") ? node.get("intent_name").asText() : "UNKNOWN";
            String rewrittenInput = node.has("rewritten_input") ? node.get("rewritten_input").asText() : phase1Result.getReasoning();
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;

            boolean ambiguous = node.has("is_ambiguous") && node.get("is_ambiguous").asBoolean();
            java.util.List<String> candidateIntents = null;
            if (node.has("candidate_intents") && node.get("candidate_intents").isArray()) {
                candidateIntents = new java.util.ArrayList<>();
                for (var candidate : node.get("candidate_intents")) {
                    candidateIntents.add(candidate.asText());
                }
            }

            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .intentName(intentName)
                    .rewrittenInput(rewrittenInput)
                    .confidence(confidence)
                    .reasoning(phase1Result.getReasoning())
                    .ambiguous(ambiguous)
                    .candidateIntents(candidateIntents)
                    .belongsToDomain(!node.has("belongs_to_domain") || node.get("belongs_to_domain").asBoolean(true))
                    .build();
        } catch (Exception e) {
            log.warn("[SubGraphRouter] Failed to parse rewrite response: {}", content, e);
            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .intentName("UNKNOWN")
                    .rewrittenInput(phase1Result.getReasoning())
                    .confidence(0.3)
                    .reasoning("解析失败")
                    .build();
        }
    }

    private String loadTemplate(String path) {
        return TemplateUtils.loadTemplate(path);
    }

    // ==================== 改写准确率轻量信号 (§5.7) ====================

    /**
     * 改写准确率轻量检测 — 实体守恒 + 金额归一 + 代词残留检测。
     * 只写 rule_check tag，完整规则由后端可观测侧离线评估。
     *
     * 检测项:
     *   P0: 原始实体（人名/金额/时间）在改写后必须出现
     *   P1: 代词残留检测（"他/她/它/这个/那个/刚才"）
     *   —: 金额规则覆盖「万」+「千」
     */
    private void checkRewriteAccuracy(String originalInput, RoutingResult result) {
        String rewritten = result.getRewrittenInput();
        if (rewritten == null || rewritten.equals(originalInput)) {
            // 未改写 → 跳过
            return;
        }

        String domain = result.getIntentName() != null ? result.getIntentName() : "UNKNOWN";
        String ruleCheck = "pass";

        // P1: 代词残留检测
        if (containsAny(rewritten, "他", "她", "它", "这个", "那个", "刚才")) {
            ruleCheck = "pronoun_unresolved";
        }

        // P0: 金额归一检测 (original 含非标准金额但 rewritten 未归一)
        if ("pass".equals(ruleCheck) && containsAmount(originalInput)) {
            if (!isAmountNormalized(rewritten)) {
                ruleCheck = "amount_not_normalized";
            }
        }

        // P0: 实体守恒检测 (数字实体在改写后必须出现)
        if ("pass".equals(ruleCheck)) {
            java.util.List<String> originalEntities = extractNumberEntities(originalInput);
            if (!originalEntities.isEmpty()) {
                boolean allPresent = true;
                for (String entity : originalEntities) {
                    if (!rewritten.contains(entity)) {
                        allPresent = false;
                        break;
                    }
                }
                if (!allPresent) {
                    ruleCheck = "entity_lost";
                }
            }
        }

        obsMetrics.recordRewriteAccuracy(ruleCheck, domain);
    }

    /** 检测文本是否包含非标准金额表达（万/千/元/块） */
    private boolean containsAmount(String text) {
        return java.util.regex.Pattern.compile("[0-9]+[万千百]|[0-9]+(\\.\\d+)?[元块]")
                .matcher(text).find();
    }

    /** 检测改写后金额是否已归一为标准数字 */
    private boolean isAmountNormalized(String text) {
        // 简单检测：改写后不应出现金额归一的目标格式残差
        return !java.util.regex.Pattern.compile("[0-9]+[万千百](?!元|块|美元)")
                .matcher(text).find();
    }

    /** 提取数字实体（金额、数量等） */
    private java.util.List<String> extractNumberEntities(String text) {
        java.util.List<String> entities = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?[万千百元块]?")
                .matcher(text);
        while (m.find()) {
            entities.add(m.group());
        }
        return entities;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
