package com.mobileagent.app.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.memory.impl.RedisStoreAdapter;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * GlobalMemoryStore 配置 — 根据 memory.store.type 切换 InMemory / Redis
 *
 * 与 GlobalSessionRepositoryConfig 独立:
 * - storage.type 控制 GlobalSessionRepository (会话寄存器, sessionId 维度)
 * - memory.store.type 控制 GlobalMemoryStore (长期记忆, userId/namespace 维度)
 * - 两者可独立选择不同后端, 例如: 会话寄存器用 InMemory, 长期记忆用 Redis
 *
 * 默认 in-memory (单实例开发), 改 memory.store.type=redis 重启即可切换
 */
@Slf4j
@Configuration
public class GlobalMemoryStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "memory.store.type", havingValue = "in-memory", matchIfMissing = true)
    public Store inMemoryStore() {
        log.info("[GlobalMemoryStore] Using InMemory implementation");
        return new MemoryStore();
    }

    @Bean
    @ConditionalOnProperty(name = "memory.store.type", havingValue = "redis")
    public Store redisStore(RedisConnectionFactory connectionFactory,
                            ObjectMapper objectMapper) {
        log.info("[GlobalMemoryStore] Using Redis implementation");
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                new org.springframework.data.redis.core.StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new RedisStoreAdapter(redisTemplate, objectMapper);
    }
}
