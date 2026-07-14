package com.mobileagent.app.orchestration;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.orchestration.listener.OrchestrationLifecycleListener;
import com.mobileagent.app.orchestration.model.OrchestrationProperties;
import com.mobileagent.app.orchestration.model.OrchestrationStateKeys;
import com.mobileagent.app.orchestration.model.OrchestrationStep;
import com.mobileagent.app.orchestration.model.StepStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 编排图配置 — 动态构建 StateGraph，零hardcode
 *
 * <h3>核心设计：yaml驱动，新增域零Java代码变更</h3>
 * <pre>
 * 新增域只需：
 * 1. 在 application.yml 的 orchestration.agents 添加 Agent 定义
 * 2. 在 application.yml 的 orchestration.routing.intents 添加路由映射
 * 3. 创建 XxxGraphConfig Bean（L2子图）
 * 4. 创建 OrchAgentConfig Bean（ReactAgent 注册）
 *
 * 本文件无需任何修改！
 * </pre>
 *
 * <h3>图结构</h3>
 * <pre>
 * START → planNode → executeStepNode → [domain_agent] → conditionCheckNode → ...
 *              ↓                              ↑                ↓
 *             END                    (dynamic from yaml)    replan → planNode
 *                                                          complete → summarizeNode → END
 *                                                          nextStep → executeStepNode
 * </pre>
 *
 * <h3>动态节点注册</h3>
 * <ul>
 *   <li>域Agent节点：遍历 {@code Map<String, ReactAgent> domainAgents}，每个entry注册一个节点</li>
 *   <li>意图路由映射：从 {@code domainToAgentMap} Bean 自动推导（orchestration.agents[].domain + .name）</li>
 *   <li>所有域Agent节点执行后统一流向 conditionCheckNode</li>
 * </ul>
 */
@Slf4j
@Configuration
public class OrchestrationGraphConfig {

