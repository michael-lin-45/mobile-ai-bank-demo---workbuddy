package com.mobileagent.app.data;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子图配置属性 - 从 application.yml 的 routing 段读取
 *
 * 包含:
 * - intents: 子图配置(名称/描述/scope/intentType)
 * - intent-groups: 子图消歧组(共享关键词的子图集合)
 * - domains: 领域关键词(供L0判断belongs_to_domain)
 *
 * 新增领域时只需修改 yml,不动 Java 代码
 */
@Data
@Component
@ConfigurationProperties(prefix = "routing")
public class SubGraphProperties {

    /** 子图配置列表 */
    private List<SubGraphConfigProps> intents = new ArrayList<>();

    /** 子图消歧组 */
    private List<SubGraphGroupProps> intentGroups = new ArrayList<>();

    /** 领域关键词映射: domain名 → 关键词列表 */
    private Map<String, DomainProps> domains = new LinkedHashMap<>();

    @Data
    public static class SubGraphConfigProps {
        private String name;
        /** 所属领域 — 用于编排层自动推导 domainAgent 的默认 L2GraphTool */
        private String domain;
        /** 对应的 L2 CompiledGraph Bean 名 — 用于编排层自动绑定默认工具 */
        private String graphBean;
        private String description;
        private String paramSchema;
        private boolean isWriteOp;
        /** 意图类型: OPERATION / QUERY / CONSULTATION */
        private String intentType;
        /** 本意图的处理范围描述，供SubGraphRouter判断belongs_to_domain */
        private String scope;
    }

    @Data
    public static class SubGraphGroupProps {
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
