package com.mobileagent.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读记忆Advisor - 仅读取ChatMemory历史注入LLM上下文，不自动写回
 *
 * 与MessageChatMemoryAdvisor的区别:
 * - before(): 读取chatMemory.get(conversationId)注入历史消息（与原版相同）
 * - before(): 不把当前UserMessage存入ChatMemory（原版会存）
 * - after():  不把LLM回复存入ChatMemory（原版会存）
 *
 * 使用场景:
 * - 路由层(ContextRouter/IntentionRouter)需要读取对话历史理解上下文
 * - 但路由层的LLM输出是JSON（路由决策），不应作为ASSISTANT消息污染ChatMemory
 * - ChatMemory的写入由BankController统一管理（只存用户可见文本）
 *
 * 使用方式:
 *   chatClient.prompt()
 *       .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
 *       .user(userInput)
 *       .call()
 */
@Slf4j
public class ReadOnlyMemoryAdvisor implements BaseChatMemoryAdvisor {

    private final ChatMemory chatMemory;
    private final String defaultConversationId;
    private final int order;

    public ReadOnlyMemoryAdvisor(ChatMemory chatMemory) {
        this(chatMemory, "default", 0);
    }

    public ReadOnlyMemoryAdvisor(ChatMemory chatMemory, String defaultConversationId, int order) {
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.hasText(defaultConversationId, "defaultConversationId cannot be null or empty");
        this.chatMemory = chatMemory;
        this.defaultConversationId = defaultConversationId;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String conversationId = getConversationId(request.context(), defaultConversationId);
        List<Message> history = chatMemory.get(conversationId);
        log.debug("[ReadOnlyMemoryAdvisor] Reading {} history messages for conversationId={}",
                history.size(), conversationId);

        // 合并: 历史消息 + 当前prompt的消息（system + user）
        List<Message> allMessages = new ArrayList<>(history);
        allMessages.addAll(request.prompt().getInstructions());

        return request.mutate()
                .prompt(request.prompt().mutate().messages(allMessages).build())
                .build();
        // 注意: 不调用 chatMemory.add(conversationId, userMessage)
        // BankController在Phase1+Phase2完成后手动添加UserMessage
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // 不自动写回: 路由层的LLM输出是JSON，不应作为ASSISTANT消息污染ChatMemory
        // BankController只将用户可见文本写入ChatMemory
        log.debug("[ReadOnlyMemoryAdvisor] Skipping auto-save to ChatMemory (BankController manages writes)");
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }

    public static Builder builder(ChatMemory chatMemory) {
        return new Builder(chatMemory);
    }

    public static class Builder {
        private final ChatMemory chatMemory;
        private String defaultConversationId = "default";
        private int order = 0;

        public Builder(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
        }

        public Builder defaultConversationId(String conversationId) {
            this.defaultConversationId = conversationId;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public ReadOnlyMemoryAdvisor build() {
            return new ReadOnlyMemoryAdvisor(chatMemory, defaultConversationId, order);
        }
    }
}
