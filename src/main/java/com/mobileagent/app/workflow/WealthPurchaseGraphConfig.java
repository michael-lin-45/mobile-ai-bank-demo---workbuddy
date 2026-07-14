package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.memory.SubGraphCheckpointSaverConfig;
import com.mobileagent.app.mock.MockBankingService;
import com.mobileagent.app.router.registry.SubGraphRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 理财购买Graph配置 - 构建WealthPurchaseGraph的StateGraph并编译
 *
 * 节点流程:
 * START → extractParams → paramRouter → askProductName/askShareCount (interruptBefore)
 *                                         ↘ executeWealthPurchase → END
 *                                         ↘ cancelExecution → END (_cancelSignal)
 * askProductName/askShareCount → paramRouter (循环)
 *
 * 提取参数: 理财产品名称(productName) + 购买份额数(shareCount)
 */
@Slf4j
@Configuration
public class WealthPurchaseGraphConfig extends AbstractGraphConfig {

    private final MockBankingService mockBankingService;
    private final SubGraphRegistry subGraphRegistry;

    public WealthPurchaseGraphConfig(@Qualifier("paramExtractChatModel") ChatModel chatModel,
                                      SubGraphCheckpointSaverConfig.SubGraphCheckpointSaverFactory subGraphCheckpointSaverFactory,
                                      MockBankingService mockBankingService,
                                      ObjectMapper objectMapper,
                                      SubGraphRegistry subGraphRegistry) {
        super(chatModel, objectMapper, subGraphCheckpointSaverFactory);
        this.mockBankingService = mockBankingService;
        this.subGraphRegistry = subGraphRegistry;
    }

    @Override
    protected String getGraphName() {
        return "WealthPurchaseGraph";
    }

    @Override
    protected String getCancelDetectionContext() {
        return "正在向用户询问理财产品购买信息(产品名称/购买份额)";
    }

    @Override
    protected List<String> getCancelKeywords() {
        List<String> keywords = new ArrayList<>(super.getCancelKeywords());
        keywords.addAll(List.of("不买了", "别买了", "取消购买", "不想买了", "不用买了"));
        return keywords;
    }

