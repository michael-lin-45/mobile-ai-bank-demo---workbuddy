package com.mobileagent.app.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * SessionStateStore配置 — 根据 storage.type 切换 InMemory / Redis
 *
 * 默认 in-memory (单实例开发)，改 storage.type=redis 重启即可切换到Redis (多实例/持久化)
 *
 * 用法: 注入 SessionStateStoreFactory，按需创建不同值类型的store:
 *   @Autowired SessionStateStoreFactory factory;
 *   SessionStateStore<ActiveAgentInfo> store = factory.create("active-agents", new TypeReference<>() {});
 */
@Slf4j
@Configuration
public class SessionStateStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public SessionStateStoreFactory inMemorySessionStateStoreFactory() {
        log.info("[SessionStateStore] Using InMemory implementation");
        return new SessionStateStoreFactory() {
            @Override
            public <V> SessionStateStore<V> create(String namespace, TypeReference<V> typeReference) {
                return new InMemorySessionStateStore<>();
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public SessionStateStoreFactory redisSessionStateStoreFactory(RedisConnectionFactory connectionFactory,
                                                                     ObjectMapper objectMapper) {
        log.info("[SessionStateStore] Using Redis implementation");
        StringRedisTemplate redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new SessionStateStoreFactory() {
            private final StringRedisTemplate template = redisTemplate;
            private final ObjectMapper mapper = objectMapper;

            @Override
            public <V> SessionStateStore<V> create(String namespace, TypeReference<V> typeReference) {
                return new RedisSessionStateStore<>(template, mapper, typeReference);
            }
        };
    }

    /**
     * SessionStateStore工厂接口 — 按需创建不同值类型的store
     *
     * 不同业务状态有不同的值类型(ActiveAgentInfo, SuspendedInfo等)，
     * 通过工厂+TypeReference支持泛型反序列化。
     * 不能用@FunctionalInterface + lambda, 因为方法是泛型的。
     */
    public interface SessionStateStoreFactory {
        <V> SessionStateStore<V> create(String namespace, TypeReference<V> typeReference);
    }
}
