package com.mobileagent.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内存存储配置 — 通过 memory.type 控制所有状态存储的底层实现
 *
 * 改 memory.type 后重启即可切换:
 * - in-memory: 所有状态存储使用内存 (ConcurrentHashMap / MemorySaver)
 * - redis:     所有状态存储使用Redis (多实例部署 / 持久化)
 */
@Data
@ConfigurationProperties(prefix = "storage")
public class MemoryProperties {

    /** 存储类型: in-memory | redis */
    private String type = "in-memory";

    /** Redis配置 (type=redis时生效) */
    private Redis redis = new Redis();

    @Data
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;
    }
}
