package com.mobileagent.app.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.util.ChatHistoryUtils;
import com.mobileagent.app.util.JsonParseUtils;
import com.mobileagent.app.util.TemplateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 意图识别+上下文改写服务 - Phase2: 使用LLM同时完成意图识别和上下文改写
 *
 * 设计:
 * - 意图识别是主要目的,上下文改写是为子Graph提参服务的手段
 * - 不使用ReadOnlyMemoryAdvisor，改为手动读取ChatMemory并格式化到{chat_history}占位符
 * - 调用方传入对应的ChatMemory实例(全局/领域级)，在system prompt中区分【对话历史】和【当前消息】
 * - 改写后的输入传递给子Graph,使子Graph参数提取更准确
 * - 不依赖AgentStateManager，由调用方传入状态字符串
 */
@Slf4j
@Service
public class IntentRouter {

    private final ChatClient chatClient;
    private final IntentRegistry intentRegistry;
    private final ObjectMapper objectMapper;
    private final int judgmentMaxPairs;

    public IntentRouter(@Qualifier("intentChatClient") ChatClient chatClient,
                           IntentRegistry intentRegistry,
                           @org.springframework.beans.factory.annotation.Value("${routing.history.judgment-max-pairs:5}") int judgmentMaxPairs) {
        this.chatClient = chatClient;
        this.intentRegistry = intentRegistry;
        this.objectMapper = new ObjectMapper();
        this.judgmentMaxPairs = judgmentMaxPairs;
    }

    private static final String DEFAULT_TEMPLATE_PATH = "prompts/l1-intention.st";

    /**
     * Phase2: 意图识别 + 上下文改写 (使用默认模板)
     */
    public RoutingResult rewriteAndIdentify(String sessionId, String userInput,
                                             RoutingResult phase1Result,
                                             String currentAgent, String pendingAgents,
                                             String sessionState, String disambigContext,
                                             ChatMemory chatMemory) {
        return rewriteAndIdentify(sessionId, userInput, phase1Result,
                currentAgent, pendingAgents, sessionState, disambigContext,
                DEFAULT_TEMPLATE_PATH, chatMemory);
    }

    /**
     * Phase2: 意图识别 + 上下文改写 (使用指定模板)
     *
     * @param sessionId 会话ID
     * @param userInput 用户原始输入
     * @param phase1Result Phase1的路由结果
     * @param currentAgent 当前活跃意图名称(如"WEALTH_CONSULT"或"无")
     * @param pendingAgents 挂起的意图列表描述(如"WEALTH_CONSULT, WEALTH_INTERPRET"或"无")
     * @param sessionState 会话状态描述(由L1 Service生成)
     * @param disambigContext 消歧上下文(如"无"或具体消歧信息)
     * @param templatePath 提示词模板路径(如"prompts/l1-intention.st")
     * @param chatMemory 指定读取的ChatMemory实例(为null时无法读取历史)
     * @return 包含意图名称、改写后输入、路由类型的完整路由结果
     */
    public RoutingResult rewriteAndIdentify(String sessionId, String userInput,
                                             RoutingResult phase1Result,
                                             String currentAgent, String pendingAgents,
                                             String sessionState, String disambigContext,
                                             String templatePath,
                                             ChatMemory chatMemory) {
        try {
            String chatHistoryStr = formatChatHistory(chatMemory, sessionId);
            String systemPrompt = buildIntentionSystemPrompt(userInput, phase1Result,
                    currentAgent, pendingAgents, sessionState, disambigContext, chatHistoryStr,
                    templatePath != null ? templatePath : DEFAULT_TEMPLATE_PATH);

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
                    .refinedRouteType("SWITCH_NEW")
                    .intentName("UNKNOWN")
                    .rewrittenInput(userInput)
                    .confidence(0.3)
                    .reasoning("LLM调用失败,降级处理")
                    .build();
        }
    }

