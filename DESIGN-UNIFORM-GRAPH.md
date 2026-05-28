# 统一大图架构设计文档（feat/uniform_graph）

## 1. 设计目标

将当前 L0/L1/L2 三层独立图架构重构为统一大图架构：
- L0 封装为根图（root graph）
- L1 封装为 L0 的子图（subgraph）
- L2（转账/账单/理财咨询/理财解读）分别为各 L1 的子图
- 全局共享一个 MemorySaver + OverAllState
- sessionId = threadId 统一管理

目标收益：
- 跨域上下文天然共享（"刚转了500"→ 理财推荐自动感知）
- 状态管理由框架统一负责，L1 不再手动管理 activeAgent/suspendedAgents
- 符合 Spring AI Alibaba 官方子图模式

## 2. 图结构设计

### 2.1 目标架构（树形结构）

```
BankGraph (L0 - root)
├── bankRouter 节点: 域路由（TRANSFER/BILL/WEALTH/CHAT）
│
├── TransferDomain (L1 - subgraph of BankGraph)
│   ├── contextRouter 节点: FOLLOW/SWITCH 判断
│   ├── TransferWorkflow (L2 - subgraph of TransferDomain)
│   │   ├── extractParams
│   │   ├── paramRouter
│   │   ├── askReceiver (interruptBefore)
│   │   ├── askAmount (interruptBefore)
│   │   ├── executeTransfer
│   │   └── cancelExecution
│   └── Edges: contextRouter → TransferWorkflow → contextRouter
│
├── BillDomain (L1 - subgraph of BankGraph)
│   ├── contextRouter 节点
│   ├── BillWorkflow (L2 - subgraph)
│   └── ...
│
├── WealthDomain (L1 - subgraph of BankGraph, 多意图)
│   ├── contextRouter 节点
│   ├── intentResolver 节点 (含消歧)
│   ├── WealthConsultWorkflow (L2 - subgraph)
│   ├── WealthInterpretWorkflow (L2 - subgraph)
│   └── ...
│
└── Edges: bankRouter → {TransferDomain, BillDomain, WealthDomain}
```

### 2.2 编译时展平效果

基于框架 `ProcessedNodesEdgesAndConfig.process()` 的展平机制，
编译后所有子图节点被合并到一个 CompiledGraph 中：

```
BankGraph (展平后):
  节点:
    bankRouter
    transferDomain.contextRouter
    transferDomain.transferWorkflow.extractParams
    transferDomain.transferWorkflow.paramRouter
    transferDomain.transferWorkflow.askReceiver    ← interruptBefore
    transferDomain.transferWorkflow.askAmount      ← interruptBefore
    transferDomain.transferWorkflow.executeTransfer
    transferDomain.transferWorkflow.cancelExecution
    billDomain.contextRouter
    billDomain.billWorkflow.extractParams
    billDomain.billWorkflow.askTime                ← interruptBefore
    billDomain.billWorkflow.askType                ← interruptBefore
    ...
    wealthDomain.contextRouter
    wealthDomain.intentResolver
    wealthDomain.wealthConsultWorkflow.askRiskLevel ← interruptBefore
    wealthDomain.wealthInterpretWorkflow.askProductName ← interruptBefore

  共享:
    一个 OverAllState (所有域的字段都在里面)
    一个 MemorySaver (一个 threadId 对应一组 checkpoint)
    一个 KeyStrategyMap (所有域的 KeyStrategy 合并)
```

## 3. OverAllState 统一状态设计

### 3.1 KeyStrategy 统一注册表

