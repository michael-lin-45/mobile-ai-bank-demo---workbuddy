package com.mobileagent.app.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.manager.AgentStateManager;
import com.mobileagent.app.util.ChatHistoryUtils;
import com.mobileagent.app.util.JsonParseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 上下文路由器 - Phase1: 使用LLM判断意图类型 (FOLLOW_UP / SWITCH_NEW / RESUME)
 *
 * 设计:
 * - 全部走LLM判断,不做确定性规则短路(避免误判)
 * - 不使用ReadOnlyMemoryAdvisor，改为手动读取ChatMemory并格式化到{chat_history}占位符
 * - 调用方传入对应的ChatMemory实例(全局/领域级)，在system prompt中区分【对话历史】和【当前消息】
 * - ChatMemory写入由调用方统一管理
 *
 * 支持两种模板:
 * - 默认(l1-routing.st): 理财L1使用,包含FOLLOW_UP/SWITCH_NEW/RESUME三种
 * - 简化(l1-routing-simple.st): 转账/账单L1使用,只有FOLLOW_UP/SWITCH_NEW两种
 */
@Slf4j
@Service
public class ContextRouter {

    private final ChatClient chatClient;
    private final IntentRegistry intentRegistry;
    private final ObjectMapper objectMapper;
    private final int judgmentMaxPairs;

    public ContextRouter(@Qualifier("contextChatClient") ChatClient chatClient,
                        IntentRegistry intentRegistry,
                        @org.springframework.beans.factory.annotation.Value("${routing.history.judgment-max-pairs:5}") int judgmentMaxPairs) {
        this.chatClient = chatClient;
        this.intentRegistry = intentRegistry;
        this.objectMapper = new ObjectMapper();
        this.judgmentMaxPairs = judgmentMaxPairs;
    }

    /**
     * Phase1: 判断意图类型 (使用默认模板 l1-routing.st + 全局ChatMemory)
     */
    public RoutingResult route(String sessionId, String userInput, AgentStateManager stateManager) {
        return route(sessionId, userInput, stateManager, "prompts/l1-routing.st", null, null);
    }

    /**
     * Phase1: 判断意图类型 (使用指定模板 + 指定ChatMemory)
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param stateManager 状态管理器
     * @param templatePath 提示词模板路径
     * @param domainName 领域名称(用于简化模板中的领域标识,可为null)
     * @param chatMemory 指定读取的ChatMemory实例(为null时无法读取历史)
     * @return 路由结果(至少包含routeType)
     */
    public RoutingResult route(String sessionId, String userInput, AgentStateManager stateManager,
                               String templatePath, String domainName, ChatMemory chatMemory) {
        try {
            String chatHistory = formatChatHistory(chatMemory, sessionId);
            String systemPrompt = buildRoutingSystemPrompt(sessionId, userInput, stateManager, templatePath, domainName, chatHistory);

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
            log.error("[ContextRouter] LLM call failed, defaulting to SWITCH_NEW", e);
            return RoutingResult.builder()
                    .routeType("SWITCH_NEW")
                    .confidence(0.3)
                    .build();
        }
    }

