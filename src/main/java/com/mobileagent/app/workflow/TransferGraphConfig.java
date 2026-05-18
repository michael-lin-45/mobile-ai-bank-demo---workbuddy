package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.mock.MockBankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
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
 * askReceiver/askAmount → paramRouter (循环)
 *
 * 关键设计:
 * - 使用interruptBefore在提问节点前中断
 * - OverAllState用key前缀"transfer."隔离子智能体状态
 * - 注册所有KeyStrategy规避Bug#4519(resume状态丢失)
 */
@Slf4j
@Configuration
public class TransferGraphConfig {

    private final ChatModel chatModel;
    private final MockBankingService mockBankingService;
    private final ObjectMapper objectMapper;

    @Value("${routing.model.planning-model:qwen-plus}")
    private String planningModel;

    public TransferGraphConfig(ChatModel chatModel, MockBankingService mockBankingService) {
        this.chatModel = chatModel;
        this.mockBankingService = mockBankingService;
        this.objectMapper = new ObjectMapper();
    }

    @Bean("transferGraph")
    public CompiledGraph transferGraph() throws GraphStateException {
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            // 公共keys
            strategies.put("messages", new AppendStrategy());
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("_question", new ReplaceStrategy());
            strategies.put("_paramName", new ReplaceStrategy());
            strategies.put("_outputContent", new ReplaceStrategy());
            strategies.put("_outputType", new ReplaceStrategy());
            strategies.put("_isFinal", new ReplaceStrategy());
            // Transfer专用keys
            strategies.put("transfer.receiver", new ReplaceStrategy());
            strategies.put("transfer.amount", new ReplaceStrategy());
            strategies.put("transfer.purpose", new ReplaceStrategy());
            return strategies;
        };

        StateGraph graph = new StateGraph(keyStrategyFactory)
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askReceiver", node_async(this::askReceiverNode))
                .addNode("askAmount", node_async(this::askAmountNode))
                .addNode("executeTransfer", node_async(this::executeTransferNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter",
                        edge_async(state -> {
                            Object param = state.value("_paramName").orElse("ALL_GOOD");
                            return param.toString();
                        }),
                        Map.of(
                                "ASK_RECEIVER", "askReceiver",
                                "ASK_AMOUNT", "askAmount",
                                "ALL_GOOD", "executeTransfer"
                        ))
                // ask节点条件路由: 有用户输入→paramRouter(继续处理), 无用户输入→END(等待回答)
                .addConditionalEdges("askReceiver",
                        edge_async(state -> {
                            String input = getLatestInput(state);
                            boolean hasInput = input != null && !input.isEmpty();
                            log.info("[TransferGraph.askReceiver→route] hasInput={} → {}", hasInput, hasInput ? "CONTINUE" : "WAIT");
                            return hasInput ? "CONTINUE" : "WAIT";
                        }),
                        Map.of("CONTINUE", "paramRouter", "WAIT", END))
                .addConditionalEdges("askAmount",
                        edge_async(state -> {
                            String input = getLatestInput(state);
                            boolean hasInput = input != null && !input.isEmpty();
                            log.info("[TransferGraph.askAmount→route] hasInput={} → {}", hasInput, hasInput ? "CONTINUE" : "WAIT");
                            return hasInput ? "CONTINUE" : "WAIT";
                        }),
                        Map.of("CONTINUE", "paramRouter", "WAIT", END))
                .addEdge("executeTransfer", END);

        SaverConfig saverConfig = SaverConfig.builder()
                .register(new MemorySaver())
                .build();

        CompiledGraph compiled = graph.compile(CompileConfig.builder()
                .saverConfig(saverConfig)
                .interruptBefore("askReceiver", "askAmount")
                .recursionLimit(50)
                .build());

        log.info("[TransferGraph] Compiled successfully with interruptBefore + ask→END conditional routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    /**
     * 节点1: 参数提取 - 使用32B模型从用户输入中提取转账参数
     */
    private Map<String, Object> extractParamsNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[TransferGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            TransferExtractResult extracted = callExtractModel(userInput);
            if (extracted.receiver != null && !extracted.receiver.isEmpty()) {
                result.put("transfer.receiver", extracted.receiver);
            }
            if (extracted.amount != null) {
                result.put("transfer.amount", extracted.amount);
            }
            if (extracted.purpose != null && !extracted.purpose.isEmpty()) {
                result.put("transfer.purpose", extracted.purpose);
            }
            log.info("[TransferGraph.extractParams] extracted: receiver={}, amount={}, purpose={}",
                    extracted.receiver, extracted.amount, extracted.purpose);
        } catch (Exception e) {
            log.error("[TransferGraph.extractParams] LLM extraction failed", e);
            // 降级: 不填充参数,让paramRouter触发提问
        }
        return result;
    }

    /**
     * 节点2: 参数路由 - 检查缺失参数,设置提问话术
     */
    private Map<String, Object> paramRouterNode(OverAllState state) {
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

    /**
     * 节点3: 收款人补充 - 从用户回答中提取收款人
     * 无用户输入时: 返回空(将路由到END等待回答)
     * 有用户输入时: 提取参数,清空_latestUserInput(将路由到paramRouter继续处理)
     */
    private Map<String, Object> askReceiverNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[TransferGraph.askReceiver] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            // 无用户输入 = 检查点,不设置参数,路由到END等待回答
            log.info("[TransferGraph.askReceiver] No user input, will route to END");
            return result;
        }

        // 有用户输入 = resume后节点执行,提取参数
        try {
            TransferExtractResult extracted = callExtractModel(userInput);
            if (extracted.receiver != null && !extracted.receiver.isEmpty()) {
                result.put("transfer.receiver", extracted.receiver);
                log.info("[TransferGraph.askReceiver] Extracted receiver={}", extracted.receiver);
            } else {
                // Fallback: 直接用用户输入作为收款人(短回答场景)
                result.put("transfer.receiver", userInput.trim());
                log.info("[TransferGraph.askReceiver] Fallback: using raw input as receiver={}", userInput.trim());
            }
        } catch (Exception e) {
            log.error("[TransferGraph.askReceiver] Extraction failed", e);
            result.put("transfer.receiver", userInput.trim());
        }
        // 清空_latestUserInput,防止下一个ask节点误用旧输入
        result.put("_latestUserInput", "");
        return result;
    }

