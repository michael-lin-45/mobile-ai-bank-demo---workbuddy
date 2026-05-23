package com.mobileagent.app.domain;

import com.mobileagent.app.data.WorkflowOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 闲聊L1 Service - 直接使用32B+大模型与用户聊天
 *
 * 设计:
 * - 无ContextRouter, 无IntentRouter, 无RoutingService
 * - 无activeThread, 无suspendedAgents
 * - 直接用ChatClient与用户对话
 * - 使用全局ChatMemory(L0注入的历史),不创建独立ChatMemory
 * - 不需要记录到领域ChatMemory(闲聊没有独立的领域ChatMemory)
 */
@Slf4j
@Service
public class ChatService {

    private final ChatClient chatChatClient;

    public ChatService(@Qualifier("chatChatClient") ChatClient chatChatClient) {
        this.chatChatClient = chatChatClient;
    }

    /**
     * 处理闲聊消息
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @return 聊天回复
     */
    public WorkflowOutput handle(String sessionId, String userInput) {
        log.info("[ChatService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            long startMs = System.currentTimeMillis();
            String content = chatChatClient.prompt()
                    .system("你是一个友好、专业的手机银行助手。当用户闲聊或询问非银行操作的问题时，与他们自然对话。保持简洁友好，偶尔可以引导用户了解银行服务。")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[ChatService] LLM call completed in {}ms", elapsedMs);

            return WorkflowOutput.completed("CHAT", content);

        } catch (Exception e) {
            log.error("[ChatService] Error handling chat", e);
            return WorkflowOutput.error("聊天服务暂时不可用: " + e.getMessage());
        }
    }
}