```java
KeyStrategyFactory unifiedKeyStrategyFactory = () -> {
    Map<String, KeyStrategy> strategies = new HashMap<>();

    // ===== L0 公共字段 =====
    strategies.put("messages", new AppendStrategy());
    strategies.put("_latestUserInput", new ReplaceStrategy());
    strategies.put("_question", new ReplaceStrategy());
    strategies.put("_outputContent", new ReplaceStrategy());
    strategies.put("_outputType", new ReplaceStrategy());
    strategies.put("_isFinal", new ReplaceStrategy());
    strategies.put("_cancelSignal", new ReplaceStrategy());
    strategies.put("_paramName", new ReplaceStrategy());

    // ===== L1 路由字段 =====
    strategies.put("_domain", new ReplaceStrategy());         // 当前域: TRANSFER/BILL/WEALTH
    strategies.put("_intent", new ReplaceStrategy());         // 当前意图
    strategies.put("_routeType", new ReplaceStrategy());      // FOLLOW/SWITCH/RESUME
    strategies.put("_activeAgent", new ReplaceStrategy());    // 当前活跃 agent
    strategies.put("_interruptedNode", new ReplaceStrategy());// 被中断的节点
    strategies.put("_suspendedAgents", new ReplaceStrategy());// 挂起的 agent 信息
    strategies.put("_disambiguationState", new ReplaceStrategy()); // 消歧状态

    // ===== Transfer 域字段 =====
    strategies.put("transfer.receiver", new ReplaceStrategy());
    strategies.put("transfer.amount", new ReplaceStrategy());
    strategies.put("transfer.purpose", new ReplaceStrategy());

    // ===== Bill 域字段 =====
    strategies.put("bill.timePeriod", new ReplaceStrategy());
    strategies.put("bill.expenseType", new ReplaceStrategy());

    // ===== Wealth 域字段 =====
    strategies.put("wealth.riskLevel", new ReplaceStrategy());
    strategies.put("wealth.focusArea", new ReplaceStrategy());
    strategies.put("wealth.productName", new ReplaceStrategy());

    return strategies;
};
```

### 3.2 与当前架构的 OverAllState 对比

| 维度 | 当前架构（4个独立 OverAllState） | 统一大图（1个共享 OverAllState） |
|------|------|------|
| 跨域数据可见性 | ❌ 互不可见 | ✅ 天然共享 |
| 域字段污染风险 | 无 | ⚠️ 需靠前缀隔离 |
| 新增域影响 | 零影响 | 需改全局 KeyStrategyFactory |
| 内存占用 | 各自独立 | 全量（包含所有域字段） |
| checkpoint 粒度 | 每域独立 | 全量快照 |

## 4. 核心矛盾：Human-in-the-loop 下的意图切换问题

### 4.1 问题描述

这是统一大图架构的**根本性挑战**，必须先解决才能推进。

**用框架源码精确追踪用户消息的走向：**

```
场景: bankGraph 中 TransferWorkflow.askAmount 被 interruptBefore 中断

=== 当前架构（L0/L1 在图外，自然拦截）===

  TransferGraph.stream(input, config) → askAmount 被 interruptBefore 中断
  → MemorySaver@A 保存 checkpoint: {nextNodeId: "askAmount"}

  用户发来消息 "查账单"
  → BankController.handle() 被调用
  → L0 DomainRouter 判断域
  → L1 ContextRouter 判断 FOLLOW/SWITCH
  → ★ L1 在 L2 之前拦截！
  → SWITCH → GES.executeGraph(billGraph, ...)  ← 新执行
  → FOLLOW → GES.resumeGraph(transferGraph, ...) ← 恢复执行
  无论哪种，L1 都有机会做路由判断，L2 不会收到"不属于自己"的输入

=== 统一大图架构（L0/L1/L2 全在图内，resume 绕过 L0/L1）===

  bankGraph.stream(input, config) → transfer.askAmount 被 interruptBefore 中断
  → 统一 MemorySaver 保存 checkpoint: {nextNodeId: "transfer.askAmount"}

  用户发来消息 "查账单"
  → Controller 调用:
      bankGraph.updateState(config, {_latestUserInput: "查账单"}, null)
      bankGraph.stream(null, updatedConfig)

  → 框架从 checkpoint 恢复
  → nextNodeId = "transfer.askAmount"
  → ★ 直接在 askAmount 节点开始执行！
  → ★ bankRouter 和 L1 contextRouter 完全被绕过！
  → ★ askAmount 把"查账单"当金额处理 → 错误！

  根因（框架源码级）:
    CompiledGraph.updateState(config, values, asNode=null):
      → asNode == null → 不重算 nextNodeId
      → checkpoint 的 nextNodeId 保持 "transfer.askAmount"
      → stream(null, updatedConfig) 从 "transfer.askAmount" 恢复
      → L0 bankRouter 和 L1 contextRouter 没有机会参与
```

