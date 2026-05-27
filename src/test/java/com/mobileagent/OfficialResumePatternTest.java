package com.mobileagent;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最小验证测试：spring-ai-alibaba 框架的 interruptBefore + updateState + resume 机制
 *
 * 验证目标：
 * 1. resume 后中断节点是否正常执行？
 * 2. resume 后遇到第二个 interruptBefore 节点是否正常中断？
 * 3. 第二次 resume 是否正常（多轮提问）？
 * 4. updateState 注入 _latestUserInput 后 ask 节点能否读到？
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OfficialResumePatternTest {

    private CompiledGraph graph;
    private final String threadId = "test-thread-001";

    @BeforeEach
    void setUp() throws Exception {
        graph = buildDualInterruptGraph();
    }

    private RunnableConfig threadConfig(String tid) {
        return RunnableConfig.builder().threadId(tid).build();
    }

    // ==================== Graph 构建 ====================

    private CompiledGraph buildDualInterruptGraph() throws Exception {
        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy());
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("_question", new ReplaceStrategy());
            strategies.put("_paramName", new ReplaceStrategy());
            strategies.put("receiver", new ReplaceStrategy());
            strategies.put("amount", new ReplaceStrategy());
            strategies.put("_outputContent", new ReplaceStrategy());
            return strategies;
        };

        StateGraph builder = new StateGraph(keyStrategyFactory)
                .addNode("extractParams", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    if (input.contains("妈")) result.put("receiver", "我妈");
                    if (input.matches(".*\\d+.*")) result.put("amount", input.replaceAll("[^0-9]", ""));
                    System.out.println("[extractParams] input=" + input + " -> " + result);
                    return result;
                }))
                .addNode("paramRouter", node_async(state -> {
                    String receiver = (String) state.value("receiver").orElse(null);
                    String amount = (String) state.value("amount").orElse(null);
                    Map<String, Object> result = new HashMap<>();
                    if (receiver == null || receiver.isEmpty()) {
                        result.put("_question", "请问您要转给谁？");
                        result.put("_paramName", "ASK_RECEIVER");
                    } else if (amount == null || amount.isEmpty()) {
                        result.put("_question", "请问您要转多少金额？");
                        result.put("_paramName", "ASK_AMOUNT");
                    } else {
                        result.put("_question", null);
                        result.put("_paramName", "ALL_GOOD");
                    }
                    System.out.println("[paramRouter] receiver=" + receiver + " amount=" + amount + " -> " + result.get("_paramName"));
                    return result;
                }))
                .addNode("askReceiver", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    System.out.println("[askReceiver] _latestUserInput=" + input);
                    if (input != null && !input.isEmpty()) result.put("receiver", input);
                    result.put("_latestUserInput", "");
                    return result;
                }))
                .addNode("askAmount", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    System.out.println("[askAmount] _latestUserInput=" + input);
                    if (input != null && !input.isEmpty()) {
                        String num = input.replaceAll("[^0-9]", "");
                        result.put("amount", num.isEmpty() ? input : num);
                    }
                    result.put("_latestUserInput", "");
                    return result;
                }))
                .addNode("executeAction", node_async(state -> {
                    String receiver = (String) state.value("receiver").orElse("unknown");
                    String amount = (String) state.value("amount").orElse("0");
                    System.out.println("[executeAction] Transfer done! receiver=" + receiver + " amount=" + amount);
                    return Map.of("_outputContent", "transferred " + amount + " to " + receiver);
                }))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter",
                        com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state ->
                                (String) state.value("_paramName").orElse("ALL_GOOD")),
                        Map.of("ASK_RECEIVER", "askReceiver", "ASK_AMOUNT", "askAmount", "ALL_GOOD", "executeAction"))
                .addEdge("executeAction", END);

        builder.addConditionalEdges("askReceiver",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));
        builder.addConditionalEdges("askAmount",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));

        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptBefore("askReceiver", "askAmount")
                .build();

        return builder.compile(compileConfig);
    }

    private CompiledGraph buildBillQueryGraph() throws Exception {
        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy());
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("_question", new ReplaceStrategy());
            strategies.put("_paramName", new ReplaceStrategy());
            strategies.put("timePeriod", new ReplaceStrategy());
            strategies.put("expenseType", new ReplaceStrategy());
            strategies.put("_outputContent", new ReplaceStrategy());
            return strategies;
        };

        StateGraph builder = new StateGraph(keyStrategyFactory)
                .addNode("extractParams", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    if (input.contains("month") || input.contains("yue")) result.put("timePeriod", input);
                    if (input.contains("expense") || input.contains("income")) result.put("expenseType", input);
                    System.out.println("[Bill.extractParams] input=" + input + " -> " + result);
                    return result;
                }))
                .addNode("paramRouter", node_async(state -> {
                    String tp = (String) state.value("timePeriod").orElse(null);
                    String et = (String) state.value("expenseType").orElse(null);
                    Map<String, Object> result = new HashMap<>();
                    if (tp == null || tp.isEmpty()) {
                        result.put("_question", "Which time period?");
                        result.put("_paramName", "ASK_TIME");
                    } else if (et == null || et.isEmpty()) {
                        result.put("_question", "Which type?");
                        result.put("_paramName", "ASK_TYPE");
                    } else {
                        result.put("_question", null);
                        result.put("_paramName", "ALL_GOOD");
                    }
                    System.out.println("[Bill.paramRouter] tp=" + tp + " et=" + et + " -> " + result.get("_paramName"));
                    return result;
                }))
                .addNode("askTime", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    System.out.println("[Bill.askTime] _latestUserInput=" + input);
                    if (input != null && !input.isEmpty()) result.put("timePeriod", input);
                    result.put("_latestUserInput", "");
                    return result;
                }))
                .addNode("askType", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    System.out.println("[Bill.askType] _latestUserInput=" + input);
                    if (input != null && !input.isEmpty()) result.put("expenseType", input);
                    result.put("_latestUserInput", "");
                    return result;
                }))
                .addNode("executeBillQuery", node_async(state -> {
                    String tp = (String) state.value("timePeriod").orElse("unknown");
                    String et = (String) state.value("expenseType").orElse("unknown");
                    System.out.println("[Bill.executeBillQuery] tp=" + tp + " et=" + et);
                    return Map.of("_outputContent", "bill query done: " + tp + " " + et);
                }))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter",
                        com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state ->
                                (String) state.value("_paramName").orElse("ALL_GOOD")),
                        Map.of("ASK_TIME", "askTime", "ASK_TYPE", "askType", "ALL_GOOD", "executeBillQuery"))
                .addEdge("executeBillQuery", END);

        builder.addConditionalEdges("askTime",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));
        builder.addConditionalEdges("askType",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));

        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptBefore("askTime", "askType")
                .build();

        return builder.compile(compileConfig);
    }

    // ==================== Test 1: First execution interrupts before askReceiver ====================

    @Test
    @Order(1)
    void test1_firstExecution_interruptsBeforeAskReceiver() throws Exception {
        System.out.println("\n===== Test1: First execution -> interrupt before askReceiver =====");

        var config = threadConfig(threadId);
        graph.stream(Map.of("messages", "transfer", "_latestUserInput", "transfer"), config).blockLast();

        var snapshot = graph.getState(config);
        System.out.println("[Test1] next=" + snapshot.next() + " _paramName=" + snapshot.state().value("_paramName").orElse(""));

        assertThat(snapshot.next()).isEqualTo("askReceiver");
        assertThat(snapshot.state().value("_paramName").orElse("")).isEqualTo("ASK_RECEIVER");
    }

    // ==================== Test 2: Resume after askReceiver, interrupts before askAmount ====================

    @Test
    @Order(2)
    void test2_resume_interruptsBeforeSecondNode() throws Exception {
        System.out.println("\n===== Test2: Resume -> askReceiver executes -> interrupt before askAmount =====");

        var config = threadConfig("thread-test2");

        // R1: first execution -> interrupt at askReceiver
        graph.stream(Map.of("messages", "transfer", "_latestUserInput", "transfer"), config).blockLast();
        System.out.println("[R1] next=" + graph.getState(config).next());

        // R2: updateState + resume
        var updatedConfig = graph.updateState(config, Map.of("_latestUserInput", "mama"), null);
        graph.stream(null, updatedConfig).blockLast();

        // Read with original config to get latest checkpoint
        var snapshot = graph.getState(config);
        System.out.println("[R2] next=" + snapshot.next()
                + " receiver=" + snapshot.state().value("receiver").orElse("")
                + " _question=" + snapshot.state().value("_question").orElse(""));

        assertThat(snapshot.state().value("receiver").orElse("").toString())
                .as("askReceiver should have set receiver")
                .isEqualTo("mama");

        assertThat(snapshot.next())
                .as("Should interrupt before askAmount")
                .isEqualTo("askAmount");

        assertThat(snapshot.next()).isEqualTo("askAmount");
    }

    // ==================== Test 3: Second resume completes the transfer ====================

    @Test
    @Order(3)
    void test3_secondResume_completesTransfer() throws Exception {
        System.out.println("\n===== Test3: Second resume -> askAmount executes -> transfer completes =====");

        var config = threadConfig("thread-test3");

        // R1
        graph.stream(Map.of("messages", "transfer", "_latestUserInput", "transfer"), config).blockLast();

        // R2: resume with receiver answer
        var uc1 = graph.updateState(config, Map.of("_latestUserInput", "mama"), null);
        graph.stream(null, uc1).blockLast();

        // R3: resume with amount answer
        var uc2 = graph.updateState(config, Map.of("_latestUserInput", "500"), null);
        graph.stream(null, uc2).blockLast();

        var finalSnapshot = graph.getState(config);
        System.out.println("[R3] next=" + finalSnapshot.next()
                + " receiver=" + finalSnapshot.state().value("receiver").orElse("")
                + " amount=" + finalSnapshot.state().value("amount").orElse("")
                + " _outputContent=" + finalSnapshot.state().value("_outputContent").orElse(""));

        assertThat(finalSnapshot.next())
                .as("Graph should be complete")
                .satisfiesAnyOf(
                        next -> assertThat(next).isEqualTo("__END__"),
                        next -> assertThat(next).isEmpty()
                );

        assertThat(finalSnapshot.state().value("receiver").orElse("").toString()).isEqualTo("mama");
        assertThat(finalSnapshot.state().value("amount").orElse("").toString()).isEqualTo("500");
        assertThat(finalSnapshot.state().value("_outputContent").orElse("").toString()).contains("500");
    }

    // ==================== Test 6: Re-interrupt at same interruptBefore node (invalid input loop) ====================

    @Test
    @Order(6)
    void test6_reInterruptAtSameNode_whenInvalidInput() throws Exception {
        System.out.println("\n===== Test6: Re-interrupt at same askAmount node when user inputs invalid data =====");

        // Build graph where askAmount may NOT set amount (simulating parse failure)
        CompiledGraph reAskGraph = buildReAskGraph();
        var config = threadConfig("thread-test6");

        // R1: First execution -> interrupt at askReceiver
        reAskGraph.stream(Map.of("messages", "transfer", "_latestUserInput", "transfer"), config).blockLast();
        var s1 = reAskGraph.getState(config);
        System.out.println("[R1] next=" + s1.next() + " _paramName=" + s1.state().value("_paramName").orElse(""));
        assertThat(s1.next()).isEqualTo("askReceiver");

        // R2: Resume with valid receiver -> interrupt at askAmount
        var uc1 = reAskGraph.updateState(config, Map.of("_latestUserInput", "mama"), null);
        reAskGraph.stream(null, uc1).blockLast();
        var s2 = reAskGraph.getState(config);
        System.out.println("[R2] next=" + s2.next() + " receiver=" + s2.state().value("receiver").orElse("")
                + " _paramName=" + s2.state().value("_paramName").orElse(""));
        assertThat(s2.state().value("receiver").orElse("").toString()).isEqualTo("mama");
        assertThat(s2.next()).isEqualTo("askAmount");

        // R3: Resume with INVALID amount (e.g., "嗯") -> askAmount doesn't set amount -> paramRouter routes back to askAmount
        //     KEY QUESTION: Does interruptBefore("askAmount") fire again?
        var uc2 = reAskGraph.updateState(config, Map.of("_latestUserInput", "嗯"), null);
        reAskGraph.stream(null, uc2).blockLast();
        var s3 = reAskGraph.getState(config);
        System.out.println("[R3] next=" + s3.next()
                + " amount=" + s3.state().value("amount").orElse("")
                + " _paramName=" + s3.state().value("_paramName").orElse(""));

        // If framework re-interrupts: next should be "askAmount" (not __END__, not looping infinitely)
        // If framework does NOT re-interrupt: it would loop infinitely until recursion limit (50)
        assertThat(s3.next())
                .as("Should re-interrupt before askAmount (same node) when graph loops back")
                .isEqualTo("askAmount");

        // amount should still be null/empty because "嗯" can't be parsed as amount
        assertThat(s3.state().value("amount").orElse("").toString())
                .as("Amount should remain unparseable for invalid input '嗯'")
                .isEmpty();

        // R4: Resume with valid amount -> should complete
        var uc3 = reAskGraph.updateState(config, Map.of("_latestUserInput", "500"), null);
        reAskGraph.stream(null, uc3).blockLast();
        var s4 = reAskGraph.getState(config);
        System.out.println("[R4] next=" + s4.next()
                + " receiver=" + s4.state().value("receiver").orElse("")
                + " amount=" + s4.state().value("amount").orElse("")
                + " _outputContent=" + s4.state().value("_outputContent").orElse(""));

        assertThat(s4.next()).satisfiesAnyOf(
                next -> assertThat(next).isEqualTo("__END__"),
                next -> assertThat(next).isEmpty()
        );
        assertThat(s4.state().value("receiver").orElse("").toString()).isEqualTo("mama");
        assertThat(s4.state().value("amount").orElse("").toString()).isEqualTo("500");
    }

    /**
     * 构建会模拟"提参失败"的 Graph：
     * askAmount 在输入不可解析时不会设置 amount（模拟真实场景）
     */
    private CompiledGraph buildReAskGraph() throws Exception {
        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy());
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("_question", new ReplaceStrategy());
            strategies.put("_paramName", new ReplaceStrategy());
            strategies.put("receiver", new ReplaceStrategy());
            strategies.put("amount", new ReplaceStrategy());
            strategies.put("_outputContent", new ReplaceStrategy());
            return strategies;
        };

        StateGraph builder = new StateGraph(keyStrategyFactory)
                .addNode("extractParams", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    if (input.contains("妈")) result.put("receiver", "我妈");
                    if (input.matches(".*\\d+.*")) result.put("amount", input.replaceAll("[^0-9]", ""));
                    System.out.println("[ReAsk.extractParams] input=" + input + " -> " + result);
                    return result;
                }))
                .addNode("paramRouter", node_async(state -> {
                    String receiver = (String) state.value("receiver").orElse(null);
                    String amount = (String) state.value("amount").orElse(null);
                    Map<String, Object> result = new HashMap<>();
                    if (receiver == null || receiver.isEmpty()) {
                        result.put("_question", "请问您要转给谁？");
                        result.put("_paramName", "ASK_RECEIVER");
                    } else if (amount == null || amount.isEmpty()) {
                        result.put("_question", "请问您要转多少金额？");
                        result.put("_paramName", "ASK_AMOUNT");
                    } else {
                        result.put("_question", null);
                        result.put("_paramName", "ALL_GOOD");
                    }
                    System.out.println("[ReAsk.paramRouter] receiver=" + receiver + " amount=" + amount + " -> " + result.get("_paramName"));
                    return result;
                }))
                .addNode("askReceiver", node_async(state -> {
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    System.out.println("[ReAsk.askReceiver] _latestUserInput=" + input);
                    if (input != null && !input.isEmpty()) result.put("receiver", input);
                    result.put("_latestUserInput", "");
                    return result;
                }))
                .addNode("askAmount", node_async(state -> {
                    // ★ 关键区别：只有数字输入才设置 amount，非数字输入不设置（模拟提参失败）
                    String input = (String) state.value("_latestUserInput").orElse("");
                    Map<String, Object> result = new HashMap<>();
                    System.out.println("[ReAsk.askAmount] _latestUserInput=" + input);
                    if (input != null && !input.isEmpty()) {
                        String num = input.replaceAll("[^0-9]", "");
                        if (!num.isEmpty()) {
                            result.put("amount", num);
                            System.out.println("[ReAsk.askAmount] Parsed amount=" + num);
                        } else {
                            // ★ 无法解析为数字 → 不设置 amount → paramRouter 会再次路由到 askAmount
                            System.out.println("[ReAsk.askAmount] CANNOT parse '" + input + "' as amount → amount stays null");
                        }
                    }
                    result.put("_latestUserInput", "");
                    return result;
                }))
                .addNode("executeAction", node_async(state -> {
                    String receiver = (String) state.value("receiver").orElse("unknown");
                    String amount = (String) state.value("amount").orElse("0");
                    System.out.println("[ReAsk.executeAction] Transfer done! receiver=" + receiver + " amount=" + amount);
                    return Map.of("_outputContent", "transferred " + amount + " to " + receiver);
                }))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter",
                        com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state ->
                                (String) state.value("_paramName").orElse("ALL_GOOD")),
                        Map.of("ASK_RECEIVER", "askReceiver", "ASK_AMOUNT", "askAmount", "ALL_GOOD", "executeAction"))
                .addEdge("executeAction", END);

        builder.addConditionalEdges("askReceiver",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));
        builder.addConditionalEdges("askAmount",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));

        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptBefore("askReceiver", "askAmount")
                .build();

        return builder.compile(compileConfig);
    }

    // ==================== Test 4: updateState _latestUserInput is readable by ask node ====================

    @Test
    @Order(4)
    void test4_updateState_readableByAskNode() throws Exception {
        System.out.println("\n===== Test4: updateState _latestUserInput readable by ask node =====");

        var config = threadConfig("thread-test4");

        graph.stream(Map.of("messages", "transfer", "_latestUserInput", "transfer"), config).blockLast();

        var uc = graph.updateState(config, Map.of("_latestUserInput", "mama"), null);

        // Verify updateState worked
        var afterUpdate = graph.getState(uc);
        assertThat((String) afterUpdate.state().value("_latestUserInput").orElse("")).isEqualTo("mama");

        // Resume and verify askReceiver read the input
        graph.stream(null, uc).blockLast();
        var afterResume = graph.getState(config);
        assertThat((String) afterResume.state().value("receiver").orElse(""))
                .as("askReceiver should read _latestUserInput='mama' and set receiver")
                .isEqualTo("mama");
    }

    // ==================== Test 5: Bill query with dual interrupt ====================

    @Test
    @Order(5)
    void test5_billQuery_dualInterrupt() throws Exception {
        System.out.println("\n===== Test5: Bill query dual interrupt =====");

        CompiledGraph billGraph = buildBillQueryGraph();
        var config = threadConfig("thread-bill-005");

        // R1: interrupt at askTime
        billGraph.stream(Map.of("messages", "query bill", "_latestUserInput", "query bill"), config).blockLast();
        assertThat(billGraph.getState(config).next()).isEqualTo("askTime");

        // R2: resume with time period -> interrupt at askType
        var uc1 = billGraph.updateState(config, Map.of("_latestUserInput", "last month"), null);
        billGraph.stream(null, uc1).blockLast();
        var s2 = billGraph.getState(config);
        assertThat(s2.state().value("timePeriod").orElse("").toString()).isEqualTo("last month");
        assertThat(s2.next()).isEqualTo("askType");

        // R3: resume with expense type -> complete
        var uc2 = billGraph.updateState(config, Map.of("_latestUserInput", "expense"), null);
        billGraph.stream(null, uc2).blockLast();
        var s3 = billGraph.getState(config);
        assertThat(s3.state().value("_outputContent").orElse("").toString()).contains("last month");
    }
}