    /**
     * 读取ChatMemory并格式化为文本历史(截断到配置对数)
     */
    private String formatChatHistory(ChatMemory chatMemory, String sessionId) {
        return ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs);
    }

    private String buildIntentionSystemPrompt(String userInput,
                                               RoutingResult phase1Result,
                                               String currentAgent, String pendingAgents,
                                               String sessionState, String disambigContext,
                                               String chatHistory,
                                               String templatePath) {
        String template = loadTemplate(templatePath);
        String intentList = intentRegistry.getIntentListDescription();

        // 根据Phase1判断确定改写模式
        String mode = phase1Result.isResume() ? "RESUME" : "SWITCH";

        return template
                .replace("{intent_list}", intentList)
                .replace("{message}", userInput)
                .replace("{mode}", mode)
                .replace("{intent_name}", currentAgent)
                .replace("{last_agent_description}", currentAgent)
                .replace("{last_agent_summary}", sessionState)
                .replace("{session_state}", sessionState)
                .replace("{pending_agents}", pendingAgents)
                .replace("{disambig_context}", disambigContext)
                .replace("{chat_history}", chatHistory);
    }

    private RoutingResult parseRewriteResponse(String content, RoutingResult phase1Result) {
        try {
            String json = JsonParseUtils.extractJson(content);
            var node = objectMapper.readTree(json);

            String intentName = node.has("intent_name") ? node.get("intent_name").asText() : "UNKNOWN";
            String rewrittenInput = node.has("rewritten_input") ? node.get("rewritten_input").asText() : phase1Result.getReasoning();
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;

            // 判断路由类型
            String refinedRouteType = determineRouteType(intentName, phase1Result, node);

            // 提取resume目标
            String resumeTarget = null;
            if ("RESUME".equals(refinedRouteType)) {
                resumeTarget = node.has("resume_target") && !node.get("resume_target").isNull()
                        ? node.get("resume_target").asText() : intentName;
            }

            // 提取消歧字段
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
                    .build();
        } catch (Exception e) {
            log.warn("[IntentRouter] Failed to parse rewrite response: {}", content, e);
            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .refinedRouteType("SWITCH_NEW")
                    .intentName("UNKNOWN")
                    .rewrittenInput(phase1Result.getReasoning())
                    .confidence(0.3)
                    .reasoning("解析失败")
                    .build();
        }
    }

    /**
     * 判断细化路由类型:
     * - 如果识别的意图在suspendedAgents中 → RESUME (由调用方传入isInSuspended判断)
     * - 否则 → SWITCH_NEW
     */
    private String determineRouteType(String intentName, RoutingResult phase1Result,
                                       com.fasterxml.jackson.databind.JsonNode node) {
        // Phase1已经判断为RESUME
        if (phase1Result.isResume()) {
            return "RESUME";
        }

        // LLM明确指定了路由类型
        if (node.has("route_type")) {
            String routeType = node.get("route_type").asText();
            if ("RESUME".equalsIgnoreCase(routeType)) {
                return "RESUME";
            }
        }

        return "SWITCH_NEW";
    }

    private String loadTemplate(String path) {
        return TemplateUtils.loadTemplate(path, this::getDefaultRewritePrompt);
    }

    private String getDefaultRewritePrompt() {
        return """
            你是一个手机银行意图识别与改写器。意图识别是主要目的,上下文改写是为子智能体提取参数服务的手段。
            
            已注册意图列表:
            {intent_list}
            
            改写模式: {mode}
            
            当前会话状态:
            {session_state}
            
            当前意图: {intent_name}
            挂起的意图: {pending_agents}
            
            消歧上下文: {disambig_context}
            
            ===对话历史(用户之前的对话，用于理解上下文)===
            {chat_history}
            ===对话历史结束===
            
            ===用户当前消息(用户此刻说的话，用于判断当前意图)===
            {message}
            ===当前消息结束===
            
            任务:
            1. 意图识别(主要): 从已注册意图列表中选择最匹配的意图,无法归入则输出UNKNOWN
            2. 上下文改写(辅助): 将依赖上下文的模糊表达改写为自包含的完整描述,方便子智能体直接提取参数
            3. 歧义检测: 如果用户输入只能匹配到意图组,标记is_ambiguous=true
            4. 路由判断: 意图在挂起列表中→RESUME,否则→SWITCH_NEW
            
            严格输出JSON:
            {
              "intent_name": "意图名称",
              "rewritten_input": "改写后的自包含描述",
              "route_type": "SWITCH_NEW | RESUME",
              "resume_target": "RESUME时填意图名,否则null",
              "confidence": 0.0-1.0,
              "is_ambiguous": false,
              "candidate_intents": [],
              "group_id": null
            }
            """;
    }
}