**根因**：框架的 resume 机制（stream(null, updatedConfig)）从 checkpoint 的
nextNodeId 恢复执行，直接跳到 L2 中断点，跳过了所有图内路由节点。

### 4.2 三种解决方案对比

#### 方案A：永远先回 L1（asNode 重路由）

**原理**：每次 resume 时，不用框架默认的 checkpoint 恢复路径，
而是通过 updateState 的 asNode 参数强制从 L1 contextRouter 重新路由。

```java
// Controller 层的 resume 逻辑
public WorkflowOutput resume(String sessionId, String userInput) {
    RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();

    // 1. 读取当前 checkpoint，获取中断点信息
    StateSnapshot snapshot = bankGraph.getState(config);
    String interruptedNode = snapshot.next();  // "transferDomain.transferWorkflow.askAmount"

    // 2. 构造 updateValues，包含中断点信息
    Map<String, Object> updateValues = new HashMap<>();
    updateValues.put("_latestUserInput", userInput);
    updateValues.put("_interruptedNode", interruptedNode);

    // 3. ★ 关键：asNode 强制从 contextRouter 重新路由
    //    而不是从 checkpoint 的 nextNodeId 恢复
    String asNode = resolveContextRouter(snapshot);
    RunnableConfig updatedConfig = bankGraph.updateState(config, updateValues, asNode);

    // 4. stream 从 contextRouter 的路由结果开始执行
    bankGraph.stream(null, updatedConfig).blockLast();

    return checkResult(bankGraph, config);
}

private String resolveContextRouter(StateSnapshot snapshot) {
    String nextNode = snapshot.next();
    // 根据中断点所属域，返回对应的 L1 contextRouter
    if (nextNode.startsWith("transferDomain.")) return "transferDomain.contextRouter";
    if (nextNode.startsWith("billDomain.")) return "billDomain.contextRouter";
    if (nextNode.startsWith("wealthDomain.")) return "wealthDomain.contextRouter";
    return "bankRouter";  // fallback
}
```

L1 contextRouter 的边条件：
```java
edge_async(state -> {
    String routeType = (String) state.value("_routeType").orElse("");

    if ("FOLLOW".equals(routeType)) {
        // 继续当前中断点
        return (String) state.value("_interruptedNode").orElse("");
    }
    if ("SWITCH".equals(routeType)) {
        // 切换到新域 → END 回到 L0 bankRouter
        return "SWITCH_OUT";
    }
    if ("RESUME".equals(routeType)) {
        // 恢复挂起的 agent
        String target = resolveSuspendedTarget(state);
        return target;
    }
    return "SWITCH_OUT";
})
```

**优点**：
- L1 路由逻辑正确执行，FOLLOW/SWITCH/RESUME 都能处理
- 与当前架构的语义等价（每次 resume 都过 L1）

**缺点**：
- **Controller 在做 L1 的调度工作**——读 checkpoint、选 asNode，
  本质上 L1 的部分逻辑还在图外
- 每次 resume 多一次 LLM 调用（ContextRouter 判断路由类型）
- asNode 参数依赖节点 ID 前缀规则，耦合框架内部实现
- 违背"把路由逻辑放进图里"的初衷

**可行性**: ✅ 技术上可行，但 Controller 仍需承担调度职责

---

#### 方案B：L2 ask 节点内置意图切换检测

**原理**：扩展现有 askNode 模式，在 ask 节点执行业务逻辑之前，
先检测用户是否切了意图，如果切了则路由回 L1。

