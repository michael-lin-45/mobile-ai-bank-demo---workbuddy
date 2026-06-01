package com.mobileagent.app.execution;

import com.mobileagent.app.data.ChunkType;
import com.mobileagent.app.data.StreamChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 流式ChatMemory写入器 — L0和L1共用同一套addMessage逻辑
 *
 * 职责拆分:
 * - writeUserMessage(): 静态方法，在L0管道入口/L1 handle入口调用
 * - AssistantWriter: 内部类，累积CHUNK → 终结chunk时写AssistantMessage
 *
 * 并发安全: AssistantWriter是per-request局部变量，StringBuilder是局部变量
 * ChatMemory.add(sessionId, msg) 内部按sessionId隔离，不同请求完全独立
 *
 * L0/L1写不同的ChatMemory bean，不存在重复写入问题:
 * - L0传入全局chatMemory
 * - L1传入领域chatMemory（wealthChatMemory/transferChatMemory/billChatMemory）
 */
@Slf4j
public class StreamingChatMemoryWriter {

    /**
     * 写入UserMessage — 静态方法，在handle入口处调用
     *
     * L0: buildChatPipeline入口调用，传入全局chatMemory
     * L1: handle入口调用addUserMessage()保持现有调用点，传入领域chatMemory
     */
    public static void writeUserMessage(ChatMemory chatMemory, String sessionId, String userInput) {
        chatMemory.add(sessionId, new UserMessage(userInput));
    }

    /**
     * 流式AssistantMessage写入器 — 每次executeNewAgent/resumeActiveAgent创建独立实例
     *
     * 职责: 累积CHUNK → 终结chunk时拼接完整文本 → chatMemory.add(AssistantMessage)
     * REROUTE chunk不写AssistantMessage（对用户不可见）
     *
     * 时序示例（流式Graph）:
     *   CHUNK "A" → 累积器: "A"        → 不写ChatMemory
     *   CHUNK "B" → 累积器: "AB"       → 不写ChatMemory
     *   CHUNK "C" → 累积器: "ABC"      → 不写ChatMemory
     *   COMPLETE  → chatMemory.add("ABC") ← 一次写入完整文本
     *
     * 时序示例（非流式Graph）:
     *   COMPLETE {content:"转账成功"} → 累积器空 → chatMemory.add("转账成功")
     */
    public static class AssistantWriter {
        private final ChatMemory chatMemory;
        private final String sessionId;
        private final StringBuilder contentAccumulator = new StringBuilder();

        public AssistantWriter(ChatMemory chatMemory, String sessionId) {
            this.chatMemory = chatMemory;
            this.sessionId = sessionId;
        }

        /** 处理每个chunk — 在doOnNext中调用 */
        public void onChunk(StreamChunk chunk) {
            if (chunk.getType() == ChunkType.CHUNK) {
                contentAccumulator.append(chunk.getContent());
            }
            if (chunk.isTerminal() && chunk.getType() != ChunkType.REROUTE) {
                writeAssistantMessage(chunk);
            }
        }

        private void writeAssistantMessage(StreamChunk terminalChunk) {
            String fullReply;
            if (contentAccumulator.length() > 0) {
                // 流式Graph: 拼接所有CHUNK文本
                fullReply = contentAccumulator.toString();
            } else {
                // 非流式Graph: 从终结chunk取完整内容
                fullReply = terminalChunk.getReplyContent();
            }
            if (fullReply != null && !fullReply.isEmpty()) {
                chatMemory.add(sessionId, new AssistantMessage(fullReply));
                log.debug("[AssistantWriter] addMessage: session={}, len={}, source={}",
                        sessionId, fullReply.length(),
                        contentAccumulator.length() > 0 ? "accumulated" : "terminal");
            }
        }
    }
}
