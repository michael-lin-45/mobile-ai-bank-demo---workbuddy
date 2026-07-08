package com.observability.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置服务 — 从 application.yml 读取关键配置项
 */
@Slf4j
@Service
public class SettingsService {

    private final Environment env;

    @Value("${spring.datasource.url:jdbc:h2:file:./data/observability}")
    private String datasourceUrl;

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${server.port:9090}")
    private int serverPort;

    public SettingsService(Environment env) {
        this.env = env;
    }

    /**
     * 获取采集配置
     */
    public Map<String, Object> getCollectionConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("collectorStatus", "running");
        config.put("collectorEndpoint", "http://localhost:4318");
        config.put("samplingRate", 100);
        config.put("tagStrategy", "full");
        config.put("promptStorageEnabled", true);
        config.put("retentionDays", 30);
        config.put("otlpReceiverPort", serverPort);
        return config;
    }

    /**
     * 获取存储配置
     */
    public Map<String, Object> getStorageConfig() {
        Map<String, Object> config = new LinkedHashMap<>();

        // 数据库类型解析
        String dbType = "H2";
        if (datasourceUrl.contains("postgresql")) dbType = "PostgreSQL";
        else if (datasourceUrl.contains("mysql")) dbType = "MySQL";
        else if (datasourceUrl.contains("h2")) dbType = "H2";

        config.put("databaseType", dbType);
        config.put("redisHost", redisHost + ":" + redisPort);
        config.put("redisStatus", "connected"); // P0 简化
        config.put("metricsRetentionHours", 24);
        config.put("traceRetentionDays", 7);
        config.put("logRetentionDays", 30);
        config.put("datasourceUrl", datasourceUrl);

        return config;
    }
}
