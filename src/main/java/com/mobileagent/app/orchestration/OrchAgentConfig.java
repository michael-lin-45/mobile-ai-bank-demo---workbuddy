package com.mobileagent.app.orchestration;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mobileagent.app.data.SubGraphProperties;
import com.mobileagent.app.data.SubGraphProperties.SubGraphConfigProps;
import com.mobileagent.app.orchestration.bridge.OrchestrationStateBridge;
import com.mobileagent.app.orchestration.hook.ProgressTraceHook;
import com.mobileagent.app.orchestration.model.OrchestrationProperties;
import com.mobileagent.app.orchestration.model.OrchestrationProperties.AgentDefinition;
import com.mobileagent.app.orchestration.tool.L2GraphTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 编排Agent Spring Bean 配置
 *
 * <h3>核心设计：routing.intents 是唯一真相源</h3>
 * <pre>
 * routing.intents 中的 domain + graph-bean 两个字段，同时服务三层：
 * - L0/L1: 原有路由逻辑不变
 * - Orchestration: 自动推导每个 domainAgent 的默认 L2GraphTool
 *
 * 自动推导逻辑：
 * 1. agent.domain → 查找 routing.intents 中 domain 匹配的所有条目
 * 2. 去重 graph-bean → 每个 graph-bean 创建一个 L2GraphTool
 * 3. tool 名 = intent 名小写 (如 TRANSFER → transfer, BILL_QUERY → bill_query)
 * 4. orchestration.agents.tools → 仅用于跨域或额外工具
 * </pre>
 */
@Slf4j
@Configuration
public class OrchAgentConfig {

    // ===== Helper Beans =====

    @Bean
    public ProgressTraceHook progressTraceHook() {
        return new ProgressTraceHook();
    }

    @Bean
    public OrchestrationStateBridge stateBridge(OrchestrationStateService stateService) {
        return new OrchestrationStateBridge(stateService);
    }

    /** 共享引用 — L2GraphTool 通过此引用读取编排图 OverAllState 中的 pending L2 threadId */
    @Bean
    public AtomicReference<CompiledGraph> orchestrationGraphRef() {
        return new AtomicReference<>();
    }

    // ===== PlannerAgent =====

    @Bean
    public ReactAgent plannerAgent(@Qualifier("orchPlannerModel") ChatModel model,
                                    SubGraphProperties routingProps) {
        // 从 routing.intents 动态生成可用域/意图列表（包含 intent-type，供 PlannerAgent 选择意图）
        StringBuilder domainIntentList = new StringBuilder();
        for (SubGraphConfigProps intent : routingProps.getIntents()) {
            domainIntentList.append("- 域: ").append(intent.getDomain())
                .append(", 意图: ").append(intent.getName())
                .append(", 类型: ").append(intent.getIntentType() != null ? intent.getIntentType() : "UNKNOWN")
                .append(", 说明: ").append(intent.getDescription());
            if (intent.getScope() != null && !intent.getScope().isEmpty()) {
                domainIntentList.append(", 适用场景: ").append(intent.getScope());
            }
            domainIntentList.append("\n");
        }

        // 从 prompts/orch-planner.st 加载 instruction 模板，注入动态意图列表
        String template = com.mobileagent.app.util.TemplateUtils.loadTemplate("prompts/orch-planner.st");
        String instruction = template.replace("{intents}", domainIntentList.toString());
        log.info("[OrchAgentConfig] Planner instruction loaded from prompts/orch-planner.st with {} intents", routingProps.getIntents().size());

        return ReactAgent.builder()
            .name("planNode")
            .model(model)
            .instruction(instruction)
            .outputKey("orch_plan")
            .build();
    }

    // ===== Domain Agents — 从 routing.intents 自动推导 =====

