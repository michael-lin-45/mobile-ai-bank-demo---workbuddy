package com.mobileagent.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由配置属性 - 从 application.yml 的 routing 段读取
 *
 * 包含:
 * - intents: 意图注册表(替代 IntentRegistry 中的硬编码)
 * - intent-groups: 意图消歧组
 * - domains: 领域关键词(替代 DomainRouter 中的硬编码常量)
 *
 * 新增领域时只需修改 yml,不动 Java 代码
 */
@Data
@Component
@ConfigurationProperties(prefix = "routing")
public class RoutingProperties {

    /** 意图注册表 */
    private List<IntentConfigProps> intents = new ArrayList<>();

    /** 意图消歧组 */
    private List<IntentGroupProps> intentGroups = new ArrayList<>();

    /** 领域关键词映射: domain名 → 关键词列表 */
    private Map<String, DomainProps> domains = new LinkedHashMap<>();

    @Data
    public static class IntentConfigProps {
        private String name;
        private String description;
        private String paramSchema;
        private boolean isWriteOp;
        /** 意图类型: OPERATION / QUERY / CONSULTATION */
        private String intentType;
        /** 本意图的处理范围描述，供IntentRouter判断belongs_to_domain */
        private String scope;
    }

    @Data
    public static class IntentGroupProps {
        private String groupId;
        private String displayName;
        private List<String> intentNames = new ArrayList<>();
        private String disambiguationQuestion;
    }

    @Data
    public static class DomainProps {
        /** 领域关键词列表 */
        private List<String> keywords = new ArrayList<>();
    }
}
