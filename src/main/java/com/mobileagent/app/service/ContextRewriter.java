package com.mobileagent.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 上下文改写器 - 当FOLLOW_UP但无activeThread时，将依赖上下文的用户输入改写为自包含描述
 *
 * 使用场景:
 * - 用户在Transfer/Bill领域完成一次操作后，继续追问(如"那上个月的呢""看看详情")
 * - 此时activeThread已清空(操作已完成)，但用户的话依赖历史上下文
 * - 改写后的输入传给子智能体，使参数提取更准确
 * - 原始用户话术由L1 Service保存到ChatMemory，改写后的仅传给Graph执行
 *
 * 参考WealthService的IntentionRouter改写机制，但更简洁:
 * - 无意图识别(L0已确定领域)
 * - 无消歧(转账/账单各自只有一个意图)
 * - 只做上下文改写
 */
@Slf4j
@Service
public class ContextRewriter {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int judgmentMaxPairs;

    public ContextRewriter(@Qualifier("intentChatClient") ChatClient chatClient,
                           @org.springframework.beans.factory.annotation.Value("${routing.history.judgment-max-pairs:5}") int judgmentMaxPairs) {
        this.chatClient = chatClient;
        this.objectMapper = new ObjectMapper();
        this.judgmentMaxPairs = judgmentMaxPairs;
    }

    /**
     * 上下文改写 - 将依赖历史的用户输入改写为自包含描述
     *
     * @param sessionId 会话ID
     * @param userInput 用户原始输入
     * @param chatMemory 领域ChatMemory(用于读取历史)
     * @param domainName 领域名称(如"转账""账单")
     * @return 改写后的输入，如果改写失败则返回原始输入
     */
    public String rewrite(String sessionId, String userInput, ChatMemory chatMemory, String domainName) {
        try {
            String chatHistory = formatChatHistory(chatMemory, sessionId);
            String systemPrompt = buildRewritePrompt(userInput, chatHistory, domainName);

            long startMs = System.currentTimeMillis();
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[ContextRewriter] LLM call completed in {}ms | domain={}, userInput={}", elapsedMs, domainName, userInput);
            log.debug("[ContextRewriter] LLM raw response: {}", content);

            String rewritten = parseRewriteResponse(content, userInput);
            log.info("[ContextRewriter] Result: original='{}' → rewritten='{}'", userInput, rewritten);
            return rewritten;

        } catch (Exception e) {
            log.error("[ContextRewriter] LLM call failed, returning original input", e);
            return userInput;
        }
    }

    private String formatChatHistory(ChatMemory chatMemory, String sessionId) {
        return ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs);
    }

    private String buildRewritePrompt(String userInput, String chatHistory, String domainName) {
        String template = loadTemplate("prompts/l1-context-rewrite.st");
        return template
                .replace("{domain_name}", domainName)
                .replace("{chat_history}", chatHistory)
                .replace("{message}", userInput);
    }

    private String parseRewriteResponse(String content, String originalInput) {
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            if (node.has("rewritten_input") && !node.get("rewritten_input").isNull()) {
                String rewritten = node.get("rewritten_input").asText();
                if (rewritten != null && !rewritten.isEmpty()) {
                    return rewritten;
                }
            }
            return originalInput;
        } catch (Exception e) {
            log.warn("[ContextRewriter] Failed to parse response: {}, returning original", content, e);
            return originalInput;
        }
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
            你是上下文改写器。将依赖对话历史的用户输入改写为自包含的完整描述。
            
            ===对话历史===
            {chat_history}
            ===对话历史结束===
            
            ===用户当前消息===
            {message}
            ===当前消息结束===
            
            严格输出JSON:
            {
              "rewritten_input": "改写后的自包含描述，不需要改写则原样输出",
              "need_rewrite": true
            }
            """;
    }
}
