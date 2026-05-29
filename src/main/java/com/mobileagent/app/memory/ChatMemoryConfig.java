package com.mobileagent.app.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ChatMemory存储配置 — 根据 storage.type 切换 InMemory / Redis
 *
 * 默认 in-memory (单实例开发)，改 storage.type=redis 重启即可切换到Redis (多实例/持久化)
 */
@Slf4j
@Configuration
public class ChatMemoryConfig {

    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public ChatMemoryRepository inMemoryChatMemoryRepository() {
        log.info("[ChatMemory] Using InMemory implementation");
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public ChatMemoryRepository redisChatMemoryRepository(RedisConnectionFactory connectionFactory,
                                                          ObjectMapper objectMapper) {
        log.info("[ChatMemory] Using Redis implementation");
        StringRedisTemplate redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new RedisChatMemoryRepository(redisTemplate, objectMapper);
    }
}
