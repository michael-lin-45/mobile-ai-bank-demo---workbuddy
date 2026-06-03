package com.mobileagent.app.router.registry;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.data.SubGraphProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 子图注册中心 - 管理所有L2子Graph的意图元数据与Graph Bean绑定
 *
 * 核心职责:
 * - 从 application.yml 加载意图配置 (名称/描述/scope/intentType)
 * - 为每个意图绑定 CompiledGraph Bean (由各 GraphConfig 自注册)
 * - 支持意图组(IntentGroup): 共享前缀关键词的意图集合,用于消歧
 * - 提供模糊匹配与关键词推断
 *
 * 注册流程:
 * 1. @PostConstruct: 从 routing.intents / routing.intent-groups 读取元数据
 * 2. 各 GraphConfig 的 @Bean 方法中调用 bindGraph() 完成Graph绑定 (自注册模式)
 */
@Slf4j
@Component
public class SubGraphRegistry {

    private final SubGraphProperties routingProperties;
    private final Map<String, IntentConfig> registry = new LinkedHashMap<>();
    private final Map<String, IntentGroup> groups = new LinkedHashMap<>();

    public SubGraphRegistry(SubGraphProperties routingProperties) {
        this.routingProperties = routingProperties;
    }

    @Data
    public static class IntentConfig {
        private final String name;
        private final String description;
        private final String paramSchema;
        private final boolean writeOp;
        /** 意图类型: OPERATION / QUERY / CONSULTATION */
        private final String intentType;
        /** 本意图的处理范围描述，供SubGraphRouter判断belongs_to_domain */
        private final String scope;
        private CompiledGraph graph;
        /** 是否为流式Graph — 由GES读取，L0/L1不关心 */
        private boolean streamable;

        public IntentConfig(String name, String description, String paramSchema, boolean writeOp,
                            String intentType, String scope) {
            this.name = name;
            this.description = description;
            this.paramSchema = paramSchema;
            this.writeOp = writeOp;
            this.intentType = intentType;
            this.scope = scope;
        }
    }

    /**
     * 意图组 - 共享同一前缀关键词的意图集合
     * 当用户输入只匹配到组级别但无法区分具体意图时,触发消歧追问
     */
    @Data
    public static class IntentGroup {
        private final String groupId;
        private final String displayName;
        private final List<String> intentNames;
        private final String disambiguationQuestion;
    }

    @PostConstruct
    public void init() {
        // 从 application.yml 读取意图配置并注册(不含graph,graph由各GraphConfig自注册)
        for (SubGraphProperties.SubGraphConfigProps props : routingProperties.getIntents()) {
            register(props.getName(), props.getDescription(), props.getParamSchema(),
                    props.isWriteOp(), props.getIntentType(), props.getScope());
        }

        // 从 application.yml 读取意图组配置并注册
        for (SubGraphProperties.SubGraphGroupProps groupProps : routingProperties.getIntentGroups()) {
            registerGroup(groupProps.getGroupId(), groupProps.getDisplayName(),
                    groupProps.getIntentNames(), groupProps.getDisambiguationQuestion());
        }

        log.info("[SubGraphRegistry] Initialized with {} intents: {}", registry.size(), registry.keySet());
        log.info("[SubGraphRegistry] IntentGroups: {}", groups.keySet());
    }

    public void register(String name, String description, String paramSchema, boolean writeOp,
                          String intentType, String scope) {
        registry.put(name, new IntentConfig(name, description, paramSchema, writeOp, intentType, scope));
    }

    /** 绑定Graph到意图 — 由各GraphConfig在@Bean方法中自注册调用 */
    public void bindGraph(String intentName, CompiledGraph graph, boolean streamable) {
        IntentConfig config = registry.get(intentName);
        if (config != null) {
            config.setGraph(graph);
            config.setStreamable(streamable);
            log.info("[SubGraphRegistry] Bound graph to intent: {} (streamable={})", intentName, streamable);
        } else {
            log.warn("[SubGraphRegistry] Attempted to bind graph to unknown intent: {}", intentName);
        }
    }

    /** 绑定Graph — 默认非流式 */
    public void bindGraph(String intentName, CompiledGraph graph) {
        bindGraph(intentName, graph, false);
    }

    /** GES读取: 意图是否为流式Graph */
    public boolean isStreamable(String intentName) {
        IntentConfig config = registry.get(intentName);
        return config != null && config.isStreamable();
    }

    public CompiledGraph getGraph(String intentName) {
        IntentConfig config = registry.get(intentName);
        return config != null ? config.getGraph() : null;
    }

    public IntentConfig getConfig(String intentName) {
        return registry.get(intentName);
    }

    public Set<String> getIntentNames() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public boolean hasIntent(String name) {
        return registry.containsKey(name);
    }

    // --- 意图组管理 ---