    /**
     * 创建域Agent映射 — 自动从 routing.intents 推导默认 L2GraphTool
     *
     * <p><b>Circular Dependency Prevention</b>: Uses ApplicationContext.getBean() by name
     * instead of injecting Map&lt;String, CompiledGraph&gt;, which would pull in the
     * orchestrationGraph bean and create a cycle: orchestrationGraph → domainAgents → graphBeans → orchestrationGraph.
     *
     * <p>推导过程：
     * <ol>
     *   <li>遍历 orchestration.agents，每个 agent 有 domain 字段</li>
     *   <li>查找 routing.intents 中 domain 匹配的所有条目</li>
     *   <li>去重 graphBean → 每个 graphBean 用 ApplicationContext 按名查找</li>
     *   <li>tool 名 = intent 名小写</li>
     *   <li>额外 tools（跨域）从 orchestration.agents.tools 读取</li>
     * </ol>
     */
    @Bean
    public Map<String, ReactAgent> domainAgents(
            OrchestrationProperties props,
            SubGraphProperties routingProps,
            @Qualifier("orchModel") ChatModel chatModel,
            ApplicationContext applicationContext,
            ProgressTraceHook progressTraceHook,
            AtomicReference<CompiledGraph> orchestrationGraphRef,
            com.mobileagent.app.execution.GraphExecutionEngine graphExecutionEngine,
            StepResultSinkRegistry sinkRegistry) {

        // 预处理：按 domain 分组 routing.intents
        Map<String, List<SubGraphConfigProps>> intentsByDomain = routingProps.getIntents().stream()
            .filter(i -> i.getDomain() != null && !i.getDomain().isEmpty())
            .collect(Collectors.groupingBy(SubGraphConfigProps::getDomain, LinkedHashMap::new, Collectors.toList()));

        Map<String, ReactAgent> agents = new LinkedHashMap<>();
        for (AgentDefinition def : props.getAgents()) {
            try {
                List<L2GraphTool> allTools = new ArrayList<>();

                // 1. 从 routing.intents 自动推导本域工具
                String domain = def.getDomain();
                if (domain != null && !domain.isEmpty()) {
                    List<SubGraphConfigProps> domainIntents = intentsByDomain.getOrDefault(domain, List.of());
                    // 按 graphBean 去重，保留每个 graphBean 的第一个 intent 信息
                    Map<String, SubGraphConfigProps> graphToFirstIntent = new LinkedHashMap<>();
                    for (SubGraphConfigProps intent : domainIntents) {
                        String gb = intent.getGraphBean();
                        if (gb != null && !gb.isEmpty()) {
                            graphToFirstIntent.putIfAbsent(gb, intent);
                        }
                    }

                    for (Map.Entry<String, SubGraphConfigProps> entry : graphToFirstIntent.entrySet()) {
                        String graphBeanName = entry.getKey();
                        SubGraphConfigProps firstIntent = entry.getValue();
                        CompiledGraph graph;
                        try {
                            graph = applicationContext.getBean(graphBeanName, CompiledGraph.class);
                        } catch (Exception e) {
                            log.warn("[OrchAgentConfig] graphBean '{}' for domain '{}' not found, skipping",
                                graphBeanName, domain);
                            continue;
                        }

                        // tool 名 = intent 名小写 (TRANSFER → transfer, BILL_QUERY → bill_query)
                        String toolName = firstIntent.getName().toLowerCase();
                        String toolDesc = firstIntent.getDescription() != null
                            ? firstIntent.getDescription()
                            : "执行" + domain.toLowerCase() + "操作";

                        allTools.add(new L2GraphTool(graph, graphExecutionEngine, orchestrationGraphRef, toolName, toolDesc, sinkRegistry));
                        log.info("[OrchAgentConfig] Auto-derived tool: {} → graphBean={} (from routing.intents domain={})",
                            toolName, graphBeanName, domain);
                    }
                }

                // 2. 添加跨域/额外工具（从 orchestration.agents.tools）
                for (OrchestrationProperties.ToolDefinition td : def.getTools()) {
                    String graphBeanName = td.getGraphBean();
                    if (graphBeanName == null || graphBeanName.isEmpty()) {
                        log.warn("[OrchAgentConfig] Extra tool '{}' has no graph-bean, skipping", td.getName());
                        continue;
                    }
                    CompiledGraph graph;
                    try {
                        graph = applicationContext.getBean(graphBeanName, CompiledGraph.class);
                    } catch (Exception e) {
                        log.warn("[OrchAgentConfig] graphBean '{}' for extra tool '{}' not found, skipping",
                            graphBeanName, td.getName());
                        continue;
                    }
                    allTools.add(new L2GraphTool(graph, graphExecutionEngine, orchestrationGraphRef, td.getName(), td.getDescription(), sinkRegistry));
                    log.info("[OrchAgentConfig] Extra tool: {} → graphBean={}", td.getName(), graphBeanName);
                }

                // 3. 跳过无工具的 agent
                if (allTools.isEmpty()) {
                    log.warn("[OrchAgentConfig] Agent '{}' (domain='{}') has no tools — no matching routing.intents + no extra tools. Skipping.",
                        def.getName(), domain);
                    continue;
                }

                // 4. 构建 ReactAgent
                ReactAgent agent = ReactAgent.builder()
                    .name(def.getName())
                    .model(chatModel)
                    .instruction(def.getInstruction())
                    .tools(allTools.toArray(new org.springframework.ai.tool.ToolCallback[0]))
                    .hooks(List.of(progressTraceHook))
                    .outputKey(def.getOutputKey())
                    .build();

                agents.put(def.getName(), agent);
                int autoCount = domain != null && intentsByDomain.containsKey(domain)
                    ? (int) intentsByDomain.get(domain).stream()
                        .map(SubGraphConfigProps::getGraphBean)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count()
                    : 0;
                log.info("[OrchAgentConfig] Created agent: name={} domain={} tools={} ({} auto + {} extra) outputKey={}",
                    def.getName(), domain, allTools.size(), autoCount, allTools.size() - autoCount, def.getOutputKey());
            } catch (Exception e) {
                log.error("[OrchAgentConfig] Failed to create domain agent: name={}", def.getName(), e);
            }
        }

        log.info("[OrchAgentConfig] Created {} domain agents from yaml config", agents.size());
        return agents;
    }

    // ===== Domain → Agent 映射（供 OrchestrationGraphConfig 使用） =====

    /**
     * 从 orchestration.agents 的 domain 字段自动推导 domain→agentName 映射
     * 替代原来的 orchestration.routing.intents 配置段
     */
    @Bean
    public Map<String, String> domainToAgentMap(OrchestrationProperties props) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (AgentDefinition def : props.getAgents()) {
            if (def.getDomain() != null && !def.getDomain().isEmpty()) {
                mapping.put(def.getDomain(), def.getName());
            }
        }
        log.info("[OrchAgentConfig] Domain→Agent mapping: {}", mapping);
        return mapping;
    }

    // ===== SummaryAgent =====

    @Bean
    public ReactAgent summaryAgent(@Qualifier("orchModel") ChatModel model) {
        String instruction = com.mobileagent.app.util.TemplateUtils.loadTemplate("prompts/orch-summary.st");
        log.info("[OrchAgentConfig] Summary instruction loaded from prompts/orch-summary.st");
        return ReactAgent.builder()
            .name("summarizeNode")
            .model(model)
            .instruction(instruction)
            .outputKey("orch_outputContent")
            .build();
    }
}
