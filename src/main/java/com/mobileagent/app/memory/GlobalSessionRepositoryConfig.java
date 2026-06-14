package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.memory.impl.InMemoryGlobalSessionRepository;
import com.mobileagent.app.memory.impl.RedisGlobalSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * GlobalSessionRepository 配置 — 根据 storage.type 切换 InMemory / Redis
 *
 * 默认 in-memory (单实例开发), 改 storage.type=redis 重启即可切换
 */
@Slf4j
@Configuration
public class GlobalSessionRepositoryConfig {

    @Bean("inMemoryGlobalSessionRepository")
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public GlobalSessionRepository inMemoryRepository() {
        log.info("[GlobalSessionRepository] Using InMemory implementation");
        return new InMemoryGlobalSessionRepository();
    }

    @Bean("redisGlobalSessionRepository")
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public GlobalSessionRepository redisRepository(RedisConnectionFactory connectionFactory,
                                                    ObjectMapper objectMapper,
                                                    @Qualifier("globalKeyStrategyFactory") KeyStrategyFactory keyStrategyFactory,
                                                    @Value("${routing.history.max-stored-pairs:20}") int maxStoredPairs) {
        log.info("[GlobalSessionRepository] Using Redis implementation, maxStoredPairs={}", maxStoredPairs);
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate = new org.springframework.data.redis.core.StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new RedisGlobalSessionRepository(redisTemplate, objectMapper, keyStrategyFactory, maxStoredPairs);
    }
}
