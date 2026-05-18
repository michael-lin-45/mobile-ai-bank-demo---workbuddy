package com.mobileagent.app.model;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 意图注册表 - 管理所有已注册意图及其对应的Graph Bean
 */
@Slf4j
@Component
public class IntentRegistry {

    private final Map<String, IntentConfig> registry = new LinkedHashMap<>();

    @Data
    public static class IntentConfig {
        private final String name;
        private final String description;
        private final String paramSchema;
        private final boolean writeOp;
        private CompiledGraph graph;

        public IntentConfig(String name, String description, String paramSchema, boolean writeOp) {
            this.name = name;
            this.description = description;
            this.paramSchema = paramSchema;
            this.writeOp = writeOp;
        }
    }

    @PostConstruct
    public void init() {
        // 注册已知意图(不含graph,graph在Bean初始化后注入)
        register("TRANSFER", "转账给他人", "收款人名称, 转账金额, 用途(可选)", true);
        register("BILL_QUERY", "查询账单明细", "时间范围, 支出/收入类型(可选)", false);
        log.info("IntentRegistry initialized with {} intents: {}", registry.size(), registry.keySet());
    }

    public void register(String name, String description, String paramSchema, boolean writeOp) {
        registry.put(name, new IntentConfig(name, description, paramSchema, writeOp));
    }

    public void bindGraph(String intentName, CompiledGraph graph) {
        IntentConfig config = registry.get(intentName);
        if (config != null) {
            config.setGraph(graph);
            log.info("Bound graph to intent: {}", intentName);
        } else {
            log.warn("Attempted to bind graph to unknown intent: {}", intentName);
        }
    }

    public CompiledGraph getGraph(String intentName) {
        IntentConfig config = registry.get(intentName);
        return config != null ? config.getGraph() : null;
    }

    public IntentConfig getConfig(String intentName) {
        return registry.get(intentName);
    }

    public boolean isWriteOp(String intentName) {
        IntentConfig config = registry.get(intentName);
        return config != null && config.isWriteOp();
    }

    /** 生成LLM可理解的意图列表描述 */
    public String getIntentListDescription() {
        StringBuilder sb = new StringBuilder();
        for (IntentConfig config : registry.values()) {
            sb.append("- ").append(config.getName())
              .append(": ").append(config.getDescription())
              .append(" (参数: ").append(config.getParamSchema()).append(")\n");
        }
        return sb.toString().trim();
    }

    public Set<String> getIntentNames() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public boolean hasIntent(String name) {
        return registry.containsKey(name);
    }
}
