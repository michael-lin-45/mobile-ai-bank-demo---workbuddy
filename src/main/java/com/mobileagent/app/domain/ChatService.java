package com.mobileagent.app.domain;

import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 闲聊L1 Service - 直接使用大模型与用户聊天
 *
 * - 无ContextRouter, 无IntentRouter, 无RoutingService
 * - 无activeThread, 无suspendedAgents
 * - 直接用ChatClient与用户对话
 * - 从 GlobalSessionContext.messages 读取对话历史
 */
@Slf4j
@Service
public class ChatService implements DomainHandler {

    private final ChatClient chatChatClient;
    private final GlobalSessionStateStore globalSessionStore;
    private final int chatMaxPairs;

    public ChatService(@Qualifier("chatChatClient") ChatClient chatChatClient,
                       GlobalSessionStateStore globalSessionStore,
                       @Value("${routing.history.chat-max-pairs:10}") int chatMaxPairs) {
        this.chatChatClient = chatChatClient;
        this.globalSessionStore = globalSessionStore;
        this.chatMaxPairs = chatMaxPairs;
    }

    @Override
    public Flux<StreamChunk> handle(String sessionId, String userInput) {
        log.info("[ChatService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            long startMs = System.currentTimeMillis();

            String chatHistory = globalSessionStore.getOrCreate(sessionId).formatRecentMessages(chatMaxPairs);

            var promptBuilder = chatChatClient.prompt()
                    .system(SYSTEM_PROMPT);

            if (!chatHistory.equals("(无历史对话)")) {
                promptBuilder = promptBuilder.user(
                        "===对话历史(参考)===\n" + chatHistory + "\n===历史结束===\n\n用户当前消息: " + userInput);
            } else {
                promptBuilder = promptBuilder.user(userInput);
            }

            String content = promptBuilder.call().content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[ChatService] LLM call completed in {}ms", elapsedMs);

            return Flux.just(StreamChunk.complete("CHAT", content));

        } catch (Exception e) {
            log.error("[ChatService] Error handling chat", e);
            return Flux.just(StreamChunk.error("聊天服务暂时不可用: " + e.getMessage()));
        }
    }

    @Override
    public String getDomainName() {
        return "闲聊";
    }

    private static final String SYSTEM_PROMPT = """
            你是一个专业的手机银行助手。你的能力包括:
            1. 闲聊: 与用户自然对话
            2. 银行知识FAQ: 回答银行业务相关问题，如:
               - 解释概念: "什么是风险等级"、"什么是理财产品"、"什么是账单周期"
               - 说明流程: "怎么转账"、"如何查询账单"
               - 通用咨询: "理财有风险吗"、"活期和定期有什么区别"
            3. 引导: 当用户表达具体操作需求时，引导他们使用对应功能

            回答原则:
            - 简洁专业，不超过3句话
            - 涉及具体操作时，引导用户直接说出需求(如"您可以说'帮我转账'")
            - 不确定的信息不要编造，建议用户咨询客服
            """;
}