```java
// AbstractGraphConfig 扩展
protected AsyncNodeAction askNode(String name,
        Function<OverAllState, Map<String, Object>> logic) {
    interruptNodes.add(name);
    return node_async(state -> {
        // 1. cancel 检测（现有逻辑）
        Map<String, Object> cancelResult = cancelAwareAsk(state);
        if (cancelResult != null) return cancelResult;

        // 2. ★ 新增：意图切换检测
        Map<String, Object> switchResult = switchAwareAsk(state);
        if (switchResult != null) return switchResult;

        // 3. 正常业务逻辑
        return logic.apply(state);
    });
}

private Map<String, Object> switchAwareAsk(OverAllState state) {
    String input = getLatestInput(state);
    if (input == null || input.isBlank()) return null;

    // 快速路径：关键字匹配
    if (looksLikeDomainSwitch(input)) {
        return Map.of("_needsReRoute", true, "_switchInput", input);
    }

    // 兜底路径：LLM 判断
    if (llmDetectsSwitch(state)) {
        return Map.of("_needsReRoute", true, "_switchInput", input);
    }

    return null;
}
```

ask 节点的条件边增加 REROUTE 路径：
```java
addConditionalEdges("askAmount",
    edge_async(state -> {
        Boolean needsReRoute = (Boolean) state.value("_needsReRoute").orElse(false);
        if (needsReRoute) return "REROUTE";

        String input = getLatestInput(state);
        boolean hasInput = input != null && !input.isEmpty();
        return hasInput ? "CONTINUE" : "WAIT";
    }),
    Map.of(
        "CONTINUE", "paramRouter",
        "WAIT", END,
        "REROUTE", "contextRouter"   // ★ 回到 L1 重新路由
    )
);
```

**优点**：
- 框架 resume 自然流（从 checkpoint 恢复到 ask 节点）
- 不需要 Controller 做特殊处理
- 与 cancelAwareAsk 模式一致（cancel 也在 L2 检测）

**缺点**：
- 每个 ask 节点多一次 LLM 调用（判断意图切换）
- **不可靠**：LLM 可能误判——"先转个账"看起来像切换，实际可能只是提供上下文
- 把路由逻辑塞进 L2，违反关注点分离
- 意图切换比 cancel 更难判断（cancel 是否定意图，切换是肯定意图）
- 误判代价高：如果误判为 FOLLOW，L2 处理了错误输入；
  如果误判为 SWITCH，用户的回答被丢弃

**可行性**: ⚠️ 技术上可行，但可靠性存疑

---

#### 方案C：混合架构——L1 留在图外，L2 统一进图

**原理**：不追求"全部在图内"，承认 L1 路由应该在图外。
L2 子图统一到一张大图内共享 OverAllState，但 L1 路由仍由 Controller/GES 管理。

```
BankGraph (L2 统一大图):
  bankRouter → {
    "TRANSFER": transferWorkflow,
    "BILL": billWorkflow,
    "WEALTH_CONSULT": wealthConsultWorkflow,
    "WEALTH_INTERPRET": wealthInterpretWorkflow
  }

Controller + GES (L1 在图外):
  - 首次: executeGraph(bankGraph, intent, userInput, sessionId)
  - FOLLOW: resumeGraph(bankGraph, intent, userInput, sessionId)
  - SWITCH: updateState(asNode="bankRouter") → 重路由
  - ContextRouter/IntentRouter 仍在图外
```

**优点**：
- L1 路由在图外，自然拦截——不存在意图切换问题
- L2 共享 OverAllState——跨域上下文天然共享
- 改动最小——只改 L2 的图编译方式，L1 逻辑基本不动
- 与当前架构的 resume 流程一致

**缺点**：
- L1 不在图内，不完全是"统一大图"
- L1 的 activeAgent/suspendedAgents 仍需手动管理（与当前架构相同）
- 跨域上下文虽然 OverAllState 里有，但 L1 仍需手动决定是否传递

**可行性**: ✅ 最务实，风险最低

### 4.3 方案对比总结

