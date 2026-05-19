package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.mock.MockBankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 理财解读Graph配置 - 构建WealthInterpretGraph的StateGraph并编译
 *
 * 节点流程:
 * START → extractParams → paramRouter → askProductName (interruptBefore)
 *                                       ↘ executeWealthInterpret → END
 * askProductName → paramRouter (循环)
 */
@Slf4j
@Configuration
public class WealthInterpretGraphConfig extends AbstractGraphConfig {

    private final MockBankingService mockBankingService;

    public WealthInterpretGraphConfig(ChatModel chatModel, MockBankingService mockBankingService) {
        super(chatModel);
        this.mockBankingService = mockBankingService;
    }

    @Override
    protected String getGraphName() {
        return "WealthInterpretGraph";
    }

    @Override
    protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
        strategies.put("wealthInterpret.productName", new ReplaceStrategy());
    }

    @Bean("wealthInterpretGraph")
    public CompiledGraph wealthInterpretGraph() throws GraphStateException {
        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askProductName", node_async(this::askProductNameNode))
                .addNode("executeWealthInterpret", node_async(this::executeWealthInterpretNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter",
                        edge_async(state -> {
                            Object param = state.value("_paramName").orElse("ALL_GOOD");
                            return param.toString();
                        }),
                        Map.of(
                                "ASK_PRODUCT_NAME", "askProductName",
                                "ALL_GOOD", "executeWealthInterpret"
                        ))
                .addEdge("executeWealthInterpret", END);

        // ask节点条件路由
        addAskConditionalEdges(graph, "askProductName");

        CompiledGraph compiled = graph.compile(createCompileConfig("askProductName"));

        log.info("[WealthInterpretGraph] Compiled successfully with interruptBefore + ask→END conditional routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[WealthInterpretGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            result.putAll(extracted);
            log.info("[WealthInterpretGraph.extractParams] extracted: {}", extracted);
        } catch (Exception e) {
            log.error("[WealthInterpretGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        String productName = getStringValue(state, "wealthInterpret.productName");

        Map<String, Object> result = new HashMap<>();
        if (productName == null || productName.isEmpty()) {
            result.put("_question", "请问您要解读哪个理财产品？(如:稳利宝、汇添富等)");
            result.put("_paramName", "ASK_PRODUCT_NAME");
            log.info("[WealthInterpretGraph.paramRouter] Missing productName → ASK_PRODUCT_NAME");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[WealthInterpretGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askProductNameNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[WealthInterpretGraph.askProductName] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[WealthInterpretGraph.askProductName] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String productName = (String) extracted.get("wealthInterpret.productName");
            if (productName != null && !productName.isEmpty()) {
                result.put("wealthInterpret.productName", productName);
                log.info("[WealthInterpretGraph.askProductName] Extracted productName={}", productName);
            } else {
                result.put("wealthInterpret.productName", userInput.trim());
                log.info("[WealthInterpretGraph.askProductName] Fallback: using raw input as productName={}", userInput.trim());
            }
        } catch (Exception e) {
            log.error("[WealthInterpretGraph.askProductName] Extraction failed", e);
            result.put("wealthInterpret.productName", userInput.trim());
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> executeWealthInterpretNode(OverAllState state) {
        String productName = getStringValue(state, "wealthInterpret.productName");

        log.info("[WealthInterpretGraph.executeWealthInterpret] productName={}", productName);

        MockBankingService.WealthInterpretResult interpretResult = mockBankingService.wealthInterpret(productName);

        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", interpretResult.message());
        result.put("_outputType", "TEXT");
        result.put("_isFinal", true);
        return result;
    }

    // ==================== WealthInterpret特有方法 ====================

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个银行理财产品解读参数提取器。从用户输入中提取理财产品名称。

            用户输入: %s

            提取规则:
            - productName: 理财产品名称,如"稳利宝"、"汇添富"、"天天利"、"安心宝"等
            - 只提取用户明确提到的产品名称,不猜测
            - 如果用户说"这个产品"、"那个"等指代,输出null

            严格输出JSON:
            {
              "productName": "理财产品名称或null"
            }
            """.formatted(userInput);
    }

    @Override
    protected Map<String, Object> parseExtractResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            String productName = node.has("productName") && !node.get("productName").isNull()
                    ? node.get("productName").asText() : null;
            if (productName != null && !productName.isEmpty()) {
                result.put("wealthInterpret.productName", productName);
            }
        } catch (Exception e) {
            log.warn("[WealthInterpretGraph] Failed to parse extract result: {}", content, e);
        }
        return result;
    }
}
