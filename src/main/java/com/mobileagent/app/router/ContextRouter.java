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
 * 上下文路由器 - Phase1: 使用LLM判断意图类型 (FOLLOW / SWITCH / RESUME)
 *
 * 设计:
 * - 全部走LLM判断,不做确定性规则短路(避免误判)
 * - 调用方传入已格式化的chatHistory字符串，本类不再自行读取ChatMemory
 * - 不依赖AgentStateManager，由L1 Service传入状态字符串(currentAgent, pendingAgents, sessionState)
 *
 * 支持两种模板:
 * - 默认(l1-routing.st): 理财L1使用,包含FOLLOW/SWITCH/RESUME三种
 * - 简化(l1-routing-simple.st): 转账/账单L1使用,只有FOLLOW/SWITCH两种
 */
@Slf4j
@Service
public class ContextRouter {

    private final ChatClient chatClient;
    private final IntentRegistry intentRegistry;
    private final ObjectMapper objectMapper;

    public ContextRouter(@Qualifier("contextChatClient") ChatClient chatClient,
                        IntentRegistry intentRegistry,
                        ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.intentRegistry = intentRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Phase1: 判断意图类型
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param currentAgent 当前活跃意图名称(如"TRANSFER"或"无")
     * @param pendingAgents 挂起的意图列表描述(如"WEALTH_CONSULT, WEALTH_INTERPRET"或"无")
     * @param templatePath 提示词模板路径
     * @param domainName 领域名称
     * @param chatHistory 已格式化的对话历史字符串(由调用方从GlobalSessionContext.messages获取)
     * @param lastQuestion 子智能体最后的提问，null表示无
     * @return 路由结果(至少包含routeType)
     */
    public RoutingResult route(String sessionId, String userInput,
                               String currentAgent, String pendingAgents,
                               String templatePath, String domainName, String chatHistory,
                               String lastQuestion) {
        try {
            String sessionState = buildSessionStateDescription(currentAgent, pendingAgents);
            String systemPrompt = buildRoutingSystemPrompt(userInput, currentAgent, pendingAgents,
                    sessionState, templatePath, domainName, chatHistory, lastQuestion);

            long startMs = System.currentTimeMillis();
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[ContextRouter] LLM call completed in {}ms | sessionId={}, template={}, domain={}",
                    elapsedMs, sessionId, templatePath, domainName);
            log.debug("[ContextRouter] LLM raw response: {}", content);

            RoutingResult result = parseRoutingResponse(content, templatePath);
            log.info("[ContextRouter] LLM result: routeType={}, confidence={}",
                    result.getRouteType(), result.getConfidence());
            return result;

        } catch (Exception e) {
            log.error("[ContextRouter] LLM call failed, defaulting to SWITCH", e);
            return RoutingResult.builder()
                    .routeType("SWITCH")
                    .confidence(0.3)
                    .build();
        }
    }

    private String buildSessionStateDescription(String currentAgent, String pendingAgents) {
        StringBuilder sb = new StringBuilder();
        if (currentAgent != null && !"无".equals(currentAgent)) {
            sb.append("当前活跃意图: ").append(currentAgent);
        } else {
            sb.append("当前无活跃意图");
        }
        if (pendingAgents != null && !"无".equals(pendingAgents)) {
            sb.append("\n挂起的意图: ").append(pendingAgents);
        }
        return sb.toString();
    }

    private String buildRoutingSystemPrompt(String userInput,
                                             String currentAgent, String pendingAgents,
                                             String sessionState,
                                             String templatePath, String domainName,
                                             String chatHistory, String lastQuestion) {
        String template = loadTemplate(templatePath);
        String intentList = intentRegistry.getIntentListDescription();

        String lastQuestionContext;
        if (lastQuestion != null && !lastQuestion.isBlank()) {
            lastQuestionContext = """
                
                ===子智能体正在等待回答的问题===
                %s
                ===问题结束===
                
                ★ 关键判断 ★
                如果【子智能体的问题】非空，首要判断: 用户当前消息是否是在回答这个问题？
                  - 是(语义上直接回答/补充参数) → FOLLOW
                    例: 问题"风险偏好？"，用户"稳健" → FOLLOW
                    例: 问题"转给谁？"，用户"张三" → FOLLOW
                    例: 问题"金额？"，用户"500" → FOLLOW
                  - 否(用户提出了新问题/新需求/与问题无关) → SWITCH
                    例: 问题"风险偏好？"，用户"什么是风险等级" → SWITCH（不是在回答，是在反问）
                    例: 问题"转给谁？"，用户"查账单" → SWITCH（完全无关）
                    例: 问题"金额？"，用户"算了不转了" → FOLLOW（取消=回应当前agent）
                """.formatted(lastQuestion);
        } else {
            lastQuestionContext = "";
        }

        String prompt = template
                .replace("{intent_list}", intentList)
                .replace("{message}", userInput)
                .replace("{current_agent}", currentAgent)
                .replace("{last_agent}", currentAgent)
                .replace("{pending_agents}", pendingAgents)
                .replace("{session_state}", sessionState)
                .replace("{chat_history}", chatHistory)
                .replace("{last_question_context}", lastQuestionContext);

        if (domainName != null) {
            prompt = prompt.replace("{domain_name}", domainName);
        }

        return prompt;
    }

    private RoutingResult parseRoutingResponse(String content, String templatePath) {
        try {
            String json = JsonParseUtils.extractJson(content);
            var node = objectMapper.readTree(json);

            String routeType = node.has("route_type") ? node.get("route_type").asText() : "SWITCH";
            boolean simpleMode = templatePath != null && templatePath.contains("simple");
            routeType = normalizeRouteType(routeType, simpleMode);

            return RoutingResult.builder()
                    .routeType(routeType)
                    .confidence(node.has("confidence") ? node.get("confidence").asDouble() : 0.5)
                    .build();
        } catch (Exception e) {
            log.warn("[ContextRouter] Failed to parse routing response: {}", content, e);
            return RoutingResult.builder()
                    .routeType("SWITCH")
                    .confidence(0.3)
                    .build();
        }
    }

    private String normalizeRouteType(String routeType, boolean simpleMode) {
        if (routeType == null) return "SWITCH";
        if (simpleMode) {
            return switch (routeType.toUpperCase()) {
                case "CONTINUE_FOLLOWUP", "FOLLOW" -> "FOLLOW";
                default -> "SWITCH";
            };
        }
        return switch (routeType.toUpperCase()) {
            case "CONTINUE_FOLLOWUP", "FOLLOW" -> "FOLLOW";
            case "SWITCH_DIRECT", "SWITCH_COMPLEX", "SWITCH" -> "SWITCH";
            case "CONTINUE_RESUME", "RESUME_PENDING", "RESUME" -> "RESUME";
            default -> "SWITCH";
        };
    }

    private String loadTemplate(String path) {
        return TemplateUtils.loadTemplate(path);
    }
}