| 维度 | 方案A (asNode重路由) | 方案B (L2意图检测) | 方案C (混合架构) |
|------|------|------|------|
| 意图切换正确性 | ✅ L1 判断，准确 | ⚠️ L2 检测，可能误判 | ✅ L1 在图外，准确 |
| 路由逻辑位置 | ⚠️ 部分在图外(Controller) | ✅ 全在图内 | ❌ L1 在图外 |
| 跨域上下文共享 | ✅ OverAllState 共享 | ✅ OverAllState 共享 | ✅ OverAllState 共享 |
| 额外 LLM 开销 | 每次resume +1次 | 每次ask +1次 | 无额外开销 |
| 改动范围 | 大（L0/L1/L2全改） | 大（L0/L1/L2全改） | 中（仅改L2编译方式） |
| 风险 | 中（asNode耦合） | 高（意图检测不可靠） | 低 |
| "统一大图"纯粹度 | 中 | 高 | 低 |

## 5. 推荐方案：方案C（混合架构）

### 5.1 推荐理由

1. **意图切换是最关键的交互模式**——手机银行场景中用户随时可能切换意图，
   这个检测必须准确，不能依赖 LLM 在 L2 节点内的判断。

2. **当前架构的 L1 在图外是正确的分层决策**——L1 的 ContextRouter +
   IntentRouter 做的是"理解用户意图"的工作，这天然是图外调度，
   不是图内节点应该做的事。

3. **跨域上下文共享是主要收益**——这个收益在方案C中同样可以实现，
   不需要 L1 在图内。

4. **风险最低**——方案C只改 L2 的图编译方式（从独立编译变为统一编译），
   L1 和 Controller 逻辑基本不动。

### 5.2 方案C 详细设计

#### 5.2.1 图结构

```java
// 新增: BankGraphConfig.java — 统一大图配置
@Configuration
public class BankGraphConfig {

    @Bean("bankGraph")
    public CompiledGraph bankGraph(
            TransferGraphConfig transferConfig,
            BillQueryGraphConfig billConfig,
            WealthConsultGraphConfig wealthConsultConfig,
            WealthInterpretGraphConfig wealthInterpretConfig) throws GraphStateException {

        // 统一 KeyStrategyFactory（包含所有域的字段）
        KeyStrategyFactory factory = createUnifiedKeyStrategyFactory(
                transferConfig, billConfig, wealthConsultConfig, wealthInterpretConfig);

        // 构建 L2 子图（保持现有逻辑不变）
        StateGraph transferWorkflow = transferConfig.buildWorkflow();
        StateGraph billWorkflow = billConfig.buildWorkflow();
        StateGraph wealthConsultWorkflow = wealthConsultConfig.buildWorkflow();
        StateGraph wealthInterpretWorkflow = wealthInterpretConfig.buildWorkflow();

        // 统一大图
        StateGraph bankGraph = new StateGraph(factory)
            .addNode("bankRouter", node_async(this::bankRouterNode))
            .addNode("transfer", transferWorkflow)           // 子图嵌入
            .addNode("bill", billWorkflow)                   // 子图嵌入
            .addNode("wealthConsult", wealthConsultWorkflow) // 子图嵌入
            .addNode("wealthInterpret", wealthInterpretWorkflow) // 子图嵌入
            .addEdge(START, "bankRouter")
            .addConditionalEdges("bankRouter",
                edge_async(state -> {
                    String intent = (String) state.value("_intent").orElse("");
                    return switch (intent) {
                        case "TRANSFER" -> "transfer";
                        case "BILL_QUERY" -> "bill";
                        case "WEALTH_CONSULT" -> "wealthConsult";
                        case "WEALTH_INTERPRET" -> "wealthInterpret";
                        default -> "FINISH";
                    };
                }),
                Map.of(
                    "transfer", "transfer",
                    "bill", "bill",
                    "wealthConsult", "wealthConsult",
                    "wealthInterpret", "wealthInterpret",
                    "FINISH", END
                ))
            // 每个子图完成后回到 bankRouter（循环）
            .addEdge("transfer", "bankRouter")
            .addEdge("bill", "bankRouter")
            .addEdge("wealthConsult", "bankRouter")
            .addEdge("wealthInterpret", "bankRouter");

        // 统一编译：一个 MemorySaver，收集所有 interruptBefore 节点
        return bankGraph.compile(CompileConfig.builder()
            .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
            .interruptBefore(collectAllInterruptNodes(transferConfig, billConfig,
                    wealthConsultConfig, wealthInterpretConfig))
            .recursionLimit(50)
            .build());
    }
}
```

