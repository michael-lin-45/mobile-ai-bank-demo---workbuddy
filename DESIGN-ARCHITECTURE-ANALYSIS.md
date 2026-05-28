# 架构分析：当前 L0/L1/L2 独立图模式 vs 官方图模式

## 1. 问题背景

当前 AI 手机银行采用 L0(入口) → L1(领域调度) → L2(独立子图) 三层架构，每个 L2 子图
(Transfer/Bill/WealthConsult/WealthInterpret) 是独立的 CompiledGraph，各自持有
MemorySaver 和 OverAllState。

Spring AI Alibaba 官方提供了四种图编排模式：
- Supervisor 模式：单图内 supervisor 节点路由到 worker 节点
- 子图模式 A：addNode("name", compiledSubGraph) — 共享 OverAllState
- 子图模式 B：addNode("name", stateGraph) — 共享 OverAllState
- 子图模式 C：NodeAction 包装 compiledGraph.invoke() — 状态隔离，手动映射

**核心问题**：这些官方模式能否用于我们的架构？是否应该迁移？还是保持当前独立图架构？

---

## 2. 架构全景对比

### 2.1 当前架构：独立图 + L1 手动编排

```
BankController (L0)
  → DomainRouter → DomainHandler
    → ContextRouter(Phase1) → IntentRouter/Resolver(Phase2)
      → GES.executeGraph(独立CompiledGraph)
      → GES.resumeGraph(独立CompiledGraph)

每个 L2:
  TransferGraph ── MemorySaver@A ── OverAllState@A (transfer.* keys)
  BillQueryGraph ── MemorySaver@B ── OverAllState@B (bill.* keys)
  WealthConsultGraph ── MemorySaver@C ── OverAllState@C (wealth.* keys)
  WealthInterpretGraph ── MemorySaver@D ── OverAllState@D (wealth.* keys)
```

**状态隔离**：各图 OverAllState 完全独立，互不可见。

**跨图协调**：L1 层手动实现，包括：
- activeAgent 跟踪（谁在执行）
- suspendedAgents 管理（谁被挂起）
- ContextRouter 三路决策（FOLLOW/SWITCH/RESUME）
- IntentResolver 消歧
- Auto-upgrade 保护（SWITCH→FOLLOW 防误判）
- 跨域 REROUTE（WEALTH→TRANSFER）

### 2.2 官方模式 A：Supervisor（单图共享状态）

```
Single StateGraph:
  START → supervisor → {
    "transfer": transferNode,
    "bill": billNode,
    "wealth_consult": wealthConsultNode,
    "wealth_interpret": wealthInterpretNode,
    "FINISH": END
  }
  → supervisor → ...

单一 OverAllState:
  { messages, next, transfer.receiver, transfer.amount,
    bill.timePeriod, bill.expenseType, wealth.riskLevel, ... }
```

**状态共享**：所有 agent 共享一个 OverAllState，通过共享 key 自然通信。

**路由**：supervisor 节点（LLM）决定 next agent。

**适用场景**：单流顺序编排，research→write→review 这类线性流程。

### 2.3 官方模式 B：子图直接嵌入（addNode + CompiledGraph/StateGraph）

```
ParentGraph:
  START → routerNode → {
    "TRANSFER": transferCompiledGraph,    ← 直接 addNode(name, compiledGraph)
    "BILL": billCompiledGraph,
    "WEALTH_CONSULT": wealthConsultCompiledGraph,
    "WEALTH_INTERPRET": wealthInterpretCompiledGraph
  }

共享 OverAllState：父子图通过共享 key 通信。
- 父图传给子图多余的 key 会被子图忽略
- 子图返回多余的 key 会被父图忽略
- 只有共同注册的 key 会自动映射
```

官方文档明确说明：
> 如果您向子图节点传递额外的键（即，除了共享键之外），子图节点将忽略它们。
> 同样，如果您从子图返回额外的键，父图将忽略它们。

**关键**：父子图**必须共享至少一个 key** 才能通信。不共享的 key 自动隔离。

### 2.4 官方模式 C：NodeAction 包装调用（状态隔离 + 手动映射）

```java
public class TransferSubGraphNode implements NodeAction {
    private final CompiledGraph transferGraph;

    @Override
    public Map<String, Object> apply(OverAllState parentState) {
        // 1. 从父状态提取子图输入（手动映射）
        String input = (String) parentState.value("userInput").orElse("");
        Map<String, Object> subInput = Map.of("messages", input);

        // 2. 执行子图（独立状态空间）
        OverAllState subResult = transferGraph.invoke(subInput);

        // 3. 将子图结果映射回父状态
        String output = (String) subResult.value("_outputContent").orElse("");
        return Map.of("result", output);
    }
}
```

**状态完全隔离**：子图有自己独立的 OverAllState，父图只看到映射后的结果。

**这正是我们当前 GES 做的事情的"官方版"**，区别在于：
- 官方：NodeAction 在父图节点内调用子图，同步完成
- 我们：L1(GES) 在父图外部调用子图，支持 interruptBefore/resume

---

## 3. 官方四种模式对我们的适配性逐项分析

### 3.0 预备：我们架构的核心需求清单

| # | 需求 | 来源场景 |
|---|------|---------|
| R1 | 多意图挂起/恢复 | 转账到一半→查账单→继续转账 |
| R2 | Human-in-the-loop (interruptBefore) | 每个ask节点中断等待用户输入 |
| R3 | 跨域上下文感知 | "刚转了500，推荐理财" |
| R4 | ContextRouter三路决策 | FOLLOW/SWITCH/RESUME |
| R5 | 消歧 | "推荐理财"→CONSULT还是INTERPRET |
| R6 | Auto-upgrade纠错 | ContextRouter误判时自动纠正 |
| R7 | 跨域REROUTE | WEALTH域识别出TRANSFER意图 |
| R8 | 域内状态隔离 | transfer.receiver不应污染bill状态 |
| R9 | 独立开发/扩展 | 新增贷款域不影响现有域 |

### 3.1 模式A：Supervisor — 能用吗？

**能实现的需求**：R2(部分), R8(部分)

**不能实现的需求**：R1, R3, R4, R5, R6, R7, R9