    /**
     * 构建编排图 — 动态注册域Agent节点和路由映射
     *
     * @param stepPreparator     步骤准备器
     * @param condition          条件检查器
     * @param summary            摘要生成器
     * @param props              yaml配置属性
     * @param plannerAgent       规划Agent（@Qualifier指定）
     * @param summaryAgent       摘要Agent（@Qualifier指定）
     * @param domainAgents       域Agent映射 — key=agentName(来自yaml), value=ReactAgent实例
     * @param domainToAgentMap   domain→agentName路由映射 — 从 orchestration.agents[].domain 自动推导
     * @param lifecycleListener  生命周期监听器
     * @return 编译后的编排图
     */
    @Bean
    public CompiledGraph orchestrationGraph(
            StepPreparator stepPreparator,
            OrchestrationCondition condition,
            OrchestrationSummary summary,
            OrchestrationProperties props,
            @Qualifier("plannerAgent") ReactAgent plannerAgent,
            @Qualifier("summaryAgent") ReactAgent summaryAgent,
            Map<String, ReactAgent> domainAgents,
            Map<String, String> domainToAgentMap,
            OrchestrationLifecycleListener lifecycleListener,
            java.util.concurrent.atomic.AtomicReference<CompiledGraph> orchestrationGraphRef) throws GraphStateException {

        log.info("[OrchestrationGraphConfig] Starting graph construction with {} domain agents, {} domain routes",
            domainAgents != null ? domainAgents.size() : 0,
            domainToAgentMap != null ? domainToAgentMap.size() : 0);

        // ===== 1. KeyStrategy Factory =====
        KeyStrategyFactory keyStrategyFactory = createKeyStrategyFactory();

        StateGraph graph = new StateGraph("orchestration", keyStrategyFactory);

        // ===== 2. 固定控制节点 =====
        graph.addNode("planNode", plannerAgent.asNode(true, false));
        graph.addNode("executeStepNode", node_async(stepPreparator::prepareStep));
        graph.addNode("conditionCheckNode", node_async(condition::checkAndRoute));
        graph.addNode("waitUserNode", node_async(state -> Map.of())); // 等待用户输入的中断点
        graph.addNode("summarizeNode", summaryAgent.asNode(true, false));
        graph.addNode("prepareSummary", node_async(state -> {
            log.info("[prepareSummary] Preparing summary prompt with step results");

            // 收集所有 _result key（域 Agent 输出）供诊断
            for (String key : state.data().keySet()) {
                if (key.endsWith("_result")) {
                    Object val = state.value(key).orElse(null);
                    String valStr = val != null ? val.toString() : "null";
                    if (valStr.length() > 200) valStr = valStr.substring(0, 200) + "...";
                    log.info("[prepareSummary] Step result {}: {}", key, valStr);
                }
            }

            // 调用 buildSummaryPrompt 生成包含步骤结果的 messages
            Map<String, Object> summaryUpdates = summary.buildSummaryPrompt(state);
            log.info("[prepareSummary] Injected summary prompt into messages ({} chars)",
                summaryUpdates.containsKey("messages") ? summaryUpdates.get("messages").toString().length() : 0);

            return summaryUpdates;
        }));

        // ===== 3. 动态域Agent节点 — 从 yaml 配置动态注册 =====
        Map<String, String> intentToAgentMap = new LinkedHashMap<>();

        if (domainAgents != null && !domainAgents.isEmpty()) {
            for (Map.Entry<String, ReactAgent> entry : domainAgents.entrySet()) {
                String agentName = entry.getKey();
                ReactAgent agent = entry.getValue();
                graph.addNode(agentName, agent.asNode(true, false));
                graph.addEdge(agentName, "conditionCheckNode");
                log.info("[OrchestrationGraphConfig] Registered dynamic agent node: {}", agentName);
            }
        } else {
            log.warn("[OrchestrationGraphConfig] No domain agents found! Graph will have no domain nodes.");
        }

        // ===== 4. 构建 domain→agent 路由映射 — 从 orchestration.agents[].domain 自动推导 =====
        if (domainToAgentMap != null && !domainToAgentMap.isEmpty()) {
            for (Map.Entry<String, String> entry : domainToAgentMap.entrySet()) {
                String domain = entry.getKey();      // e.g. "TRANSFER"
                String agentName = entry.getValue();  // e.g. "transfer_agent"
                if (domainAgents != null && domainAgents.containsKey(agentName)) {
                    intentToAgentMap.put(domain, agentName);
                    log.info("[OrchestrationGraphConfig] Registered domain routing: {} → {}", domain, agentName);
                } else {
                    log.warn("[OrchestrationGraphConfig] Domain '{}' maps to agent '{}' which is not in domainAgents! Skipping.",
                        domain, agentName);
                }
            }
        } else {
            log.warn("[OrchestrationGraphConfig] No domain→agent mapping! Domain routing will be empty.");
        }

        // ===== 5. 边定义 =====
        // START: REPLANNING → planNode (replan fallback), 有步骤→executeStepNode, 无步骤→planNode
        graph.addConditionalEdges(START,
            edge_async(state -> {
                String status = state.value(OrchestrationStateKeys.ORCH_STATUS, "");
                if ("REPLANNING".equals(status)) {
                    log.info("[OrchestrationGraphConfig] START routing: REPLANNING → planNode");
                    return "plan";
                }
                List<?> steps = state.value(OrchestrationStateKeys.STEPS, List.of());
                boolean hasSteps = steps != null && !steps.isEmpty();
                return hasSteps ? "execute" : "plan";
            }),
            Map.of("plan", "planNode", "execute", "executeStepNode"));

        // FIXME: planNode → END 是异常路径（PlannerAgent输出为空），应走兜底输出告知用户规划失败，而非直接END静默结束
        graph.addConditionalEdges("planNode",
            edge_async(state -> {
                // Debug: 打印 orch_plan 的实际类型和内容
                Object planObj = state.value("orch_plan").orElse(null);
                String planText = extractPlanText(planObj);
                log.info("[OrchestrationGraphConfig] planNode routing: orch_plan type={}, textLen={}",
                    planObj != null ? planObj.getClass().getSimpleName() : "null",
                    planText != null ? planText.length() : 0);
                if (planText != null && planText.length() > 0) {
                    log.info("[OrchestrationGraphConfig] Planner output (first 2000): {}", truncate(planText, 2000));
                }

                // 检查 orch_plan（Planner 输出）—— STEPS 由 StepPreparator 在 executeStepNode 中从 orch_plan 解析
                boolean hasPlan = planText != null && !planText.isEmpty();
                log.info("[OrchestrationGraphConfig] planNode routing: hasPlan={} → {}",
                    hasPlan, hasPlan ? "execute" : "end");
                return hasPlan ? "execute" : "end";
            }),
            Map.of("execute", "executeStepNode", "end", END));

        if (!intentToAgentMap.isEmpty()) {
            graph.addConditionalEdges("executeStepNode",
                edge_async(this::routeByIntent),
                intentToAgentMap);
        } else {
            log.warn("[OrchestrationGraphConfig] No domain routing available, executeStepNode → conditionCheckNode directly");
            graph.addEdge("executeStepNode", "conditionCheckNode");
        }

        graph.addConditionalEdges("conditionCheckNode",
            edge_async(this::routeAfterCondition),
            Map.of("nextStep", "executeStepNode",
                   "replan", "planNode",
                   "waitUser", "waitUserNode",
                   "complete", "prepareSummary"));

        // waitUserNode → 条件路由: 简化为2路径 (decomposeByIntent refactor)
        //   answer → executeStepNode (恢复当前步骤)
        //   cancel → conditionCheckNode (CANCELLED步骤被跳过，找下一个)
        graph.addConditionalEdges("waitUserNode",
            edge_async(this::routeAfterWaitUser),
            Map.of("answer", "executeStepNode",
                   "cancel", "conditionCheckNode"));

        // prepareSummary → summarizeNode → END
        graph.addEdge("prepareSummary", "summarizeNode");
        graph.addEdge("summarizeNode", END);

        // ===== 6. 编译图 =====
        int recursionLimit = props != null && props.getGlobal() != null
            ? props.getGlobal().getRecursionLimit() : 50;

        CompiledGraph compiled = graph.compile(CompileConfig.builder()
            .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
            .withLifecycleListener(lifecycleListener)
            .recursionLimit(recursionLimit)
            .interruptBefore("waitUserNode") // 暂停等待用户输入
            .build());

        log.info("[OrchestrationGraphConfig] Compiled orchestration graph: {} dynamic agent nodes, {} domain routes",
            domainAgents != null ? domainAgents.size() : 0,
            intentToAgentMap.size());

        // 设置共享引用 — L2GraphTool 可通过此引用读取编排图 state
        orchestrationGraphRef.set(compiled);

        return compiled;
    }

