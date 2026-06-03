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

import java.util.ArrayList;
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
 *                                         ↘ cancelExecution → END (_cancelSignal)
 * askTime/askType → paramRouter (循环)
 */
@Slf4j
@Configuration
public class BillQueryGraphConfig extends AbstractGraphConfig {

    private final MockBankingService mockBankingService;
    private final SubGraphRegistry subGraphRegistry;

    public BillQueryGraphConfig(@Qualifier("paramExtractChatModel") ChatModel chatModel,
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
        return "BillQueryGraph";
    }

    @Override
    protected String getCancelDetectionContext() {
        return "正在向用户询问账单查询条件(时间范围/收支类型)";
    }

    @Override
    protected List<String> getCancelKeywords() {
        List<String> keywords = new ArrayList<>(super.getCancelKeywords());
        keywords.addAll(List.of("不查了", "别查了", "取消查询", "不想查了", "不用查了"));
        return keywords;
    }

    @Override
    protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
        strategies.put("bill.timePeriod", new ReplaceStrategy());
        strategies.put("bill.expenseType", new ReplaceStrategy());
    }

    @Bean("billQueryGraph")
    public CompiledGraph billQueryGraph() throws GraphStateException {
        Map<String, String> paramEdges = new HashMap<>(Map.of(
                "ASK_TIME", "askTime",
                "ASK_TYPE", "askType",
                "ALL_GOOD", "executeBillQuery"
        ));
        addCancelEdge(paramEdges);

        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askTime", askNode("askTime", this::askTimeLogic))
                .addNode("askType", askNode("askType", this::askTypeLogic))
                .addNode("executeBillQuery", node_async(this::executeBillQueryNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
                .addEdge("executeBillQuery", END);

        addCancelNode(graph);

        // ask节点条件路由
        addAskConditionalEdges(graph, "askTime");
        addAskConditionalEdges(graph, "askType");

        CompiledGraph compiled = graph.compile(createInterruptCompileConfig());
        subGraphRegistry.bindGraph("BILL_QUERY", compiled);

        log.info("[BillQueryGraph] Compiled successfully with interruptBefore + ask→END + cancel routing");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareExtractParams(state);
        if (cancelResult != null) return cancelResult;

        String userInput = getLatestInput(state);
        log.info("[BillQueryGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            mergeExtractedWithoutOverwrite(result, extracted, state);
            log.info("[BillQueryGraph.extractParams] extracted: {}", extracted);
        } catch (Exception e) {
            log.error("[BillQueryGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareParamRouter(state);
        if (cancelResult != null) return cancelResult;

        String timePeriod = getStringValue(state, "bill.timePeriod");
        String expenseType = getStringValue(state, "bill.expenseType");

        Map<String, Object> result = new HashMap<>();
        if (timePeriod == null || timePeriod.isEmpty()) {
            result.put("_question", "请问您要查询哪个时间段的账单？(如:上个月、最近一周)");
            result.put("_paramName", "ASK_TIME");
            log.info("[BillQueryGraph.paramRouter] Missing timePeriod → ASK_TIME");
        } else if (expenseType == null || expenseType.isEmpty()) {
            result.put("_question", "请问您要查询支出、收入还是收支？(如:支出、收入、收支)");
            result.put("_paramName", "ASK_TYPE");
            log.info("[BillQueryGraph.paramRouter] Missing expenseType → ASK_TYPE");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[BillQueryGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askTimeLogic(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[BillQueryGraph.askTime] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[BillQueryGraph.askTime] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String timePeriod = (String) extracted.get("bill.timePeriod");
            if (timePeriod != null && !timePeriod.isEmpty()) {
                result.put("bill.timePeriod", timePeriod);
                log.info("[BillQueryGraph.askTime] Extracted timePeriod={}", timePeriod);
            }
            String expenseType = (String) extracted.get("bill.expenseType");
            if (expenseType != null && !expenseType.isEmpty()) {
                result.put("bill.expenseType", expenseType);
                log.info("[BillQueryGraph.askTime] Also extracted expenseType={}", expenseType);
            }
        } catch (Exception e) {
            log.error("[BillQueryGraph.askTime] Extraction failed", e);
        }
        result.put("_latestUserInput", "");
        return result;
    }

    private Map<String, Object> askTypeLogic(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[BillQueryGraph.askType] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            log.info("[BillQueryGraph.askType] No user input, will route to END");
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String expenseType = (String) extracted.get("bill.expenseType");
            if (expenseType != null && !expenseType.isEmpty()) {
                result.put("bill.expenseType", expenseType);
                log.info("[BillQueryGraph.askType] Extracted expenseType={}", expenseType);
            }
            String timePeriod = (String) extracted.get("bill.timePeriod");
            if (timePeriod != null && !timePeriod.isEmpty()) {
                result.put("bill.timePeriod", timePeriod);
                log.info("[BillQueryGraph.askType] Also extracted timePeriod={}", timePeriod);
            }
        } catch (Exception e) {
            log.error("[BillQueryGraph.askType] Extraction failed", e);
        }
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

    // ==================== SubAgent数据快照 ====================

    @Override
    protected Map<String, Object> extractSubAgentDataSnapshot(OverAllState state) {
        Map<String, Object> data = new HashMap<>();
        String timePeriod = getStringValue(state, "bill.timePeriod");
        if (timePeriod != null && !timePeriod.isEmpty()) data.put("timePeriod", timePeriod);
        String expenseType = getStringValue(state, "bill.expenseType");
        if (expenseType != null && !expenseType.isEmpty()) data.put("expenseType", expenseType);
        return data;
    }

    // ==================== BillQuery特有方法 ====================

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个银行账单查询参数提取器。从用户输入中提取账单查询相关参数。
            
            用户输入: %s
            
            提取规则:
            - timePeriod: 时间范围,保留用户的原始表述,如"上个月"、"最近一周"、"昨天"、"2024年1月"
            - expenseType: 收支类型,只有三种取值:
              · "支出" - 用户想查支出/开销/花费/开支/消费
              · "收入" - 用户想查收入
              · "收支" - 用户想同时查支出和收入(如"收支"、"都查"、"全部")
            - expenseType提取原则: 关注用户表达的查询意图,而非仅仅匹配关键词
              · "上个月支出有点多,想看看原因" → expenseType="支出" (用户明确关注支出)
              · "最近花了多少钱" → expenseType="支出" (花了=支出)
              · "我这个月开支情况" → expenseType="支出" (开支=支出)
              · "上个月收入怎么样" → expenseType="收入" (关注收入)
              · "查账单" → expenseType=null (未指定类型)
              · "全部""都查""收支明细" → expenseType="收支"
            - 不要凭空猜测: 如果用户输入中确实没有任何收支类型的线索(连间接表达都没有),才输出null
            
            严格输出JSON:
            {
              "timePeriod": "时间范围或null",
              "expenseType": "支出/收入/收支 或null"
            }
            """.formatted(userInput);
    }

    @Override
    protected Map<String, Object> parseExtractResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            String timePeriod = node.has("timePeriod") && !node.get("timePeriod").isNull()
                    ? node.get("timePeriod").asText() : null;
            String expenseType = node.has("expenseType") && !node.get("expenseType").isNull()
                    ? node.get("expenseType").asText() : null;

            if (timePeriod != null && !timePeriod.isEmpty()) result.put("bill.timePeriod", timePeriod);
            if (expenseType != null && !expenseType.isEmpty()) result.put("bill.expenseType", expenseType);
        } catch (Exception e) {
            log.warn("[BillQueryGraph] Failed to parse extract result: {}", content, e);
        }
        return result;
    }
}
