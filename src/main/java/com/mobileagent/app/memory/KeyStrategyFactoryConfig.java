package com.mobileagent.app.memory;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KeyStrategyFactory 配置 — 将 factory 作为独立 Bean
 *
 * GlobalSessionStateStore 和 RedisGlobalSessionStorage 都可以注入此 Bean。
 * DomainStateAware 实现来自 DomainServiceConfig 中的轻量注册 Bean (零依赖), 无循环依赖。
 */
@Slf4j
@Configuration
public class KeyStrategyFactoryConfig {

    @Bean
    public KeyStrategyFactory globalKeyStrategyFactory(List<DomainStateAware> domainStateProviders) {
        log.info("[KeyStrategyFactory] Created with {} domain providers", domainStateProviders.size());
        return createKeyStrategyFactory(domainStateProviders);
    }

    private KeyStrategyFactory createKeyStrategyFactory(List<DomainStateAware> providers) {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();

            // 公共 keys
            strategies.put("messages", new AppendStrategy());
            strategies.put("_lastDomain", new ReplaceStrategy());
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("_question", new ReplaceStrategy());
            strategies.put("_paramName", new ReplaceStrategy());
            strategies.put("_outputContent", new ReplaceStrategy());
            strategies.put("_outputType", new ReplaceStrategy());
            strategies.put("_isFinal", new ReplaceStrategy());
            strategies.put("_cancelSignal", new ReplaceStrategy());
            strategies.put("_globalStateData", new ReplaceStrategy());

            // 动态注册: L1 域的 DomainState key
            for (DomainStateAware provider : providers) {
                strategies.put(provider.getStateKey(), provider.getStateStrategy());
                log.info("[KeyStrategyFactory] Registered domain state key: {} ({})",
                        provider.getStateKey(), provider.getClass().getSimpleName());
            }

            return strategies;
        };
    }
}