    // ==================== 路由函数 ====================

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /** Extract text from orch_plan value — ReactAgent outputKey writes AssistantMessage, not String */
    private static String extractPlanText(Object planObj) {
        if (planObj == null) return null;
        if (planObj instanceof Message msg) return msg.getText();
        return planObj.toString();
    }

    /**
     * waitUserNode后的路由 — 简化为2路径 (decomposeByIntent refactor)
     *
     * <p>decomposeByIntent 在 OrchestrationAgent 中直接操作步骤状态（ANSWER/MERGE/INSERT/CANCEL等），
     * 图恢复时只需两条路径：
     * <ul>
     *   <li>answer → executeStepNode（恢复当前步骤执行）</li>
     *   <li>cancel → conditionCheckNode（CANCELLED步骤被findNextPendingStep跳过，找下一个）</li>
     * </ul>
     *
     * <p>NEW_INTENT 不再走 planNode 重规划 — decomposeByIntent 直接 INSERT 新步骤。
     * REPLAN 也在 OrchestrationAgent 中处理（设 ORCH_STATUS=REPLANNING → START边路由到planNode）。
     */
    private String routeAfterWaitUser(OverAllState state) {
        // Check if any non-COMPLETED steps were CANCELLED by decomposeByIntent
        // If CANCELLED steps exist → route to conditionCheckNode to find next step
        @SuppressWarnings("unchecked")
        List<?> steps = (List<?>) state.value(OrchestrationStateKeys.STEPS).orElse(List.of());
        boolean hasCancelled = false;
        if (steps != null) {
            for (Object obj : steps) {
                if (obj instanceof OrchestrationStep step && step.getStatus() == StepStatus.CANCELLED) {
                    hasCancelled = true;
                    break;
                }
            }
        }

        if (hasCancelled) {
            log.info("[OrchestrationGraphConfig.routeAfterWaitUser] Cancelled steps detected → conditionCheckNode");
            return "cancel";
        }

        // Default: answer — resume current step execution
        log.info("[OrchestrationGraphConfig.routeAfterWaitUser] → answer (resume step execution)");
        return "answer";
    }

