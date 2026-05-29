package com.mobileagent.app.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis实现的ChatMemoryRepository — 将对话历史按conversationId存储到Redis
 *
 * 序列化格式: 每条Message存为JSON，包含 @type 字段区分消息类型
 * Redis key: chat-memory:{conversationId} → List<Message JSON>
 *
 * 与InMemoryChatMemoryRepository接口完全一致，改 storage.type=redis 即可切换
 */
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat-memory:";
    private static final long DEFAULT_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return List.of();
        return keys.stream()
                .map(key -> key.substring(KEY_PREFIX.length()))
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) return List.of();

        List<Message> messages = new ArrayList<>();
        for (String json : jsonList) {
            try {
                messages.add(deserializeMessage(json));
            } catch (JsonProcessingException e) {
                log.warn("[RedisChatMemory] Failed to deserialize message for conversation={}, skipping", conversationId, e);
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;
        redisTemplate.delete(key);
        if (messages == null || messages.isEmpty()) return;

        for (Message message : messages) {
            try {
                String json = serializeMessage(message);
                redisTemplate.opsForList().rightPush(key, json);
            } catch (JsonProcessingException e) {
                log.warn("[RedisChatMemory] Failed to serialize message for conversation={}, skipping", conversationId, e);
            }
        }
        redisTemplate.expire(key, DEFAULT_TTL_HOURS, TimeUnit.HOURS);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }

    // ==================== 序列化 ====================

    private String serializeMessage(Message message) throws JsonProcessingException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("@type", message.getMessageType().name());
        map.put("text", message.getText());
        if (message.getMetadata() != null && !message.getMetadata().isEmpty()) {
            map.put("metadata", message.getMetadata());
        }
        return objectMapper.writeValueAsString(map);
    }

    private Message deserializeMessage(String json) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
        String type = (String) map.get("@type");
        String text = (String) map.get("text");

        return switch (type) {
            case "USER" -> new UserMessage(text);
            case "ASSISTANT" -> new AssistantMessage(text);
            case "SYSTEM" -> new SystemMessage(text);
            default -> throw new IllegalArgumentException("Unknown message type: " + type);
        };
    }
}
