package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.memory.CheckpointSaverConfig;
import com.mobileagent.app.mock.MockBankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 *                                       ↘ executeWealthInterpret → END (流式节点)
 *                                       ↘ cancelExecution → END (_cancelSignal)
 * askProductName → paramRouter (循环)
 *
 * 流式设计:
 * - executeWealthInterpret节点返回Map中包含Flux<ChatResponse>
 * - Spring AI Alibaba Graph自动检测Flux值，包装为StreamingOutput
 * - GES的executeStreaming路径将StreamingOutput转为StreamChunk.chunk()
 * - 前端逐字追加显示，用户体验远优于等待全部生成完再显示
 */
@Slf4j
@Configuration
public class WealthInterpretGraphConfig extends AbstractGraphConfig {

    private final MockBankingService mockBankingService;
    private final ChatClient wealthInterpretChatClient;

    public WealthInterpretGraphConfig(@Qualifier("paramExtractChatModel") ChatModel chatModel,
                                       CheckpointSaverConfig.CheckpointSaverFactory checkpointSaverFactory,
                                       MockBankingService mockBankingService,
                                       @Qualifier("wealthInterpretChatClient") ChatClient wealthInterpretChatClient) {
        super(chatModel, checkpointSaverFactory);
        this.mockBankingService = mockBankingService;
        this.wealthInterpretChatClient = wealthInterpretChatClient;
    }

    @Override
    protected String getGraphName() {
        return "WealthInterpretGraph";
    }

    @Override
    protected String getCancelDetectionContext() {
        return "正在向用户询问理财产品名称";
    }

    @Override
    protected List<String> getCancelKeywords() {
        List<String> keywords = new ArrayList<>(super.getCancelKeywords());
        keywords.addAll(List.of("不想解读了", "取消解读", "不想要了", "不用解读了"));
        return keywords;
    }

    @Override
    protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
        strategies.put("wealthInterpret.productName", new ReplaceStrategy());
    }

    @Bean("wealthInterpretGraph")
    public CompiledGraph wealthInterpretGraph() throws GraphStateException {
        Map<String, String> paramEdges = new HashMap<>(Map.of(
                "ASK_PRODUCT_NAME", "askProductName",
                "ALL_GOOD", "executeWealthInterpret"
        ));
        addCancelEdge(paramEdges);

        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askProductName", askNode("askProductName", this::askProductNameLogic))
                .addNode("executeWealthInterpret", node_async(this::executeWealthInterpretNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
                .addEdge("executeWealthInterpret", END);

        addCancelNode(graph);

        // ask节点条件路由
        addAskConditionalEdges(graph, "askProductName");

        CompiledGraph compiled = graph.compile(createInterruptCompileConfig());

        log.info("[WealthInterpretGraph] Compiled successfully with interruptBefore + ask→END + cancel routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareExtractParams(state);
        if (cancelResult != null) return cancelResult;

        String userInput = getLatestInput(state);
        log.info("[WealthInterpretGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            mergeExtractedWithoutOverwrite(result, extracted, state);
            log.info("[WealthInterpretGraph.extractParams] extracted: {}", extracted);
        } catch (Exception e) {
            log.error("[WealthInterpretGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareParamRouter(state);
        if (cancelResult != null) return cancelResult;

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

    private Map<String, Object> askProductNameLogic(OverAllState state) {
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
            }
            // LLM返回null: 用户确实没指定,不猜测,让paramRouter继续追问
        } catch (Exception e) {
            log.error("[WealthInterpretGraph.askProductName] Extraction failed", e);
            // LLM调用失败: 同样不设置,让paramRouter继续追问
        }
        result.put("_latestUserInput", "");
        return result;
    }

    /**
     * 流式理财产品解读节点 — 返回Map中包含Flux<ChatResponse>
     *
     * Spring AI Alibaba Graph的NodeExecutor.getEmbedFlux()会自动检测Map中的Flux值，
     * 将每个ChatResponse包装为StreamingOutput(GRAPH_NODE_STREAMING/FINISHED)，
     * GES的mapStreamingOutput()再转为StreamChunk.chunk()推给前端。
     *
     * 降级逻辑: 如果LLM调用初始化失败，降级为mock服务返回(非流式)
     */
    private Map<String, Object> executeWealthInterpretNode(OverAllState state) {
        String productName = getStringValue(state, "wealthInterpret.productName");
        log.info("[WealthInterpretGraph.executeWealthInterpret] productName={}, mode=STREAMING", productName);

        Map<String, Object> result = new HashMap<>();
        result.put("_outputType", "TEXT");
        result.put("_isFinal", true);

        try {
            String prompt = buildWealthInterpretPrompt(productName);
            Flux<ChatResponse> responseFlux = wealthInterpretChatClient.prompt()
                    .user(prompt)
                    .stream()
                    .chatResponse();

            // Map中的Flux值会被NodeExecutor.getEmbedFlux()自动检测
            // 每个ChatResponse → StreamingOutput(GRAPH_NODE_STREAMING) → StreamChunk.chunk()
            result.put("streaming_output", responseFlux);
        } catch (Exception e) {
            log.warn("[WealthInterpretGraph.executeWealthInterpret] LLM streaming init failed, falling back to mock", e);
            // 降级: 使用mock服务返回完整结果(非流式)
            MockBankingService.WealthInterpretResult interpretResult = mockBankingService.wealthInterpret(productName);
            result.put("_outputContent", interpretResult.message());
        }

        return result;
    }

    /**
     * 构建理财产品解读prompt — 引导LLM生成详细的、结构化的产品解读
     */
    private String buildWealthInterpretPrompt(String productName) {
        return """
            你是一位专业的银行理财产品分析师，请为用户详细解读以下理财产品。

            产品名称: %s

            解读要求:
            1. 产品类型及定位
            2. 风险等级评估(R1-R5)
            3. 历史年化收益率范围
            4. 投资期限及流动性
            5. 起购金额
            6. 底层资产配置
            7. 适合的投资者类型
            8. 投资建议和注意事项

            请用专业但易懂的语言，详细解读该产品，让普通投资者也能理解。
            如果不确定具体数据，请基于产品名称给出合理的分析框架和参考范围。
            字数控制在200个以内
            """.formatted(productName);
    }

    // ==================== SubAgent数据快照 ====================

    @Override
    protected Map<String, Object> extractSubAgentDataSnapshot(OverAllState state) {
        Map<String, Object> data = new HashMap<>();
        String productName = getStringValue(state, "wealthInterpret.productName");
        if (productName != null && !productName.isEmpty()) data.put("productName", productName);
        return data;
    }

    // ==================== WealthInterpret特有方法 ====================

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个银行理财产品解读参数提取器。从用户输入中提取用户想了解的产品或标的信息。

            用户输入: %s

            提取规则:
            - productName: 用户想了解的产品/标的名称
              - 银行理财产品: 如"稳利宝"、"汇添富"、"天天利"、"安心宝"、"朝朝盈"等
              - 基金/股票名称: 如"科技成长基金"、"通富微电"、"贵州茅台"等
              - 行业/概念名称: 如"新能源"、"半导体"、"白酒"等
            - 只提取用户明确提到的名称,不猜测
            - 如果用户说"这个产品"、"那个"等指代,输出null
            - 用户可能用口语化表达提到产品,如"通富微电最近不错"中的"通富微电","有个叫朝朝盈的"中的"朝朝盈"

            严格输出JSON:
            {
              "productName": "产品/标的名称或null"
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
