package com.mobileagent.app.orchestration.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排配置属性 — 从 application.yml 的 orchestration 段读取
 *
 * <p>设计原则：routing.intents 是唯一真相源。
 * 只需在 routing.intents 中加 domain + graph-bean 两个字段，
 * L0/L1/Orchestration 三层共享，不用东配西配。
 *
 * <h3>yaml结构示例</h3>
 * <pre>
 * # 唯一真相源 — 加 domain + graph-bean 即可同时服务 L0/L1/Orchestration
 * routing:
 *   intents:
 *     - name: TRANSFER
 *       domain: TRANSFER              # ← 新增：所属领域
 *       graph-bean: transferGraph     # ← 新增：对应的 L2 CompiledGraph Bean
 *       description: "转账给他人"
 *       ...其他L0/L1字段不变...
 *
 * # 编排只需引用 domain，工具自动从 routing.intents 推导
 * orchestration:
 *   agents:
 *     - name: transfer_agent
 *       domain: TRANSFER              # ← 引用 routing.domains key
 *       model: orchModel
 *       instruction: 你是转账领域专家...
 *       tools:                        # 可选：跨域工具（本域工具已自动推导）
 *         - name: bill_query
 *           graph-bean: billQueryGraph
 *           description: 查询余额（跨域工具）
 *       output-key: transfer_result
 *   global:
 *     planner-model: orchPlannerModel
 *     agent-model: orchModel
 * </pre>
 *
 * <h3>自动推导规则</h3>
 * <ul>
 *   <li>agent.domain → 查找 routing.intents 中 domain 匹配的所有条目</li>
 *   <li>去重 graph-bean → 每个 graph-bean 自动创建一个 L2GraphTool</li>
 *   <li>tool 名 = intent 名小写 (如 TRANSFER → transfer, BILL_QUERY → bill_query)</li>
 *   <li>单 graph 域 (TRANSFER→transferGraph) → 1个默认工具</li>
 *   <li>多 graph 域 (WEALTH→wealthConsultGraph+wealthInterpretGraph) → 多个工具</li>
 *   <li>orchestration.agents.tools → 仅用于跨域或额外工具</li>
 *   <li>domain→agentName 映射 → 从 agents[].domain + agents[].name 自动推导，无需单独配置</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "orchestration")
public class OrchestrationProperties {

    /** 域Agent配置列表 */
    private List<AgentDefinition> agents = new ArrayList<>();

    /** 全局设置 */
    private GlobalConfig global = new GlobalConfig();

    // ==================== 域Agent定义 ====================

    @Data
    public static class AgentDefinition {
        /** Agent名称（也是编排图中的节点ID） */
        private String name;
        /** 所属领域 — 对应 routing.domains 的 key，同时用于从 routing.intents 自动推导默认 L2GraphTool */
        private String domain;
        /** Agent指令 */
        private String instruction;
        /** 额外工具列表（跨域工具或同域额外graph，本域工具由 routing.intents 自动推导） */
        private List<ToolDefinition> tools = new ArrayList<>();
        /** 输出到OverAllState的key名 */
        private String outputKey;
    }

    // ==================== Tool定义（仅用于跨域/额外工具） ====================

    @Data
    public static class ToolDefinition {
        /** Tool名称（给ReactAgent LLM看的） */
        private String name;
        /** 指向 CompiledGraph Bean 名 */
        private String graphBean;
        /** Tool描述（给ReactAgent LLM看的，影响Tool选择） */
        private String description;
    }

    // ==================== 全局设置 ====================

    @Data
    public static class GlobalConfig {
        /** 编排图递归限制 */
        private int recursionLimit = 50;
        /** 编排图超时（秒） */
        private int timeoutSeconds = 300;
        /** 编排层获取对话历史的条数（用于PlannerAgent解析指代，如"刚才推荐的"） */
        private int orchMaxMessages = 6;
    }
}
