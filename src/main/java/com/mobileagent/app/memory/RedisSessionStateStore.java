package com.mobileagent.app.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis实现的SessionStateStore — 将业务状态存储到Redis
 *
 * Redis key: session-state:{namespace}:{key} → value JSON
 * 整个namespace下的key通过 Redis KEYS session-state:{namespace}:* 查询
 *
 * 与InMemory实现完全等价，改 storage.type=redis 重启即可切换
 */
@Slf4j
public class RedisSessionStateStore<V> implements SessionStateStore<V> {

    private static final String KEY_PREFIX = "session-state:";
    private static final long DEFAULT_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TypeReference<V> typeReference;

    /**
     * @param redisTemplate Redis操作模板
     * @param objectMapper JSON序列化
     * @param typeReference 值类型引用(用于反序列化), 如 new TypeReference<ActiveAgentInfo>() {}
     */
    public RedisSessionStateStore(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   TypeReference<V> typeReference) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.typeReference = typeReference;
    }

    private String redisKey(String namespace, String key) {
        return KEY_PREFIX + namespace + ":" + key;
    }

    @Override
    public Optional<V> get(String namespace, String key) {
        String rk = redisKey(namespace, key);
        String json = redisTemplate.opsForValue().get(rk);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, typeReference));
        } catch (JsonProcessingException e) {
            log.warn("[RedisSessionState] Failed to deserialize: namespace={}, key={}", namespace, key, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String namespace, String key, V value) {
        String rk = redisKey(namespace, key);
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(rk, json, DEFAULT_TTL_HOURS, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            log.warn("[RedisSessionState] Failed to serialize: namespace={}, key={}", namespace, key, e);
        }
    }

    @Override
    public void remove(String namespace, String key) {
        redisTemplate.delete(redisKey(namespace, key));
    }

    @Override
    public boolean containsKey(String namespace, String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey(namespace, key)));
    }

    @Override
    public Map<String, V> getAll(String namespace) {
        String pattern = KEY_PREFIX + namespace + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) return Collections.emptyMap();

        Map<String, V> result = new HashMap<>();
        String prefix = KEY_PREFIX + namespace + ":";
        for (String rk : keys) {
            String key = rk.substring(prefix.length());
            String json = redisTemplate.opsForValue().get(rk);
            if (json != null) {
                try {
                    result.put(key, objectMapper.readValue(json, typeReference));
                } catch (JsonProcessingException e) {
                    log.warn("[RedisSessionState] Failed to deserialize in getAll: namespace={}, key={}", namespace, key, e);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void clear(String namespace) {
        String pattern = KEY_PREFIX + namespace + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
