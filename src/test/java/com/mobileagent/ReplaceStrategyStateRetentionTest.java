package com.mobileagent;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证测试：ReplaceStrategy 注册的 key，当节点不输出该 key 时，是否会被清除？
 *
 * 背景：passThroughStepThreadMap 注释声称 "Framework ReplaceStrategy clears registered keys
 * not in node output, so we must explicitly carry forward any state we want to preserve."
 *
 * 需要验证这个说法是否正确。如果 ReplaceStrategy 不会清除未输出的 key，
 * 则 passThroughStepThreadMap workaround 是冗余的，可以安全删除。
 *
 * 测试策略：
 * 1. 构建一个图，注册 keyA (ReplaceStrategy) 和 keyB (AppendStrategy) 和 keyC (ReplaceStrategy)
 * 2. 初始输入包含 keyA, keyB, keyC
 * 3. 节点1 只输出 keyA 的新值，不输出 keyB 和 keyC
 * 4. 节点2 什么都不输出
 * 5. 验证 keyB 和 keyC 是否在节点1和节点2执行后仍然存在
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplaceStrategyStateRetentionTest {

    // ==================== Test 1: 基本验证 — 节点不输出 ReplaceStrategy key，key 是否保留 ====================

    @Test
    @Order(1)
    void test_replaceStrategyKey_survivesNodeThatDoesNotOutputIt() throws Exception {
        System.out.println("\n===== Test1: ReplaceStrategy key 未被节点输出，是否保留？ =====");

        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy());
            strategies.put("keyA", new ReplaceStrategy());  // 节点1 会输出
            strategies.put("keyB", new ReplaceStrategy());  // 节点1 不会输出
            strategies.put("keyC", new ReplaceStrategy());  // 节点1 和节点2 都不会输出
            return strategies;
        };

        StateGraph builder = new StateGraph(keyStrategyFactory)
                .addNode("node1", node_async(state -> {
                    // 只输出 keyA，不输出 keyB 和 keyC
                    System.out.println("[node1] state before: keyA=" + state.value("keyA").orElse(null)
                            + " keyB=" + state.value("keyB").orElse(null)
                            + " keyC=" + state.value("keyC").orElse(null));
                    Map<String, Object> result = new HashMap<>();
                    result.put("keyA", "updated-by-node1");
                    // 故意不输出 keyB 和 keyC
                    return result;
                }))
                .addNode("node2", node_async(state -> {
                    // 完全不输出任何自定义 key
                    System.out.println("[node2] state before: keyA=" + state.value("keyA").orElse(null)
                            + " keyB=" + state.value("keyB").orElse(null)
                            + " keyC=" + state.value("keyC").orElse(null));
                    return Map.of("messages", "node2-executed");
                }))
                .addEdge(START, "node1")
                .addEdge("node1", "node2")
                .addEdge("node2", END);

        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build();
        CompiledGraph graph = builder.compile(compileConfig);

        var config = RunnableConfig.builder().threadId("test-retention-1").build();

        // 初始输入包含所有 key
        Map<String, Object> stepThreadMap = new LinkedHashMap<>();
        stepThreadMap.put("s-0", "thread-A");
        stepThreadMap.put("s-1", "thread-B");

        graph.stream(Map.of(
                "messages", "initial",
                "keyA", "initial-A",
                "keyB", stepThreadMap,
                "keyC", "initial-C"
        ), config).blockLast();

        var snapshot = graph.getState(config);
        System.out.println("[Test1] FINAL state:");
        System.out.println("  keyA=" + snapshot.state().value("keyA").orElse(null));
        System.out.println("  keyB=" + snapshot.state().value("keyB").orElse(null));
        System.out.println("  keyC=" + snapshot.state().value("keyC").orElse(null));

        // ★ 核心断言
        // keyA 应该被 node1 更新
        assertThat(snapshot.state().value("keyA").orElse("").toString()).isEqualTo("updated-by-node1");

        // keyB (Map 类型, ReplaceStrategy) — node1 和 node2 都没输出它 → 如果保留，说明 ReplaceStrategy 不会清除未输出的 key
        assertThat(snapshot.state().value("keyB").orElse(null)).as("keyB (ReplaceStrategy) should survive when not in node output").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> keyBValue = (Map<String, String>) snapshot.state().value("keyB").orElse(null);
        assertThat(keyBValue).as("keyB should retain its Map value").containsEntry("s-0", "thread-A");

        // keyC (String 类型, ReplaceStrategy) — 同理
        assertThat(snapshot.state().value("keyC").orElse("").toString()).as("keyC (ReplaceStrategy) should survive when not in node output").isEqualTo("initial-C");
    }

    // ==================== Test 2: 模拟 L2_STEP_THREAD_MAP 场景 — 多节点执行后 Map 是否保留 ====================

    @Test
    @Order(2)
    void test_l2StepThreadMap_survivesMultipleNodes() throws Exception {
        System.out.println("\n===== Test2: 模拟 L2_STEP_THREAD_MAP — 多节点执行后 Map 是否保留 =====");

        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy());
            strategies.put("_l2_stepThreadMap", new ReplaceStrategy());   // 和 OrchestrationGraphConfig 一样
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("orch_status", new ReplaceStrategy());
            strategies.put("orch_steps", new ReplaceStrategy());
            strategies.put("_stepL2Status", new ReplaceStrategy());
            return strategies;
        };

        // 模拟编排图：planner → stepPreparator → conditionCheck → routeAfterCondition → end
        StateGraph builder = new StateGraph(keyStrategyFactory)
                .addNode("planner", node_async(state -> {
                    // planner 不输出 _l2_stepThreadMap
                    System.out.println("[planner] _l2_stepThreadMap=" + state.value("_l2_stepThreadMap").orElse(null));
                    Map<String, Object> result = new HashMap<>();
                    result.put("orch_steps", "s-0:TRANSFER,s-1:WEALTH_PURCHASE");
                    result.put("orch_status", "EXECUTING");
                    return result;
                }))
                .addNode("stepPreparator", node_async(state -> {
                    // stepPreparator 也不输出 _l2_stepThreadMap（模拟没有 passThroughStepThreadMap 的场景）
                    System.out.println("[stepPreparator] _l2_stepThreadMap=" + state.value("_l2_stepThreadMap").orElse(null));
                    Map<String, Object> result = new HashMap<>();
                    result.put("_stepL2Status", "INTERRUPTED");
                    // 故意不输出 _l2_stepThreadMap — 这就是 passThroughStepThreadMap 存在的原因
                    return result;
                }))
                .addNode("conditionCheck", node_async(state -> {
                    // conditionCheck 也不输出 _l2_stepThreadMap
                    System.out.println("[conditionCheck] _l2_stepThreadMap=" + state.value("_l2_stepThreadMap").orElse(null));
                    Map<String, Object> result = new HashMap<>();
                    result.put("orch_status", "WAITING_USER");
                    return result;
                }))
                .addEdge(START, "planner")
                .addEdge("planner", "stepPreparator")
                .addEdge("stepPreparator", "conditionCheck")
                .addEdge("conditionCheck", END);

        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build();
        CompiledGraph graph = builder.compile(compileConfig);

        var config = RunnableConfig.builder().threadId("test-l2stepthreadmap").build();

        // 初始输入含 _l2_stepThreadMap（模拟 L2GraphTool 之前写入的）
        Map<String, String> stepThreadMap = new LinkedHashMap<>();
        stepThreadMap.put("s-0", "00031-5f597845-transfer-s-0-1782662729653");
        stepThreadMap.put("s-1", "000314-61c004c2-wealth_purchase-s-1-1782663835909");

        graph.stream(Map.of(
                "messages", "看看收入，超5000转1000，再买5000股",
                "_latestUserInput", "看看收入，超5000转1000，再买5000股",
                "_l2_stepThreadMap", stepThreadMap
        ), config).blockLast();

        var snapshot = graph.getState(config);
        System.out.println("[Test2] FINAL state:");
        System.out.println("  _l2_stepThreadMap=" + snapshot.state().value("_l2_stepThreadMap").orElse(null));
        System.out.println("  orch_status=" + snapshot.state().value("orch_status").orElse(null));
        System.out.println("  _stepL2Status=" + snapshot.state().value("_stepL2Status").orElse(null));

        // ★ 核心断言：_l2_stepThreadMap 在 3 个不输出它的节点执行后仍然存在
        @SuppressWarnings("unchecked")
        Map<String, String> finalMap = (Map<String, String>) snapshot.state().value("_l2_stepThreadMap").orElse(null);
        assertThat(finalMap).as("_l2_stepThreadMap should survive through 3 nodes that don't output it").isNotNull();
        assertThat(finalMap).as("_l2_stepThreadMap should contain s-0 mapping").containsEntry("s-0", "00031-5f597845-transfer-s-0-1782662729653");
        assertThat(finalMap).as("_l2_stepThreadMap should contain s-1 mapping").containsEntry("s-1", "000314-61c004c2-wealth_purchase-s-1-1782663835909");
    }

    // ==================== Test 3: 节点输出其他 key 时，是否清除不相关的 key ====================

    @Test
    @Order(3)
    void test_nodeOutputDoesNotClearUnrelatedKeys() throws Exception {
        System.out.println("\n===== Test3: 节点输出部分 key，是否清除不相关 key？ =====");

        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy());
            strategies.put("key1", new ReplaceStrategy());
            strategies.put("key2", new ReplaceStrategy());
            strategies.put("key3", new ReplaceStrategy());
            strategies.put("key4", new ReplaceStrategy());
            strategies.put("key5", new ReplaceStrategy());
            return strategies;
        };

        StateGraph builder = new StateGraph(keyStrategyFactory)
                .addNode("node1", node_async(state -> {
                    // 只输出 key1 和 key3
                    return Map.of("key1", "updated-1", "key3", "updated-3");
                }))
                .addNode("node2", node_async(state -> {
                    // 只输出 key5
                    return Map.of("key5", "updated-5");
                }))
                .addEdge(START, "node1")
                .addEdge("node1", "node2")
                .addEdge("node2", END);

        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build();
        CompiledGraph graph = builder.compile(compileConfig);

        var config = RunnableConfig.builder().threadId("test-unrelated-keys").build();

        graph.stream(Map.of(
                "messages", "initial",
                "key1", "init-1",
                "key2", "init-2",
                "key3", "init-3",
                "key4", "init-4",
                "key5", "init-5"
        ), config).blockLast();

        var snapshot = graph.getState(config);

        // ★ 所有 key 都应该存在
        assertThat(snapshot.state().value("key1").orElse("").toString()).isEqualTo("updated-1");  // 被更新
        assertThat(snapshot.state().value("key2").orElse("").toString()).isEqualTo("init-2");      // 未被输出 → 应保留原值
        assertThat(snapshot.state().value("key3").orElse("").toString()).isEqualTo("updated-3");  // 被更新
        assertThat(snapshot.state().value("key4").orElse("").toString()).isEqualTo("init-4");      // 未被输出 → 应保留原值
        assertThat(snapshot.state().value("key5").orElse("").toString()).isEqualTo("updated-5");  // 被更新

        System.out.println("[Test3] ★ ALL keys survived! ReplaceStrategy does NOT clear unoutput keys.");
    }

    // ==================== Test 4: 直接调用 OverAllState.updateState 验证增量语义 ====================

    @Test
    @Order(4)
    void test_overAllStateUpdateState_isIncremental() throws Exception {
        System.out.println("\n===== Test4: OverAllState.updateState 是增量更新还是全量替换？ =====");

        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("keyA", new ReplaceStrategy());
            strategies.put("keyB", new ReplaceStrategy());
            strategies.put("keyC", new ReplaceStrategy());
            return strategies;
        };

        // 初始 state: keyA=1, keyB=2, keyC=3
        OverAllState state = OverAllStateBuilder.builder()
                .withKeyStrategies(keyStrategyFactory.apply())
                .withData(Map.of("keyA", "1", "keyB", "2", "keyC", "3"))
                .build();

        System.out.println("[Test4] Initial: keyA=" + state.value("keyA").orElse(null)
                + " keyB=" + state.value("keyB").orElse(null)
                + " keyC=" + state.value("keyC").orElse(null));

        // updateState 只更新 keyA，不包含 keyB 和 keyC
        state.updateState(Map.of("keyA", "updated-A"));

        System.out.println("[Test4] After updateState(keyA): keyA=" + state.value("keyA").orElse(null)
                + " keyB=" + state.value("keyB").orElse(null)
                + " keyC=" + state.value("keyC").orElse(null));

        // ★ keyA 被更新，keyB 和 keyC 不受影响
        assertThat(state.value("keyA").orElse("").toString()).isEqualTo("updated-A");
        assertThat(state.value("keyB").orElse("").toString()).isEqualTo("2");
        assertThat(state.value("keyC").orElse("").toString()).isEqualTo("3");

        System.out.println("[Test4] ★ OverAllState.updateState is INCREMENTAL, not full replacement!");
        System.out.println("[Test4] ★ Keys not in partialState are UNTOUCHED.");
    }
}