    @Override
    protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
        strategies.put("wealthPurchase.productName", new ReplaceStrategy());
        strategies.put("wealthPurchase.shareCount", new ReplaceStrategy());
    }

    @Bean("wealthPurchaseGraph")
    public CompiledGraph wealthPurchaseGraph() throws GraphStateException {
        Map<String, String> paramEdges = new HashMap<>(Map.of(
                "ASK_PRODUCT_NAME", "askProductName",
                "ASK_SHARE_COUNT", "askShareCount",
                "ALL_GOOD", "executeWealthPurchase"
        ));
        addCancelEdge(paramEdges);

        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askProductName", askNode("askProductName", this::askProductNameLogic))
                .addNode("askShareCount", askNode("askShareCount", this::askShareCountLogic))
                .addNode("executeWealthPurchase", node_async(this::executeWealthPurchaseNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
                .addEdge("executeWealthPurchase", END);

        addCancelNode(graph);

        // ask节点条件路由
        addAskConditionalEdges(graph, "askProductName");
        addAskConditionalEdges(graph, "askShareCount");

        CompiledGraph compiled = graph.compile(createInterruptCompileConfig());
        subGraphRegistry.bindGraph("WEALTH_PURCHASE", compiled);

        log.info("[WealthPurchaseGraph] Compiled successfully with interruptBefore + ask→END + cancel routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareExtractParams(state);
        if (cancelResult != null) return cancelResult;

        String userInput = getLatestInput(state);
        log.info("[WealthPurchaseGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            mergeExtractedWithoutOverwrite(result, extracted, state);
            log.info("[WealthPurchaseGraph.extractParams] extracted: {}", extracted);
        } catch (Exception e) {
            log.error("[WealthPurchaseGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareParamRouter(state);
        if (cancelResult != null) return cancelResult;

        String productName = getStringValue(state, "wealthPurchase.productName");
        Object shareCountObj = state.value("wealthPurchase.shareCount").orElse(null);
        String shareCount = shareCountObj != null ? shareCountObj.toString() : null;

        Map<String, Object> result = new HashMap<>();
        if (productName == null || productName.isEmpty()) {
            result.put("_question", "请问您要购买哪款理财产品？");
            result.put("_paramName", "ASK_PRODUCT_NAME");
            log.info("[WealthPurchaseGraph.paramRouter] Missing productName → ASK_PRODUCT_NAME");
        } else if (shareCount == null || shareCount.isEmpty()) {
            result.put("_question", "请问您要购买多少份额？");
            result.put("_paramName", "ASK_SHARE_COUNT");
            log.info("[WealthPurchaseGraph.paramRouter] Missing shareCount → ASK_SHARE_COUNT");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[WealthPurchaseGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askProductNameLogic(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[WealthPurchaseGraph.askProductName] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[WealthPurchaseGraph.askProductName] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String productName = (String) extracted.get("wealthPurchase.productName");
            if (productName != null && !productName.isEmpty()) {
                result.put("wealthPurchase.productName", productName);
                log.info("[WealthPurchaseGraph.askProductName] Extracted productName={}", productName);
            }
            Object shareCount = extracted.get("wealthPurchase.shareCount");
            if (shareCount != null) {
                result.put("wealthPurchase.shareCount", shareCount);
                log.info("[WealthPurchaseGraph.askProductName] Also extracted shareCount={}", shareCount);
            }
        } catch (Exception e) {
            log.error("[WealthPurchaseGraph.askProductName] Extraction failed", e);
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> askShareCountLogic(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[WealthPurchaseGraph.askShareCount] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[WealthPurchaseGraph.askShareCount] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            Object shareCount = extracted.get("wealthPurchase.shareCount");
            if (shareCount != null) {
                result.put("wealthPurchase.shareCount", shareCount);
                log.info("[WealthPurchaseGraph.askShareCount] Extracted shareCount={}", shareCount);
            } else {
                BigDecimal directCount = parseDirectAmount(userInput);
                if (directCount != null) {
                    result.put("wealthPurchase.shareCount", directCount);
                    log.info("[WealthPurchaseGraph.askShareCount] Direct parsed shareCount={}", directCount);
                }
            }
            String productName = (String) extracted.get("wealthPurchase.productName");
            if (productName != null && !productName.isEmpty()) {
                result.put("wealthPurchase.productName", productName);
                log.info("[WealthPurchaseGraph.askShareCount] Also extracted productName={}", productName);
            }
        } catch (Exception e) {
            log.error("[WealthPurchaseGraph.askShareCount] Extraction failed", e);
            BigDecimal directCount = parseDirectAmount(userInput);
            if (directCount != null) {
                result.put("wealthPurchase.shareCount", directCount);
            }
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> executeWealthPurchaseNode(OverAllState state) {
        String productName = getStringValue(state, "wealthPurchase.productName");
        Object shareCountObj = state.value("wealthPurchase.shareCount").orElse(null);
        BigDecimal shareCount = shareCountObj instanceof BigDecimal ? (BigDecimal) shareCountObj
                : shareCountObj != null ? new BigDecimal(shareCountObj.toString()) : BigDecimal.ZERO;

        log.info("[WealthPurchaseGraph.executeWealthPurchase] productName={}, shareCount={}", productName, shareCount);

        MockBankingService.WealthPurchaseResult purchaseResult = mockBankingService.wealthPurchase(productName, shareCount);

        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", purchaseResult.message());
        result.put("_outputType", "CONFIRMATION");
        result.put("_isFinal", true);
        return result;
    }

    // ==================== SubAgent数据快照 ====================

    @Override
    protected Map<String, Object> extractSubAgentDataSnapshot(OverAllState state) {
        Map<String, Object> data = new HashMap<>();
        String productName = getStringValue(state, "wealthPurchase.productName");
        if (productName != null && !productName.isEmpty()) data.put("productName", productName);
        Object shareCount = state.value("wealthPurchase.shareCount").orElse(null);
        if (shareCount != null) data.put("shareCount", shareCount.toString());
        return data;
    }

    // ==================== 参数提取 ====================

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个银行理财产品购买参数提取器。从用户输入中提取理财产品购买相关参数。
            
            用户输入: %s
            
            提取规则:
            - productName: 理财产品名称，如"稳利宝"、"汇添富"、"天天利"、"安心宝"、"沪深300ETF"等
            - shareCount: 购买份额数(数字)，如100、500、1000
            - 只提取用户明确提到的参数,不猜测
            - 如果用户说"买300股"或"买500份",shareCount就是数字部分(300或500)
            
            严格输出JSON:
            {
              "productName": "产品名称或null",
              "shareCount": 份额数字或null
            }
            """.formatted(userInput);
    }

    @Override
    protected Map<String, Object> parseExtractResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            String productName = node.has("productName") && !node.get("productName").isNull() ? node.get("productName").asText() : null;
            BigDecimal shareCount = null;
            if (node.has("shareCount") && !node.get("shareCount").isNull()) {
                String shareCountStr = node.get("shareCount").asText();
                shareCount = parseDirectAmount(shareCountStr);
            }

            if (productName != null && !productName.isEmpty()) result.put("wealthPurchase.productName", productName);
            if (shareCount != null) result.put("wealthPurchase.shareCount", shareCount);
        } catch (Exception e) {
            log.warn("[WealthPurchaseGraph] Failed to parse extract result: {}", content, e);
        }
        return result;
    }

    private BigDecimal parseDirectAmount(String input) {
        if (input == null) return null;
        try {
            String cleaned = input.replaceAll("[元块份股]", "").trim();
            if (cleaned.endsWith("万")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
                return new BigDecimal(cleaned).multiply(new BigDecimal("10000"));
            }
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