| 需求 | 适配性 | 原因 |
|------|--------|------|
| R1 多流挂起/恢复 | ❌ | 单一OverAllState只有一个执行位置，无法同时维护多个中断点 |
| R2 interruptBefore | ⚠️ | 支持，但interruptBefore列表需包含所有agent的所有ask节点 |
| R3 跨域上下文 | ✅ | 天然共享OverAllState，最自然的优势 |
| R4 三路决策 | ❌ | supervisor只做"路由到谁"，无FOLLOW/SWITCH/RESUME语义 |
| R5 消歧 | ⚠️ | 可增加消歧节点，但增加图复杂度 |
| R6 Auto-upgrade | ❌ | supervisor一次决策，无纠错机制 |
| R7 跨域REROUTE | ❌ | 所有agent在同一个图里，没有"域"的概念 |
| R8 状态隔离 | ❌ | 全局共享OverAllState，需靠key前缀人工隔离 |
| R9 独立扩展 | ❌ | 新增agent需改全局KeyStrategyFactory和图结构 |

**致命问题**：R1（多流挂起/恢复）无法实现。

当Transfer执行到askAmount被interruptBefore中断：
1. 整个supervisorGraph的stream()返回
2. 用户说"查账单"→需要恢复supervisorGraph
3. 但框架的resume只能从上次中断点（askAmount）继续
4. **无法跳到bill节点**——updateState只能更新字段值，无法控制执行路径跳转

### 3.2 模式B：子图直接嵌入 — 能用吗？

**表面看最接近**——可以把TransferGraph/BillGraph等作为子图嵌入父图。

**关键问题**：子图共享父图OverAllState，看似解决了R3和R8。

但实际上：

**问题1：interruptBefore如何穿透子图？**

官方文档说"支持中断（通过返回InterruptionMetadata）"，但：
- 子图内部的interruptBefore是在子图的CompiledGraph编译时配置的
- 父图的stream()执行到子图节点时，子图作为一个整体执行
- 子图内部的中断**是否会传播到父图的stream()**？文档未明确
- 如果不传播：子图的human-in-the-loop完全失效
- 如果传播：父图的resume如何知道要恢复到子图内部的哪个节点？

**问题2：多流挂起/恢复仍不可行**

和Supervisor模式同样的问题——父图只有一个执行位置，
无法同时维护"Transfer子图中断在askAmount"+"Bill子图中断在askTime"。

**问题3：KeyStrategyFactory必须全量合并**

父图和子图共享OverAllState意味着KeyStrategyFactory必须包含所有域的所有字段：
```java
parentKeyStrategyFactory = {
    // 公共
    "messages", "_latestUserInput", "_question", "_cancelSignal", ...
    // Transfer
    "transfer.receiver", "transfer.amount", "transfer.purpose",
    // Bill
    "bill.timePeriod", "bill.expenseType",
    // Wealth
    "wealth.riskLevel", "wealth.focusArea", "wealth.productName",
    // 未来...
}
```
新增域必须改全局KeyStrategyFactory——违反R9。

### 3.3 模式C：NodeAction包装调用 — 这就是我们现在的做法！

```java
// 官方模式C
public class TransferSubGraphNode implements NodeAction {
    public Map<String, Object> apply(OverAllState parentState) {
        Map<String, Object> subInput = Map.of("messages", input);
        OverAllState subResult = transferGraph.invoke(subInput);  // 独立状态
        return Map.of("result", output);  // 手动映射
    }
}

// 我们的当前架构
public WorkflowOutput executeGraph(CompiledGraph graph, ...) {
    Map<String, Object> input = Map.of("messages", userInput, "_latestUserInput", userInput);
    graph.stream(input, config).blockLast();  // 独立状态，支持interruptBefore+checkpoint
    return checkGraphResult(graph, config, intent);  // 手动映射→WorkflowOutput
}
```

**本质上是同一种模式**，区别在于：

| 维度 | 官方模式C | 我们的架构 |
|------|----------|-----------|
| 调用方式 | NodeAction内部invoke() | L1(GES)外部stream() |
| 状态隔离 | ✅ 子图独立OverAllState | ✅ 子图独立OverAllState |
| 状态映射 | 手动inputMapper/outputMapper | 手动input Map + checkGraphResult |
| interruptBefore | ❌ 不支持（invoke是同步调用） | ✅ 支持（stream+MemorySaver） |
| resume | ❌ 不支持 | ✅ 支持（updateState+stream(null)） |
| 挂起/恢复 | ❌ 不支持 | ✅ 支持（L1的suspendedAgents） |

**关键差异**：官方模式C是"同步调用"——子图必须一次性执行完成。
我们的架构是"异步流式+checkpoint"——子图可以中断、保存、恢复。

官方模式C的文档也明确指出：
> 注意事项: 中断支持由您自己实现

**结论：我们的架构就是官方模式C的增强版，增加了interruptBefore/resume/挂起恢复能力。**

### 3.4 能否混合使用？—— 域内子图 + 域间独立

一个值得探讨的方向：**域内用官方子图模式，域间保持独立图**。

例如，Wealth域目前有WEALTH_CONSULT和WEALTH_INTERPRET两个意图，
由MultiSubAgentDomainService管理。能否把它们做成一个Wealth父图内的两个子图？

```
WealthParentGraph (单一CompiledGraph):
  START → wealthRouter → {
    "CONSULT": wealthConsultSubGraph,   ← 官方子图模式
    "INTERPRET": wealthInterpretSubGraph,
    "DISAMBIGUATE": disambiguatorNode,
    "FINISH": END
  }

BankController (L0) → DomainRouter → WealthDomainService (L1)
  → GES.executeGraph(wealthParentGraph, ...)
```

**优势**：
- Wealth域内部路由（CONSULT/INTERPRET/消歧）在图内完成，L1变薄
- 消歧变成图内节点，不需要L1的disambiguationStates
- 域内状态共享（如用户已说"稳健型"，CONSULT和INTERPRET都能看到）

**问题**：
1. **interruptBefore穿透**：WealthConsult子图内部有askRiskLevel被interruptBefore中断，
   这个中断能否传播到wealthParentGraph的stream()？如果不行，human-in-the-loop失效。
2. **域内挂起/恢复**：CONSULT执行到一半→用户说"解读XX产品"→需要切换到INTERPRET。
   单一wealthParentGraph能否做到？同样面临"多中断点"问题。
3. **复杂度收益有限**：Wealth域只有2个意图，消歧逻辑不复杂，
   迁移到子图模式节省的代码量有限。

**暂不推荐**。如果框架未来明确支持子图interruptBefore传播和多中断点，
可以重新评估。

---

## 3.5 OverAllState 深度剖析：统一 threadId 为什么不能统一 OverAllState？

这是理解当前架构的最关键问题。我们统一了 threadId = sessionId，直觉上
"同一个 threadId 应该对应同一个状态"，但实际并非如此。

### 3.5.1 MemorySaver 的真实存储结构（源码级分析）