    /**
     * 按意图路由 — 动态，从当前步骤的 domain 字段读取
     *
     * <p>返回 domain 字符串（如 "TRANSFER"），通过 intentToAgentMap 映射到对应的 agent 节点名。
     * 这是动态路由的核心：不hardcode任何域名称，完全由 yaml 配置驱动。
     *
     * @param state 当前 OverAllState
     * @return 意图字符串，映射到 intentToAgentMap 中的 agent 节点名
     */
    private String routeByIntent(OverAllState state) {
        int currentIndex = state.value(OrchestrationStateKeys.CURRENT_STEP_INDEX, 0);
        List<?> steps = state.value(OrchestrationStateKeys.STEPS, List.of());

        if (steps != null && currentIndex >= 0 && currentIndex < steps.size()) {
            Object stepObj = steps.get(currentIndex);
            if (stepObj instanceof OrchestrationStep step) {
                String domain = step.getDomain(); // e.g. "TRANSFER" — maps via intentToAgentMap
                log.info("[OrchestrationGraphConfig.routeByIntent] Step index={}, domain={}", currentIndex, domain);
                return domain;
            }
        }

        // Fallback: 无法确定路由 — 记录警告
        // 这种情况不应在正常流程中出现，说明 PlannerAgent 输出了无效步骤
        log.warn("[OrchestrationGraphConfig.routeByIntent] Cannot determine route — no step at index {} (steps size={})",
            currentIndex, steps != null ? steps.size() : 0);
        return "";
    }

    /**
     * 条件检查后路由 — 决定下一步走向
     *
     * <p>路由逻辑（优先级从高到低）：
     * <ol>
     *   <li>重路由信号 → replan（意图变更）</li>
     *   <li>参数保真度失败且重规划次数未超限 → replan</li>
     *   <li>所有步骤完成 → complete</li>
     *   <li>还有待执行步骤 → nextStep</li>
     * </ol>
     *
     * @param state 当前 OverAllState
     * @return 路由目标：nextStep / replan / complete
     */
    private String routeAfterCondition(OverAllState state) {
        // 0. L2 子图 interrupt → 暂停等待用户输入
        String orchStatus = state.value(OrchestrationStateKeys.ORCH_STATUS, "");
        if ("WAITING_USER".equals(orchStatus)) {
            log.info("[OrchestrationGraphConfig.routeAfterCondition] WAITING_USER → waitUserNode (interrupt)");
            return "waitUser";
        }

        // 1. DONE check — MUST be before AUTO_RESUME, because AUTO_RESUME_STEP_ID
        //    is never cleared and would cause stale routing to nextStep even when
        //    all steps are completed.
        if ("DONE".equals(orchStatus)) {
            log.info("[OrchestrationGraphConfig.routeAfterCondition] Orchestration DONE → complete");
            return "complete";
        }

        // 1.5 Phase 2D: AUTO_RESUME — 条件性回答评估通过，自动恢复步骤（不暂停）
        String autoResumeStepId = state.value(OrchestrationStateKeys.AUTO_RESUME_STEP_ID, "");
        if (autoResumeStepId != null && !autoResumeStepId.isEmpty()) {
            log.info("[OrchestrationGraphConfig.routeAfterCondition] AUTO_RESUME stepId={} → nextStep (auto-resume)", autoResumeStepId);
            return "nextStep";
        }

        // 2. Check pending steps
        List<?> steps = state.value(OrchestrationStateKeys.STEPS, List.of());

        // 3.5 Resume INTERRUPTED step — conditionCheckNode popped an INTERRUPTED step and set EXECUTING.
        // Route to executeStepNode to resume it with the user's latest input.
        // L2GraphTool will resume the L2 subgraph — if input has the answer, step completes; if not, L2 interrupts again.
        int currentIdx = state.value(OrchestrationStateKeys.CURRENT_STEP_INDEX, -1);
        if (currentIdx >= 0 && steps != null && currentIdx < steps.size()) {
            Object stepObj = steps.get(currentIdx);
            if (stepObj instanceof OrchestrationStep step && step.getStatus() == StepStatus.INTERRUPTED) {
                log.info("[OrchestrationGraphConfig.routeAfterCondition] Current step INTERRUPTED → nextStep (resume with user input)");
                return "nextStep";
            }
        }

        // 4. Check pending steps
        boolean hasPending = false;
        if (steps != null) {
            for (Object obj : steps) {
                if (obj instanceof OrchestrationStep step) {
                    if (step.getStatus() == StepStatus.PENDING || step.getStatus() == null) {
                        hasPending = true;
                        break;
                    }
                }
            }
        }

        if (hasPending) {
            log.info("[OrchestrationGraphConfig.routeAfterCondition] Has pending steps → nextStep");
            return "nextStep";
        }

        log.info("[OrchestrationGraphConfig.routeAfterCondition] No pending steps → complete");
        return "complete";
    }

