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
 * 上下文路由器 - Phase1: 使用LLM判断意图类型 (FOLLOW / SWITCH / CANCEL)
 *
 * 设计:
 * - 无活跃意图时(hasActiveAgent=false)直接短路返回SWITCH，省一次LLM调用
 * - 有活跃意图时走LLM判断FOLLOW/SWITCH/CANCEL
 * - 调用方传入已格式化的chatHistory字符串，本类不再自行读取ChatMemory
 * - 不依赖AgentStateManager，由L1 Service传入状态字符串(currentAgent, sessionState)
 *
 * RESUME是纯粹的代码行为: executeRoute根据suspendedAgents状态决定是否resume，
 * 不由LLM判断，也不经过本路由器输出。
 *
 * 支持两种模板:
 * - 默认(l1-context.st): 理财L1使用,包含FOLLOW/SWITCH/CANCEL三种
 * - 简化(l1-context-simple.st): 转账/账单L1使用,包含FOLLOW/SWITCH/CANCEL三种
 */
@Slf4j
@Service
public class ContextRouter {

    private final ChatClient chatClient;
    private final SubGraphRegistry subGraphRegistry;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics obsMetrics;

    public ContextRouter(@Qualifier("contextChatClient") ChatClient chatClient,
                        SubGraphRegistry subGraphRegistry,
                        ObjectMapper objectMapper,
                        ObservabilityMetrics obsMetrics) {
        this.chatClient = chatClient;
        this.subGraphRegistry = subGraphRegistry;
        this.objectMapper = objectMapper;
        this.obsMetrics = obsMetrics;
    }

    /**
     * Phase1: 判断意图类型
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param currentAgent 当前活跃意图名称(如"TRANSFER")，无活跃意图时传null
     * @param hasActiveAgent 是否存在活跃意图(用于短路判断，避免魔法字符串)
     * @param templatePath 提示词模板路径
     * @param domainName 领域名称
     * @param chatHistory 已格式化的对话历史字符串(由调用方从GlobalSessionContext.messages获取)
     * @param lastQuestion 子智能体最后的提问，null表示无
     * @return 路由结果(至少包含routeType)
     */
    public RoutingResult route(String sessionId, String userInput,
                               String currentAgent, boolean hasActiveAgent,
                               String templatePath, String domainName, String chatHistory,
                               String lastQuestion) {
        // 无活跃意图 → FOLLOW/CANCEL无意义，直接SWITCH（省一次LLM调用）
        if (!hasActiveAgent) {
            log.info("[ContextRouter] No active agent → short-circuit SWITCH | sessionId={}", sessionId);
            return RoutingResult.builder()
                    .routeType("SWITCH")
                    .confidence(1.0)
                    .reasoning("无活跃意图，必定为新意图")
                    .build();
        }

        try {
            String currentAgentDisplay = currentAgent != null ? currentAgent : "无";
            String sessionState = buildSessionStateDescription(currentAgentDisplay);
            String systemPrompt = buildRoutingSystemPrompt(userInput, currentAgentDisplay,
                    sessionState, templatePath, domainName, chatHistory, lastQuestion);

            long startMs = System.currentTimeMillis();
            // 托管模式：routeType 在 LLM 返回后才解析，需延迟回填到 span.intent
            AgentSpanContext ctx = AgentSpanContext.setWithHeldSpan("L1-LLM1", "ContextRouter", null, sessionId, null);
            String content;
            try {
                content = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userInput)
                        .call()
                        .content();
            } catch (Exception e) {
                // LLM 调用失败：ObsChatModel 已在异常路径自行 end span；此处回填兜底 routeType 并清理
                ctx.commitIntent(resolveFallbackRouteType(currentAgentDisplay, lastQuestion), java.util.Map.of());
                throw e;
            }
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[ContextRouter] LLM call completed in {}ms | sessionId={}, template={}, domain={}",
                    elapsedMs, sessionId, templatePath, domainName);
            log.debug("[ContextRouter] LLM raw response: {}", content);

            RoutingResult result = parseRoutingResponse(content, templatePath);
            log.info("[ContextRouter] LLM result: routeType={}, confidence={}",
                    result.getRouteType(), result.getConfidence());
            // 埋点：L1 上下文路由调用计数（解锁 P0-5/P1-2 backend agent_call:L1 聚合）
            obsMetrics.recordL1Call(result.getRouteType(), domainName);
            // 回填真实 routeType 到 span.intent（托管模式结束 span）
            ctx.commitIntent(result.getRouteType(), java.util.Map.of());
            return result;

        } catch (Exception e) {
            String fallback = resolveFallbackRouteType(currentAgent, lastQuestion);
            log.error("[ContextRouter] LLM call failed, defaulting to '{}'", fallback, e);
            return RoutingResult.builder()
                    .routeType(fallback)
                    .confidence(0.3)
                    .build();
        } finally {
            // 兜底：若上方未成功 commit（如埋点/状态更新异常），仍结束 held span 并清理 ThreadLocal，防止 span 泄漏
            AgentSpanContext.clear();
        }
    }

    private String buildSessionStateDescription(String currentAgent) {
        if (currentAgent != null && !"无".equals(currentAgent)) {
            return "当前活跃意图: " + currentAgent;
        }
        return "当前无活跃意图";
    }

    private String buildRoutingSystemPrompt(String userInput,
                                             String currentAgent,
                                             String sessionState,
                                             String templatePath, String domainName,
                                             String chatHistory, String lastQuestion) {
        String template = loadTemplate(templatePath);
        String intentList = subGraphRegistry.getIntentListDescription();

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
                  - 取消/放弃(如"算了""不转了""取消") → CANCEL
                    例: 问题"转给谁？"，用户"不转了" → CANCEL（取消当前操作，不是回答问题）
                    例: 问题"金额？"，用户"算了" → CANCEL
                  - 否(用户提出了新问题/新需求/与问题无关) → SWITCH
                    例: 问题"风险偏好？"，用户"什么是风险等级" → SWITCH（不是在回答，是在反问）
                    例: 问题"转给谁？"，用户"查账单" → SWITCH（完全无关）
                """.formatted(lastQuestion);
        } else {
            lastQuestionContext = "";
        }

        String prompt = template
                .replace("{intent_list}", intentList)
                .replace("{message}", userInput)
                .replace("{current_agent}", currentAgent)
                .replace("{last_agent}", currentAgent)
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
            case "CANCEL_OPERATION", "CANCEL" -> "CANCEL";
            case "SWITCH_DIRECT", "SWITCH_COMPLEX", "SWITCH" -> "SWITCH";
            // RESUME — 代码根据suspendedAgents状态决定，不由LLM输出。
            // 若LLM意外输出RESUME，视为SWITCH（下游executeRoute根据状态自动resume）
            case "CONTINUE_RESUME", "RESUME_PENDING", "RESUME" -> "SWITCH";
            default -> "SWITCH";
        };
    }

    private String loadTemplate(String path) {
        return TemplateUtils.loadTemplate(path);
    }

    /**
     * LLM 不可用时的兜底路由类型：若已存在活跃意图且子智能体正在等待回答，
     * 则假定用户在回答该问题(FOLLOW)，继续当前 agent；否则 SWITCH。
     * 仅在 LLM 异常路径生效，正常 LLM 路径不受影响。
     */
    private String resolveFallbackRouteType(String currentAgent, String lastQuestion) {
        if (currentAgent != null && !"无".equals(currentAgent)
                && lastQuestion != null && !lastQuestion.isBlank()) {
            return "FOLLOW";
        }
        return "SWITCH";
    }
}
