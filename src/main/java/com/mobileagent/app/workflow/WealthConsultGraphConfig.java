package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.mock.MockBankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 理财咨询Graph配置 - 构建WealthConsultGraph的StateGraph并编译
 *
 * 节点流程:
 * START → extractParams → paramRouter → askRiskLevel (interruptBefore)
 *                                       ↘ executeWealthConsult → END
 *                                       ↘ cancelExecution → END (_cancelSignal)
 * askRiskLevel → paramRouter (循环)
 */
@Slf4j
@Configuration
public class WealthConsultGraphConfig extends AbstractGraphConfig {

    private final MockBankingService mockBankingService;

    public WealthConsultGraphConfig(@Qualifier("paramExtractChatModel") ChatModel chatModel, MockBankingService mockBankingService) {
        super(chatModel);
        this.mockBankingService = mockBankingService;
    }

    @Override
    protected String getGraphName() {
        return "WealthConsultGraph";
    }

    @Override
    protected String getCancelDetectionContext() {
        return "正在向用户询问理财咨询的风险偏好";
    }

    @Override
    protected List<String> getCancelKeywords() {
        List<String> keywords = new ArrayList<>(super.getCancelKeywords());
        keywords.addAll(List.of("不想咨询了", "取消咨询", "不用推荐了"));
        return keywords;
    }

    @Override
    protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
        strategies.put("wealthConsult.riskLevel", new ReplaceStrategy());
    }

    @Bean("wealthConsultGraph")
    public CompiledGraph wealthConsultGraph() throws GraphStateException {
        Map<String, String> paramEdges = new HashMap<>(Map.of(
                "ASK_RISK_LEVEL", "askRiskLevel",
                "ALL_GOOD", "executeWealthConsult"
        ));
        addCancelEdge(paramEdges);

        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askRiskLevel", node_async(this::askRiskLevelNode))
                .addNode("executeWealthConsult", node_async(this::executeWealthConsultNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
                .addEdge("executeWealthConsult", END);

        addCancelNode(graph);

        // ask节点条件路由
        addAskConditionalEdges(graph, "askRiskLevel");

        CompiledGraph compiled = graph.compile(createCompileConfig("askRiskLevel"));

        log.info("[WealthConsultGraph] Compiled successfully with interruptBefore + ask→END + cancel routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareExtractParams(state);
        if (cancelResult != null) return cancelResult;

        String userInput = getLatestInput(state);
        log.info("[WealthConsultGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            result.putAll(extracted);
            log.info("[WealthConsultGraph.extractParams] extracted: {}", extracted);
        } catch (Exception e) {
            log.error("[WealthConsultGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareParamRouter(state);
        if (cancelResult != null) return cancelResult;

        String riskLevel = getStringValue(state, "wealthConsult.riskLevel");

        Map<String, Object> result = new HashMap<>();
        if (riskLevel == null || riskLevel.isEmpty()) {
            result.put("_question", "请问您的风险偏好是什么？(激进/稳健/保守)");
            result.put("_paramName", "ASK_RISK_LEVEL");
            log.info("[WealthConsultGraph.paramRouter] Missing riskLevel → ASK_RISK_LEVEL");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[WealthConsultGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askRiskLevelNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[WealthConsultGraph.askRiskLevel] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[WealthConsultGraph.askRiskLevel] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String riskLevel = (String) extracted.get("wealthConsult.riskLevel");
            if (riskLevel != null && !riskLevel.isEmpty()) {
                result.put("wealthConsult.riskLevel", riskLevel);
                log.info("[WealthConsultGraph.askRiskLevel] Extracted riskLevel={}", riskLevel);
            } else {
                result.put("wealthConsult.riskLevel", normalizeRiskLevel(userInput.trim()));
                log.info("[WealthConsultGraph.askRiskLevel] Fallback: normalized riskLevel={}", userInput.trim());
            }
        } catch (Exception e) {
            log.error("[WealthConsultGraph.askRiskLevel] Extraction failed", e);
            result.put("wealthConsult.riskLevel", normalizeRiskLevel(userInput.trim()));
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> executeWealthConsultNode(OverAllState state) {
        String riskLevel = getStringValue(state, "wealthConsult.riskLevel");

        log.info("[WealthConsultGraph.executeWealthConsult] riskLevel={}", riskLevel);

        MockBankingService.WealthConsultResult consultResult = mockBankingService.wealthConsult(riskLevel);

        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", consultResult.message());
        result.put("_outputType", "TEXT");
        result.put("_isFinal", true);
        return result;
    }

    // ==================== WealthConsult特有方法 ====================

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个银行理财咨询参数提取器。从用户输入中提取理财咨询相关参数。

            用户输入: %s

            提取规则:
            - riskLevel: 风险偏好,只能为"激进"、"稳健"、"保守"之一
            - 如果用户说"高风险"、"进取"等 → 激进
            - 如果用户说"低风险"、"安全"等 → 保守
            - 如果用户说"中等"、"平衡"等 → 稳健
            - 只提取用户明确提到的参数,不猜测

            严格输出JSON:
            {
              "riskLevel": "风险偏好(激进/稳健/保守)或null"
            }
            """.formatted(userInput);
    }

    @Override
    protected Map<String, Object> parseExtractResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            String riskLevel = node.has("riskLevel") && !node.get("riskLevel").isNull()
                    ? node.get("riskLevel").asText() : null;
            if (riskLevel != null && !riskLevel.isEmpty()) {
                result.put("wealthConsult.riskLevel", riskLevel);
            }
        } catch (Exception e) {
            log.warn("[WealthConsultGraph] Failed to parse extract result: {}", content, e);
        }
        return result;
    }

    private String normalizeRiskLevel(String input) {
        if (input == null) return null;
        String lower = input.toLowerCase();
        if (lower.contains("激进") || lower.contains("高风险") || lower.contains("进取")) return "激进";
        if (lower.contains("保守") || lower.contains("低风险") || lower.contains("稳健保守")) return "保守";
        if (lower.contains("稳健") || lower.contains("中等") || lower.contains("平衡")) return "稳健";
        return "稳健";
    }
}
