package com.mobileagent.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.model.IntentRegistry;
import com.mobileagent.app.model.RoutingResult;
import com.mobileagent.app.state.AgentStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 意图路由器 - Phase1: 使用4B模型判断意图类型 (FOLLOW_UP / SWITCH_NEW / RESUME)
 *
 * 规则:
 * - 只看用户最后一句 + 上下文，不做改写
 * - FOLLOW_UP: 用户在回答当前agent的问题(包括取消/放弃等否定回答,由子Graph检测)
 * - SWITCH_NEW: 用户表达了新的意图
 * - RESUME: 用户想恢复之前挂起的操作
 * - 取消意图: 不在Phase1判断, 由子Graph通过detectCancelFromInput()自行检测
 */
@Slf4j
@Service
public class IntentRouter {

    private final ChatModel chatModel;
    private final IntentRegistry intentRegistry;
    private final ObjectMapper objectMapper;

    @Value("${routing.model.routing-model:qwen-turbo}")
    private String routingModel;

    public IntentRouter(ChatModel chatModel, IntentRegistry intentRegistry) {
        this.chatModel = chatModel;
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
        // 先做确定性规则检查
        RoutingResult deterministic = checkDeterministicRules(sessionId, userInput, stateManager);
        if (deterministic != null) {
            log.info("[IntentRouter] Deterministic match: routeType={}, reasoning={}",
                    deterministic.getRouteType(), deterministic.getReasoning());
            return deterministic;
        }

        // LLM判断
        try {
            String prompt = buildRoutingPrompt(sessionId, userInput, stateManager);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String content = response.getResult().getOutput().getText();
            log.debug("[IntentRouter] LLM raw response: {}", content);

            RoutingResult result = parseRoutingResponse(content);
            log.info("[IntentRouter] LLM result: routeType={}, confidence={}, reasoning={}",
                    result.getRouteType(), result.getConfidence(), result.getReasoning());
            return result;

        } catch (Exception e) {
            log.error("[IntentRouter] LLM call failed, defaulting to SWITCH_NEW", e);
            return RoutingResult.builder()
                    .routeType("SWITCH_NEW")
                    .confidence(0.3)
                    .reasoning("LLM调用失败,降级为新意图")
                    .build();
        }
    }

    /** 确定性规则检查 - 快速路径,不调用LLM */
    private RoutingResult checkDeterministicRules(String sessionId, String userInput, AgentStateManager stateManager) {
        // 1. 短回答模式(数字/确认词) → FOLLOW_UP
        if (userInput.matches("^\\d+(\\.\\d+)?(元|块|万)?$") ||
            userInput.matches("^(确认|好的|是的|对|继续|可以|没问题)$")) {
            return RoutingResult.builder()
                    .routeType("FOLLOW_UP")
                    .confidence(0.95)
                    .reasoning("确定性短回答")
                    .build();
        }

        // 2. 明确恢复指令 → RESUME
        if (userInput.matches(".*(回到|继续|恢复).*(转账|账单|查询|理财).*$") ||
            userInput.matches(".*(转账|账单|查询|理财).*(回到|继续|恢复).*$")) {
            return RoutingResult.builder()
                    .routeType("RESUME")
                    .confidence(0.9)
                    .reasoning("明确的恢复指令")
                    .build();
        }

        // 3. 短回答+有活跃线程 → FOLLOW_UP (避免LLM将简单回答误判为新意图)
        //    包括取消意图的短句(如"不查了""算了")也走FOLLOW_UP,由子Graph自行检测
        AgentStateManager.ActiveThreadInfo activeThread = stateManager.getActiveThread(sessionId);
        boolean hasActiveContext = activeThread != null || stateManager.isInDisambiguation(sessionId);
        if (hasActiveContext && userInput.length() <= 8) {
            if (!userInput.matches(".*(转账|查账|查一下|汇款|理财|理财推荐|理财解读).*$")) {
                return RoutingResult.builder()
                        .routeType("FOLLOW_UP")
                        .confidence(0.85)
                        .reasoning("短回答+有活跃线程 → 回答提问")
                        .build();
            }
        }

        return null; // 无确定性匹配,需要LLM判断(包括CANCEL)
    }

    private String buildRoutingPrompt(String sessionId, String userInput, AgentStateManager stateManager) {
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
            // 尝试提取JSON
            String json = extractJson(content);
            var node = objectMapper.readTree(json);

            String routeType = node.has("route_type") ? node.get("route_type").asText() : "SWITCH_NEW";

            // 统一路由类型
            routeType = normalizeRouteType(routeType);

            return RoutingResult.builder()
                    .routeType(routeType)
                    .confidence(node.has("confidence") ? node.get("confidence").asDouble() : 0.5)
                    .reasoning(node.has("reasoning") ? node.get("reasoning").asText() : "")
                    .resumeTarget(node.has("resume_target") && !node.get("resume_target").isNull()
                            ? node.get("resume_target").asText() : null)
                    .intentName(node.has("intent_name") && !node.get("intent_name").isNull()
                            ? node.get("intent_name").asText() : null)
                    .build();
        } catch (Exception e) {
            log.warn("[IntentRouter] Failed to parse routing response: {}", content, e);
            return RoutingResult.builder()
                    .routeType("SWITCH_NEW")
                    .confidence(0.3)
                    .reasoning("解析失败,降级为新意图")
                    .build();
        }
    }

    private String normalizeRouteType(String routeType) {
        if (routeType == null) return "SWITCH_NEW";
        return switch (routeType.toUpperCase()) {
            case "CONTINUE_FOLLOWUP", "FOLLOW_UP" -> "FOLLOW_UP";
            case "SWITCH_DIRECT", "SWITCH_COMPLEX", "SWITCH_NEW" -> "SWITCH_NEW";
            case "CONTINUE_RESUME", "RESUME_PENDING", "RESUME" -> "RESUME";
            case "CANCEL" -> "FOLLOW_UP"; // CANCEL降级为FOLLOW_UP,由子Graph检测
            default -> "SWITCH_NEW";
        };
    }

    private String extractJson(String content) {
        // 去掉markdown代码块
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

        // 尝试找到JSON对象
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
            log.warn("[IntentRouter] Failed to load template: {}, using fallback", path);
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
            
            用户输入: {message}
            
            判断路由类型:
            1. FOLLOW_UP: 用户在回答当前agent的问题或追问(包括取消/放弃等否定回答)
            2. SWITCH_NEW: 用户表达了新的意图
            3. RESUME: 用户想恢复之前挂起的操作
            
            严格输出JSON:
            {
              "route_type": "FOLLOW_UP | SWITCH_NEW | RESUME",
              "intent_name": "意图名称(SWITCH_NEW时必填)",
              "confidence": 0.0-1.0,
              "reasoning": "判断理由",
              "resume_target": "RESUME时填要恢复的意图名,其他填null"
            }
            """;
    }
}