    public void registerGroup(String groupId, String displayName,
                              List<String> intentNames, String disambiguationQuestion) {
        groups.put(groupId, new IntentGroup(groupId, displayName, intentNames, disambiguationQuestion));
        log.debug("[SubGraphRegistry] Registered intent group: {} -> {}", groupId, intentNames);
    }

    /** 查找意图所属的组(如果该意图属于某个歧义组) */
    public IntentGroup findGroupByIntent(String intentName) {
        if (intentName == null) return null;
        for (IntentGroup group : groups.values()) {
            if (group.getIntentNames().contains(intentName)) {
                return group;
            }
        }
        return null;
    }

    /** 通过组ID查找意图组 */
    public IntentGroup getGroup(String groupId) {
        return groups.get(groupId);
    }

    /** 检查意图名是否是一个组ID(而非具体意图) */
    public boolean isGroupName(String name) {
        return groups.containsKey(name);
    }

    // --- 意图模糊匹配 ---

    /**
     * 生成本领域意图的范围描述(含intentType+scope)，供SubGraphRouter做belongs_to_domain判断
     *
     * 格式: - TRANSFER [OPERATION]: 资金转账操作，将钱转给他人或理财产品等
     */
    public String getDomainIntentScopeDescription(List<String> domainIntentNames) {
        StringBuilder sb = new StringBuilder();
        for (String intentName : domainIntentNames) {
            IntentConfig config = registry.get(intentName);
            if (config != null) {
                sb.append("- ").append(config.getName());
                if (config.getIntentType() != null) {
                    sb.append(" [").append(config.getIntentType()).append("]");
                }
                if (config.getScope() != null) {
                    sb.append(": ").append(config.getScope());
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 模糊匹配意图 - 当LLM返回的intent不在注册表中时尝试匹配
     * 优先匹配已注册意图名,其次检查组名,最后基于关键词推断
     *
     * @param intent LLM返回的意图名(可能不准确)
     * @param userInput 用户原始输入(用于关键词推断)
     * @return 匹配到的意图名(可能是具体意图、组名、或null)
     */
    public String fuzzyMatchIntent(String intent, String userInput) {
        if (intent == null) return guessIntentFromInput(userInput);
        String upper = intent.toUpperCase().replace("-", "_").replace(" ", "_");
        for (String registeredIntent : registry.keySet()) {
            if (upper.contains(registeredIntent) || registeredIntent.contains(upper)) {
                return registeredIntent;
            }
        }
        // 检查是否匹配到意图组名(如"WEALTH")
        if (groups.containsKey(upper)) {
            return upper; // 返回组名,Controller层后续处理消歧
        }
        // 基于关键词的简单匹配
        if (upper.contains("TRANSFER") || upper.contains("转账")) return "TRANSFER";
        if (upper.contains("BILL") || upper.contains("账单")) return "BILL_QUERY";
        if (upper.contains("WEALTH") || upper.contains("理财")) return "WEALTH";
        // LLM返回UNKNOWN时,从用户输入推断
        return guessIntentFromInput(userInput);
    }

    /**
     * 从用户输入关键词推断意图
     *
     * @param userInput 用户原始输入
     * @return 推断的意图名(可能是具体意图、组名、或null)
     */
    public String guessIntentFromInput(String userInput) {
        if (userInput == null) return null;
        String lower = userInput.toLowerCase();
        if (lower.contains("转账") || lower.contains("转钱") || lower.contains("汇款") || lower.contains("transfer")) return "TRANSFER";
        if (lower.contains("账单") || lower.contains("明细") || lower.contains("消费") || lower.contains("支出") || lower.contains("bill")) return "BILL_QUERY";
        if (lower.contains("理财推荐") || lower.contains("理财咨询")) return "WEALTH_CONSULT";
        if (lower.contains("理财解读") || lower.contains("理财产品")) return "WEALTH_INTERPRET";
        if (lower.contains("理财")) return "WEALTH"; // 模糊,需要消歧
        return null;
    }

    /** 生成LLM可理解的意图列表描述(含组信息) */
    public String getIntentListDescription() {
        StringBuilder sb = new StringBuilder();
        for (IntentConfig config : registry.values()) {
            sb.append("- ").append(config.getName())
              .append(": ").append(config.getDescription())
              .append(" (参数: ").append(config.getParamSchema()).append(")\n");
        }
        if (!groups.isEmpty()) {
            sb.append("\n意图组(用户输入模糊时可能匹配到组级别,需要消歧):\n");
            for (IntentGroup group : groups.values()) {
                sb.append("- ").append(group.getGroupId())
                  .append(" (").append(group.getDisplayName()).append(")")
                  .append(" → ").append(String.join(" / ", group.getIntentNames()))
                  .append("\n");
            }
        }
        return sb.toString().trim();
    }
}