#### 5.2.2 L2 GraphConfig 改造

当前每个 L2 GraphConfig 独立编译，改为只构建 StateGraph（不编译）：

```java
// TransferGraphConfig.java 改造
// 旧: @Bean transferGraph → 独立 compile()
// 新: buildWorkflow() → 只返回 StateGraph，由 BankGraphConfig 统一编译

public StateGraph buildWorkflow() {
    return new StateGraph(createKeyStrategyFactory())  // 仍用自己的 KeyStrategyFactory
        .addNode("extractParams", node_async(this::extractParamsNode))
        .addNode("paramRouter", node_async(this::paramRouterNode))
        .addNode("askReceiver", askNode("askReceiver", this::askReceiverLogic))
        .addNode("askAmount", askNode("askAmount", this::askAmountLogic))
        .addNode("executeTransfer", node_async(this::executeTransferNode))
        .addNode("cancelExecution", node_async(this::cancelExecutionNode))
        .addEdge(START, "extractParams")
        .addConditionalEdges("extractParams", createCancelAwareRouter(),
            Map.of("ASK_RECEIVER", "askReceiver",
                   "ASK_AMOUNT", "askAmount",
                   "ALL_GOOD", "executeTransfer",
                   "CANCEL", "cancelExecution"))
        // ... 其余边不变
        // ★ 不再调用 compile()！
        // ★ 不再创建自己的 MemorySaver！
}
```

#### 5.2.3 L1 / GES / Controller 改造

L1 逻辑基本不变，但 GES 从操作"独立 CompiledGraph"变为操作"统一 bankGraph"：

```java
// GraphExecutionEngine.java 改造
@Service
public class GraphExecutionEngine {

    private final CompiledGraph bankGraph;  // ★ 统一大图

    // 首次执行：传 intent 让 bankRouter 路由
    public WorkflowOutput executeGraph(String intent, String userInput, String sessionId) {
        RunnableConfig config = threadConfig(sessionId);
        Map<String, Object> input = Map.of(
            "messages", userInput,
            "_latestUserInput", userInput,
            "_intent", intent,           // ★ 告诉 bankRouter 路由到哪个子图
            "_question", null
        );
        bankGraph.stream(input, config).blockLast();
        return checkGraphResult(bankGraph, config, intent);
    }

    // FOLLOW resume：直接从 checkpoint 恢复（与当前逻辑一致）
    public WorkflowOutput resumeGraph(String intent, String userInput, String sessionId) {
        RunnableConfig config = threadConfig(sessionId);
        RunnableConfig updatedConfig = bankGraph.updateState(
            config, Map.of("_latestUserInput", userInput), null);
        bankGraph.stream(null, updatedConfig).blockLast();
        return checkGraphResult(bankGraph, config, intent);
    }

    // SWITCH 重路由：用 asNode=bankRouter 强制重新路由
    public WorkflowOutput switchGraph(String newIntent, String userInput, String sessionId) {
        RunnableConfig config = threadConfig(sessionId);
        Map<String, Object> updateValues = Map.of(
            "_latestUserInput", userInput,
            "_intent", newIntent
        );
        // ★ asNode="bankRouter" → 从 bankRouter 重新路由到新子图
        RunnableConfig updatedConfig = bankGraph.updateState(config, updateValues, "bankRouter");
        bankGraph.stream(null, updatedConfig).blockLast();
        return checkGraphResult(bankGraph, config, newIntent);
    }
}
```

#### 5.2.4 跨域上下文自动共享

因为所有 L2 子图共享 OverAllState，跨域切换时上下文自动可用：