```java
// MemorySaver.java - 框架源码
public class MemorySaver implements BaseCheckpointSaver {
    // ★ 核心存储：Map<threadId, LinkedList<Checkpoint>>
    final Map<String, LinkedList<Checkpoint>> _checkpointsByThread = new HashMap<>();

    // 读/写都通过 threadId 索引
    protected final <T> T loadOrInitCheckpoints(RunnableConfig config, ...) {
        var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        return transformer.tryApply(
            _checkpointsByThread.computeIfAbsent(threadId, k -> new LinkedList<>())
        );
    }
}
```

**关键发现**：
- MemorySaver 的唯一索引键是 **threadId**
- 同一个 threadId 下存储的是一个 **LinkedList<Checkpoint>**（按时间倒序，最新在前）
- 没有任何 graph-identity 参与索引

### 3.5.2 Checkpoint 的真实内容（源码级分析）

```java
// Checkpoint.java - 框架源码
public class Checkpoint {
    private final String id;                    // UUID
    private Map<String, Object> state;          // ★ OverAllState.data() — 纯键值对
    private String nodeId;                      // ★ 当前执行到的节点ID
    private String nextNodeId;                  // ★ 下一个要执行的节点ID
}
```

**关键发现**：
- Checkpoint 不仅存储 state（数据），还存储 **nodeId + nextNodeId**（执行位置）
- nodeId 和 nextNodeId 是**图结构相关的**——"askAmount" 在 TransferGraph 里存在，
  在 BillQueryGraph 里不存在
- Checkpoint 不包含任何 graph-identity 标识

### 3.5.3 三个层次的隔离原因

**层次1：不同 MemorySaver 实例 → 物理隔离**

```
当前代码（AbstractGraphConfig.createSaverConfig）：
  protected SaverConfig createSaverConfig() {
      return SaverConfig.builder()
          .register(new MemorySaver())    // ★ 每次 new 一个！
          .build();
  }

结果：
  TransferGraph  →  CompileConfig  →  MemorySaver@A  →  _checkpointsByThread = HashMap@A
  BillQueryGraph →  CompileConfig  →  MemorySaver@B  →  _checkpointsByThread = HashMap@B

  MemorySaver@A["user-123"]  =  [Checkpoint{nodeId=extractParams, nextNodeId=askAmount, state={transfer.receiver=我妈}}]
  MemorySaver@B["user-123"]  =  [Checkpoint{nodeId=extractParams, nextNodeId=askTime, state={bill.expenseType=null}}]

  两个完全独立的 HashMap，threadId 相同但物理存储不同 → 数据天然隔离
```

**层次2：即使共享 MemorySaver → Checkpoint 冲突**

假设我们改成共享一个 MemorySaver 实例：

```
SharedMemorySaver["user-123"] = LinkedList<Checkpoint>

时刻1: TransferGraph.stream(input, {threadId: "user-123"})
  → 执行 extractParams → askAmount 被 interruptBefore 中断
  → addCheckpoint: Checkpoint{nodeId=extractParams, nextNodeId=askAmount, state={...}}
  → LinkedList: [cp1: {nodeId=extractParams, nextNodeId=askAmount}]

时刻2: 用户说"查账单" → BillQueryGraph.stream(input, {threadId: "user-123"})
  → input != null → stateCreate() 创建全新 OverAllState（不看已有 checkpoint）
  → 执行 extractParams → askTime 被 interruptBefore 中断
  → addCheckpoint: Checkpoint{nodeId=extractParams, nextNodeId=askTime, state={...}}
  → LinkedList: [cp2: {nodeId=extractParams, nextNodeId=askTime}, cp1: {nodeId=extractParams, nextNodeId=askAmount}]

  ★ cp2 覆盖了 cp1 成为最新 checkpoint！
  ★ 但 cp2 的 nextNodeId=askTime 在 BillQueryGraph 里才有意义
  ★ 如果 resume TransferGraph，会用 get(config) 取最新 cp2，发现 nextNodeId=askTime
  ★ 但 TransferGraph 根本没有 askTime 节点 → 执行错误！
```

**层次3：即使共享 MemorySaver + 同一 KeyStrategyFactory → 执行位置冲突**

假设我们更进一步，所有图共享 KeyStrategyFactory（包含所有域的所有字段）：

```
UnifiedKeyStrategyFactory = {
    messages, _latestUserInput, _question, _cancelSignal, _outputContent, _paramName,
    transfer.receiver, transfer.amount, transfer.purpose,
    bill.timePeriod, bill.expenseType,
    wealth.riskLevel, wealth.focusArea, wealth.productName
}

时刻1: TransferGraph 中断 → Checkpoint{nextNodeId=askAmount, state={transfer.receiver=我妈}}
时刻2: BillQueryGraph 中断 → Checkpoint{nextNodeId=askTime, state={bill.timePeriod=Q1}}

问题1: resume 时框架取最新 checkpoint（askTime），但当前要恢复的是 TransferGraph
       → TransferGraph 没有 askTime → GraphStateException

问题2: Checkpoint 不存 graph-identity，框架无法区分这个 checkpoint 属于哪个图
       → 不知道应该用哪个图的 nodeFactories 和 edges 来继续执行
```

### 3.5.4 根本原因总结：三层隔离，互不连通

```
                  ┌─────────────────────────────────────────┐
                  │ 如果统一 OverAllState，需要同时满足：      │
                  │                                         │
                  │  1. 共享 MemorySaver 实例                │
                  │  2. 共享 KeyStrategyFactory（所有域字段）  │
                  │  3. 共享图结构（nodeFactories + edges）   │
                  │                                         │
                  │  而 3 = 合并为一个大图 = Supervisor 模式  │
                  └─────────────────────────────────────────┘

当前架构:
  TransferGraph  → MemorySaver@A + KeyStrategy@A + Nodes@A  ← 三者绑定
  BillQueryGraph → MemorySaver@B + KeyStrategy@B + Nodes@B  ← 三者绑定

  threadId 只是 MemorySaver 内部的索引键，不是跨 MemorySaver 的全局协调器。
  就像两个独立的 HashMap，put 进相同 key，也是各存各的。
```

### 3.5.5 如果想实现"共享 MemorySaver 但不共享图结构"？

理论上可以在 threadId 中嵌入 graph-identity：

```java
// 方案A：threadId 嵌入图标识
transferGraph:   threadId = "user-123:TRANSFER"
billQueryGraph:  threadId = "user-123:BILL_QUERY"

// 这样即使共享 MemorySaver，各图的 checkpoint 也不会冲突：
_checkpointsByThread = {
    "user-123:TRANSFER":    [cp{nextNodeId=askAmount, ...}],
    "user-123:BILL_QUERY":  [cp{nextNodeId=askTime, ...}]
}
```

