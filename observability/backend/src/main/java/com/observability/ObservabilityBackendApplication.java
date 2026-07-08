package com.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 可观测平台 — Spring Boot 后端主类
 *
 * 端口: 9090
 * 存储: Redis (热层, 127.0.0.1:6379) + H2 (温层, file:./data/observability)
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.observability.repository")
@EnableScheduling
public class ObservabilityBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObservabilityBackendApplication.class, args);
    }
}
