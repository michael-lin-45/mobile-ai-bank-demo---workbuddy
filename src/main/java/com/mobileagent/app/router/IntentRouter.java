package com.mobileagent.app.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.data.RoutingResult;
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
public class IntentRouter {

    private final ChatClient chatClient;
    private final IntentRegistry intentRegistry;
    private final ObjectMapper objectMapper;

    public IntentRouter(@Qualifier("intentChatClient") ChatClient chatClient,
                           IntentRegistry intentRegistry,
                           ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.intentRegistry = intentRegistry;
        this.objectMapper = objectMapper;
    }

    private static final String DEFAULT_TEMPLATE_PATH = "prompts/l1-intention.st";

    /**
     * Phase2: 意图识别 + 上下文改写
     *
     * @param sessionId 会话ID
     * @param userInput 用户原始输入
     * @param phase1Result Phase1的路由结果
     * @param currentAgent 当前活跃意图名称
     * @param pendingAgents 挂起的意图列表描述
     * @param sessionState 会话状态描述
     * @param disambigContext 消歧上下文
     * @param templatePath 提示词模板路径
     * @param chatHistory 已格式化的对话历史字符串(由调用方从GlobalSessionContext.messages获取)
     * @param domainIntentScopeList 本领域意图的范围描述
     * @return 包含意图名称、改写后输入、路由类型、归属判断的完整路由结果
     */
    public RoutingResult rewriteAndIdentify(String sessionId, String userInput,
                                             RoutingResult phase1Result,
                                             String currentAgent, String pendingAgents,
                                             String sessionState, String disambigContext,
                                             String templatePath,
                                             String chatHistory,
                                             String domainIntentScopeList) {
        try {
            String systemPrompt = buildIntentionSystemPrompt(userInput, phase1Result,
                    currentAgent, pendingAgents, sessionState, disambigContext, chatHistory,
                    templatePath != null ? templatePath : DEFAULT_TEMPLATE_PATH,
                    domainIntentScopeList);

            long startMs = System.currentTimeMillis();
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[IntentRouter] LLM call completed in {}ms | sessionId={}, userInput={}", elapsedMs, sessionId, userInput);
            log.debug("[IntentRouter] LLM raw response: {}", content);

            RoutingResult result = parseRewriteResponse(content, phase1Result);
            log.info("[IntentRouter] Result: intent={}, routeType={}, rewritten={}, confidence={}",
                    result.getIntentName(), result.getRefinedRouteType(),
                    result.getRewrittenInput(), result.getConfidence());
            return result;

        } catch (Exception e) {
            log.error("[IntentRouter] LLM call failed", e);
            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .refinedRouteType("SWITCH")
                    .intentName("UNKNOWN")
                    .rewrittenInput(userInput)
                    .confidence(0.3)
                    .reasoning("LLM调用失败,降级处理")
                    .build();
        }
    }

    private String buildIntentionSystemPrompt(String userInput,
                                               RoutingResult phase1Result,
                                               String currentAgent, String pendingAgents,
                                               String sessionState, String disambigContext,
                                               String chatHistory,
                                               String templatePath,
                                               String domainIntentScopeList) {
        String template = loadTemplate(templatePath);
        String allIntentList = intentRegistry.getIntentListDescription();
        String scopeList = domainIntentScopeList != null ? domainIntentScopeList : allIntentList;

        String mode = phase1Result.isResume() ? "RESUME" : "SWITCH";

        return template
                .replace("{intent_list}", scopeList)
                .replace("{intent_scope_list}", scopeList)
                .replace("{all_intent_list}", allIntentList)
                .replace("{message}", userInput)
                .replace("{mode}", mode)
                .replace("{intent_name}", currentAgent)
                .replace("{last_agent_description}", currentAgent)
                .replace("{last_agent_summary}", sessionState)
                .replace("{session_state}", sessionState)
                .replace("{pending_agents}", pendingAgents)
                .replace("{disambig_context}", disambigContext)
                .replace("{chat_history}", chatHistory)
                .replace("{domain_name}", "");
    }

    private RoutingResult parseRewriteResponse(String content, RoutingResult phase1Result) {
        try {
            String json = JsonParseUtils.extractJson(content);
            var node = objectMapper.readTree(json);

            String intentName = node.has("intent_name") ? node.get("intent_name").asText() : "UNKNOWN";
            String rewrittenInput = node.has("rewritten_input") ? node.get("rewritten_input").asText() : phase1Result.getReasoning();
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;

            String refinedRouteType = determineRouteType(intentName, phase1Result, node);

            String resumeTarget = null;
            if ("RESUME".equals(refinedRouteType)) {
                resumeTarget = node.has("resume_target") && !node.get("resume_target").isNull()
                        ? node.get("resume_target").asText() : intentName;
            }

            boolean ambiguous = node.has("is_ambiguous") && node.get("is_ambiguous").asBoolean();
            java.util.List<String> candidateIntents = null;
            String groupId = null;
            if (node.has("candidate_intents") && node.get("candidate_intents").isArray()) {
                candidateIntents = new java.util.ArrayList<>();
                for (var candidate : node.get("candidate_intents")) {
                    candidateIntents.add(candidate.asText());
                }
            }
            if (node.has("group_id") && !node.get("group_id").isNull()) {
                groupId = node.get("group_id").asText();
            }

            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .refinedRouteType(refinedRouteType)
                    .intentName(intentName)
                    .rewrittenInput(rewrittenInput)
                    .confidence(confidence)
                    .reasoning(phase1Result.getReasoning())
                    .resumeTarget(resumeTarget)
                    .ambiguous(ambiguous)
                    .candidateIntents(candidateIntents)
                    .groupId(groupId)
                    .belongsToDomain(!node.has("belongs_to_domain") || node.get("belongs_to_domain").asBoolean(true))
                    .build();
        } catch (Exception e) {
            log.warn("[IntentRouter] Failed to parse rewrite response: {}", content, e);
            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .refinedRouteType("SWITCH")
                    .intentName("UNKNOWN")
                    .rewrittenInput(phase1Result.getReasoning())
                    .confidence(0.3)
                    .reasoning("解析失败")
                    .build();
        }
    }

    private String determineRouteType(String intentName, RoutingResult phase1Result,
                                       com.fasterxml.jackson.databind.JsonNode node) {
        if (phase1Result.isResume()) {
            return "RESUME";
        }
        if (node.has("route_type")) {
            String routeType = node.get("route_type").asText();
            if ("RESUME".equalsIgnoreCase(routeType)) {
                return "RESUME";
            }
        }
        return "SWITCH";
    }

    private String loadTemplate(String path) {
        return TemplateUtils.loadTemplate(path);
    }
}