    /**
     * 节点4: 金额补充 - 从用户回答中提取金额
     * 无用户输入时: 返回空(将路由到END等待回答)
     * 有用户输入时: 提取参数,清空_latestUserInput(将路由到paramRouter继续处理)
     */
    private Map<String, Object> askAmountNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[TransferGraph.askAmount] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[TransferGraph.askAmount] No user input, will route to END");
            return result;
        }

        try {
            TransferExtractResult extracted = callExtractModel(userInput);
            if (extracted.amount != null) {
                result.put("transfer.amount", extracted.amount);
                log.info("[TransferGraph.askAmount] Extracted amount={}", extracted.amount);
            } else {
                // Fallback: 尝试直接解析数字
                BigDecimal directAmount = parseDirectAmount(userInput);
                if (directAmount != null) {
                    result.put("transfer.amount", directAmount);
                    log.info("[TransferGraph.askAmount] Direct parsed amount={}", directAmount);
                } else {
                    // 无法解析 → 不设置参数,清空输入,让paramRouter重新路由到askAmount→END
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
        // 清空_latestUserInput,防止下一个ask节点误用旧输入
        result.put("_latestUserInput", "");
        return result;
    }

    /**
     * 节点5: 执行转账 - 调用模拟银行服务
     */
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

    // ==================== 辅助方法 ====================

    private String getLatestInput(OverAllState state) {
        Object input = state.value("_latestUserInput").orElse(null);
        if (input != null && !input.toString().isEmpty()) {
            return input.toString();
        }
        // fallback: 从messages取最后一条
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(list.size() - 1));
        }
        return "";
    }

    private String getStringValue(OverAllState state, String key) {
        Object value = state.value(key).orElse(null);
        return value != null ? value.toString() : null;
    }

    /** 调用32B模型提取转账参数 */
    private TransferExtractResult callExtractModel(String userInput) {
        String prompt = buildExtractPrompt(userInput);
        ChatResponse response = chatModel.call(new Prompt(prompt));
        String content = response.getResult().getOutput().getText();
        return parseExtractResult(content);
    }

    private String buildExtractPrompt(String userInput) {
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

    private TransferExtractResult parseExtractResult(String content) {
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
            return new TransferExtractResult(receiver, amount, purpose);
        } catch (Exception e) {
            log.warn("[TransferGraph] Failed to parse extract result: {}", content, e);
            return new TransferExtractResult(null, null, null);
        }
    }

    private BigDecimal parseDirectAmount(String input) {
        if (input == null) return null;
        try {
            // 去掉"元"、"块"、"万"等单位
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

    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7);
        else if (trimmed.startsWith("```")) trimmed = trimmed.substring(3);
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        trimmed = trimmed.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private record TransferExtractResult(String receiver, BigDecimal amount, String purpose) {}
}