但这只是**避免冲突**，不是**共享状态**。各图的 OverAllState 仍然独立。

### 3.5.6 真正的跨图状态共享只有两条路

```
路径1: 合并为一个大图（Supervisor/子图模式）
  → 一个 CompiledGraph，一个 MemorySaver，一个 KeyStrategyFactory，一个 OverAllState
  → 自然共享，但失去多流并发能力（前面已分析，不可行）

路径2: 在图外部手动桥接（当前架构 + 跨域上下文传递）
  → 各图保持独立 OverAllState
  → L1 层在 executeGraph/resumeGraph 时，将跨域信息作为 input 的一部分注入
  → 不共享 OverAllState，但共享关键上下文
  → 保持多流并发能力
```

**路径2 是我们选择的方向**——不追求 OverAllState 统一，
而是通过 L1 层的上下文传递实现"逻辑上的状态共享"。

---

## 3.6 官方 ParentGraph-SubGraph vs 我们的 L0-L1-L2：执行模型本质差异

### 3.6.1 官方子图的"展平"机制（源码级分析）

官方的 `addNode("transfer", stateGraph)` 或 `addNode("transfer", compiledSubGraph)`
在编译时做了什么？源码 `ProcessedNodesEdgesAndConfig.process()` 给出了答案：

```java
// ProcessedNodesEdgesAndConfig.java - 框架源码
static ProcessedNodesEdgesAndConfig process(StateGraph stateGraph, CompileConfig config) {
    var subgraphNodes = stateGraph.nodes.onlySubStateGraphNodes();
    if (subgraphNodes.isEmpty()) {
        return new ProcessedNodesEdgesAndConfig(stateGraph, config);  // 无子图，直接返回
    }

    var nodes = new StateGraph.Nodes(stateGraph.nodes.exceptSubStateGraphNodes());
    var edges = new StateGraph.Edges(stateGraph.edges.elements);
    Map<String, KeyStrategy> keyStrategyMap = new LinkedHashMap<>();

    for (var subgraphNode : subgraphNodes) {
        var sgWorkflow = subgraphNode.subGraph();

        // ★ 1. 合并 KeyStrategy
        subgraphNode.keyStrategies().forEach(keyStrategyMap::putIfAbsent);
        ProcessedNodesEdgesAndConfig processedSubGraph = process(sgWorkflow, config);  // 递归处理
        processedSubGraph.keyStrategyMap().forEach(keyStrategyMap::putIfAbsent);

        // ★ 2. 展平节点：子图节点 → 前缀化的父图节点
        //    transfer子图的 extractParams → transfer.extractParams
        //    transfer子图的 askAmount → transfer.askAmount
        processedSubGraphNodes.elements.stream()
            .map(n -> n.withIdUpdated(subgraphNode::formatId))
            .forEach(nodes.elements::add);

        // ★ 3. 展平边：子图内部边 → 前缀化的父图边
        processedSubGraphEdges.elements.stream()
            .filter(e -> !Objects.equals(e.sourceId(), START))
            .filter(e -> !e.anyMatchByTargetId(END))
            .map(e -> e.withSourceAndTargetIdsUpdated(subgraphNode::formatId, ...))
            .forEach(edges.elements::add);

        // ★ 4. 展平 interruptBefore：
        //    如果配置了 interruptBefore("transfer")，自动替换为子图入口节点
        //    "transfer" → "transfer.extractParams"（子图的第一个节点）
        interruptsBefore = interruptsBefore.stream()
            .map(interrupt -> Objects.equals(subgraphNode.id(), interrupt)
                ? sgEdgeStartRealTargetId : interrupt)
            .collect(Collectors.toUnmodifiableSet());

        // ★ 5. 展平 START/END：
        //    子图的 START → 父图指入子图入口的边
        //    子图的 END → 父图指出子图出口的边
    }

    return new ProcessedNodesEdgesAndConfig(nodes, edges, interruptsBefore, interruptsAfter, keyStrategyMap);
}
```

**核心发现：官方子图在编译时被完全展平（flatten）为父图的一部分。**

编译后的效果：

```
编译前（逻辑视图）:
  ParentGraph:
    START → router → {
      "TRANSFER": TransferSubGraph,
      "BILL": BillSubGraph
    }
  TransferSubGraph:
    START → extractParams → paramRouter → {
      "ASK_AMOUNT": askAmount,
      "ALL_GOOD": executeTransfer
    }
    askAmount → paramRouter → ...
    executeTransfer → END

编译后（实际物理结构）:
  ParentGraph (展平后):
    START → router → {
      "TRANSFER": transfer.extractParams,     ← 子图节点加上前缀
      "BILL": bill.extractParams
    }
    transfer.extractParams → transfer.paramRouter → {
      "ASK_AMOUNT": transfer.askAmount,
      "ALL_GOOD": transfer.executeTransfer
    }
    transfer.askAmount → transfer.paramRouter → ...
    transfer.executeTransfer → (回到router)
    bill.extractParams → bill.paramRouter → ...
    ...
```

**这就是为什么官方子图能共享 OverAllState 的根本原因——它们编译后就是同一个图的节点！**

### 3.6.2 官方模式 vs 我们的 L0-L1-L2：执行模型对比

| 维度 | 官方 ParentGraph-SubGraph | 我们的 L0-L1-L2 |
|------|--------------------------|----------------|
| **编译时** | 子图被展平，所有节点合并到一个 CompiledGraph | 各子图独立编译，4个独立的 CompiledGraph |
| **运行时** | 一次 stream() 调用执行整个图（包括子图节点） | 多次 stream() 调用，每次执行一个子图 |
| **OverAllState** | 全图共享一个 OverAllState + KeyStrategyMap | 各子图独立 OverAllState + KeyStrategyMap |
| **MemorySaver** | 全图共享一个 MemorySaver | 各子图独立 MemorySaver |
| **interruptBefore** | 子图内部节点自动加前缀加入父图 interruptBefore 列表 | 各子图独立配置 interruptBefore |
| **resume** | 从父图级别恢复，框架自动找到子图内的中断点 | 从子图级别恢复，L1 负责选择恢复哪个子图 |
| **节点ID** | 全局唯一（子图前缀化：transfer.askAmount） | 子图内唯一（askAmount） |
| **路由决策** | 父图内条件边决定 | L1 的 ContextRouter + IntentRouter 决定 |

