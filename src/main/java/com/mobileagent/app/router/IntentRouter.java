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
                           IntentRegistry intentRegistry) {
        this.chatClient = chatClient;
        this.intentRegistry = intentRegistry;
        this.objectMapper = new ObjectMapper();
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
        return TemplateUtils.loadTemplate(path, this::getDefaultRewritePrompt);
    }

    private String getDefaultRewritePrompt() {
        return """
            你是一个手机银行意图识别与改写器。意图识别是主要目的,上下文改写是为子智能体提取参数服务的手段。
            
            本领域处理以下意图:
            {intent_scope_list}
            
            已注册意图列表(全局,供跨域归属判断参考):
            {all_intent_list}
            
            改写模式: {mode}
            
            当前会话状态:
            {session_state}
            
            当前意图: {intent_name}
            挂起的意图: {pending_agents}
            
            消歧上下文: {disambig_context}
            
            ===对话历史===
            {chat_history}
            ===对话历史结束===

            注意: 对话历史分为"本域"和"其他领域参考"两部分。
            - 本域消息: 用于理解当前领域上下文，识别意图和改写输入
            - 其他领域参考: 仅用于消解跨域指代(如"刚才转的500"→从转账历史中找到金额)
            - 改写时，跨域指代必须从他域参考中消解，不要遗漏

            ★★★ 改写边界(必须遵守) ★★★
            - 只补全用户明确说过的内容(指代消解+参数继承)，不添加用户没说的信息
            - 禁止为用户未指定的参数填默认值
            - 未指定的参数由子智能体追问，改写器不应替用户做决定
            
            ===用户当前消息(用户此刻说的话，用于判断当前意图)===
            {message}
            ===当前消息结束===
            
            任务:
            1. 意图识别(主要): 从已注册意图列表中选择最匹配的意图,无法归入则输出UNKNOWN
            2. 上下文改写(辅助): 将依赖上下文的模糊表达改写为自包含的完整描述,方便子智能体直接提取参数
               - 如果用户输入引用了其他领域的内容(如"刚才说的那个理财")，从其他领域参考中查找并消解指代
            3. 归属判断: 判断用户意图是否属于本领域处理范围，必须结合意图的[类型][描述][范围]逐一比对
               - ★ 核心原则: 用户意图必须**匹配子智能体的intentType**才能判定belongs_to_domain=true
               - [OPERATION]类型: 用户必须要求**执行该操作**才属于scope("我要转账"→true, "我刚才转给谁了"→false)
               - [QUERY]类型: 用户必须要求**查询数据**才属于scope("查账单"→true, "账单在哪看"→false)
               - [CONSULTATION]类型: 用户必须要求**咨询/推荐/解读**才属于scope("推荐理财"→true, "理财有风险吗"→false)
               - 追问/回顾操作结果、询问知识/概念/FAQ → belongs_to_domain=false
               - 无法确定时 → belongs_to_domain=true (保守策略)
            4. 歧义检测: 如果用户输入只能匹配到意图组,标记is_ambiguous=true
            5. 路由判断: 意图在挂起列表中→RESUME,否则→SWITCH
            
            严格输出JSON:
            {
              "intent_name": "意图名称",
              "rewritten_input": "改写后的自包含描述",
              "belongs_to_domain": true,
              "route_type": "SWITCH | RESUME",
              "resume_target": "RESUME时填意图名,否则null",
              "confidence": 0.0-1.0,
              "is_ambiguous": false,
              "candidate_intents": [],
              "group_id": null
            }
            """;
    }
}
