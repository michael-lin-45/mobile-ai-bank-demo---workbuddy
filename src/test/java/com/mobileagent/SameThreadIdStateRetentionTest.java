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
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证测试：同一个 threadId 多次执行 Graph，OverAllState 是否保留上一次的数据
 *
 * ★ 关键发现（通过实际测试验证）:
 * 1. 同一 CompiledGraph + 同一 MemorySaver + 同一 threadId
 * 2. 新 stream() 调用时，getInitialState 会从 Checkpoint 合并历史数据
 * 3. 但每次 @BeforeEach 创建新 CompiledGraph 时，MemorySaver 也是新的 → Checkpoint 丢失
 * 4. 所以必须共享 CompiledGraph 实例（@BeforeAll）才能测试跨执行的 Checkpoint 保留
 *
 * 场景:
 * - 第1次: "转账给我妈300" → receiver=我妈, amount=300 → __END__
 * - 第2次: "转给我弟弟"   → receiver=我弟弟, amount=? → Checkpoint是否保留amount=300?
 * - 第3次: 中断resume完成 → 再新stream("转账") → 是否恢复数据?
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SameThreadIdStateRetentionTest {

    private static CompiledGraph graph;
    private final String threadId = "retention-test-001";

    @BeforeAll
    static void setUp() throws Exception {
        graph = buildTransferGraph();
    }

    private RunnableConfig threadConfig(String tid) {
        return RunnableConfig.builder().threadId(tid).build();
    }

    // ==================== Graph 构建 ====================

    private static CompiledGraph buildTransferGraph() throws Exception {
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
                    // 简单提取逻辑（不依赖LLM，确定性测试）
                    if (input.contains("妈")) result.put("receiver", "我妈");
                    else if (input.contains("弟弟")) result.put("receiver", "我弟弟");
                    else if (input.contains("姐姐")) result.put("receiver", "我姐姐");
                    if (input.matches(".*\\d+.*")) {
                        String num = input.replaceAll("[^0-9]", "");
                        if (!num.isEmpty()) result.put("amount", num);
                    }
                    System.out.println("[extractParams] input=" + input + " -> extracted=" + result);
                    // 打印当前 state 中已有的值（来自 Checkpoint 恢复）
                    System.out.println("[extractParams] state.receiver=" + state.value("receiver").orElse(null)
                            + " state.amount=" + state.value("amount").orElse(null));
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
                        if (!num.isEmpty()) result.put("amount", num);
                        else result.put("amount", input);
                    }
                    result.put("_latestUserInput", "");
                    return result;
                }))
                .addNode("executeAction", node_async(state -> {
                    String receiver = (String) state.value("receiver").orElse("unknown");
                    String amount = (String) state.value("amount").orElse("0");
                    System.out.println("[executeAction] ★ Transfer done! receiver=" + receiver + " amount=" + amount);
                    return Map.of("_outputContent", "已转账 " + amount + " 元给 " + receiver);
                }))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter",
                        edge_async(state -> (String) state.value("_paramName").orElse("ALL_GOOD")),
                        Map.of("ASK_RECEIVER", "askReceiver", "ASK_AMOUNT", "askAmount", "ALL_GOOD", "executeAction"))
                .addEdge("executeAction", END);

        builder.addConditionalEdges("askReceiver",
                edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));
        builder.addConditionalEdges("askAmount",
                edge_async(state -> "CONTINUE"),
                Map.of("CONTINUE", "paramRouter"));

        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptBefore("askReceiver", "askAmount")
                .build();

        return builder.compile(compileConfig);
    }

    // ==================== Test 1: 第1次执行 "转账给我妈300" → 一次走完 __END__ ====================

    @Test
    @Order(1)
    void test1_firstTransfer_completesDirectly() throws Exception {
        System.out.println("\n===== Test1: 第1次 '转账给我妈300' → receiver=我妈, amount=300 → __END__ =====");

        var config = threadConfig(threadId);

        // 首次执行：input 包含完整参数，应直接走到 __END__
        graph.stream(Map.of(
                "messages", "转账给我妈300",
                "_latestUserInput", "转账给我妈300"
        ), config).blockLast();

        var snapshot = graph.getState(config);
        System.out.println("[Test1] next=" + snapshot.next()
                + " receiver=" + snapshot.state().value("receiver").orElse("")
                + " amount=" + snapshot.state().value("amount").orElse("")
                + " _outputContent=" + snapshot.state().value("_outputContent").orElse(""));

        // 验证：直接完成
        assertThat(snapshot.next()).satisfiesAnyOf(
                next -> assertThat(next).isEqualTo("__END__"),
                next -> assertThat(next).isEmpty()
        );
        assertThat(snapshot.state().value("receiver").orElse("").toString()).isEqualTo("我妈");
        assertThat(snapshot.state().value("amount").orElse("").toString()).isEqualTo("300");
        assertThat(snapshot.state().value("_outputContent").orElse("").toString()).contains("300");
    }

    // ==================== Test 2: 第2次执行 "转给我弟弟" → Checkpoint 是否保留了 amount=300？ ====================

    @Test
    @Order(2)
    void test2_secondTransfer_checkpointDataMerged() throws Exception {
        System.out.println("\n===== Test2: 第2次 '转给我弟弟' → Checkpoint是否保留上次数据？ =====");

        var config = threadConfig(threadId);

        // 再次用同一 threadId 执行（非 resume，是新的 stream 调用）
        graph.stream(Map.of(
                "messages", "转给我弟弟",
                "_latestUserInput", "转给我弟弟"
        ), config).blockLast();

        var snapshot = graph.getState(config);
        System.out.println("[Test2] ★★★ CHECK POINT ★★★");
        System.out.println("[Test2] next=" + snapshot.next()
                + " receiver=" + snapshot.state().value("receiver").orElse("")
                + " amount=" + snapshot.state().value("amount").orElse("")
                + " _outputContent=" + snapshot.state().value("_outputContent").orElse("")
                + " _question=" + snapshot.state().value("_question").orElse(""));

        String amount = snapshot.state().value("amount").orElse("").toString();
        String receiver = snapshot.state().value("receiver").orElse("").toString();

        System.out.println("[Test2] ★ receiver=" + receiver + ", amount=" + amount);

        // 验证 receiver 被新值覆盖
        assertThat(receiver).isEqualTo("我弟弟");

        // ★ 核心验证：amount 是否从 Checkpoint 恢复？
        if (!amount.isEmpty()) {
            System.out.println("[Test2] ★★★ Checkpoint 数据已恢复！amount=" + amount + " (来自第1次执行的Checkpoint)");
            assertThat(amount).isEqualTo("300");
            // receiver=我弟弟 + amount=300 → ALL_GOOD → 直接完成
            assertThat(snapshot.next()).satisfiesAnyOf(
                    next -> assertThat(next).isEqualTo("__END__"),
                    next -> assertThat(next).isEmpty()
            );
        } else {
            System.out.println("[Test2] ★★★ Checkpoint 数据未恢复！amount 为空 → 图应中断在 askAmount");
            assertThat(snapshot.next()).isEqualTo("askAmount");
        }
    }

    // ==================== Test 3: 如果第2次中断 → resume完成 → 第3次新stream ====================

    /**
     * 测试流程:
     * 1. Test2 可能中断在 askAmount（如果 Checkpoint 未恢复）→ 先 resume 完成
     * 2. 同 threadId 新 stream("转账") → 验证 Checkpoint 是否合并
     *
     * ★ 关键发现:
     * - 新 stream() 调用时，getInitialState 会从 Checkpoint 合并历史数据（同一CompiledGraph + 同一MemorySaver）
     * - 只有 resume（updateState + stream(null, config)）才保留中断点继续执行
     */
    @Test
    @Order(3)
    void test3_thirdTransfer_resumeThenNewStream() throws Exception {
        System.out.println("\n===== Test3: 第2次状态检查 → resume完成(如需要) → 第3次新stream('转账') =====");

        var config = threadConfig(threadId);

        // 检查第2次的最终状态
        var preSnapshot = graph.getState(config);
        System.out.println("[Test3-pre] next=" + preSnapshot.next()
                + " receiver=" + preSnapshot.state().value("receiver").orElse("")
                + " amount=" + preSnapshot.state().value("amount").orElse(""));

        // 如果第2次中断了，先 resume 完成
        if ("askAmount".equals(preSnapshot.next())) {
            System.out.println("[Test3-pre] 第2次中断在 askAmount，resume 回答 '400'");
            var uc = graph.updateState(config, Map.of("_latestUserInput", "400"), null);
            graph.stream(null, uc).blockLast();

            var afterResume = graph.getState(config);
            System.out.println("[Test3-pre] resume后 next=" + afterResume.next()
                    + " receiver=" + afterResume.state().value("receiver").orElse("")
                    + " amount=" + afterResume.state().value("amount").orElse("")
                    + " _outputContent=" + afterResume.state().value("_outputContent").orElse(""));

            // resume 后应该完成
            assertThat(afterResume.next()).satisfiesAnyOf(
                    next -> assertThat(next).isEqualTo("__END__"),
                    next -> assertThat(next).isEmpty()
            );
        } else {
            System.out.println("[Test3-pre] 第2次已完成（Checkpoint数据被合并了），无需resume");
        }

        // ★ 第3次：用同一个 threadId 执行新 stream("转账")
        System.out.println("[Test3] ★ 开始第3次执行（新stream）: '转账'");
        graph.stream(Map.of(
                "messages", "转账",
                "_latestUserInput", "转账"
        ), config).blockLast();

        var snapshot = graph.getState(config);
        System.out.println("[Test3] ★★★ CHECK POINT ★★★");
        System.out.println("[Test3] next=" + snapshot.next()
                + " receiver=" + snapshot.state().value("receiver").orElse("")
                + " amount=" + snapshot.state().value("amount").orElse("")
                + " _question=" + snapshot.state().value("_question").orElse(""));

        String receiver = snapshot.state().value("receiver").orElse("").toString();
        String amount = snapshot.state().value("amount").orElse("").toString();

        // ★ 核心验证：新 stream() 是否合并了上次 Checkpoint 的数据？
        if (!receiver.isEmpty()) {
            System.out.println("[Test3] ★★★ Checkpoint 数据已恢复！receiver=" + receiver + ", amount=" + amount);
            // Checkpoint 被合并了 → 可能直接完成（receiver 和 amount 都从 Checkpoint 恢复）
        } else {
            System.out.println("[Test3] ★★★ Checkpoint 数据未恢复 → 中断在 askReceiver");
            assertThat(snapshot.next()).isEqualTo("askReceiver");

            // 追问回答：给我姐姐
            var uc1 = graph.updateState(config, Map.of("_latestUserInput", "给我姐姐"), null);
            graph.stream(null, uc1).blockLast();

            var afterAskReceiver = graph.getState(config);
            System.out.println("[Test3] 回答'给我姐姐'后 next=" + afterAskReceiver.next()
                    + " receiver=" + afterAskReceiver.state().value("receiver").orElse("")
                    + " amount=" + afterAskReceiver.state().value("amount").orElse("")
                    + " _question=" + afterAskReceiver.state().value("_question").orElse(""));

            // 检查是直接完成还是再问金额
            if ("askAmount".equals(afterAskReceiver.next())) {
                // 追问回答：转600
                System.out.println("[Test3] 追问金额，回答 '600'");
                var uc2 = graph.updateState(config, Map.of("_latestUserInput", "600"), null);
                graph.stream(null, uc2).blockLast();

                var afterAskAmount = graph.getState(config);
                System.out.println("[Test3] 回答'600'后 next=" + afterAskAmount.next()
                        + " receiver=" + afterAskAmount.state().value("receiver").orElse("")
                        + " amount=" + afterAskAmount.state().value("amount").orElse("")
                        + " _outputContent=" + afterAskAmount.state().value("_outputContent").orElse(""));

                assertThat(afterAskAmount.state().value("receiver").orElse("").toString()).isEqualTo("给我姐姐");
                assertThat(afterAskAmount.state().value("amount").orElse("").toString()).isEqualTo("600");
            } else {
                // amount 从 Checkpoint 恢复，直接完成
                System.out.println("[Test3] amount 从 Checkpoint 恢复，直接完成！receiver="
                        + afterAskReceiver.state().value("receiver").orElse("")
                        + " amount=" + afterAskReceiver.state().value("amount").orElse(""));
            }
        }
    }

    // ==================== Test 4: 全新 threadId，确认无历史数据干扰 ====================

    @Test
    @Order(4)
    void test4_freshThreadId_noHistoryData() throws Exception {
        System.out.println("\n===== Test4: 全新 threadId → 无历史数据 → 应该中断 =====");

        var config = threadConfig("fresh-thread-no-history");

        graph.stream(Map.of(
                "messages", "转账",
                "_latestUserInput", "转账"
        ), config).blockLast();

        var snapshot = graph.getState(config);
        System.out.println("[Test4] next=" + snapshot.next()
                + " receiver=" + snapshot.state().value("receiver").orElse("")
                + " amount=" + snapshot.state().value("amount").orElse("")
                + " _question=" + snapshot.state().value("_question").orElse(""));

        // 全新 threadId，无 Checkpoint → receiver=null → 应中断在 askReceiver
        assertThat(snapshot.next()).isEqualTo("askReceiver");
        assertThat(snapshot.state().value("_question").orElse("").toString()).contains("转给谁");
    }
}