### 3.6.3 关键差异：单次 stream vs 多次 stream

```
官方模式（单次 stream）:
  parentGraph.stream(input, config)
    → router节点执行 → 路由到 "TRANSFER"
    → transfer.extractParams 执行
    → transfer.paramRouter 执行
    → transfer.askAmount 即将执行 → interruptBefore!
    → ★ 整个 stream() 返回，checkpoint 保存 nextNodeId="transfer.askAmount"
    → 用户输入 → parentGraph.updateState(config, {...}, null)
    → parentGraph.stream(null, updatedConfig)   ← 恢复时仍然是同一个图
    → 从 transfer.askAmount 继续 → ... → END

  整个生命周期：一次 parentGraph.stream() → 中断 → 一次 parentGraph.stream(null) 恢复
  OverAllState 始终只有一个，MemorySaver 只有一个 threadId 的 checkpoint 列表

我们的模式（多次 stream）:
  TransferGraph.stream(input, config{threadId: "user-123"})
    → extractParams → askAmount 中断 → MemorySaver@A 存储 checkpoint

  用户说"查账单" → L1 判断 SWITCH →

  BillGraph.stream(input, config{threadId: "user-123"})    ← ★ 另一个 CompiledGraph
    → extractParams → askTime 中断 → MemorySaver@B 存储 checkpoint

  用户说"继续转账" → L1 判断 RESUME →

  TransferGraph.stream(null, updatedConfig{threadId: "user-123"})  ← ★ 又回到原 CompiledGraph
    → 从 MemorySaver@A 恢复 → askAmount 继续 → ...

  整个生命周期：3次 stream() 调用，2个 CompiledGraph，2个 MemorySaver
```

### 3.6.4 官方模式能适用我们 L0-L1-L2 的场景吗？

#### 尝试1：把 L0+L1+L2 全部合并为一个 ParentGraph

```java
StateGraph bankGraph = new StateGraph(unifiedKeyStrategyFactory)
    // L1路由节点
    .addNode("domainRouter", node_async(this::domainRouterNode))
    .addNode("contextRouter", node_async(this::contextRouterNode))
    .addNode("intentRouter", node_async(this::intentRouterNode))
    .addNode("disambiguator", node_async(this::disambiguatorNode))

    // L2子图（展平后成为 bankGraph 的节点）
    .addNode("transfer", transferStateGraph)
    .addNode("bill", billStateGraph)
    .addNode("wealthConsult", wealthConsultStateGraph)
    .addNode("wealthInterpret", wealthInterpretStateGraph)

    .addEdge(START, "domainRouter")
    .addConditionalEdges("domainRouter", ..., Map.of(
        "TRANSFER", "transfer",
        "BILL", "bill",
        "WEALTH", "contextRouter",
        ...
    ))
    // 每个子图结束后回到 domainRouter
    .addEdge("transfer", "domainRouter")  // ← 但这有问题！
    .addEdge("bill", "domainRouter")
    ...;
```

**问题清单：**

**问题1：interruptBefore 时整个图的 stream() 返回**

当 transfer 子图的 askAmount 被 interruptBefore 中断：
- `bankGraph.stream()` 整个返回
- Checkpoint 保存 `{nextNodeId: "transfer.askAmount"}`
- 用户下次输入时，调用 `bankGraph.updateState()` + `bankGraph.stream(null, updatedConfig)`
- 框架从 `transfer.askAmount` 继续 → **没问题，能正常恢复**

但——

**问题2：用户说"查账单"时需要跳到另一个子图**

```
当前状态: checkpoint{nextNodeId: "transfer.askAmount"}  ← 中断在转账
用户输入: "查账单"

L1 逻辑: ContextRouter 判 SWITCH → 需要：
  1. 保存 transfer 的中间状态
  2. 跳到 bill 子图

在官方模式下：
  bankGraph.updateState(config, {_latestUserInput: "查账单"}, null)
  bankGraph.stream(null, updatedConfig)
  → 框架从 checkpoint 恢复 → nextNodeId="transfer.askAmount"
  → 继续执行 transfer.askAmount！
  → ★ 用户说"查账单"却被当成金额回答处理了！

  updateState 只能更新 state 数据，不能改变 nextNodeId！
  ★ 框架的 resume 只能从上次中断点继续，无法跳到另一个分支
```

**这是根本性障碍。** 框架的 resume 机制是"从中断点恢复"，不是"跳到新节点"。
我们的场景需要"放弃当前中断点，跳到另一个子图"——框架不支持。

**问题3：多意图挂起/恢复**

```
时刻1: transfer 子图中断 at askAmount
时刻2: 用户说"查账单" → 需要挂起 transfer，执行 bill
时刻3: bill 执行完 → 用户说"继续转账" → 需要恢复 transfer at askAmount

在官方单图模式下：
  时刻1: checkpoint{nextNodeId: "transfer.askAmount", state: {transfer.receiver: "我妈"}}
  时刻2: 无法"挂起" transfer 跳到 bill，因为：
          - 只有 ONE 个 checkpoint 列表
          - 新的 stream(input) 会覆盖旧 checkpoint
          - transfer 的中间状态（transfer.receiver="我妈"）丢失
  时刻3: 无法恢复，因为 transfer 的 checkpoint 已被 bill 的覆盖
```

**问题4：节点ID全局冲突**

展平后所有子图节点 ID 被前缀化（transfer.askAmount, bill.askAmount），
但如果两个子图有同名节点，前缀不同所以不冲突。**但 interruptBefore 列表
变成了所有子图的所有 ask 节点的并集：**

```java
interruptBefore = {
    "transfer.askReceiver", "transfer.askAmount",
    "bill.askTime", "bill.askType",
    "wealthConsult.askRiskLevel", "wealthConsult.askFocusArea",
    "wealthInterpret.askProductName"
}
```

这在技术上可行（interruptBefore 只在节点即将执行时检查），但配置膨胀且不优雅。

**问题5：L1 路由逻辑需要变成图内节点**

ContextRouter 的 FOLLOW/SWITCH/RESUME 三路决策、IntentResolver 的消歧、
Auto-upgrade 纠错——这些全部需要在图内实现为节点，而不仅仅是 LLM 路由。
Supervisor 模式的简单 LLM 路由无法覆盖这些复杂决策。

#### 尝试2：仅 L2 合并为域级 ParentGraph（保持 L0/L1 不变）

