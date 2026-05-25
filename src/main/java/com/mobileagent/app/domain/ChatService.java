package com.mobileagent.app.domain;

import com.mobileagent.app.data.WorkflowOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 闲聊L1 Service - 直接使用大模型与用户聊天
 *
 * 设计:
 * - 无ContextRouter, 无IntentRouter, 无RoutingService
 * - 无activeThread, 无suspendedAgents
 * - 直接用ChatClient与用户对话
 * - 使用全局ChatMemory(L0注入的历史),不创建独立ChatMemory
 * - 不需要记录到领域ChatMemory(闲聊没有独立的领域ChatMemory)
 * - 实现 DomainHandler 接口, 可被 DomainServiceRegistry 统一分发
 * - 增强 system prompt 支持银行知识FAQ, 接住 REROUTE 过来的问题
 */
@Slf4j
@Service
public class ChatService implements DomainHandler {

    private final ChatClient chatChatClient;

    public ChatService(@Qualifier("chatChatClient") ChatClient chatChatClient) {
        this.chatChatClient = chatChatClient;
    }

    /**
     * 处理闲聊消息
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param globalChatHistory 全局跨域对话历史(可能为null)
     * @return 聊天回复
     */
    @Override
    public WorkflowOutput handle(String sessionId, String userInput, String globalChatHistory) {
        log.info("[ChatService] Handling: sessionId={}, input={}", sessionId, userInput);

        try {
            long startMs = System.currentTimeMillis();

            var promptBuilder = chatChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId));

            // 注入全局跨域历史作为额外上下文
            if (globalChatHistory != null && !globalChatHistory.isBlank()) {
                promptBuilder = promptBuilder.user(
                        "===跨域对话历史(参考)===\n" + globalChatHistory + "\n===历史结束===\n\n用户当前消息: " + userInput);
            } else {
                promptBuilder = promptBuilder.user(userInput);
            }

            String content = promptBuilder.call().content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[ChatService] LLM call completed in {}ms", elapsedMs);

            return WorkflowOutput.completed("CHAT", content);

        } catch (Exception e) {
            log.error("[ChatService] Error handling chat", e);
            return WorkflowOutput.error("聊天服务暂时不可用: " + e.getMessage());
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
