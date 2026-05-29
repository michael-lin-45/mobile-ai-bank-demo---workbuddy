package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis实现的CheckpointSaver — 将Graph的Checkpoint存储到Redis
 *
 * Redis key: checkpoint:{threadId} → List<Checkpoint JSON>
 * 每个threadId下维护一个有序的checkpoint列表(最新在尾部)
 *
 * 与MemorySaver接口完全一致，改 storage.type=redis 即可切换
 */
@Slf4j
public class RedisCheckpointSaver implements BaseCheckpointSaver {

    private static final String KEY_PREFIX = "checkpoint:";
    private static final long DEFAULT_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String threadId = config.threadId().orElse(null);
        if (threadId == null) return List.of();
        String key = KEY_PREFIX + threadId;
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) return List.of();

        List<Checkpoint> checkpoints = new ArrayList<>();
        for (String json : jsonList) {
            try {
                checkpoints.add(deserializeCheckpoint(json));
            } catch (JsonProcessingException e) {
                log.warn("[RedisCheckpoint] Failed to deserialize checkpoint for threadId={}, skipping", threadId, e);
            }
        }
        return checkpoints;
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = config.threadId().orElse(null);
        if (threadId == null) return Optional.empty();
        String key = KEY_PREFIX + threadId;
        String json = redisTemplate.opsForList().index(key, -1); // 最新checkpoint
        if (json == null) return Optional.empty();
        try {
            return Optional.of(deserializeCheckpoint(json));
        } catch (JsonProcessingException e) {
            log.warn("[RedisCheckpoint] Failed to deserialize checkpoint for threadId={}", threadId, e);
            return Optional.empty();
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = config.threadId().orElse(null);
        if (threadId == null) return config;
        String key = KEY_PREFIX + threadId;
        String json = serializeCheckpoint(checkpoint);
        redisTemplate.opsForList().rightPush(key, json);
        redisTemplate.expire(key, DEFAULT_TTL_HOURS, TimeUnit.HOURS);
        return config;
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String threadId = config.threadId().orElse(null);
        if (threadId == null) return new Tag(null, List.of());
        String key = KEY_PREFIX + threadId;

        // 读取所有checkpoint用于返回
        Collection<Checkpoint> checkpoints = list(config);
        redisTemplate.delete(key);

        return new Tag(threadId, checkpoints);
    }

    // ==================== 序列化 ====================

    private String serializeCheckpoint(Checkpoint checkpoint) throws JsonProcessingException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", checkpoint.getId());
        map.put("nodeId", checkpoint.getNodeId());
        map.put("nextNodeId", checkpoint.getNextNodeId());
        map.put("state", checkpoint.getState());
        return objectMapper.writeValueAsString(map);
    }

    private Checkpoint deserializeCheckpoint(String json) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
        return Checkpoint.builder()
                .id((String) map.get("id"))
                .nodeId((String) map.get("nodeId"))
                .nextNodeId((String) map.get("nextNodeId"))
                .state((Map<String, Object>) map.get("state"))
                .build();
    }
}