```java
// 转账域：SingleSubAgent → 直接就是 TransferGraph，无需父图

// 理财域：MultiSubAgent → 能否把 WEALTH_CONSULT 和 WEALTH_INTERPRET 合并为一个父图？
StateGraph wealthParentGraph = new StateGraph(wealthKeyStrategyFactory)
    .addNode("wealthRouter", node_async(this::wealthRouterNode))
    .addNode("wealthConsult", wealthConsultStateGraph)   // 子图
    .addNode("wealthInterpret", wealthInterpretStateGraph) // 子图
    .addEdge(START, "wealthRouter")
    .addConditionalEdges("wealthRouter", ..., Map.of(
        "CONSULT", "wealthConsult",
        "INTERPRET", "wealthInterpret",
        "DISAMBIGUATE", "disambiguatorNode",
        "FINISH", END
    ))
    .addEdge("wealthConsult", "wealthRouter")
    .addEdge("wealthInterpret", "wealthRouter");
```

**收益**：
- 消歧逻辑从 L1 移到图内（disambiguator 节点）
- CONSULT 和 INTERPRET 共享 OverAllState（如 wealth.riskLevel）
- L1 的 WealthService 变薄——只需要 executeGraph/resumeGraph

**问题**：
- 同样面临"挂起 CONSULT 启动 INTERPRET"的多流并发问题
- 框架不支持"从一个子图的中断点跳到另一个子图"
- 消歧节点本身不需要 human-in-the-loop，但图内消歧的交互模式
  （LLM路由→消歧→等待用户选择→路由到具体子图）比 L1 的直接处理更重

**暂不推荐**，但这是未来最有可能的优化方向——如果框架升级支持
"从中断点跳到新节点"或"多 checkpoint 并发管理"。

### 3.6.5 架构差异总结图

```
官方 ParentGraph-SubGraph（展平模型）:

  ┌───────────────────────────────────────────────────────┐
  │  ParentGraph (一个 CompiledGraph)                       │
  │                                                         │
  │  ┌─router──┐   ┌─transfer.extractParams──┐             │
  │  │  LLM    │──→│  LLM提取转账参数         │             │
  │  └────┬────┘   └────────────┬─────────────┘             │
  │       │                     │                            │
  │       │         ┌─transfer.paramRouter──┐                │
  │       │         │  路由到缺失参数         │                │
  │       │         └────────────┬──────────┘                │
  │       │                     │                            │
  │       │         ┌─transfer.askAmount──┐  ← interrupt    │
  │       │         │  问用户转多少钱       │    Before!      │
  │       │         └─────────────────────┘                 │
  │       │                                                 │
  │  ┌─ OverAllState ──────────────────────────┐            │
  │  │  messages: [...]                         │            │
  │  │  _latestUserInput: "转500"               │            │
  │  │  transfer.receiver: "我妈"                │            │
  │  │  transfer.amount: 500                    │            │
  │  │  bill.timePeriod: null   ← 也在里面！     │            │
  │  │  wealth.riskLevel: null  ← 也在里面！     │            │
  │  └──────────────────────────────────────────┘            │
  │                                                         │
  │  ┌─ MemorySaver ──────────────────────────┐             │
  │  │  "user-123": [cp{next: transfer.askAmount}]│          │
  │  └──────────────────────────────────────────┘            │
  └───────────────────────────────────────────────────────┘

  一次 stream() → 中断 → 一次 stream(null) → 完成
  ★ 无法在中断后跳到另一个子图 ★


我们的 L0-L1-L2（独立图模型）:

  ┌─ L1: WealthService ──────────────────────────────────────┐
  │  activeAgent: {intent: WEALTH_CONSULT, lastQuestion: ...} │
  │  suspendedAgents: {}                                      │
  │  contextRouter → intentResolver → 执行决策                 │
  └──────────┬─────────────────────────────────────────────────┘
             │ 选择
    ┌────────┼────────┐
    ▼        ▼        ▼
  ┌────Transfer────┐ ┌────Bill────┐ ┌──WealthConsult──┐
  │ CompiledGraph A │ │ CompiledGraph B │ │ CompiledGraph C │
  │                 │ │              │ │                  │
  │ OverAllState@A  │ │ OverAllState@B │ │ OverAllState@C  │
  │ transfer.*      │ │ bill.*       │ │ wealth.*         │
  │ _question       │ │ _question    │ │ _question        │
  │                 │ │              │ │                  │
  │ MemorySaver@A   │ │ MemorySaver@B │ │ MemorySaver@C   │
  │ "user-123":     │ │ "user-123":  │ │ "user-123":      │
  │  [cp{askAmount}]│ │  []          │ │  []              │
  └─────────────────┘ └──────────────┘ └──────────────────┘

  多次 stream() → 多个 checkpoint → L1 协调挂起/恢复
  ★ 任何子图都可以独立中断、独立恢复 ★
  ★ L1 做路由决策，不受框架执行模型限制 ★
```

---

## 4. 关键场景深度分析

### 3.1 场景一：正常单意图执行（"转账给我妈500元"）

| | 当前架构 | 官方单图 |
|---|---|---|
| 流程 | L0→L1→TransferGraph→askAmount→resume | supervisor→transfer→askAmount→? |
| 难度 | 简单 | 简单 |
| 差异 | 无 | 无 |

**结论**：两者都能轻松处理，无差异。

### 3.2 场景二：Human-in-the-loop 中断恢复（"转账"→问金额→用户回答→继续）

| | 当前架构 | 官方单图 |
|---|---|---|
| 中断机制 | interruptBefore + MemorySaver checkpoint | interruptBefore + MemorySaver checkpoint |
| 恢复机制 | GES.resumeGraph → updateState + stream(null) | 同样 updateState + stream(null) |
| 难度 | 简单 | 简单 |

**结论**：两者等价。

### 3.3 场景三：跨域切换 + 挂起恢复（"转账到一半"→"查账单"→"继续转账"）

| | 当前架构 | 官方单图 |
|---|---|---|
| 挂起 Transfer | L1 suspendOwnAgent()，checkpoint 留在 MemorySaver@A | **无原生支持** |
| 启动 Bill | GES.executeGraph(billGraph, ...)，新 OverAllState@B | supervisor 路由到 bill，但 Transfer 的中间状态怎么办？ |
| 恢复 Transfer | GES.resumeGraph(transferGraph, ...)，从 MemorySaver@A 恢复 | **需要自行实现状态快照/恢复** |
| 难度 | L1 已实现 | **非常困难** |

**这是核心分歧点**：

官方 Supervisor 模式假设**单一线性对话流**——supervisor 路由到 worker，worker 完成后
回到 supervisor，再路由到下一个 worker。不存在"worker 执行到一半挂起，启动另一个
worker，之后再回来"的场景。

