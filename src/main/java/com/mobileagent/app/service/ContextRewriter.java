package com.mobileagent.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.state.AgentStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 上下文改写+意图识别服务 - Phase2: 使用8B模型同时完成改写和识别
 *
 * 核心逻辑:
 * - 一个LLM调用同时完成上下文改写和意图识别
 * - 改写: "那昨天的呢" → "查询昨天的账单明细"
 * - 识别: → intent=bill_query
 * - 路由: → SWITCH_NEW 或 RESUME
 */
@Slf4j
@Service
public class ContextRewriter {

    private final ChatModel chatModel;
    private final IntentRegistry intentRegistry;
    private final ObjectMapper objectMapper;

    @Value("${routing.model.rewrite-model:qwen-turbo}")
    private String rewriteModel;

    public ContextRewriter(ChatModel chatModel, IntentRegistry intentRegistry) {
        this.chatModel = chatModel;
        this.intentRegistry = intentRegistry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Phase2: 上下文改写 + 意图识别 (合并为一个LLM调用)
     *
     * @param sessionId 会话ID
     * @param userInput 用户原始输入
     * @param phase1Result Phase1的路由结果
     * @param stateManager 状态管理器
     * @return 包含改写后输入、意图名称、路由类型的完整路由结果
     */
    public RoutingResult rewriteAndIdentify(String sessionId, String userInput,
                                             RoutingResult phase1Result,
                                             AgentStateManager stateManager) {
        try {
            String prompt = buildRewritePrompt(sessionId, userInput, phase1Result, stateManager);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String content = response.getResult().getOutput().getText();
            log.debug("[ContextRewriter] LLM raw response: {}", content);

            RoutingResult result = parseRewriteResponse(content, phase1Result);
            log.info("[ContextRewriter] Result: intent={}, routeType={}, rewritten={}, confidence={}",
                    result.getIntentName(), result.getRefinedRouteType(),
                    result.getRewrittenInput(), result.getConfidence());
            return result;

        } catch (Exception e) {
            log.error("[ContextRewriter] LLM call failed", e);
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

    private String buildRewritePrompt(String sessionId, String userInput,
                                       RoutingResult phase1Result,
                                       AgentStateManager stateManager) {
        String template = loadTemplate("prompts/l1-rewrite.st");
        String intentList = intentRegistry.getIntentListDescription();
        String sessionState = stateManager.getSessionStateDescription(sessionId);

        AgentStateManager.ActiveThreadInfo active = stateManager.getActiveThread(sessionId);
        String currentIntent = active != null ? active.getIntent() : "无";
        String pendingList = stateManager.hasSuspendedAgents(sessionId)
                ? String.join(", ", stateManager.getAllSuspended(sessionId).keySet())
                : "无";

        // 根据Phase1判断确定改写模式
        String mode = phase1Result.isResume() ? "RESUME" : "SWITCH";

        return template
                .replace("{intent_list}", intentList)
                .replace("{message}", userInput)
                .replace("{mode}", mode)
                .replace("{intent_name}", currentIntent)
                .replace("{last_agent_description}", currentIntent)
                .replace("{last_agent_summary}", sessionState)
                .replace("{session_state}", sessionState)
                .replace("{pending_agents}", pendingList);
    }

    private RoutingResult parseRewriteResponse(String content, RoutingResult phase1Result) {
        try {
            String json = extractJson(content);
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

            return RoutingResult.builder()
                    .routeType(phase1Result.getRouteType())
                    .refinedRouteType(refinedRouteType)
                    .intentName(intentName)
                    .rewrittenInput(rewrittenInput)
                    .confidence(confidence)
                    .reasoning(phase1Result.getReasoning())
                    .resumeTarget(resumeTarget)
                    .build();
        } catch (Exception e) {
            log.warn("[ContextRewriter] Failed to parse rewrite response: {}", content, e);
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
     * - 如果识别的意图在suspendedAgents中 → RESUME
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
            log.warn("[ContextRewriter] Failed to load template: {}, using fallback", path);
            return getDefaultRewritePrompt();
        }
    }

    private String getDefaultRewritePrompt() {
        return """
            你是一个手机银行意图改写器。同时完成上下文改写和意图识别。
            
            已注册意图列表:
            {intent_list}
            
            改写模式: {mode}
            
            当前会话状态:
            {session_state}
            
            挂起的意图: {pending_agents}
            
            用户输入: {message}
            
            任务:
            1. 上下文改写: 将模糊表达改写为自包含的完整描述
            2. 意图识别: 从已注册意图列表中选择最匹配的意图
            3. 路由判断: 如果识别的意图在挂起列表中 → RESUME, 否则 → SWITCH_NEW
            
            严格输出JSON:
            {
              "intent_name": "意图名称(从已注册列表选择)",
              "rewritten_input": "改写后的自包含描述",
              "route_type": "SWITCH_NEW | RESUME",
              "resume_target": "RESUME时填要恢复的意图名,其他填null",
              "confidence": 0.0-1.0
            }
            """;
    }
}
