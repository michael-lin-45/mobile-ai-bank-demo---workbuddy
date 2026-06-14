package com.mobileagent.app.memory.impl;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 实现的 GlobalSessionRepository — 将 state.data() 序列化到 Redis
 */
@Slf4j
public class RedisGlobalSessionRepository implements GlobalSessionRepository {

    private static final String KEY_PREFIX = "global-session:";
    private static final long DEFAULT_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KeyStrategyFactory keyStrategyFactory;
    private final int maxStoredPairs;

    public RedisGlobalSessionRepository(StringRedisTemplate redisTemplate,
                                         ObjectMapper objectMapper,
                                         KeyStrategyFactory keyStrategyFactory,
                                         int maxStoredPairs) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyStrategyFactory = keyStrategyFactory;
        this.maxStoredPairs = maxStoredPairs;
    }

    @Override
    public GlobalSessionContext get(String sessionId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        if (json == null) return null;
        try {
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            OverAllState state = new OverAllState();
            state.registerKeyAndStrategy(keyStrategyFactory.apply());
            state.updateState(data);
            return new GlobalSessionContext(sessionId, state, maxStoredPairs);
        } catch (Exception e) {
            log.warn("[RedisGlobalSessionRepository] Failed to deserialize: sessionId={}", sessionId, e);
            return null;
        }
    }

    @Override
    public void put(String sessionId, GlobalSessionContext ctx) {
        try {
            String json = objectMapper.writeValueAsString(ctx.data());
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, json, DEFAULT_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[RedisGlobalSessionRepository] Failed to serialize: sessionId={}", sessionId, e);
        }
    }

    @Override
    public void remove(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}
