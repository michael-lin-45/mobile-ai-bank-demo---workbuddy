package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.memory.impl.InMemoryGlobalSessionStorage;
import com.mobileagent.app.memory.impl.RedisGlobalSessionStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * GlobalSessionStorage 配置 — 根据 storage.type 切换 InMemory / Redis
 *
 * 默认 in-memory (单实例开发), 改 storage.type=redis 重启即可切换
 */
@Slf4j
@Configuration
public class GlobalSessionStorageConfig {

    @Bean("inMemoryGlobalSessionStorage")
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public GlobalSessionStorage inMemoryStorage() {
        log.info("[GlobalSessionStorage] Using InMemory implementation");
        return new InMemoryGlobalSessionStorage();
    }

    @Bean("redisGlobalSessionStorage")
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public GlobalSessionStorage redisStorage(RedisConnectionFactory connectionFactory,
                                              ObjectMapper objectMapper,
                                              @Qualifier("globalKeyStrategyFactory") KeyStrategyFactory keyStrategyFactory) {
        log.info("[GlobalSessionStorage] Using Redis implementation");
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate = new org.springframework.data.redis.core.StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new RedisGlobalSessionStorage(redisTemplate, objectMapper, keyStrategyFactory);
    }
}
