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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 账单查询Graph配置 - 构建BillQueryGraph的StateGraph并编译
 *
 * 节点流程:
 * START → extractParams → paramRouter → askTime/askType (interruptBefore)
 *                                         ↘ executeBillQuery → END
 * askTime/askType → paramRouter (循环)
 *
 * 仿照TransferGraph,提取timePeriod和expenseType参数
 */
@Slf4j
@Configuration
public class BillQueryGraphConfig {

    private final ChatModel chatModel;
    private final MockBankingService mockBankingService;
    private final ObjectMapper objectMapper;

    @Value("${routing.model.planning-model:qwen-plus}")
    private String planningModel;

    public BillQueryGraphConfig(ChatModel chatModel, MockBankingService mockBankingService) {
        this.chatModel = chatModel;
        this.mockBankingService = mockBankingService;
        this.objectMapper = new ObjectMapper();
    }

    @Bean("billQueryGraph")
    public CompiledGraph billQueryGraph() throws GraphStateException {
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
            // Bill专用keys
            strategies.put("bill.timePeriod", new ReplaceStrategy());
            strategies.put("bill.expenseType", new ReplaceStrategy());
            return strategies;
        };

        StateGraph graph = new StateGraph(keyStrategyFactory)
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askTime", node_async(this::askTimeNode))
                .addNode("askType", node_async(this::askTypeNode))
                .addNode("executeBillQuery", node_async(this::executeBillQueryNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter",
                        edge_async(state -> {
                            Object param = state.value("_paramName").orElse("ALL_GOOD");
                            return param.toString();
                        }),
                        Map.of(
                                "ASK_TIME", "askTime",
                                "ASK_TYPE", "askType",
                                "ALL_GOOD", "executeBillQuery"
                        ))
                // ask节点条件路由: 有用户输入→paramRouter(继续处理), 无用户输入→END(等待回答)
                .addConditionalEdges("askTime",
                        edge_async(state -> {
                            String input = getLatestInput(state);
                            boolean hasInput = input != null && !input.isEmpty();
                            log.info("[BillQueryGraph.askTime→route] hasInput={} → {}", hasInput, hasInput ? "CONTINUE" : "WAIT");
                            return hasInput ? "CONTINUE" : "WAIT";
                        }),
                        Map.of("CONTINUE", "paramRouter", "WAIT", END))
                .addConditionalEdges("askType",
                        edge_async(state -> {
                            String input = getLatestInput(state);
                            boolean hasInput = input != null && !input.isEmpty();
                            log.info("[BillQueryGraph.askType→route] hasInput={} → {}", hasInput, hasInput ? "CONTINUE" : "WAIT");
                            return hasInput ? "CONTINUE" : "WAIT";
                        }),
                        Map.of("CONTINUE", "paramRouter", "WAIT", END))
                .addEdge("executeBillQuery", END);

        SaverConfig saverConfig = SaverConfig.builder()
                .register(new MemorySaver())
                .build();

        CompiledGraph compiled = graph.compile(CompileConfig.builder()
                .saverConfig(saverConfig)
                .interruptBefore("askTime", "askType")
                .recursionLimit(50)
                .build());

        log.info("[BillQueryGraph] Compiled successfully with interruptBefore + ask→END conditional routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[BillQueryGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            BillExtractResult extracted = callExtractModel(userInput);
            if (extracted.timePeriod != null && !extracted.timePeriod.isEmpty()) {
                result.put("bill.timePeriod", extracted.timePeriod);
            }
            if (extracted.expenseType != null && !extracted.expenseType.isEmpty()) {
                result.put("bill.expenseType", extracted.expenseType);
            }
            log.info("[BillQueryGraph.extractParams] extracted: timePeriod={}, expenseType={}",
                    extracted.timePeriod, extracted.expenseType);
        } catch (Exception e) {
            log.error("[BillQueryGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        String timePeriod = getStringValue(state, "bill.timePeriod");
        String expenseType = getStringValue(state, "bill.expenseType");

        Map<String, Object> result = new HashMap<>();
        if (timePeriod == null || timePeriod.isEmpty()) {
            result.put("_question", "请问您要查询哪个时间段的账单？(如:上个月、最近一周)");
            result.put("_paramName", "ASK_TIME");
            log.info("[BillQueryGraph.paramRouter] Missing timePeriod → ASK_TIME");
        } else if (expenseType == null || expenseType.isEmpty()) {
            result.put("_question", "请问您要查询哪种类型的支出？(如:餐饮、交通、全部支出)");
            result.put("_paramName", "ASK_TYPE");
            log.info("[BillQueryGraph.paramRouter] Missing expenseType → ASK_TYPE");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[BillQueryGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askTimeNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[BillQueryGraph.askTime] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            // 无用户输入 = 检查点,路由到END等待回答
            log.info("[BillQueryGraph.askTime] No user input, will route to END");
            return result;
        }

        // 有用户输入 = resume后节点执行,提取参数
        try {
            BillExtractResult extracted = callExtractModel(userInput);
            if (extracted.timePeriod != null && !extracted.timePeriod.isEmpty()) {
                result.put("bill.timePeriod", extracted.timePeriod);
                log.info("[BillQueryGraph.askTime] Extracted timePeriod={}", extracted.timePeriod);
            } else {
                // Fallback: 直接用用户输入
                result.put("bill.timePeriod", userInput.trim());
                log.info("[BillQueryGraph.askTime] Fallback: using raw input as timePeriod={}", userInput.trim());
            }
        } catch (Exception e) {
            log.error("[BillQueryGraph.askTime] Extraction failed", e);
            result.put("bill.timePeriod", userInput.trim());
        }
        // 清空_latestUserInput,防止下一个ask节点误用旧输入
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> askTypeNode(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[BillQueryGraph.askType] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[BillQueryGraph.askType] No user input, will route to END");
            return result;
        }

        try {
            BillExtractResult extracted = callExtractModel(userInput);
            if (extracted.expenseType != null && !extracted.expenseType.isEmpty()) {
                result.put("bill.expenseType", extracted.expenseType);
                log.info("[BillQueryGraph.askType] Extracted expenseType={}", extracted.expenseType);
            } else {
                // Fallback: 直接用用户输入
                result.put("bill.expenseType", userInput.trim());
                log.info("[BillQueryGraph.askType] Fallback: using raw input as expenseType={}", userInput.trim());
            }
        } catch (Exception e) {
            log.error("[BillQueryGraph.askType] Extraction failed", e);
            result.put("bill.expenseType", userInput.trim());
        }
        // 清空_latestUserInput,防止下一个ask节点误用旧输入
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> executeBillQueryNode(OverAllState state) {
        String timePeriod = getStringValue(state, "bill.timePeriod");
        String expenseType = getStringValue(state, "bill.expenseType");

        log.info("[BillQueryGraph.executeBillQuery] timePeriod={}, expenseType={}", timePeriod, expenseType);

        MockBankingService.BillQueryResult queryResult = mockBankingService.queryBill(timePeriod, expenseType);

        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", queryResult.message());
        result.put("_outputType", "TEXT");
        result.put("_isFinal", true);
        return result;
    }

    // ==================== 辅助方法 ====================

    private String getLatestInput(OverAllState state) {
        Object input = state.value("_latestUserInput").orElse(null);
        if (input != null && !input.toString().isEmpty()) {
            return input.toString();
        }
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

    private BillExtractResult callExtractModel(String userInput) {
        String prompt = buildExtractPrompt(userInput);
        ChatResponse response = chatModel.call(new Prompt(prompt));
        String content = response.getResult().getOutput().getText();
        return parseExtractResult(content);
    }

    private String buildExtractPrompt(String userInput) {
        return """
            你是一个银行账单查询参数提取器。从用户输入中提取账单查询相关参数。
            
            用户输入: %s
            
            提取规则:
            - timePeriod: 时间范围,保留用户的原始表述,如"上个月"、"最近一周"、"昨天"、"2024年1月"
            - expenseType: 支出/收入类型,如"餐饮"、"交通"、"支出"、"全部"、"收入"
            - 只提取用户明确提到的参数,不猜测
            - 如果用户说"全部"、"所有"等,expenseType设为"支出"
            
            严格输出JSON:
            {
              "timePeriod": "时间范围或null",
              "expenseType": "支出类型或null"
            }
            """.formatted(userInput);
    }

    private BillExtractResult parseExtractResult(String content) {
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            String timePeriod = node.has("timePeriod") && !node.get("timePeriod").isNull()
                    ? node.get("timePeriod").asText() : null;
            String expenseType = node.has("expenseType") && !node.get("expenseType").isNull()
                    ? node.get("expenseType").asText() : null;
            return new BillExtractResult(timePeriod, expenseType);
        } catch (Exception e) {
            log.warn("[BillQueryGraph] Failed to parse extract result: {}", content, e);
            return new BillExtractResult(null, null);
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

    private record BillExtractResult(String timePeriod, String expenseType) {}
}