在单图模式中，如果 Transfer 执行到 askAmount 被中断，用户说"先查账单"，supervisor
需要：
1. 保存 Transfer 的中间状态（transfer.receiver="我妈"）
2. 路由到 Bill 节点
3. Bill 执行完（或也中断）后，能回到 Transfer 的 askAmount 继续

**框架没有提供这种"多并发中断点"的机制**。单一 OverAllState 只有一个"当前执行位置"，
无法同时维护多个中断点。

### 3.4 场景四：消歧（"推荐理财"→ CONSULT 还是 INTERPRET？）

| | 当前架构 | 官方单图 |
|---|---|---|
| 消歧流程 | IntentResolver 返回 DISAMBIGUATION，L1 暂停当前 agent | supervisor 需要实现消歧节点 |
| 消歧中取消 | IntentResolver.isCancelExpression() → CANCELLED | 需要额外实现 |
| 消歧后路由 | 恢复/启动对应 agent | supervisor 路由 |

**结论**：单图模式可以实现，但需要在 supervisor 和路由节点间插入消歧逻辑，
增加图的复杂度。当前 L1 的消歧实现已经很成熟。

### 3.5 场景五：ContextRouter 三路决策

| | 当前架构 | 官方单图 |
|---|---|---|
| FOLLOW | 用户在延续当前对话，直接 resume | supervisor 需要理解"用户在延续" |
| SWITCH | 用户切换意图，挂起当前，启动新的 | supervisor 需要理解"用户要切换" |
| RESUME | 用户恢复之前的挂起意图 | **supervisor 没有挂起意图的概念** |

**ContextRouter 的 FOLLOW/SWITCH/RESUME 三路决策是当前架构的灵魂**。
在单图模式中，supervisor 只做"路由到谁"，无法表达"我在继续/切换/恢复"这种语义。

### 3.6 场景六：Auto-upgrade 保护

当前架构：ContextRouter 误判 SWITCH，但 IntentRouter 识别的意图与 activeAgent 一致
→ 自动降级为 FOLLOW，避免误切。

单图模式：supervisor 一次决策，没有纠错机制。要么增加二阶段确认节点，
要么接受误切。

---

## 4. 深层架构矛盾分析

### 4.1 核心矛盾：单流 vs 多流

```
官方模式假设的对话流（单流）：
  User → Supervisor → Worker1 → Supervisor → Worker2 → ... → END
  ─────────────────── 一条线性路径 ───────────────────

手机银行实际的对话流（多流）：
  User → Transfer(中断@askAmount) → Bill(执行完成) → Transfer(恢复@askAmount)
  ──────────────── 多条可挂起/恢复的并发流 ────────────────
```

**这是根本性的模型不匹配**。框架的 Graph 执行模型是"单次执行一条路径"，
而手机银行需要"多条路径可暂停/可恢复/可切换"。

### 4.2 单图模式下的 OverAllState 膨胀问题

如果所有 agent 合并到一个图，OverAllState 需要包含所有 agent 的业务字段：

```java
KeyStrategyFactory = {
    // 公共字段
    "messages", "next", "_latestUserInput", "_question", "_cancelSignal",
    "_outputContent", "_paramName",

    // Transfer 字段
    "transfer.receiver", "transfer.amount", "transfer.purpose",

    // Bill 字段
    "bill.timePeriod", "bill.expenseType",

    // Wealth 字段
    "wealth.riskLevel", "wealth.focusArea", "wealth.productName",

    // 未来: 贷款、活动、信用卡...
    "loan.type", "loan.amount", "activity.id", ...
}
```

每增加一个业务域，所有 agent 的 OverAllState 都会膨胀。当前隔离模式下，
每个 agent 只关心自己的字段，互不干扰。

### 4.3 interruptBefore 的冲突

单图模式下，interruptBefore 列表包含所有 agent 的所有 ask 节点：

```java
interruptBefore = ["askReceiver", "askAmount", "askTime", "askType",
                   "askRiskLevel", "askFocusArea", "askProductName", ...]
```

框架在执行到这些节点时**总是**中断。但问题是：
- 当 supervisor 路由到 Transfer 的 askAmount 时应该中断 ✓
- 但 Transfer 的 askReceiver 和 Bill 的 askTime 不应该中断 ✗

interruptBefore 是**全局生效**的配置，无法做到"只在当前执行路径上中断"。
虽然 interruptBefore 只在节点即将执行时才触发，所以实际运行时不会误中断，
但这意味着编译配置要罗列所有可能的 ask 节点，不够优雅。

> 注：经仔细分析，interruptBefore 是在节点即将执行时才检查的，所以
> 不会误中断不相关路径上的节点。但配置上仍需罗列所有节点名。

---

## 5. 结论与建议

### 5.1 核心结论：保持当前独立图架构

**当前架构是正确选择**，原因：

1. **多流并发**：手机银行场景天然需要"多意图挂起/恢复"，当前架构通过
   L1 的 activeAgent + suspendedAgents + 独立 MemorySaver 天然支持，
   官方单图模式没有这个能力。

2. **状态隔离**：每个业务域的 OverAllState 独立，不互相污染，扩展新域
   零影响现有域。单图模式下一个域的 bug 可能影响全局状态。

3. **L1 路由已成熟**：ContextRouter(三路决策) + IntentResolver(消歧) +
   Auto-upgrade(纠错) + REROUTE(跨域) 这套组合拳已经覆盖了复杂场景，
   没必要推倒重来用 supervisor 节点重写。

4. **独立开发/部署**：每个 L2 图可以独立开发、测试、迭代，符合团队协作。

### 5.2 当前架构的不足（与官方模式相比）

| 不足 | 影响 | 严重度 |
|------|------|--------|
| 跨域上下文不共享 | "刚转了500，推荐理财产品" 无法自动感知转账金额 | 中 |
| 协议字段重复定义 | 每个图的 KeyStrategyFactory 都重复注册 _question 等公共字段 | 低 |
| L1 层逻辑较重 | activeAgent/suspended/disambiguation 全靠 L1 手动管理 | 低 |

### 5.3 改进建议：在当前架构上优化

不迁移到单图模式，但借鉴官方模式的思路做增量改进：

#### 改进1：跨域上下文传递（解决"跨域不共享"问题）

当前：跨域切换时 GES.executeGraph 只传 input Map = {messages, _latestUserInput, _question}

改进：L1 在跨域切换时，将前一域的关键摘要注入新域的 input：

