package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.mock.MockBankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
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
 * 转账Graph配置 - 构建TransferGraph的StateGraph并编译
 *
 * 节点流程:
 * START → extractParams → paramRouter → askReceiver/askAmount (interruptBefore)
 *                                         ↘ executeTransfer → END
 *                                         ↘ cancelExecution → END (_cancelSignal)
 * askReceiver/askAmount → paramRouter (循环)
 */
@Slf4j
@Configuration
public class TransferGraphConfig extends AbstractGraphConfig {

    private final MockBankingService mockBankingService;

    public TransferGraphConfig(ChatModel chatModel, MockBankingService mockBankingService) {
        super(chatModel);
        this.mockBankingService = mockBankingService;
    }

    @Override
    protected String getGraphName() {
        return "TransferGraph";
    }

    @Override
    protected String getCancelDetectionContext() {
        return "正在向用户询问转账信息(收款人/金额)";
    }

    @Override
    protected List<String> getCancelKeywords() {
        List<String> keywords = new ArrayList<>(super.getCancelKeywords());
        keywords.addAll(List.of("不转了", "别转了", "取消转账", "不想转了", "不用转了"));
        return keywords;
    }

    @Override
    protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
        strategies.put("transfer.receiver", new ReplaceStrategy());
        strategies.put("transfer.amount", new ReplaceStrategy());
        strategies.put("transfer.purpose", new ReplaceStrategy());
    }

    @Bean("transferGraph")
    public CompiledGraph transferGraph() throws GraphStateException {
        Map<String, String> paramEdges = new HashMap<>(Map.of(
                "ASK_RECEIVER", "askReceiver",
                "ASK_AMOUNT", "askAmount",
                "ALL_GOOD", "executeTransfer"
        ));
        addCancelEdge(paramEdges);

        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askReceiver", node_async(this::askReceiverNode))
                .addNode("askAmount", node_async(this::askAmountNode))
                .addNode("executeTransfer", node_async(this::executeTransferNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
                .addEdge("executeTransfer", END);

        addCancelNode(graph);

        // ask节点条件路由
        addAskConditionalEdges(graph, "askReceiver");
        addAskConditionalEdges(graph, "askAmount");

        CompiledGraph compiled = graph.compile(createCompileConfig("askReceiver", "askAmount"));

        log.info("[TransferGraph] Compiled successfully with interruptBefore + ask→END + cancel routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareExtractParams(state);
        if (cancelResult != null) return cancelResult;

        String userInput = getLatestInput(state);
        log.info("[TransferGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            result.putAll(extracted);
            log.info("[TransferGraph.extractParams] extracted: {}", extracted);
        } catch (Exception e) {
            log.error("[TransferGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareParamRouter(state);
        if (cancelResult != null) return cancelResult;

        String receiver = getStringValue(state, "transfer.receiver");
        Object amountObj = state.value("transfer.amount").orElse(null);
        String amount = amountObj != null ? amountObj.toString() : null;

        Map<String, Object> result = new HashMap<>();
        if (receiver == null || receiver.isEmpty()) {
            result.put("_question", "请问您要转给谁？");
            result.put("_paramName", "ASK_RECEIVER");
            log.info("[TransferGraph.paramRouter] Missing receiver → ASK_RECEIVER");
        } else if (amount == null || amount.isEmpty()) {
            result.put("_question", "请问您要转多少金额？");
            result.put("_paramName", "ASK_AMOUNT");
            log.info("[TransferGraph.paramRouter] Missing amount → ASK_AMOUNT");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[TransferGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askReceiverNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[TransferGraph.askReceiver] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[TransferGraph.askReceiver] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String receiver = (String) extracted.get("transfer.receiver");
            if (receiver != null && !receiver.isEmpty()) {
                result.put("transfer.receiver", receiver);
                log.info("[TransferGraph.askReceiver] Extracted receiver={}", receiver);
            } else {
                result.put("transfer.receiver", userInput.trim());
                log.info("[TransferGraph.askReceiver] Fallback: using raw input as receiver={}", userInput.trim());
            }
        } catch (Exception e) {
            log.error("[TransferGraph.askReceiver] Extraction failed", e);
            result.put("transfer.receiver", userInput.trim());
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> askAmountNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[TransferGraph.askAmount] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[TransferGraph.askAmount] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            Object amount = extracted.get("transfer.amount");
            if (amount != null) {
                result.put("transfer.amount", amount);
                log.info("[TransferGraph.askAmount] Extracted amount={}", amount);
            } else {
                BigDecimal directAmount = parseDirectAmount(userInput);
                if (directAmount != null) {
                    result.put("transfer.amount", directAmount);
                    log.info("[TransferGraph.askAmount] Direct parsed amount={}", directAmount);
                } else {
                    log.warn("[TransferGraph.askAmount] Could not parse amount from: {}", userInput);
                }
            }
        } catch (Exception e) {
            log.error("[TransferGraph.askAmount] Extraction failed", e);
            BigDecimal directAmount = parseDirectAmount(userInput);
            if (directAmount != null) {
                result.put("transfer.amount", directAmount);
            }
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> executeTransferNode(OverAllState state) {
        String receiver = getStringValue(state, "transfer.receiver");
        Object amountObj = state.value("transfer.amount").orElse(null);
        BigDecimal amount = amountObj instanceof BigDecimal ? (BigDecimal) amountObj
                : amountObj != null ? new BigDecimal(amountObj.toString()) : BigDecimal.ZERO;
        String purpose = getStringValue(state, "transfer.purpose");

        log.info("[TransferGraph.executeTransfer] receiver={}, amount={}, purpose={}", receiver, amount, purpose);

        MockBankingService.TransferResult transferResult = mockBankingService.executeTransfer(receiver, amount, purpose);

        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", transferResult.message());
        result.put("_outputType", "CONFIRMATION");
        result.put("_isFinal", true);
        return result;
    }

    // ==================== Transfer特有方法 ====================

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个银行转账参数提取器。从用户输入中提取转账相关参数。
            
            用户输入: %s
            
            提取规则:
            - receiver: 收款人姓名,如"张三"、"李四"
            - amount: 转账金额(数字),如500、1000.50
            - purpose: 用途(可选),如"房租"、"还款"
            - 只提取用户明确提到的参数,不猜测
            
            严格输出JSON:
            {
              "receiver": "收款人姓名或null",
              "amount": 金额数字或null,
              "purpose": "用途或null"
            }
            """.formatted(userInput);
    }

    @Override
    protected Map<String, Object> parseExtractResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            String receiver = node.has("receiver") && !node.get("receiver").isNull() ? node.get("receiver").asText() : null;
            BigDecimal amount = null;
            if (node.has("amount") && !node.get("amount").isNull()) {
                String amountStr = node.get("amount").asText();
                amount = parseDirectAmount(amountStr);
            }
            String purpose = node.has("purpose") && !node.get("purpose").isNull() ? node.get("purpose").asText() : null;

            if (receiver != null && !receiver.isEmpty()) result.put("transfer.receiver", receiver);
            if (amount != null) result.put("transfer.amount", amount);
            if (purpose != null && !purpose.isEmpty()) result.put("transfer.purpose", purpose);
        } catch (Exception e) {
            log.warn("[TransferGraph] Failed to parse extract result: {}", content, e);
        }
        return result;
    }

    private BigDecimal parseDirectAmount(String input) {
        if (input == null) return null;
        try {
            String cleaned = input.replaceAll("[元块]", "").trim();
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