```
用户: "转账500元给我妈"
→ TransferWorkflow 执行
→ OverAllState: {transfer.receiver: "我妈", transfer.amount: 500, ...}
→ 转账完成

用户: "推荐理财产品"
→ L1 ContextRouter 判断 SWITCH → GES.switchGraph("WEALTH_CONSULT", ...)
→ WealthConsultWorkflow 执行
→ ★ extractParamsNode 可以读到 transfer.amount=500（同一个 OverAllState）
→ ★ 可以利用这个上下文做更精准的推荐
```

### 5.3 仍需解决的问题

#### 问题1：子图展平后的节点 ID 冲突

当前各 L2 子图有同名节点（如 extractParams, paramRouter）。
展平后会被加前缀（transfer.extractParams, bill.extractParams），
但前缀格式取决于框架的 formatId 实现——需要确认。

#### 问题2：KeyStrategyFactory 合并冲突

各 L2 子图的 KeyStrategyFactory 都注册了公共字段（messages, _question 等）。
合并时需要去重。框架的 ProcessedNodesEdgesAndConfig 使用 putIfAbsent，
先注册的优先——需要确保公共字段只注册一次。

#### 问题3：interruptBefore 节点收集

统一图的 interruptBefore 列表需要包含所有 L2 子图的 ask 节点。
当前 askNode() 自动收集到 interruptNodes 列表，
但子图展平时这些节点会被加前缀——需要确认展平后 interruptBefore 列表是否正确。

#### 问题4：子图 END → bankRouter 的循环

子图完成后回到 bankRouter，bankRouter 需要判断是结束还是继续。
如果 _isFinal=true，bankRouter 路由到 END；否则继续循环。

#### 问题5：cancel 信号在统一图中的传播

当前 cancel 通过 GES.cancelGraph 注入 _cancelSignal 后 resume。
统一图中，cancel 逻辑在 L2 子图的 cancelAwareExtractParams/cancelAwareAsk 中，
应该仍然有效——因为它们读的是 OverAllState 中的 _cancelSignal。

## 6. 实施步骤

### Phase 1: L2 子图解耦（不改变运行行为）

1. 各 L2 GraphConfig 增加 `buildWorkflow()` 方法，返回 StateGraph 而非 CompiledGraph
2. 保留独立的 `@Bean` 编译方式（向后兼容）
3. 验证 buildWorkflow() 返回的 StateGraph 能独立编译且行为不变

### Phase 2: 统一大图编译

1. 创建 BankGraphConfig，统一编译所有 L2 子图
2. 统一 KeyStrategyFactory（合并去重）
3. 统一 MemorySaver
4. 编写集成测试，验证单意图流程正常

### Phase 3: GES 适配

1. GraphExecutionEngine 改为操作统一 bankGraph
2. 新增 switchGraph() 方法（使用 asNode 重路由）
3. L1 适配：FOLLOW 仍用 resumeGraph，SWITCH 改用 switchGraph
4. 验证跨域切换流程

### Phase 4: 跨域上下文利用

1. L2 extractParamsNode 读取跨域字段（如 transfer.amount → 理财推荐参考）
2. 验证跨域上下文确实可用

## 7. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 子图展平后节点 ID 格式不确定 | 中 | 高 | 先写 POC 验证 formatId 行为 |
| interruptBefore 在展平后不生效 | 低 | 高 | 框架源码已确认展平时会合并 interruptBefore |
| asNode="bankRouter" 导致意外的 LLM 调用 | 中 | 中 | bankRouter 的边条件需兼容 asNode 场景 |
| OverAllState 膨胀影响性能 | 低 | 中 | 字段数量有限（<30），影响可忽略 |
| 多域同时中断的 checkpoint 冲突 | 低 | 高 | 方案C 中同一时刻只有一个活跃子图 |

## 8. 开放问题

1. **框架 formatId 前缀格式**：需要写 POC 确认展平后节点 ID 的确切格式
2. **asNode 在 resume 中的行为**：需要确认 updateState(asNode) 后 stream(null)
   是否确实从 asNode 的路由结果开始执行
3. **方案A vs 方案C 的最终决策**：是否要在 Phase 3 之后尝试方案A（L1 进图）？
