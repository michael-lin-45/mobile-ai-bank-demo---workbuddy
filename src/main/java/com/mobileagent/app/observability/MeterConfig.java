package com.mobileagent.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer MeterRegistry → OTel 桥接配置
 *
 * 绑定 Micrometer MeterRegistry 的通用标签和导出配置。
 * OTLP 导出由 management.otlp.metrics.export 配置驱动。
 */
@Slf4j
@Configuration
public class MeterConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> {
            registry.config()
                    .commonTags("application", "mobile-ai-demo")
                    .commonTags("service", "bank-ai-agent");
            log.info("[MeterConfig] Configured common tags: application=mobile-ai-demo, service=bank-ai-agent");
        };
    }
}