    // ==================== KeyStrategy Factory ====================

    /**
     * 创建 KeyStrategy 工厂 — 注册所有 OverAllState key 的合并策略
     *
     * <p>策略说明：
     * <ul>
     *   <li>AppendStrategy: messages — 消息列表追加</li>
     *   <li>ReplaceStrategy: 所有其他key — 新值覆盖旧值</li>
     * </ul>
     */
    private KeyStrategyFactory createKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new LinkedHashMap<>();

            // 消息追加策略
            strategies.put("messages", new AppendStrategy());

            // Session identity
            strategies.put(OrchestrationStateKeys.SESSION_ID, new ReplaceStrategy());

            // 核心编排状态
            strategies.put(OrchestrationStateKeys.STEPS, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.CURRENT_STEP_INDEX, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.STEP_RESULTS, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.ORIGINAL_REQUEST, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.LATEST_USER_INPUT, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.ORCH_STATUS, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.WAITING_QUESTION, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.INTERRUPTION_REASON, new ReplaceStrategy());

            // 可观测性字段
            strategies.put(OrchestrationStateKeys.CURRENT_ACTION, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.NEXT_ACTION, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.STEP_STATUS, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.AGENT_PHASE, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.AGENT_NAME, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.CURRENT_TOOL_CALLS, new ReplaceStrategy());

            // L2 execution status
            strategies.put(OrchestrationStateKeys.STEP_L2_STATUS, new ReplaceStrategy());

            // stepId → threadId 映射 + 步骤级等待
            strategies.put(OrchestrationStateKeys.L2_STEP_THREAD_MAP, new ReplaceStrategy());
            strategies.put(OrchestrationStateKeys.WAITING_STEP_ID, new ReplaceStrategy());

            // Phase 2D: 条件性回答自动恢复信号
        strategies.put(OrchestrationStateKeys.AUTO_RESUME_STEP_ID, new ReplaceStrategy());
        strategies.put(OrchestrationStateKeys.AUTO_RESUME_ANSWER, new ReplaceStrategy());
        strategies.put(OrchestrationStateKeys.CANCELLED_L1_INTENTS, new ReplaceStrategy());

            // 路由信号
            strategies.put(OrchestrationStateKeys.WAITING_STEP_INDEX, new ReplaceStrategy());

            // ReactAgent outputKey — 必须注册否则 output 写入丢失
            strategies.put("orch_plan", new ReplaceStrategy());
            strategies.put("orch_outputContent", new ReplaceStrategy());

            return strategies;
        };
    }
}
