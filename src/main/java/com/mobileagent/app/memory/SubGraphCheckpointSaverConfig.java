package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.memory.impl.RedisSubGraphCheckpointSaver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * L2子图CheckpointSaver存储配置 — 根据 storage.type 切换 InMemory / Redis
 *
 * 注意: 每个 L2 子图需要独立的 CheckpointSaver 实例，
 * 此处只提供 prototype 作用的工厂Bean，由调用方每次获取新实例。
 *
 * 默认 in-memory (单实例开发)，改 storage.type=redis 重启即可切换到Redis (多实例/持久化)
 */
@Slf4j
@Configuration
public class SubGraphCheckpointSaverConfig {

    /**
     * InMemory SubGraphCheckpointSaver工厂 — 每次调用返回新的 MemorySaver 实例
     *
     * 不同的图必须使用独立的 MemorySaver，否则 human-in-the-loop 的保存点会互相覆盖
     */
    @Bean("inMemorySubGraphCheckpointSaverFactory")
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public SubGraphCheckpointSaverFactory inMemorySubGraphCheckpointSaverFactory() {
        log.info("[SubGraphCheckpointSaver] Using InMemory implementation (MemorySaver)");
        return MemorySaver::new;
    }

    /**
     * Redis SubGraphCheckpointSaver工厂 — 每次调用返回新的 RedisSubGraphCheckpointSaver 实例
     *
     * 不同的图使用独立的 RedisSubGraphCheckpointSaver，通过不同的 threadId 隔离数据
     */
    @Bean("redisSubGraphCheckpointSaverFactory")
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public SubGraphCheckpointSaverFactory redisSubGraphCheckpointSaverFactory(RedisConnectionFactory connectionFactory,
                                                                              ObjectMapper objectMapper) {
        log.info("[SubGraphCheckpointSaver] Using Redis implementation (RedisSubGraphCheckpointSaver)");
        StringRedisTemplate redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return () -> new RedisSubGraphCheckpointSaver(redisTemplate, objectMapper);
    }

    /**
     * SubGraphCheckpointSaver工厂接口 — 每次调用 create() 返回独立的 BaseCheckpointSaver 实例
     *
     * 不同 L2 子图需要独立的 saver 实例（不同的 threadId 空间），
     * 通过工厂模式避免单例共享导致 checkpoint 覆盖。
     */
    @FunctionalInterface
    public interface SubGraphCheckpointSaverFactory {
        BaseCheckpointSaver create();
    }
}