```java
// GES.executeGraph 增强
public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                   String userInput, String sessionId,
                                   String crossDomainContext) {  // 新增参数
    Map<String, Object> input = new HashMap<>();
    input.put("messages", userInput);
    input.put("_latestUserInput", userInput);
    input.put("_question", null);
    input.put("_crossDomainContext", crossDomainContext);  // 跨域上下文
    // ...
}
```

L1 在 executeNewAgent 时提供 crossDomainContext：
```java
// AbstractDomainService.executeNewAgent 增强
String crossDomainContext = buildCrossDomainContext(sessionId);
WorkflowOutput result = graphExecutionEngine.executeGraph(
    graph, intent, rewrittenInput, sessionId, crossDomainContext);
```

这样"刚转了500"的信息可以通过 crossDomainContext 传给理财推荐，
而不需要共享 OverAllState。

#### 改进2：协议字段统一注册（解决"重复定义"问题）

当前：每个 AbstractGraphConfig 子类都在 createKeyStrategyFactory() 中重复注册
messages, _latestUserInput, _question 等。

改进：AbstractGraphConfig 基类已处理（当前代码 createKeyStrategyFactory 已包含
公共 keys），无需额外改动。只需确保子类 registerCustomKeys() 不覆盖公共字段。

#### 改进3：L1 层状态管理简化（长期方向）

当前 L1 的 activeAgent + suspendedAgents 管理已经足够，但如果未来域/意图数量
大幅增长，可以考虑：

- 将 L1 状态管理提取为独立的 SessionStateManager 服务
- 支持跨域的 session 级别状态（如全局用户偏好）
- 支持更复杂的挂起策略（优先级、超时、依赖关系）

---

## 6. 如果一定要用官方单图模式，需要解决什么？

仅作参考，不建议迁移。如果未来框架升级后原生支持多流并发，可以重新评估。

### 6.1 需要解决的核心问题

1. **多中断点管理**：单一 OverAllState 需要支持"多个并发中断点"，
   每个中断点记录：哪个 agent、在哪个节点、已收集的参数。
   当前框架不支持，需要自行扩展。

2. **挂起/恢复语义**：需要在 OverAllState 中增加：
   - `_suspendedAgents`: Map<String, SuspendedState> — 被挂起的 agent 列表
   - `_activeAgent`: String — 当前活跃 agent
   - `_agentResumePoint`: Map<String, String> — 每个 agent 恢复时的节点

3. **Supervisor 节点增强**：不是简单的 LLM 路由，需要实现
   ContextRouter 的 FOLLOW/SWITCH/RESUME 三路决策。

4. **消歧集成**：supervisor 节点或路由节点需要集成 IntentResolver 的消歧逻辑。

5. **状态命名空间**：所有业务字段需要加前缀（如 transfer.receiver），
   防止跨 agent 字段冲突。KeyStrategyFactory 需要注册所有域的所有字段。

### 6.2 迁移后的伪代码（示意）

```java
StateGraph supervisorGraph = new StateGraph(superKeyStrategyFactory)
    .addNode("contextRouter", node_async(this::contextRouterNode))
    .addNode("supervisor", node_async(this::supervisorNode))
    .addNode("disambiguator", node_async(this::disambiguatorNode))
    .addNode("transfer", transferCompiledGraph)        // 子图
    .addNode("bill", billCompiledGraph)                // 子图
    .addNode("wealthConsult", wealthConsultCompiledGraph)  // 子图
    .addNode("wealthInterpret", wealthInterpretCompiledGraph) // 子图
    .addEdge(START, "contextRouter")
    .addConditionalEdges("contextRouter",
        edge_async(state -> (String) state.value("_routeType").orElse("SWITCH")),
        Map.of(
            "FOLLOW", "supervisor",    // 继续当前 agent
            "SWITCH", "supervisor",    // 切换到新 agent
            "RESUME", "supervisor"     // 恢复挂起 agent
        ))
    .addConditionalEdges("supervisor",
        edge_async(state -> (String) state.value("next").orElse("FINISH")),
        Map.of(
            "TRANSFER", "transfer",
            "BILL_QUERY", "bill",
            "WEALTH_CONSULT", "wealthConsult",
            "WEALTH_INTERPRET", "wealthInterpret",
            "DISAMBIGUATE", "disambiguator",
            "FINISH", END
        ))
    // 每个 agent 回到 supervisor
    .addEdge("transfer", "contextRouter")
    .addEdge("bill", "contextRouter")
    .addEdge("wealthConsult", "contextRouter")
    .addEdge("wealthInterpret", "contextRouter")
    .addEdge("disambiguator", "supervisor");
```

**问题**：
- transfer 子图执行到 askAmount 被 interruptBefore 中断 → 整个 supervisorGraph 的 stream() 返回
- 用户说"查账单" → 需要恢复 supervisorGraph，但跳过 transfer 的 askAmount，直接进入 bill
- updateState 只能更新 OverAllState 的字段值，**无法控制执行跳转到哪个节点**
- 当前框架的 resume 机制只能从上次中断的节点继续，无法跳到另一个分支

**这证明了框架的执行模型与多流并发需求根本不匹配。**

---

## 7. 最终建议

### 保持当前 L0/L1/L2 独立图架构

| 维度 | 当前架构 | 官方单图 | 判定 |
|------|---------|---------|------|
| 多流挂起/恢复 | ✅ L1 原生支持 | ❌ 框架不支持 | **当前架构胜** |
| 状态隔离 | ✅ 各图独立 | ❌ 全局共享易污染 | **当前架构胜** |
| 跨域上下文 | ⚠️ 需手动传递 | ✅ 天然共享 | 官方胜，但可改进 |
| 路由决策 | ✅ 三路+消歧+纠错 | ⚠️ 需重写 | **当前架构胜** |
| 代码简洁度 | ⚠️ L1 较重 | ✅ 框架标准 | 官方胜，但不可行 |
| 扩展性 | ✅ 新增域零影响 | ❌ 改全局 KeyStrategy | **当前架构胜** |
| 开发独立性 | ✅ 各域独立 | ❌ 全局耦合 | **当前架构胜** |

**核心结论**：官方模式适用于"单流顺序编排"场景（如 research→write→review），
手机银行的"多流并发挂起恢复"场景超出其设计范围。当前架构虽然 L1 层较重，
但恰恰是因为框架缺失多流调度能力才需要 L1 来补位，这是正确的分层决策。

### 优先改进项

1. **跨域上下文传递**（改进1）—— 解决"转完账推荐理财"的上下文断裂
2. **统一协议字段**（改进2）—— 当前已基本完成
3. **L1 状态管理简化**（改进3）—— 长期方向，当前优先级低
