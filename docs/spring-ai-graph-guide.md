# Spring AI Alibaba Graph 开发详解 — 以转账场景为例

> 本文基于 `TransferGraphConfig.java`，完整解读 Spring AI Alibaba Graph 的节点定义、边连接、条件路由、循环提参、interruptBefore、askNode、OverAllState、KeyStrategy、CheckpointSaver、RunnableConfig 等核心概念与编程模型。

---

## 目录

1. [Spring AI Alibaba Graph 是什么](#1-spring-ai-alibaba-graph-是什么)
2. [转账场景全貌](#2-转账场景全貌)
3. [StateGraph 构建：节点与边](#3-statemath-构建节点与边)
4. [OverAllState：图的共享状态](#4-overallstate图的共享状态)
5. [KeyStrategy：状态字段的合并策略](#5-keystrategy状态字段的合并策略)
6. [节点实现：每个节点做什么](#6-节点实现每个节点做什么)
7. [条件路由：paramRouter 如何分流](#7-条件路由paramrouter-如何分流)
8. [askNode 机制：interruptBefore + resume 循环](#8-asknode-机制interruptbefore--resume-循环)
9. [ask→END 模式：另一种中断方式](#9-askend-模式另一种中断方式)
10. [取消机制：cancelAware 全链路](#10-取消机制cancelaware-全链路)
11. [CheckpointSaver 与 RunnableConfig](#11-checkpointsaver-与-runnableconfig)
12. [compile：图的编译与配置](#12-compile图的编译与配置)
13. [Graph 执行流程：execute 与 resume](#13-graph-执行流程execute-与-resume)
14. [完整执行时序：从"帮我转账"到"转账成功"](#14-完整执行时序从帮我转账到转账成功)
15. [API 速查表](#15-api-速查表)

---

## 1. Spring AI Alibaba Graph 是什么

Spring AI Alibaba Graph 是基于 **StateGraph** 模式的图执行引擎，灵感来自 LangGraph。核心思想：

```
图 = 节点(Node) + 边(Edge) + 共享状态(OverAllState)

节点: 一个函数 (OverAllState → Map<String, Object>)
边:   节点之间的连接，可以是固定的或条件性的
状态: 所有节点共享的可变状态，节点返回的Map会自动合并回状态
```

**核心编程模型**：

1. **定义 StateGraph** — 添加节点、添加边、定义条件路由
2. **编译** — `graph.compile(config)` 生成 `CompiledGraph`（不可变的可执行图）
3. **执行** — `compiledGraph.stream(input, config)` 同步执行，或 `compiledGraph.graphResponseStream(input, config)` 流式执行

---

## 2. 转账场景全貌

转账的对话流程：

```
用户: "帮我转账"        → 缺收款人 → 系统追问"转给谁？"
用户: "张三"            → 缺金额   → 系统追问"转多少？"
用户: "500"             → 参数齐全 → 执行转账 → "转账成功！已向张三转入500元"
```

对应的 Graph 结构：

```
                    ┌──────────────────────────────────────────┐
                    │              TransferGraph                │
                    │                                          │
START ──→ extractParams ──→ paramRouter ──┬──→ askReceiver ──┐
                    │                     │    (interruptBefore)│
                    │                     ├──→ askAmount ─────┐│
                    │                     │    (interruptBefore)││
                    │                     ├──→ executeTransfer → END
                    │                     └──→ cancelExecution → END
                    │                                          │
                    │  askReceiver ──→ paramRouter (循环提参)   │
                    │  askAmount   ──→ paramRouter (循环提参)   │
                    └──────────────────────────────────────────┘
```

---

## 3. StateGraph 构建：节点与边

### 3.1 完整构建代码

```java
// TransferGraphConfig.java
@Bean("transferGraph")
public CompiledGraph transferGraph() throws GraphStateException {
    // 1. 定义条件边映射表
    Map<String, String> paramEdges = new HashMap<>(Map.of(
            "ASK_RECEIVER", "askReceiver",      // _paramName="ASK_RECEIVER" → 走askReceiver节点
            "ASK_AMOUNT",   "askAmount",         // _paramName="ASK_AMOUNT"   → 走askAmount节点
            "ALL_GOOD",     "executeTransfer"     // _paramName="ALL_GOOD"     → 走执行节点
    ));
    addCancelEdge(paramEdges);  // 添加 "CANCEL" → "cancelExecution"

    // 2. 构建 StateGraph
    StateGraph graph = new StateGraph(createKeyStrategyFactory())
            .addNode("extractParams",      node_async(this::extractParamsNode))
            .addNode("paramRouter",        node_async(this::paramRouterNode))
            .addNode("askReceiver",        askNode("askReceiver", this::askReceiverLogic))
            .addNode("askAmount",          askNode("askAmount",   this::askAmountLogic))
            .addNode("executeTransfer",    node_async(this::executeTransferNode))
            .addEdge(START,                "extractParams")       // 固定边：START → extractParams
            .addEdge("extractParams",      "paramRouter")         // 固定边：extractParams → paramRouter
            .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
            .addEdge("executeTransfer",    END);                  // 固定边：executeTransfer → END

    addCancelNode(graph);                                        // 添加 cancelExecution 节点 + END 边
    addAskConditionalEdges(graph, "askReceiver");                // askReceiver → paramRouter 或 END
    addAskConditionalEdges(graph, "askAmount");                  // askAmount   → paramRouter 或 END

    // 3. 编译
    CompiledGraph compiled = graph.compile(createInterruptCompileConfig());
    return compiled;
}
```

### 3.2 边的类型

| 类型 | API | 含义 | 示例 |
|------|-----|------|------|
| 固定边 | `addEdge(from, to)` | 无条件跳转 | `START → extractParams` |
| 条件边 | `addConditionalEdges(from, router, edgeMap)` | 根据路由函数的返回值选择下一个节点 | `paramRouter → {ASK_RECEIVER: askReceiver, ALL_GOOD: executeTransfer}` |

### 3.3 节点的注册方式

| 方式 | API | 适用场景 |
|------|-----|----------|
| `node_async(this::method)` | 普通节点 | 不需要 interruptBefore 的节点 |
| `askNode("name", this::method)` | ask 节点 | 需要 interruptBefore + 取消检测的追问节点 |

`askNode()` 内部做了三件事（后文详述）：
1. 自动包装 `cancelAwareAsk` 取消检测
2. 自动注册到 `interruptNodes` 列表
3. 转换为 `AsyncNodeAction`

---

## 4. OverAllState：图的共享状态

### 4.1 什么是 OverAllState

`OverAllState` 是 Graph 的**全局共享状态**，所有节点都能读取和修改。每个节点函数的签名是：

```java
Map<String, Object> nodeName(OverAllState state)
```

节点返回的 `Map<String, Object>` 会根据 `KeyStrategy` **自动合并回** OverAllState。

### 4.2 TransferGraph 的状态字段

```
┌─────────────────────────────────────────────────────────────────┐
│                     OverAllState                                  │
├──────────────────────────┬──────────────────────────────────────┤
│ 字段名                    │ 说明                                  │
├──────────────────────────┼──────────────────────────────────────┤
│ messages                 │ 消息列表 (AppendStrategy)              │
│ _latestUserInput         │ 最新用户输入 (ReplaceStrategy)          │
│ _question                │ 系统提问 (ReplaceStrategy)              │
│ _paramName               │ 参数路由标记 (ReplaceStrategy)          │
│ _outputContent           │ 最终输出内容 (ReplaceStrategy)          │
│ _outputType              │ 输出类型 (ReplaceStrategy)              │
│ _isFinal                 │ 是否终结 (ReplaceStrategy)              │
│ _cancelSignal            │ 取消信号 (ReplaceStrategy)              │
│ _globalStateData         │ 全局数据注入 (ReplaceStrategy)          │
├──────────────────────────┼──────────────────────────────────────┤
│ transfer.receiver        │ 收款人 (ReplaceStrategy) ← 子类注册     │
│ transfer.amount          │ 转账金额 (ReplaceStrategy) ← 子类注册   │
│ transfer.purpose         │ 转账用途 (ReplaceStrategy) ← 子类注册   │
└──────────────────────────┴──────────────────────────────────────┘
```

### 4.3 状态的读写

**读取**：通过 `state.value(key)` 获取 `Optional<Object>`

```java
String receiver = getStringValue(state, "transfer.receiver");
Object amountObj = state.value("transfer.amount").orElse(null);
String question = (String) state.value("_question").orElse("");
```

**写入**：节点返回 Map，框架自动合并

```java
// paramRouterNode 的返回值会自动合并到 OverAllState
Map<String, Object> result = new HashMap<>();
result.put("_question", "请问您要转给谁？");    // 写入提问
result.put("_paramName", "ASK_RECEIVER");       // 写入路由标记
return result;  // 框架自动合并
```

---

## 5. KeyStrategy：状态字段的合并策略

### 5.1 为什么需要合并策略

当节点返回 `Map<String, Object>` 时，框架需要知道**如何合并**到当前状态。不同字段有不同的合并语义：

| 字段 | 期望行为 | 策略 |
|------|----------|------|
| `transfer.receiver` | 新值覆盖旧值 | `ReplaceStrategy` |
| `messages` | 新消息追加到列表末尾 | `AppendStrategy` |
| `_cancelSignal` | 新值覆盖旧值 | `ReplaceStrategy` |

### 5.2 两种内置策略

```java
// ReplaceStrategy：新值直接覆盖旧值
// state = {receiver: "张三"}  →  节点返回 {receiver: "李四"}  →  state = {receiver: "李四"}

// AppendStrategy：新值追加到列表
// state = {messages: ["你好"]}  →  节点返回 {messages: ["转账"]}  →  state = {messages: ["你好", "转账"]}
```

### 5.3 KeyStrategyFactory 的创建

```java
// AbstractGraphConfig.createKeyStrategyFactory()
protected KeyStrategyFactory createKeyStrategyFactory() {
    return () -> {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        // 公共 keys
        strategies.put("messages",          new AppendStrategy());   // 消息追加
        strategies.put("_latestUserInput",  new ReplaceStrategy());  // 最新输入
        strategies.put("_question",         new ReplaceStrategy());  // 提问
        strategies.put("_paramName",        new ReplaceStrategy());  // 路由标记
        strategies.put("_outputContent",    new ReplaceStrategy());  // 输出内容
        strategies.put("_outputType",       new ReplaceStrategy());  // 输出类型
        strategies.put("_isFinal",          new ReplaceStrategy());  // 终结标记
        strategies.put("_cancelSignal",     new ReplaceStrategy());  // 取消信号
        strategies.put("_globalStateData",  new ReplaceStrategy());  // 全局数据
        // 子类自定义 keys
        registerCustomKeys(strategies);
        return strategies;
    };
}

// TransferGraphConfig.registerCustomKeys()
@Override
protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
    strategies.put("transfer.receiver", new ReplaceStrategy());
    strategies.put("transfer.amount",   new ReplaceStrategy());
    strategies.put("transfer.purpose",  new ReplaceStrategy());
}
```

**关键**：`KeyStrategyFactory` 是一个 **lambda `() → Map`**，每次图编译时调用一次，生成字段策略映射表。StateGraph 构造函数接收它：

```java
StateGraph graph = new StateGraph(createKeyStrategyFactory())
```

---

## 6. 节点实现：每个节点做什么

### 6.1 extractParams — 参数提取节点

```java
private Map<String, Object> extractParamsNode(OverAllState state) {
    // 1. 取消检测（短路返回）
    Map<String, Object> cancelResult = cancelAwareExtractParams(state);
    if (cancelResult != null) return cancelResult;

    // 2. 获取用户输入
    String userInput = getLatestInput(state);

    // 3. 调用 LLM 提取参数
    Map<String, Object> result = new HashMap<>();
    try {
        Map<String, Object> extracted = callExtractModel(userInput);  // buildPrompt → LLM → parseJSON
        mergeExtractedWithoutOverwrite(result, extracted, state);      // 合并但不覆盖已有参数
    } catch (Exception e) {
        log.error("LLM extraction failed", e);  // 提取失败不影响流程，paramRouter会继续追问
    }
    return result;
}
```

**LLM 提取的 prompt**（由 `buildExtractPrompt` 生成）：

```
你是一个银行转账参数提取器。从用户输入中提取转账相关参数。

用户输入: 帮我给张三转1000块

提取规则:
- receiver: 收款方(资金去向)，可以是个人(如"张三")、理财产品(如"朝朝盈")等
- amount: 转账金额(数字),如500、1000.50
- purpose: 转账用途/备注(可选),如"房租"、"还款"
- 只提取用户明确提到的参数,不猜测

严格输出JSON:
{
  "receiver": "收款方或null",
  "amount": 金额数字或null,
  "purpose": "用途或null"
}
```

**`mergeExtractedWithoutOverwrite` 的作用**：在 resume 场景下，用户可能说"继续转账"而不重复提到收款人。此时 LLM 会返回 `{receiver: null}`，如果不保护已有值，收款人参数会被覆盖为 null。此方法确保：**已有参数不被 LLM 的 null/空值覆盖**。

### 6.2 paramRouter — 参数路由节点

```java
private Map<String, Object> paramRouterNode(OverAllState state) {
    // 1. 取消检测
    Map<String, Object> cancelResult = cancelAwareParamRouter(state);
    if (cancelResult != null) return cancelResult;

    // 2. 检查参数完整性
    String receiver = getStringValue(state, "transfer.receiver");
    Object amountObj = state.value("transfer.amount").orElse(null);
    String amount = amountObj != null ? amountObj.toString() : null;

    Map<String, Object> result = new HashMap<>();
    if (receiver == null || receiver.isEmpty()) {
        // 缺收款人 → 设置提问和路由标记
        result.put("_question", "请问您要转给谁？");
        result.put("_paramName", "ASK_RECEIVER");
    } else if (amount == null || amount.isEmpty()) {
        // 缺金额 → 设置提问和路由标记
        result.put("_question", "请问您要转多少金额？");
        result.put("_paramName", "ASK_AMOUNT");
    } else {
        // 参数齐全
        result.put("_question", null);
        result.put("_paramName", "ALL_GOOD");
    }
    return result;
}
```

**`_paramName` 是条件路由的"方向盘"**。paramRouter 不直接决定走哪个节点，而是设置 `_paramName` 的值，由条件边函数读取这个值来决定路由。

### 6.3 askReceiver / askAmount — 追问节点

```java
private Map<String, Object> askReceiverLogic(OverAllState state) {
    String userInput = getLatestInput(state);
    Map<String, Object> result = new HashMap<>();

    if (userInput == null || userInput.isEmpty()) {
        return result;  // 无用户输入 → 走 WAIT → END（等待用户回答）
    }

    try {
        // 用 LLM 从用户回答中提取参数
        Map<String, Object> extracted = callExtractModel(userInput);
        String receiver = (String) extracted.get("transfer.receiver");
        if (receiver != null && !receiver.isEmpty()) {
            result.put("transfer.receiver", receiver);
        }
    } catch (Exception e) {
        log.error("Extraction failed", e);
    }

    // 清空 _latestUserInput，防止下次执行时重复处理
    result.put("_latestUserInput", "");
    return result;
}
```

**ask 节点的关键设计**：
- 有用户输入时：提取参数，清空 `_latestUserInput`，走 `CONTINUE → paramRouter`
- 无用户输入时：直接返回空 Map，走 `WAIT → END`（图中断，等待用户下次回答）

### 6.4 executeTransfer — 执行节点

```java
private Map<String, Object> executeTransferNode(OverAllState state) {
    String receiver = getStringValue(state, "transfer.receiver");
    Object amountObj = state.value("transfer.amount").orElse(null);
    BigDecimal amount = ...;
    String purpose = getStringValue(state, "transfer.purpose");

    // 调用银行服务执行转账
    MockBankingService.TransferResult transferResult = mockBankingService.executeTransfer(receiver, amount, purpose);

    Map<String, Object> result = new HashMap<>();
    result.put("_outputContent", transferResult.message());  // 设置输出内容
    result.put("_outputType", "CONFIRMATION");               // 设置输出类型
    result.put("_isFinal", true);                            // 标记为终结
    return result;
}
```

---

## 7. 条件路由：paramRouter 如何分流

### 7.1 条件边的三要素

```java
.addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
//                         ↑ 来源节点      ↑ 路由函数                ↑ 边映射表
```

**路由函数** — 读取 OverAllState 中的 `_paramName`，返回字符串：

```java
// createCancelAwareRouter()
protected AsyncEdgeAction createCancelAwareRouter() {
    return edge_async(state -> {
        if (isCancelled(state)) return "CANCEL";                        // 取消信号 → CANCEL
        Object param = state.value("_paramName").orElse("ALL_GOOD");    // 默认走 ALL_GOOD
        return param.toString();                                         // 返回路由标记字符串
    });
}
```

**边映射表** — 路由字符串 → 目标节点：

```java
paramEdges = {
    "ASK_RECEIVER"  → "askReceiver",       // 缺收款人
    "ASK_AMOUNT"    → "askAmount",          // 缺金额
    "ALL_GOOD"      → "executeTransfer",    // 参数齐全
    "CANCEL"        → "cancelExecution"     // 用户取消
}
```

### 7.2 路由决策流程

```
paramRouter 执行完毕
    ↓ (OverAllState 中 _paramName 已更新)
条件路由函数执行
    ↓ 读取 _paramName
    ├── "ASK_RECEIVER"  → askReceiver 节点
    ├── "ASK_AMOUNT"    → askAmount 节点
    ├── "ALL_GOOD"      → executeTransfer 节点
    └── "CANCEL"        → cancelExecution 节点
```

---

## 8. askNode 机制：interruptBefore + resume 循环

这是整个 Graph 最核心的机制 — 如何实现"系统追问 → 用户回答 → 继续执行"的循环。

### 8.1 interruptBefore 原理

`interruptBefore` 是 CompileConfig 的选项，含义是：**在执行到指定节点之前暂停图**。

```java
// 编译时注册 interruptBefore
CompileConfig config = CompileConfig.builder()
        .saverConfig(createSaverConfig())
        .interruptBefore("askReceiver", "askAmount")   // ← 在这两个节点之前中断
        .build();

CompiledGraph compiled = graph.compile(config);
```

**中断时发生了什么**：
1. 图执行到 `askReceiver` 节点之前
2. 框架**保存当前 OverAllState 到 CheckpointSaver**（以 threadId 为 key）
3. `graph.stream()` 的 `blockLast()` 返回（图暂停了）
4. GES 的 `checkGraphResult()` 检测到 `nextNode="askReceiver"` → 返回 `INTERRUPTED`

**恢复时发生了什么**：
1. L1 调用 `graph.updateState(config, updateData, null)` — 将用户回答注入状态
2. L1 调用 `graph.stream(null, updatedConfig)` — 从 checkpoint 恢复，继续执行 `askReceiver`

### 8.2 askNode() 的自动化

`askNode()` 方法将三个步骤封装为一个调用：

```java
// 手动方式（旧）：
.addNode("askReceiver", node_async(this::askReceiverLogic))
// 编译时还要手写：.interruptBefore("askReceiver")

// askNode 自动方式（新）：
.addNode("askReceiver", askNode("askReceiver", this::askReceiverLogic))
// 自动注册 interruptBefore，编译时用 createInterruptCompileConfig() 即可
```

**askNode 的内部实现**：

```java
protected AsyncNodeAction askNode(String nodeName,
        java.util.function.Function<OverAllState, Map<String, Object>> askLogic) {
    // 1. 自动注册到 interruptNodes 列表
    interruptNodes.add(nodeName);

    // 2. 返回包装了取消检测的 AsyncNodeAction
    return node_async(state -> {
        Map<String, Object> cancelResult = cancelAwareAsk(state);  // 取消检测
        if (cancelResult != null) return cancelResult;
        return askLogic.apply(state);                               // 执行业务逻辑
    });
}
```

### 8.3 interruptNodes 的自动收集

```java
// 基类中的自动收集列表
private final List<String> interruptNodes = new ArrayList<>();

// askNode() 每次调用时自动添加
// askNode("askReceiver", ...) → interruptNodes = ["askReceiver"]
// askNode("askAmount", ...)   → interruptNodes = ["askReceiver", "askAmount"]

// 编译时自动使用
protected CompileConfig createInterruptCompileConfig() {
    return createCompileConfig(interruptNodes.toArray(new String[0]));
}
// 等价于: CompileConfig.builder().interruptBefore("askReceiver", "askAmount").build()
```

### 8.4 循环提参的完整时序

```
第1轮: 用户 "帮我转账"
│
├─ graph.stream({messages: "帮我转账", _latestUserInput: "帮我转账"}, config)
│     START → extractParams → {receiver: null, amount: null}  (LLM提取不到)
│     → paramRouter → {_paramName: "ASK_RECEIVER", _question: "转给谁？"}
│     → 条件路由: "ASK_RECEIVER" → askReceiver
│     → interruptBefore! 图暂停，checkpoint保存
│
├─ GES checkGraphResult → INTERRUPTED(question="转给谁？")
├─ L0 输出: "请问您要转给谁？"
│
第2轮: 用户 "张三"
│
├─ graph.updateState(config, {_latestUserInput: "张三"}, null)  ← L1注入用户回答
├─ graph.stream(null, updatedConfig)                            ← 从checkpoint恢复
│     askReceiver 执行 → {transfer.receiver: "张三", _latestUserInput: ""}
│     → ask条件路由: hasInput=true → CONTINUE → paramRouter
│     → paramRouter → {_paramName: "ASK_AMOUNT", _question: "转多少？"}
│     → 条件路由: "ASK_AMOUNT" → askAmount
│     → interruptBefore! 图再次暂停
│
├─ GES checkGraphResult → INTERRUPTED(question="转多少？")
├─ L0 输出: "请问您要转多少金额？"
│
第3轮: 用户 "500"
│
├─ graph.updateState(config, {_latestUserInput: "500"}, null)
├─ graph.stream(null, updatedConfig)
│     askAmount 执行 → {transfer.amount: 500, _latestUserInput: ""}
│     → ask条件路由: hasInput=true → CONTINUE → paramRouter
│     → paramRouter → {_paramName: "ALL_GOOD"}
│     → 条件路由: "ALL_GOOD" → executeTransfer
│     → {_outputContent: "转账成功！已向张三转入500元"}
│     → END
│
├─ GES checkGraphResult → COMPLETED
├─ L0 输出: "转账成功！已向张三转入500元"
```

---

## 9. ask→END 模式：另一种中断方式

### 9.1 两种中断模式

| 模式 | 触发条件 | 表现 | 恢复方式 |
|------|----------|------|----------|
| **interruptBefore** | paramRouter 设置 `_paramName` → 条件路由到 ask 节点 | 图在 ask 节点之前暂停 | `updateState` + `stream(null, config)` |
| **ask→END** | ask 节点执行时无用户输入 | ask 节点执行完 → `WAIT → END` | `updateState` + `stream(null, config)` |

### 9.2 ask→END 的路由逻辑

```java
// addAskConditionalEdges()
protected void addAskConditionalEdges(StateGraph graph, String askNodeName) throws GraphStateException {
    graph.addConditionalEdges(askNodeName,
            edge_async(state -> {
                String input = getLatestInput(state);
                boolean hasInput = input != null && !input.isEmpty();
                return hasInput ? "CONTINUE" : "WAIT";
            }),
            Map.of("CONTINUE", "paramRouter", "WAIT", END));
}
```

**时序对比**：

```
interruptBefore 模式（图在ask节点之前暂停）:
  paramRouter → [中断] → askReceiver → paramRouter

ask→END 模式（ask节点执行完，发现没输入，走END）:
  paramRouter → askReceiver → [无输入] → WAIT → END
```

**为什么需要两种模式？**

- `interruptBefore`：首次进入 ask 节点时，还没有用户输入，图在节点前暂停
- `ask→END`：兜底模式，如果 interruptBefore 没生效（如配置错误），ask 节点执行后发现没输入，走 END 而不是死循环

实际运行中，**interruptBefore 是主路径**，ask→END 是安全网。

---

## 10. 取消机制：cancelAware 全链路

### 10.1 取消检测的两层策略

```
用户输入
    │
    ├── 第1层: 关键字匹配 (0ms)
    │     "取消", "算了", "不转了", "别转了" → 直接命中
    │
    └── 第2层: LLM判断 (200-500ms)
          关键字未命中时，LLM理解上下文判断取消意图
```

### 10.2 取消信号的传播

```
用户: "算了，不转了"
│
├─ extractParamsNode
│   └─ cancelAwareExtractParams()
│       └─ detectCancelFromInput() → true! → 返回 {_cancelSignal: true}
│
├─ paramRouterNode
│   └─ cancelAwareParamRouter()
│       └─ isCancelled(state) → true! → 返回 {_paramName: "CANCEL"}
│
├─ 条件路由: "CANCEL" → cancelExecution
│
├─ cancelExecutionNode
│   └─ {_outputContent: "操作已取消", _isFinal: true, _cancelSignal: null}
│
└─ END → GES checkGraphResult → COMPLETED(content="操作已取消")
```

### 10.3 取消在 ask 节点中的检测

ask 节点由 `askNode()` 自动包装了 `cancelAwareAsk`：

```java
// askNode 内部逻辑
return node_async(state -> {
    Map<String, Object> cancelResult = cancelAwareAsk(state);  // 自动检测取消
    if (cancelResult != null) return cancelResult;               // 取消则短路
    return askLogic.apply(state);                                 // 正常执行业务
});
```

这意味着用户在追问阶段说"算了"，取消信号会被 ask 节点捕获 → 设置 `_cancelSignal=true` → 后续 paramRouter 读到 → 走 CANCEL 路由。

---

## 11. CheckpointSaver 与 RunnableConfig

### 11.1 CheckpointSaver 的作用

CheckpointSaver 负责**持久化图的执行状态**。当图被 `interruptBefore` 中断时，当前的 OverAllState 被保存；resume 时从保存点恢复。

### 11.2 MemorySaver（InMemory 实现）

```java
// CheckpointSaverConfig.java
@Bean("inMemoryCheckpointSaverFactory")
@ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
public CheckpointSaverFactory inMemoryCheckpointSaverFactory() {
    return MemorySaver::new;  // 每次调用创建新的 MemorySaver 实例
}
```

**为什么每个图需要独立的 MemorySaver？**

不同的 L2 子图（Transfer / Bill / Wealth）有不同的状态空间。如果共享同一个 MemorySaver，threadId 可能冲突导致 checkpoint 覆盖。工厂模式确保每个图编译时创建独立实例。

### 11.3 SaverConfig 的构建

```java
// AbstractGraphConfig.createSaverConfig()
protected SaverConfig createSaverConfig() {
    BaseCheckpointSaver saver = checkpointSaverFactory.create();  // 每次创建新实例
    return SaverConfig.builder()
            .register(saver)
            .build();
}
```

### 11.4 RunnableConfig — 执行上下文

`RunnableConfig` 是每次图执行时的配置对象，核心字段是 **threadId**：

```java
// GraphExecutionEngine.java
private RunnableConfig threadConfig(String threadId) {
    return RunnableConfig.builder().threadId(threadId).build();
}
```

**threadId 的作用**：
- CheckpointSaver 用 threadId 隔离不同会话的 checkpoint
- `graph.getState(config)` 用 threadId 查找对应的 checkpoint
- `graph.updateState(config, data, null)` 用 threadId 定位要更新的 checkpoint

**threadId 的生成**（在 L1 AbstractDomainService 中）：

```java
protected String generateThreadId(String sessionId, String intent) {
    String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
    return sessionId + "-" + intent + "-" + suffix;
    // 例如: "session1-TRANSFER-a3f2b1"
}
```

**为什么每次新执行生成不同 threadId？**

同 sessionId 下如果复用 threadId，CheckpointSaver 会自动合并历史状态，导致旧参数"脏数据"残留。独立 threadId 确保每次执行从干净状态开始。

### 11.5 Checkpoint 的生命周期

```
execute (首次执行):
  graph.stream(input, config)                    ← config含threadId
  → 中断 → MemorySaver保存 {threadId: "session1-TRANSFER-a3f2b1", state: {...}}

resume (恢复执行):
  graph.updateState(config, updateData, null)    ← 用同一个threadId更新
  graph.stream(null, updatedConfig)               ← 从checkpoint恢复
  → 完成 → GES调用clearCheckpoint:
    graph.compileConfig.checkpointSaver().release(config)
    → 删除该threadId下所有checkpoint
```

### 11.6 Checkpoint 的读取

```java
// GraphExecutionEngine.checkGraphResult() / buildStreamingTerminalChunk()
var snapshot = graph.getState(config);           // 读取当前threadId的checkpoint
String nextNode = snapshot.next();               // 下一个要执行的节点
OverAllState state = snapshot.state();           // 当前状态
String question = (String) state.value("_question").orElse("");
```

**关键注意**：`getState()` 必须用**原始 config**（只有 threadId），不能用 `updateState` 返回的 `updatedConfig`。这是 Spring AI Alibaba 的一个 API 约束。

---

## 12. compile：图的编译与配置

### 12.1 编译过程

```java
CompiledGraph compiled = graph.compile(createInterruptCompileConfig());
```

编译是不可逆操作，生成 `CompiledGraph` — 一个**不可变的可执行图**。编译时确定：
1. 节点拓扑结构
2. KeyStrategy 映射
3. CheckpointSaver 实例
4. interruptBefore 节点列表
5. 递归限制（防止死循环）

### 12.2 CompileConfig 详解

```java
// createInterruptCompileConfig() 展开
CompileConfig.builder()
    .saverConfig(createSaverConfig())                    // CheckpointSaver配置
    .interruptBefore("askReceiver", "askAmount")          // 中断节点列表
    .recursionLimit(50)                                   // 最大递归深度
    .build()
```

| 配置项 | 作用 | 默认值 |
|--------|------|--------|
| `saverConfig` | CheckpointSaver 注册 | 无（不支持 interruptBefore） |
| `interruptBefore` | 在哪些节点之前中断 | 无 |
| `recursionLimit` | 最大节点执行次数（防死循环） | 25 |

**recursionLimit 的意义**：如果 paramRouter 的路由逻辑有 bug 导致死循环（如永远返回 ASK_RECEIVER），recursionLimit 会在执行50次节点后强制终止图。

---

## 13. Graph 执行流程：execute 与 resume

### 13.1 首次执行（execute）

```java
// GraphExecutionEngine.executeBlocking()
RunnableConfig config = threadConfig(threadId);                  // 构建含threadId的config
graph.stream(input, config).blockLast();                         // 同步执行，等图跑完或中断
WorkflowOutput output = checkGraphResult(graph, config, intent); // 检查最终状态
```

`input` 是图启动时的初始状态：

```java
// AbstractDomainService.buildGraphInput()
Map<String, Object> input = new HashMap<>();
input.put("messages", rewrittenInput);          // 用户消息
input.put("_latestUserInput", rewrittenInput);   // 最新输入
input.put("_question", null);                    // 清空提问
// 全局数据注入
input.put("_globalStateData", globalData);
```

### 13.2 恢复执行（resume）

```java
// GraphExecutionEngine.resumeBlocking()
RunnableConfig config = threadConfig(threadId);

// 1. 注入用户回答和全局数据
Map<String, Object> updateData = new HashMap<>();
updateData.put("_latestUserInput", userInput);    // 用户回答
updateData.put("_globalStateData", globalStateData);
RunnableConfig updatedConfig = graph.updateState(config, updateData, null);

// 2. 从checkpoint恢复，继续执行
graph.stream(null, updatedConfig).blockLast();

// 3. 用原始config检查结果（不是updatedConfig！）
WorkflowOutput output = checkGraphResult(graph, config, intent);
```

**`updateState` 的三个参数**：

| 参数 | 含义 | 此处的值 |
|------|------|----------|
| `config` | 含 threadId 的配置 | `threadConfig(threadId)` |
| `updateData` | 要注入的状态数据 | `{_latestUserInput: "张三", _globalStateData: ...}` |
| `nodeId` | 从哪个节点恢复（null = 从中断点恢复） | `null` |

**为什么 `stream` 第二参数传 `null`？**

`stream(null, updatedConfig)` 的第一个参数是**新的 input Map**。传 null 表示"使用 checkpoint 中已保存的状态"，不要覆盖。用户回答通过 `updateState` 注入，不需要通过 input 再次传入。

**注意**：由于方法重载歧义，`stream(null, config)` 的 null 必须强转：

```java
graph.stream((Map<String, Object>) null, updatedConfig)
// 或流式:
graph.graphResponseStream((Map<String, Object>) null, updatedConfig)
```

### 13.3 checkGraphResult：判断图的最终状态

```java
private WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config, String intent) {
    var snapshot = graph.getState(config);  // 用原始config读取checkpoint

    if (snapshot == null) return WorkflowOutput.completed(intent, "操作完成(无状态)");

    String nextNode = snapshot.next();      // 下一个要执行的节点
    OverAllState state = snapshot.state();
    String question = (String) state.value("_question").orElse("");

    // 判断1: interruptBefore中断（nextNode非空且非__END__）
    if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
        return WorkflowOutput.interrupted(intent, question);
    }

    // 判断2: ask→END中断（图结束但有提问）
    if (question != null && !question.isEmpty()) {
        return WorkflowOutput.interrupted(intent, question);
    }

    // 正常完成
    String content = (String) state.value("_outputContent").orElse("操作已完成");
    clearCheckpoint(graph, config, intent);  // 清理checkpoint
    return WorkflowOutput.completed(intent, content);
}
```

---

## 14. 完整执行时序：从"帮我转账"到"转账成功"

### 第1轮：用户说"帮我转账"

```
L1: TransferService.handle("session1", "帮我转账", ...)
    └─ executeNewAgent("session1", "TRANSFER", "帮我转账")
         ├─ generateThreadId → "session1-TRANSFER-a3f2b1"
         ├─ setActiveAgent("session1", "TRANSFER", "session1-TRANSFER-a3f2b1")
         └─ graphExecutionEngine.executeGraph(transferGraph, "TRANSFER", input, threadId)
              └─ executeBlocking()
                   graph.stream(input, config)

=== Graph 内部执行 ===

[START]
  ↓
[extractParams]
  │ cancelAwareExtractParams → 无取消信号
  │ getLatestInput → "帮我转账"
  │ callExtractModel → LLM提取 → {receiver: null, amount: null}
  │   (LLM无法从"帮我转账"提取具体收款人/金额)
  │ mergeExtractedWithoutOverwrite → 空合并
  │ return {}
  ↓
[paramRouter]
  │ cancelAwareParamRouter → 无取消信号
  │ transfer.receiver = null → 缺收款人
  │ return {_paramName: "ASK_RECEIVER", _question: "请问您要转给谁？"}
  ↓
[条件路由] createCancelAwareRouter()
  │ _paramName = "ASK_RECEIVER"
  │ return "ASK_RECEIVER"
  ↓
[askReceiver] ← interruptBefore! 图在此暂停
  │ MemorySaver保存checkpoint:
  │   threadId = "session1-TRANSFER-a3f2b1"
  │   state = {messages: ["帮我转账"], _paramName: "ASK_RECEIVER",
  │            _question: "请问您要转给谁？", transfer.receiver: null, ...}
  ↓
graph.stream().blockLast() 返回

=== GES 检查 ===
checkGraphResult(graph, config, "TRANSFER")
  getState(config) → nextNode = "askReceiver" → 非空非__END__
  → INTERRUPTED(question="请问您要转给谁？")
  → StreamChunk.interrupted("TRANSFER", "请问您要转给谁？")

=== L1 处理 ===
assistantWriter.onChunk(INTERRUPTED) → 写入领域ChatMemory
handleActiveAgentState → active.lastQuestion = "请问您要转给谁？"

=== L0 输出 ===
data: {"type":"INTERRUPTED","intent":"TRANSFER","question":"请问您要转给谁？"}
data: [DONE]
```

### 第2轮：用户说"张三"

```
L1: TransferService.handle("session1", "张三", ...)
    │ getOwnActiveAgent → intent=TRANSFER, threadId=session1-TRANSFER-a3f2b1
    │ lastQuestion="请问您要转给谁？" → ContextRouter → FOLLOW
    └─ resumeActiveAgent("session1", "张三", activeAgent)
         └─ graphExecutionEngine.resumeGraph(transferGraph, "TRANSFER", "张三",
                                              "session1-TRANSFER-a3f2b1", globalData)
              └─ resumeBlocking()
                   graph.updateState(config, {_latestUserInput: "张三", _globalStateData: ...}, null)
                   graph.stream(null, updatedConfig)

=== Graph 内部执行（从checkpoint恢复）===

[askReceiver] ← 从中断点继续
  │ cancelAwareAsk → 无取消信号
  │ getLatestInput → "张三"
  │ callExtractModel → LLM提取 → {transfer.receiver: "张三"}
  │ result = {transfer.receiver: "张三", _latestUserInput: ""}
  ↓
[ask条件路由]
  │ getLatestInput → "" (已被清空)
  │ hasInput = false → "WAIT" → END
  │
  │ 等等！为什么走WAIT而不是CONTINUE？
  │ 因为 askReceiverLogic 最后执行了 result.put("_latestUserInput", "")
  │ 清空了用户输入。但框架在恢复时用的是updateState注入的"张三"，
  │ askReceiverLogic读取后才清空。所以流程应该是：
  │ getLatestInput → "张三" (从updateState注入的)
  │ askReceiverLogic → 提取receiver="张三" → 清空_latestUserInput → return
  │ addAskConditionalEdges路由 → hasInput? → _latestUserInput="" → WAIT → END
  │
  │ ❌ 上面的分析有误！askReceiverLogic清空的是返回Map中的_latestUserInput，
  │ 这个Map还没合并回state。条件路由读取的是合并前的旧state。
  │ 实际上：ask条件路由读到的是updateState注入的"张三"
  │ hasInput = true → "CONTINUE" → paramRouter ✅

  ↓
[paramRouter] (循环回来)
  │ transfer.receiver = "张三" → 已有收款人
  │ transfer.amount = null → 缺金额
  │ return {_paramName: "ASK_AMOUNT", _question: "请问您要转多少金额？"}
  ↓
[条件路由]
  │ _paramName = "ASK_AMOUNT"
  │ return "ASK_AMOUNT"
  ↓
[askAmount] ← interruptBefore! 图再次暂停

=== GES 检查 ===
→ INTERRUPTED(question="请问您要转多少金额？")
```

### 第3轮：用户说"500"

```
L1: resumeActiveAgent("session1", "500", activeAgent)
    └─ graphExecutionEngine.resumeGraph(...)

=== Graph 内部执行 ===

[askAmount] ← 从checkpoint恢复
  │ getLatestInput → "500"
  │ callExtractModel → {transfer.amount: 500}
  │   或 parseDirectAmount("500") → 500
  │ result = {transfer.amount: 500, _latestUserInput: ""}
  ↓
[ask条件路由]
  │ hasInput = true → "CONTINUE" → paramRouter
  ↓
[paramRouter]
  │ transfer.receiver = "张三" ✓
  │ transfer.amount = "500" ✓
  │ return {_paramName: "ALL_GOOD", _question: null}
  ↓
[条件路由]
  │ _paramName = "ALL_GOOD"
  │ return "ALL_GOOD"
  ↓
[executeTransfer]
  │ receiver="张三", amount=500
  │ mockBankingService.executeTransfer("张三", 500, null)
  │ return {_outputContent: "转账成功！已向张三转入500元", _outputType: "CONFIRMATION", _isFinal: true}
  ↓
[END]

=== GES 检查 ===
checkGraphResult → nextNode=null (或__END__) → question="" → COMPLETED
clearCheckpoint → 释放该threadId的checkpoint
→ StreamChunk.complete("TRANSFER", "转账成功！已向张三转入500元")
```

---

## 15. API 速查表

### StateGraph 构建

| API | 说明 |
|-----|------|
| `new StateGraph(keyStrategyFactory)` | 创建图，传入状态策略工厂 |
| `.addNode(name, asyncNodeAction)` | 添加节点 |
| `.addEdge(from, to)` | 添加固定边 |
| `.addConditionalEdges(from, router, edgeMap)` | 添加条件边 |
| `START` / `END` | 特殊节点：起点/终点 |

### 编译与执行

| API | 说明 |
|-----|------|
| `graph.compile(compileConfig)` | 编译为 CompiledGraph |
| `compiledGraph.stream(input, config)` | 同步执行，返回 `Stream<GraphResponse>` |
| `compiledGraph.graphResponseStream(input, config)` | 流式执行，返回 `Flux<GraphResponse>` |
| `compiledGraph.getState(config)` | 读取当前 checkpoint |
| `compiledGraph.updateState(config, data, nodeId)` | 更新状态（用于 resume） |

### CompileConfig

| 配置 | 说明 |
|------|------|
| `.saverConfig(saverConfig)` | 注册 CheckpointSaver |
| `.interruptBefore(node1, node2, ...)` | 在指定节点前中断 |
| `.recursionLimit(n)` | 最大节点执行次数 |

### RunnableConfig

| 字段 | 说明 |
|------|------|
| `threadId` | 会话线程ID，checkpoint 隔离 key |

### CheckpointSaver

| 实现 | 说明 |
|------|------|
| `MemorySaver` | InMemory，开发环境用 |
| `RedisCheckpointSaver` | Redis持久化，生产环境用 |
| `saver.release(config)` | 清理指定 threadId 的所有 checkpoint |

### OverAllState

| API | 说明 |
|-----|------|
| `state.value(key)` | 读取字段，返回 `Optional<Object>` |
| `state.next()` | 读取下一个要执行的节点名（仅 checkpoint 中有） |

### KeyStrategy

| 策略 | 说明 |
|------|------|
| `ReplaceStrategy` | 新值覆盖旧值 |
| `AppendStrategy` | 新值追加到列表 |

### 节点函数签名

```java
// 普通节点
Map<String, Object> nodeName(OverAllState state)

// 异步包装
node_async(this::nodeName)

// ask节点（自动interruptBefore + 取消检测）
askNode("nodeName", this::askLogic)
```

### 条件路由函数签名

```java
// 路由函数：OverAllState → String
edge_async(state -> {
    return "ROUTE_NAME";  // 必须在edgeMap中有对应条目
})
```
