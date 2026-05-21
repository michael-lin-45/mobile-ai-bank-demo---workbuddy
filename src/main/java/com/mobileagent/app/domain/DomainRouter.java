package com.mobileagent.app.domain;

import com.mobileagent.app.service.ChatHistoryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * L0领域路由器 - 无状态，根据对话历史+当前消息判断领域
 *
 * 设计:
 * - 完全无状态，不管理threadId
 * - 手动读取全局ChatMemory，将历史格式化后拼接到system prompt的{chat_history}占位符
 * - 不使用ReadOnlyMemoryAdvisor，直接在system prompt中区分【对话历史】和【当前消息】
 * - 每轮都重新判断领域（闲聊中说"查账单"会自动路由到BILL）
 * - 输出: WEALTH / TRANSFER / BILL / UNSUPPORTED / CHAT
 *
 * UNSUPPORTED处理:
 * - 用户提到银行业务但不属于已支持领域(理财/转账/账单)时路由到UNSUPPORTED
 * - 如贷款、信用卡、活动、权益、积分、开户、销户等
 * - 返回结果包含unsupportedFeature字段，用于生成"XX功能暂不支持"的回复
 */
@Slf4j
@Component
public class DomainRouter {

    private final ChatClient domainChatClient;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;
    private final int judgmentMaxPairs;

    public DomainRouter(@Qualifier("domainChatClient") ChatClient domainChatClient,
                        ChatMemory chatMemory,
                        @org.springframework.beans.factory.annotation.Value("${routing.history.judgment-max-pairs:5}") int judgmentMaxPairs) {
        this.domainChatClient = domainChatClient;
        this.chatMemory = chatMemory;
        this.objectMapper = new ObjectMapper();
        this.judgmentMaxPairs = judgmentMaxPairs;
    }

    /**
     * L0领域路由结果
     */
    public record DomainResult(String domain, String unsupportedFeature, double confidence, String rawResponse) {
        public boolean isUnsupported() {
            return "UNSUPPORTED".equals(domain);
        }
    }

    /**
     * L0领域路由
     */
    public DomainResult route(String sessionId, String userInput) {
        try {
            String chatHistory = formatChatHistory(sessionId);
            String systemPrompt = buildDomainPrompt(userInput, chatHistory);

            long startMs = System.currentTimeMillis();
            String content = domainChatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[DomainRouter] LLM call completed in {}ms | sessionId={}, userInput={}", elapsedMs, sessionId, userInput);
            log.info("[DomainRouter] LLM raw response: {}", content);

            DomainResult result = parseDomainResponse(content);
            log.info("[DomainRouter] Domain resolved: {} (feature={}) for input='{}'", result.domain(), result.unsupportedFeature(), userInput);
            return result;

        } catch (Exception e) {
            log.error("[DomainRouter] LLM call failed, defaulting to CHAT", e);
            return new DomainResult("CHAT", null, 0.3, null);
        }
    }

    /**
     * 读取全局ChatMemory并格式化为文本历史(截断到配置对数)
     * 格式: "用户: xxx\n助手: yyy\n用户: zzz\n助手: www"
     */
    private String formatChatHistory(String sessionId) {
        return ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs);
    }

    private String buildDomainPrompt(String userInput, String chatHistory) {
        String template = loadTemplate("prompts/l0-domain.st");
        return template
                .replace("{message}", userInput)
                .replace("{chat_history}", chatHistory);
    }

    private DomainResult parseDomainResponse(String content) {
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);

            String domain = node.has("domain") ? node.get("domain").asText() : "CHAT";
            domain = normalizeDomain(domain);

            String unsupportedFeature = null;
            if ("UNSUPPORTED".equals(domain) && node.has("unsupported_feature") && !node.get("unsupported_feature").isNull()) {
                unsupportedFeature = node.get("unsupported_feature").asText();
            }

            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;
            return new DomainResult(domain, unsupportedFeature, confidence, content);

        } catch (Exception e) {
            log.warn("[DomainRouter] Failed to parse domain response, raw='{}'", content, e);
            return new DomainResult("CHAT", null, 0.3, content);
        }
    }

    private String normalizeDomain(String domain) {
        if (domain == null) return "CHAT";
        return switch (domain.toUpperCase()) {
            case "WEALTH" -> "WEALTH";
            case "TRANSFER" -> "TRANSFER";
            case "BILL" -> "BILL";
            case "UNSUPPORTED" -> "UNSUPPORTED";
            default -> "CHAT";
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
            log.warn("[DomainRouter] Failed to load template: {}, using fallback", path);
            return getDefaultDomainPrompt();
        }
    }

    private String getDefaultDomainPrompt() {
        return """
            你是手机银行领域路由器。判断用户当前消息属于哪个业务领域。

            五个领域: WEALTH(理财), TRANSFER(转账), BILL(账单), UNSUPPORTED(银行功能但暂不支持), CHAT(闲聊)

            输出JSON: {"domain": "WEALTH|TRANSFER|BILL|UNSUPPORTED|CHAT", "unsupported_feature": "UNSUPPORTED时填功能名否则null", "confidence": 0.0-1.0}
            """;
    }
}
