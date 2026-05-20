package com.mobileagent.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.state.AgentStateManager;
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
 * 意图路由器 - Phase1: 使用LLM判断意图类型 (FOLLOW_UP / SWITCH_NEW / RESUME)
 *
 * 设计:
 * - 全部走LLM判断,不做确定性规则短路(避免误判)
 * - 通过ReadOnlyMemoryAdvisor自动注入对话历史,让LLM理解追问上下文
 * - ReadOnlyMemoryAdvisor只读不写,ChatMemory写入由BankController统一管理
 *   (避免路由JSON作为ASSISTANT消息污染对话历史)
 *
 * 对话历史格式 (由BankController维护):
 *   USER: "我想转账"
 *   ASSISTANT: "请问您要转给谁？"
 *   USER: "张三"
 *   ASSISTANT: "请问您要转多少金额？"
 */
@Slf4j
@Service
public class ContextRouter {

    private final ChatClient chatClient;
    private final IntentRegistry intentRegistry;
    private final ObjectMapper objectMapper;

    public ContextRouter(@Qualifier("contextChatClient") ChatClient chatClient,
                        IntentRegistry intentRegistry) {
        this.chatClient = chatClient;
        this.intentRegistry = intentRegistry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Phase1: 判断意图类型
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param stateManager 状态管理器
     * @return 路由结果(至少包含routeType)
     */
    public RoutingResult route(String sessionId, String userInput, AgentStateManager stateManager) {
        try {
            String systemPrompt = buildRoutingSystemPrompt(sessionId, userInput, stateManager);

            long startMs = System.currentTimeMillis();
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[ContextRouter] LLM call completed in {}ms | sessionId={}, userInput={}", elapsedMs, sessionId, userInput);
            log.debug("[ContextRouter] LLM raw response: {}", content);

            RoutingResult result = parseRoutingResponse(content);
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

    private String buildRoutingSystemPrompt(String sessionId, String userInput, AgentStateManager stateManager) {
        String template = loadTemplate("prompts/l0-routing.st");
        String intentList = intentRegistry.getIntentListDescription();
        String sessionState = stateManager.getSessionStateDescription(sessionId);

        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        String currentAgent = active != null ? active.getIntent() : "无";
        String pendingAgents = stateManager.hasSuspendedAgents(sessionId)
                ? String.join(", ", stateManager.getAllSuspended(sessionId).keySet())
                : "无";

        return template
                .replace("{intent_list}", intentList)
                .replace("{message}", userInput)
                .replace("{current_agent}", currentAgent)
                .replace("{last_agent}", currentAgent)
                .replace("{pending_agents}", pendingAgents)
                .replace("{session_state}", sessionState);
    }

    private RoutingResult parseRoutingResponse(String content) {
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);

            String routeType = node.has("route_type") ? node.get("route_type").asText() : "SWITCH_NEW";
            routeType = normalizeRouteType(routeType);

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

    private String normalizeRouteType(String routeType) {
        if (routeType == null) return "SWITCH_NEW";
        return switch (routeType.toUpperCase()) {
            case "CONTINUE_FOLLOWUP", "FOLLOW_UP" -> "FOLLOW_UP";
            case "SWITCH_DIRECT", "SWITCH_COMPLEX", "SWITCH_NEW" -> "SWITCH_NEW";
            case "CONTINUE_RESUME", "RESUME_PENDING", "RESUME" -> "RESUME";
            default -> "SWITCH_NEW";
        };
    }

    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
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
            你是一个手机银行意图路由器。根据用户输入和当前会话状态判断意图类型。
            
            已注册意图列表:
            {intent_list}
            
            当前会话状态:
            {session_state}
            
            当前活跃意图: {current_agent}
            挂起的意图: {pending_agents}
            
            用户输入: {message}
            
            判断路由类型:
            1. FOLLOW_UP: 用户在回答当前agent的问题或追问(包括取消/放弃等否定回答)
            2. SWITCH_NEW: 用户表达了新的意图
            3. RESUME: 用户想恢复之前挂起的操作
            
            注意: 即使用户表达取消/放弃/中断意愿(如"不查了""算了""取消"),也应归类为FOLLOW_UP,
            因为这是对当前agent的回应,由agent自行判断处理。
            
            严格输出JSON,不要输出其他内容:
            {
              "route_type": "FOLLOW_UP | SWITCH_NEW | RESUME",
              "confidence": 0.0-1.0
            }
            """;
    }
}
