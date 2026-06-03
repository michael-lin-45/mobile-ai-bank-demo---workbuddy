package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.memory.SubGraphCheckpointSaverConfig;
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
 *                                       ↘ askFocusArea (interruptBefore)
 *                                       ↘ executeWealthConsult → END
 *                                       ↘ cancelExecution → END (_cancelSignal)
 * askRiskLevel/askFocusArea → paramRouter (循环)
 */
@Slf4j
@Configuration
public class WealthConsultGraphConfig extends AbstractGraphConfig {

    private final MockBankingService mockBankingService;

    public WealthConsultGraphConfig(@Qualifier("paramExtractChatModel") ChatModel chatModel,
                                      SubGraphCheckpointSaverConfig.SubGraphCheckpointSaverFactory subGraphCheckpointSaverFactory,
                                      MockBankingService mockBankingService,
                                      ObjectMapper objectMapper) {
        super(chatModel, objectMapper, subGraphCheckpointSaverFactory);
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
        strategies.put("wealthConsult.focusArea", new ReplaceStrategy());
    }

    @Bean("wealthConsultGraph")
    public CompiledGraph wealthConsultGraph() throws GraphStateException {
        Map<String, String> paramEdges = new HashMap<>(Map.of(
                "ASK_RISK_LEVEL", "askRiskLevel",
                "ASK_FOCUS_AREA", "askFocusArea",
                "ALL_GOOD", "executeWealthConsult"
        ));
        addCancelEdge(paramEdges);

        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askRiskLevel", askNode("askRiskLevel", this::askRiskLevelLogic))
                .addNode("askFocusArea", askNode("askFocusArea", this::askFocusAreaLogic))
                .addNode("executeWealthConsult", node_async(this::executeWealthConsultNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
                .addEdge("executeWealthConsult", END);

        addCancelNode(graph);

        // ask节点条件路由
        addAskConditionalEdges(graph, "askRiskLevel");
        addAskConditionalEdges(graph, "askFocusArea");

        CompiledGraph compiled = graph.compile(createInterruptCompileConfig());

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
            mergeExtractedWithoutOverwrite(result, extracted, state);
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
        String focusArea = getStringValue(state, "wealthConsult.focusArea");

        Map<String, Object> result = new HashMap<>();
        if (riskLevel == null || riskLevel.isEmpty()) {
            result.put("_question", "请问您的风险偏好是什么？(激进/稳健/保守)");
            result.put("_paramName", "ASK_RISK_LEVEL");
            log.info("[WealthConsultGraph.paramRouter] Missing riskLevel → ASK_RISK_LEVEL");
        } else if (focusArea == null || focusArea.isEmpty()) {
            result.put("_question", "请问您关注哪个领域的理财？(科技/能源/汽车/银行/工业/饮食/娱乐/全部)");
            result.put("_paramName", "ASK_FOCUS_AREA");
            log.info("[WealthConsultGraph.paramRouter] Missing focusArea → ASK_FOCUS_AREA");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[WealthConsultGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askRiskLevelLogic(OverAllState state) {
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
            }
            // LLM返回null: 用户确实没指定,不猜测,让paramRouter继续追问
            String focusArea = (String) extracted.get("wealthConsult.focusArea");
            if (focusArea != null && !focusArea.isEmpty()) {
                result.put("wealthConsult.focusArea", focusArea);
                log.info("[WealthConsultGraph.askRiskLevel] Extracted focusArea={}", focusArea);
            }
        } catch (Exception e) {
            log.error("[WealthConsultGraph.askRiskLevel] Extraction failed", e);
            // LLM调用失败: 同样不设置,让paramRouter继续追问
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> askFocusAreaLogic(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[WealthConsultGraph.askFocusArea] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[WealthConsultGraph.askFocusArea] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String focusArea = (String) extracted.get("wealthConsult.focusArea");
            if (focusArea != null && !focusArea.isEmpty()) {
                result.put("wealthConsult.focusArea", focusArea);
                log.info("[WealthConsultGraph.askFocusArea] Extracted focusArea={}", focusArea);
            }
            String riskLevel = (String) extracted.get("wealthConsult.riskLevel");
            if (riskLevel != null && !riskLevel.isEmpty()) {
                result.put("wealthConsult.riskLevel", riskLevel);
                log.info("[WealthConsultGraph.askFocusArea] Also extracted riskLevel={}", riskLevel);
            }
        } catch (Exception e) {
            log.error("[WealthConsultGraph.askFocusArea] Extraction failed", e);
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> executeWealthConsultNode(OverAllState state) {
        String riskLevel = getStringValue(state, "wealthConsult.riskLevel");
        String focusArea = getStringValue(state, "wealthConsult.focusArea");

        log.info("[WealthConsultGraph.executeWealthConsult] riskLevel={}, focusArea={}", riskLevel, focusArea);

        MockBankingService.WealthConsultResult consultResult = mockBankingService.wealthConsult(riskLevel, focusArea);

        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", consultResult.message());
        result.put("_outputType", "TEXT");
        result.put("_isFinal", true);
        return result;
    }

    // ==================== SubAgent数据快照 ====================

    @Override
    protected Map<String, Object> extractSubAgentDataSnapshot(OverAllState state) {
        Map<String, Object> data = new HashMap<>();
        String riskLevel = getStringValue(state, "wealthConsult.riskLevel");
        if (riskLevel != null && !riskLevel.isEmpty()) data.put("riskLevel", riskLevel);
        String focusArea = getStringValue(state, "wealthConsult.focusArea");
        if (focusArea != null && !focusArea.isEmpty()) data.put("focusArea", focusArea);
        return data;
    }

    // ==================== WealthConsult特有方法 ====================

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个银行理财咨询参数提取器。从用户输入中提取理财咨询相关参数。

            用户输入: %s

            提取规则:
            - riskLevel: 风险偏好,只能为"激进"、"稳健"、"保守"之一
              如果用户说"高风险"、"进取"等 → 激进
              如果用户说"低风险"、"安全"等 → 保守
              如果用户说"中等"、"平衡"等 → 稳健
            - focusArea: 关注领域,只能为"科技"、"能源"、"汽车"、"银行"、"工业"、"饮食"、"娱乐"、"全部"之一
              如果用户说"互联网"、"AI"、"芯片"等 → 科技
              如果用户说"新能源"、"光伏"、"电力"等 → 能源
              如果用户说"车企"、"造车"、"电动车"等 → 汽车
              如果用户说"金融"、"银行股"等 → 银行
              如果用户说"制造"、"工厂"等 → 工业
              如果用户说"食品"、"餐饮"、"消费"等 → 饮食
              如果用户说"影视"、"游戏"、"传媒"等 → 娱乐
              如果用户没提关注领域 → null(不要猜测)
            - 只提取用户明确提到的参数,不猜测

            严格输出JSON:
            {
              "riskLevel": "风险偏好(激进/稳健/保守)或null",
              "focusArea": "关注领域(科技/能源/汽车/银行/工业/饮食/娱乐/全部)或null"
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
            String focusArea = node.has("focusArea") && !node.get("focusArea").isNull()
                    ? node.get("focusArea").asText() : null;
            if (focusArea != null && !focusArea.isEmpty()) {
                result.put("wealthConsult.focusArea", normalizeFocusArea(focusArea));
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
        // 无法映射时不猜测,返回null让paramRouter继续追问
        return null;
    }

    private String normalizeFocusArea(String input) {
        if (input == null) return null;
        String validAreas = "科技,能源,汽车,银行,工业,饮食,娱乐,全部";
        for (String area : validAreas.split(",")) {
            if (input.equals(area)) return area;
        }
        // 语义映射
        if (input.contains("互联") || input.contains("AI") || input.contains("芯片") || input.contains("半导体")) return "科技";
        if (input.contains("新能源") || input.contains("光伏") || input.contains("电力")) return "能源";
        if (input.contains("车") || input.contains("电动")) return "汽车";
        if (input.contains("金融") || input.contains("银行股")) return "银行";
        if (input.contains("制造") || input.contains("工厂")) return "工业";
        if (input.contains("食品") || input.contains("餐饮") || input.contains("消费")) return "饮食";
        if (input.contains("影视") || input.contains("游戏") || input.contains("传媒")) return "娱乐";
        // 用户明确表示不限定领域
        if (input.contains("未指定") || input.contains("随便") || input.contains("都行")
                || input.contains("不限") || input.contains("无所谓") || input.contains("都可以")) return "全部";
        // 无法映射时不猜测,返回null让paramRouter继续追问
        return null;
    }
}