    /**
     * 读取ChatMemory并格式化为文本历史(截断到配置对数)
     * 格式: "用户: xxx\n助手: yyy"
     */
    private String formatChatHistory(ChatMemory chatMemory, String sessionId) {
        return ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs);
    }

    private String buildRoutingSystemPrompt(String sessionId, String userInput,
                                             AgentStateManager stateManager,
                                             String templatePath, String domainName,
                                             String chatHistory) {
        String template = loadTemplate(templatePath);
        String intentList = intentRegistry.getIntentListDescription();
        String sessionState = stateManager.getSessionStateDescription(sessionId);

        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        String currentAgent = active != null ? active.getIntent() : "无";
        String pendingAgents = stateManager.hasSuspendedAgents(sessionId)
                ? String.join(", ", stateManager.getAllSuspended(sessionId).keySet())
                : "无";

        String prompt = template
                .replace("{intent_list}", intentList)
                .replace("{message}", userInput)
                .replace("{current_agent}", currentAgent)
                .replace("{last_agent}", currentAgent)
                .replace("{pending_agents}", pendingAgents)
                .replace("{session_state}", sessionState)
                .replace("{chat_history}", chatHistory);

        // 简化模板需要domain_name替换
        if (domainName != null) {
            prompt = prompt.replace("{domain_name}", domainName);
        }

        return prompt;
    }

    private RoutingResult parseRoutingResponse(String content, String templatePath) {
        try {
            String json = JsonParseUtils.extractJson(content);
            var node = objectMapper.readTree(json);

            String routeType = node.has("route_type") ? node.get("route_type").asText() : "SWITCH_NEW";
            boolean simpleMode = templatePath != null && templatePath.contains("simple");
            routeType = normalizeRouteType(routeType, simpleMode);

            return RoutingResult.builder()
                    .routeType(routeType)
                    .confidence(node.has("confidence") ? node.get("confidence").asDouble() : 0.5)
                    .build();
        } catch (Exception e) {
            log.warn("[ContextRouter] Failed to parse routing response: {}", content, e);
            return RoutingResult.builder()
                    .routeType("SWITCH_NEW")
                    .confidence(0.3)
                    .build();
        }
    }

    private String normalizeRouteType(String routeType, boolean simpleMode) {
        if (routeType == null) return "SWITCH_NEW";
        if (simpleMode) {
            // 简化模式: 只支持FOLLOW_UP和SWITCH_NEW
            return switch (routeType.toUpperCase()) {
                case "CONTINUE_FOLLOWUP", "FOLLOW_UP" -> "FOLLOW_UP";
                default -> "SWITCH_NEW"; // RESUME在简化模式下降级为SWITCH_NEW
            };
        }
        return switch (routeType.toUpperCase()) {
            case "CONTINUE_FOLLOWUP", "FOLLOW_UP" -> "FOLLOW_UP";
            case "SWITCH_DIRECT", "SWITCH_COMPLEX", "SWITCH_NEW" -> "SWITCH_NEW";
            case "CONTINUE_RESUME", "RESUME_PENDING", "RESUME" -> "RESUME";
            default -> "SWITCH_NEW";
        };
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[ContextRouter] Failed to load template: {}, using fallback", path);
            return getDefaultRoutingPrompt();
        }
    }

    private String getDefaultRoutingPrompt() {
        return """
            你是一个手机银行意图路由器。根据【对话历史】理解上下文，根据【当前消息】判断意图类型。
            
            已注册意图列表:
            {intent_list}
            
            当前会话状态:
            {session_state}
            
            当前活跃意图: {current_agent}
            挂起的意图: {pending_agents}
            
            ===对话历史(用户之前的对话，用于理解上下文)===
            {chat_history}
            ===对话历史结束===
            
            ===用户当前消息(用户此刻说的话，用于判断当前意图)===
            {message}
            ===当前消息结束===
            
            判断路由类型:
            1. FOLLOW_UP: 用户的话顺着最近一轮对话继续,回答系统刚才的问题或补充信息
            2. SWITCH_NEW: 用户另起了一个完全不同的话题,或同一领域内切换了不同意图
            3. RESUME: 用户的话和当前话题有转折,但和之前某个被挂起的任务形成了顺延
            
            注意: 
            - 同领域内切换不同意图 = SWITCH_NEW！例: 当前WEALTH_CONSULT(推荐),用户说"解读朝朝盈" → SWITCH_NEW
            - 用户表达取消/放弃(如"不查了""算了""取消")应归FOLLOW_UP,这是对当前agent的回应,由agent自行处理。
            - 如果用户不是在回答系统刚问的问题,而是提出了新需求(即使还在同一领域),必须归SWITCH_NEW
            
            严格输出JSON,不要输出其他内容:
            {
              "route_type": "FOLLOW_UP | SWITCH_NEW | RESUME",
              "confidence": 0.0-1.0
            }
            """;
    }
}
