package com.mobileagent.app.service;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ChatHistory格式化工具 - 统一L0/L1各节点的历史格式化+截断逻辑
 *
 * 4个服务共用:
 * - DomainRouter (L0) - 领域路由
 * - ContextRouter (L1) - 上下文路由
 * - IntentionRouter (L1) - 意图识别+改写
 * - ContextRewriter (L1) - 上下文改写
 *
 * 截断策略:
 * - 从ChatMemory读取全部历史消息
 * - 只保留最后judgmentMaxPairs对(2*judgmentMaxPairs条消息)
 * - 避免传给LLM的历史过长，减少token消耗和噪声
 */
@Slf4j
public class ChatHistoryUtils {

    /**
     * 读取ChatMemory并格式化为文本历史，截断到指定对数
     *
     * @param chatMemory ChatMemory实例(可为null)
     * @param sessionId 会话ID
     * @param judgmentMaxPairs 传给LLM的最大对话对数(1对=1条用户+1条助手)
     * @return 格式化的历史文本，如 "用户: xxx\n助手: yyy"
     */
    public static String formatAndTruncate(ChatMemory chatMemory, String sessionId, int judgmentMaxPairs) {
        if (chatMemory == null) {
            return "(无历史对话)";
        }
        try {
            List<Message> messages = chatMemory.get(sessionId);
            if (messages == null || messages.isEmpty()) {
                return "(无历史对话)";
            }

            // 截断: 只保留最后judgmentMaxPairs对 = 2*judgmentMaxPairs条消息
            int maxMessages = judgmentMaxPairs * 2;
            int start = Math.max(0, messages.size() - maxMessages);
            List<Message> truncated = messages.subList(start, messages.size());

            if (start > 0) {
                log.debug("[ChatHistoryUtils] Truncated history: total={}, showing last {} messages ({} pairs)",
                        messages.size(), truncated.size(), judgmentMaxPairs);
            }

            StringBuilder sb = new StringBuilder();
            for (Message msg : truncated) {
                String role = switch (msg.getMessageType()) {
                    case USER -> "用户";
                    case ASSISTANT -> "助手";
                    case SYSTEM -> "系统";
                    default -> msg.getMessageType().name();
                };
                sb.append(role).append(": ").append(msg.getText()).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[ChatHistoryUtils] Failed to read chat history for sessionId={}", sessionId, e);
            return "(无法读取历史对话)";
        }
    }
}
