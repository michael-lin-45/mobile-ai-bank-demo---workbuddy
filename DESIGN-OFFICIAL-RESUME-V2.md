# 官方模式二重设计：updateState + resume

> 基于 OfficialResumePatternTest 6/6 验证通过的结果

## 一、验证结论

| 验证项 | 结果 | 证据 |
|--------|------|------|
| 首次执行中断在 askReceiver | ✅ | `next=askReceiver` |
| resume 后 askReceiver 正常执行 | ✅ | `[askReceiver] _latestUserInput=mama` |
| resume 后遇到第二个 interruptBefore(askAmount) 正常中断 | ✅ | `next=askAmount, receiver=mama` |
| 第二次 resume 正常完成 | ✅ | `next=__END__, amount=500, _outputContent=transferred 500 to mama` |
| updateState 注入 _latestUserInput 可被 ask 节点读取 | ✅ | `receiver=mama` |
| 账单双中断点同样通过 | ✅ | askTime → askType → END |
| **同节点二次中断（无效输入循环回同一 ask 节点）** | ✅ | **`next=askAmount`（框架再次中断），Test6 验证** |

**关键发现：** `getState()` 要用原始 config（只有 threadId），不能用 `updateState()` 返回的 updatedConfig。

**关键发现（纠正）：** interruptBefore 是纯配置驱动的，每次遇到列表中的节点都会中断，**包括循环回同一节点**。不存在"同节点不会再次中断"的行为。

---

## 二、核心设计决策

### 2.1 threadId = sessionId

| 决策 | 说明 |
|------|------|
| threadId = sessionId | 稳定，不随机生成，resume 时复用同一 checkpoint |
| 每个 Graph 自带 MemorySaver | TransferGraph 的 MemorySaver 和 BillQueryGraph 的 MemorySaver 是独立实例 |
| 同一 sessionId 不冲突 | 不同 MemorySaver 实例中 key="user-123" 互不影响 |

```
TransferGraph:
  MemorySaver(transfer)["user-123"] = Checkpoint{next=askAmount, state={receiver="我妈", amount=null}}

BillQueryGraph:
  MemorySaver(bill)["user-123"] = Checkpoint{next=askType, state={timePeriod="上个月", expenseType=null}}

两个 Checkpoint 独立存在，互不干扰，threadId 都是 "user-123"
```

### 2.2 MemorySaver 架构详解

**当前代码（不需要改动）：每个 Graph 有独立的 MemorySaver 实例。**

```java
// AbstractGraphConfig.java — 每次 createSaverConfig() 创建新实例
protected SaverConfig createSaverConfig() {
    return SaverConfig.builder()
            .register(new MemorySaver())     // ← 每次调用创建新实例
            .build();
}

// 每个 GraphConfig 的 @Bean 方法都会调用 createCompileConfig() → createSaverConfig()
// TransferGraph → MemorySaver@A
// BillQueryGraph → MemorySaver@B
// WealthConsultGraph → MemorySaver@C
// WealthInterpretGraph → MemorySaver@D
```

**为什么独立 MemorySaver 是正确的：**

| 维度 | 说明 |
|------|------|
| State 结构不同 | TransferGraph 有 `transfer.receiver`，BillQueryGraph 有 `bill.timePeriod`，KeyStrategy 不同 |
| Checkpoint 不兼容 | 同一个 MemorySaver 无法存储不同结构的 OverAllState |
| 跨域切换天然支持 | 每个 Graph 的 checkpoint 在自己的 MemorySaver 中独立保留 |
| threadId=sessionId 不冲突 | `"user-123"` 在 MemorySaver@A 和 MemorySaver@B 中是独立的 Map.Entry |

**为什么不能共享 MemorySaver：**

如果所有 Graph 共享一个 MemorySaver，用同一个 threadId "user-123"：
- TransferGraph 写入的 Checkpoint 会被 BillQueryGraph 的 Checkpoint 覆盖
- 因为 MemorySaver 内部用 `(threadId, checkpointId)` 做 key，同一 threadId 只保留最新 checkpoint
- 跨域切回时，之前的 checkpoint 已被覆盖，无法恢复

**独立 MemorySaver 的跨域切换流程：**

```
1. 用户在 TransferGraph → 中断在 askAmount
   MemorySaver@A["user-123"] = Checkpoint{receiver="我妈", nextNode="askAmount"}

2. 用户切换到 BillQueryGraph
   MemorySaver@A["user-123"] 不变（BillQueryGraph 写的是 MemorySaver@B）
   MemorySaver@B["user-123"] = Checkpoint{timePeriod=null, nextNode="askTime"}

3. 用户切回 TransferGraph → resume
   GES.resumeGraph(transferGraph, ...) → 读 MemorySaver@A["user-123"] → receiver="我妈" 还在！
```

### 2.3 interruptBefore 同节点二次中断（源码级验证）

**结论：interruptBefore 是纯配置驱动的，每次遇到列表中的节点都会中断，包括循环回同一节点。**

**Test6 验证结果（6/6 通过）：**

```
R2: askAmount 中断 → 用户输入 "嗯" →
R3: askAmount 解析失败 → paramRouter 路由回 askAmount → ★ 框架再次中断！
    next=askAmount  ← 不是无限循环，不是 __END__，而是正确地再次中断

R4: 用户输入 "500" → askAmount 正常解析 → 完成
    next=__END__, receiver=mama, amount=500
```

**源码证据（从 spring-ai-alibaba 1.1.2.0 字节码反编译重构）：**

```java
// GraphRunnerContext.shouldInterruptBefore() — 反编译重构
private boolean shouldInterruptBefore(String nodeId, String previousNodeId) {
    if (previousNodeId == null) return false;   // ← 唯一跳过条件：首次启动（无前驱节点）
    return compiledGraph.compileConfig
           .interruptsBefore()                  // ← 纯配置检查
           .contains(nodeId);                   // ← 不关心是否"同一节点"、是否"同一轮resume"
}
```

```java
// GraphRunnerContext.shouldInterrupt() — 反编译重构
public boolean shouldInterrupt() {
    return shouldInterruptBefore(nextNodeId, currentNodeId)
        || shouldInterruptAfter(currentNodeId, nextNodeId);
}
```

**执行引擎（MainGraphExecutor.execute()）关键流程：**

```
Step 1: shouldStop / isMaxIterationsReached → handleCompletion
Step 2: ReturnFromEmbed → handle subgraph return
Step 3: currentNodeId != null && config.isInterrupted(currentNodeId) → withNodeResumed + return done
        ↑ 这一步处理"模式一"的 InterruptableAction 中断恢复
        ↑ 对于模式二 interruptBefore，config.interruptedNodes 为空（因为 updateState 返回新 config）
        ↑ 所以 Step 3 在模式二 resume 时不会触发
Step 4: isStartNode → handleStartNode
Step 5: isEndNode → handleEndNode
Step 6: getResumeFromAndReset + interruptBeforeEdge → handle resume from interrupt
Step 7: shouldInterrupt() → ★ 模式二的核心检查
        如果 shouldInterrupt() 返回 true：
        → config.markNodeAsInterrupted(currentNodeId)
        → 创建 InterruptionMetadata → 返回 done → stream 完成
Step 8: nodeExecutor.execute() — 正常执行节点
```

**关键：RunnableConfig.interruptedNodes 的生命周期**

```java
// RunnableConfig 构造器 — 每次创建新实例都初始化空 Map
private RunnableConfig(Builder builder) {
    ...
    this.interruptedNodes = new ConcurrentHashMap<>();  // ← 总是空的！
}

// updateState() 返回的是新 RunnableConfig 实例
// 所以 stream(null, updatedConfig) 时 interruptedNodes 为空
// Step 3 的 isInterrupted() 检查不会触发
// Step 7 的 shouldInterrupt() 会正常检查 interruptBefore 配置
```

**对用户输入无效数据的完整流程：**

```
Round 1: 用户说 "我要转账给我妈"
  → extractParams → paramRouter → ASK_RECEIVER → interruptBefore("askReceiver") ★

Round 2: 用户回答 "我妈"
  → updateState("_latestUserInput"="我妈") → stream(null, updatedConfig)
  → askReceiver 执行: receiver="我妈" → paramRouter → ASK_AMOUNT
  → interruptBefore("askAmount") ★

Round 3: 用户输入 "嗯"（无法解析为金额）
  → updateState("_latestUserInput"="嗯") → stream(null, updatedConfig)
  → askAmount 执行: 无法解析 → amount 未设置 → _latestUserInput=""
  → addAskConditionalEdges: _latestUserInput="" → "WAIT" → END？不，getLatestInput() 有 messages fallback
    实际：askAmount 设置了 _latestUserInput=""，addAskConditionalEdges 读 getLatestInput()
    → getLatestInput() 读 _latestUserInput="" → messages 非空 → "CONTINUE" → paramRouter
  → paramRouter: amount=null → ASK_AMOUNT → shouldInterruptBefore("askAmount", "paramRouter")
    → previousNodeId="paramRouter" 不为 null → interruptsBefore.contains("askAmount") → true
    → ★ 再次中断！nextNode="askAmount"
  → L1 收到 INTERRUPTED，question="请问您要转多少金额"

Round 4: 用户输入 "500"
  → updateState("_latestUserInput"="500") → stream(null, updatedConfig)
  → askAmount 执行: amount=500 → paramRouter → ALL_GOOD → executeAction → END
```

**UX 影响与改进方向（本次不实现，但需记录）：**

| 问题 | 说明 | 改进方向 |
|------|------|---------|
| 重复提问相同问题 | 用户看到两次 "请问您要转多少金额？"，可能困惑 | paramRouter 可检测"重入"并改写 _question，如 "抱歉，无法识别金额，请重新输入" |
| ask 节点 fallback | 当前 askAmount 对无法解析的输入不设置 amount，导致循环 | 这是正确行为：不设值 → paramRouter 重新路由 → 框架再次中断 |
| 无限重试 | 用户持续输入无效数据 | 可在 L1 层增加重试次数限制（如 3 次后主动取消） |

### 2.4 OverAllState 共享策略

**"共享"的含义：**
- 同一 session 中，任意时刻切回某个子 Graph，其之前的 OverAllState 仍在
- 不是物理共享一个 HashMap，而是每个 Graph 的 checkpoint 独立保留
- 切走时 checkpoint 不清理，切回来时 resume 即恢复

**不需要 SessionStateStore：**
- TransferGraph 的 checkpoint 保留了 receiver="我妈"
- 切到 BillQueryGraph 不影响 TransferGraph 的 checkpoint
- 切回 TransferGraph 时，用同一 threadId 的 config resume，receiver 自然在

### 2.5 官方模式二 vs 旧方式对比

| 维度 | 旧方式（重执行） | 官方模式二（resume） |
|------|-----------------|---------------------|
| resume 调用 | `stream(newInput, newConfig)` 新 threadId | `updateState()` + `stream(null, config)` 同一 threadId |
| state 来源 | 手动注入 accumulatedParams | checkpoint 自动保留 |
| 执行范围 | 从 START 重新执行所有节点 | 只执行从中断点开始的节点 |
| L1 是否传 accumulatedParams | 是 | **否** |
| L1 是否传 threadId | 是（每次新 UUID） | **否**（固定 sessionId） |
| MemorySaver 泄漏 | 是（旧 threadId 永不清理） | **否**（同一 threadId 覆盖更新） |

---

## 三、架构图

### 3.1 整体架构

```
sessionId = "user-123"

┌──────────────────────────────────────────────────────────┐
│ L0: BankController + DomainRouter                       │
│   状态: lastActiveDomain["user-123"] = "TRANSFER"       │
└──────────────────────┬──────────────────────────────────┘
                       │ domain="TRANSFER"
┌──────────────────────▼──────────────────────────────────┐
│ L1: SingleSubAgentDomainService(转账)                    │
│                                                          │
│   ActiveAgentInfo["user-123"] = {                        │
│     intent: "TRANSFER",                                  │
│     lastQuestion: "请问您要转多少金额"                     │
│   }                                                      │
│   ★ 不再有 threadId（= sessionId，由 GES 推导）           │
│   ★ 不再有 accumulatedParams（checkpoint 自动保留）       │
│                                                          │
│   路由: ContextRouter → IntentRouter                     │
└──────────────────────┬──────────────────────────────────┘
                       │ intent + userInput + sessionId
┌──────────────────────▼──────────────────────────────────┐
│ GraphExecutionEngine (GES)                               │
│                                                          │
│   executeGraph(graph, intent, userInput, sessionId):     │
│     1. config = threadConfig(sessionId)  // threadId=sessionId │
│     2. input = {messages, _latestUserInput, _question=null}   │
│     3. graph.stream(input, config)  // 首次执行          │
│     4. checkGraphResult(graph, config, intent)            │
│                                                          │
│   resumeGraph(graph, intent, userInput, sessionId):      │
│     1. config = threadConfig(sessionId)                  │
│     2. graph.updateState(config,                         │
│          Map.of("_latestUserInput", userInput), null)     │
│     3. graph.stream(null, updatedConfig)  // 恢复执行     │
│     4. checkGraphResult(graph, config, intent)  // 用原始config读 │
│                                                          │
│   ★ L1 不传 accumulatedParams，不传 threadId             │
│   ★ GES 内部封装 updateState + stream(null, config)      │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│ L2: TransferGraph (CompiledGraph)                        │
│   threadId = "user-123"  ← 稳定！                       │
│   MemorySaver(transfer):                                 │
│     ["user-123"] = Checkpoint{                           │
│       state = {receiver="我妈", amount=null, ...}        │
│       nextNode = "askAmount"                             │
│     }                                                    │
│                                                          │
│   ★ 子 Graph 代码几乎不变                                 │
│   ★ interruptBefore + addAskConditionalEdges 保持原样     │
└──────────────────────────────────────────────────────────┘
```

### 3.2 各层状态对比（改前 vs 改后）

```
                        改前                                改后
L0 lastActiveDomain:   [不变]                              [不变]

L1 activeThread:       {threadId, intent,                   {intent, lastQuestion}
                       accumulatedParams,                  ★ 删除 threadId (GES推导)
                       lastQuestion}                       ★ 删除 accumulatedParams (checkpoint)

L1 suspendedAgents:    {intent → {threadId,                 {intent → {}}
                       accumulatedParams}}                 ★ 删除 threadId, accumulatedParams

L1 saveL2Result:       保存 accumulatedParams               只保存 lastQuestion
                       + lastQuestion                       ★ 不再保存 accumulatedParams

GES executeGraph:      (graph, intent, threadId,            (graph, intent, userInput, sessionId)
                       userInput, sessionId,                 ★ 删除 threadId, accumulatedParams
                       accumulatedParams)

GES resumeGraph:       (intent, newThreadId,                 (graph, intent, userInput, sessionId)
                       userInput, sessionId,                 ★ 封装 updateState + stream(null,config)
                       accumulatedParams)                    ★ 删除 newThreadId, accumulatedParams

L2 子Graph:            不变                                 不变
L2 MemorySaver:        每次新 threadId → 泄漏               threadId=sessionId → 覆盖更新，无泄漏
```

---

## 四、GES 重设计

### 4.1 新接口

```java
@Service
public class GraphExecutionEngine {

    private final IntentRegistry intentRegistry;

    /** 生成 threadId = sessionId 的 config */
    private RunnableConfig threadConfig(String sessionId) {
        return RunnableConfig.builder().threadId(sessionId).build();
    }

    /**
     * 首次执行 Graph（新意图或跨域切换后的首次执行）
     *
     * @param graph 目标 CompiledGraph
     * @param intent 意图名称
     * @param userInput 用户输入
     * @param sessionId 会话ID（= threadId）
     */
    public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                       String userInput, String sessionId) {
        RunnableConfig config = threadConfig(sessionId);

        Map<String, Object> input = new HashMap<>();
        input.put("messages", userInput);
        input.put("_latestUserInput", userInput);
        input.put("_question", null);

        graph.stream(input, config).blockLast();
        return checkGraphResult(graph, config, intent, sessionId);
    }

    /**
     * 恢复执行 Graph — 官方模式二: updateState + stream(null, config)
     *
     * @param graph 目标 CompiledGraph
     * @param intent 意图名称
     * @param userInput 用户输入
     * @param sessionId 会话ID（= threadId）
     */
    public WorkflowOutput resumeGraph(CompiledGraph graph, String intent,
                                      String userInput, String sessionId) {
        RunnableConfig config = threadConfig(sessionId);

        // Step 1: updateState 注入用户输入
        RunnableConfig updatedConfig = graph.updateState(
                config, Map.of("_latestUserInput", userInput), null);

        // Step 2: stream(null, updatedConfig) 从中断点恢复
        graph.stream(null, updatedConfig).blockLast();

        // Step 3: 用原始 config 读最新 checkpoint（不是 updatedConfig！）
        return checkGraphResult(graph, config, intent, sessionId);
    }

    /**
     * 取消执行 — 注入 _cancelSignal 后恢复
     */
    public WorkflowOutput cancelGraph(CompiledGraph graph, String intent, String sessionId) {
        RunnableConfig config = threadConfig(sessionId);
        RunnableConfig updatedConfig = graph.updateState(
                config, Map.of("_latestUserInput", "取消", "_cancelSignal", true), null);
        graph.stream(null, updatedConfig).blockLast();
        return WorkflowOutput.completed(intent, "好的,已取消当前操作。");
    }

    /**
     * 检查执行结果 — 用原始 config 读最新 checkpoint
     */
    private WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config,
                                             String intent, String sessionId) {
        var snapshot = graph.getState(config);
        // ... 同现有逻辑，但不再提取 accumulatedParams ...
        // INTERRUPTED: 返回 question
        // COMPLETED: 返回 content
    }
}
```

### 4.2 关键注意事项

| 注意点 | 说明 |
|--------|------|
| getState 用原始 config | `getState(config)` 用只有 threadId 的 config，不用 updateState 返回的 updatedConfig |
| updateState 第二参数传 null | interruptBefore 模式下 nodeId 传 null |
| stream 第二参数传 null | `stream(null, updatedConfig)` 表示使用 checkpoint 中的 state |
| 不再提取 accumulatedParams | checkpoint 自动保留完整 OverAllState，不需要手动传递 |

### 4.3 WorkflowOutput 简化

```java
// 改前
WorkflowOutput {
    status: INTERRUPTED / COMPLETED
    intent: "TRANSFER"
    threadId: "aaa111"         ← 删除
    question: "转给谁"
    accumulatedParams: {...}   ← 删除
    content: "转账成功"
}

// 改后
WorkflowOutput {
    status: INTERRUPTED / COMPLETED
    intent: "TRANSFER"
    question: "转给谁"
    content: "转账成功"
    // ★ 删除 threadId（= sessionId，调用方已有）
    // ★ 删除 accumulatedParams（checkpoint 自动保留）
}
```

---

## 五、L1 DomainService 重设计

### 5.1 ActiveThreadInfo → ActiveAgentInfo

```java
// 改前
ActiveThreadInfo {
    String threadId;                    ← 删除
    String intent;
    Instant createdAt;
    Instant expiresAt;
    Map<String, Object> accumulatedParams;  ← 删除
    String lastQuestion;
}

// 改后
ActiveAgentInfo {
    String intent;                      // 当前活跃意图
    Instant createdAt;
    Instant expiresAt;
    String lastQuestion;                // 子 Graph 最后的提问
    // ★ threadId = sessionId（由 GES 推导）
    // ★ accumulatedParams 在 checkpoint 中（由 GES 管理）
}
```

### 5.2 SuspendedInfo 简化

```java
// 改前
SuspendedInfo {
    String threadId;                    ← 删除
    String intent;
    Instant suspendedAt;
    Instant expiresAt;
    Map<String, Object> accumulatedParams;  ← 删除
}

// 改后
SuspendedInfo {
    String intent;                      // 挂起的意图
    Instant suspendedAt;
    Instant expiresAt;
    // ★ 无 threadId（= sessionId）
    // ★ 无 accumulatedParams（在子 Graph 的 checkpoint 中）
}
```

### 5.3 AbstractDomainService 修改清单

| 方法 | 改动 |
|------|------|
| `setOwnActiveThread(sessionId, threadId, intent)` | → `setOwnActiveAgent(sessionId, intent)` — 删除 threadId 参数 |
| `getOwnActiveThread(sessionId)` | → `getOwnActiveAgent(sessionId)` — 返回 ActiveAgentInfo |
| `clearOwnActiveThread(sessionId)` | → `clearOwnActiveAgent(sessionId)` |
| `saveL2Result(sessionId, output)` | 只保存 lastQuestion，不再保存 accumulatedParams |
| `resumeActiveThread(sessionId, userInput, active)` | 简化：`resumeActiveAgent(sessionId, userInput, active)` — 只调 GES.resumeGraph |
| `executeNewThread(sessionId, intent, rewrittenInput)` | 简化：`executeNewAgent(sessionId, intent, rewrittenInput)` — 只调 GES.executeGraph |
| `generateThreadId()` | **删除** |
| `buildCurrentAgent(sessionId)` | 不变（读 active.intent） |

### 5.4 resumeActiveAgent（改后）

```java
// 改前
protected WorkflowOutput resumeActiveThread(String sessionId, String userInput, ActiveThreadInfo active) {
    addUserMessage(sessionId, userInput);
    String newThreadId = generateThreadId();
    setOwnActiveThread(sessionId, newThreadId, active.getIntent());
    WorkflowOutput result = graphExecutionEngine.resumeGraph(
            active.getIntent(), newThreadId, userInput, sessionId, active.getAccumulatedParams());
    saveL2Result(sessionId, result);
    recordSystemReply(sessionId, result);
    if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
        clearOwnActiveThread(sessionId);
    }
    return result;
}

// 改后
protected WorkflowOutput resumeActiveAgent(String sessionId, String userInput, ActiveAgentInfo active) {
    addUserMessage(sessionId, userInput);
    CompiledGraph graph = intentRegistry.getGraph(active.getIntent());
    WorkflowOutput result = graphExecutionEngine.resumeGraph(
            graph, active.getIntent(), userInput, sessionId);  // ★ 不传 accumulatedParams
    saveL2Result(sessionId, result);
    recordSystemReply(sessionId, result);
    if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
        clearOwnActiveAgent(sessionId);
    }
    return result;
}
```

### 5.5 executeNewAgent（改后）

```java
// 改前
protected WorkflowOutput executeNewThread(String sessionId, String intent, String rewrittenInput) {
    var graph = intentRegistry.getGraph(intent);
    String newThreadId = generateThreadId();
    setOwnActiveThread(sessionId, newThreadId, intent);
    WorkflowOutput result = graphExecutionEngine.executeGraph(
            graph, intent, newThreadId, rewrittenInput, sessionId, null);
    saveL2Result(sessionId, result);
    if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
        clearOwnActiveThread(sessionId);
    }
    return result;
}

// 改后
protected WorkflowOutput executeNewAgent(String sessionId, String intent, String rewrittenInput) {
    var graph = intentRegistry.getGraph(intent);
    setOwnActiveAgent(sessionId, intent);  // ★ 不传 threadId
    WorkflowOutput result = graphExecutionEngine.executeGraph(
            graph, intent, rewrittenInput, sessionId);  // ★ 不传 threadId, accumulatedParams
    saveL2Result(sessionId, result);
    if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
        clearOwnActiveAgent(sessionId);
    }
    return result;
}
```

### 5.6 saveL2Result 简化

```java
// 改前
protected void saveL2Result(String sessionId, WorkflowOutput output) {
    ActiveThreadInfo active = getOwnActiveThread(sessionId);
    if (active != null) {
        if (output.getAccumulatedParams() != null && !output.getAccumulatedParams().isEmpty()) {
            active.setAccumulatedParams(output.getAccumulatedParams());
        }
        if (output.getStatus() == WorkflowStatus.INTERRUPTED && output.getQuestion() != null) {
            active.setLastQuestion(output.getQuestion());
        } else if (output.getStatus() == WorkflowStatus.COMPLETED) {
            active.setLastQuestion(null);
        }
    }
}

// 改后
protected void saveL2Result(String sessionId, WorkflowOutput output) {
    ActiveAgentInfo active = getOwnActiveAgent(sessionId);
    if (active != null) {
        // ★ 不再保存 accumulatedParams（checkpoint 自动保留）
        if (output.getStatus() == WorkflowStatus.INTERRUPTED && output.getQuestion() != null) {
            active.setLastQuestion(output.getQuestion());
        } else if (output.getStatus() == WorkflowStatus.COMPLETED) {
            active.setLastQuestion(null);
        }
    }
}
```

### 5.7 MultiSubAgentDomainService 修改清单

| 方法/状态 | 改动 |
|-----------|------|
| `suspendOwnAgent(sessionId, intent, threadId)` | → `suspendOwnAgent(sessionId, intent)` — 删除 threadId |
| `SuspendedInfo.accumulatedParams` | **删除** — params 在子 Graph 的 checkpoint 中 |
| `handleResume(sessionId, intent, userInput)` | 用 GES.resumeGraph(graph, intent, userInput, sessionId) — 不传 suspendedParams |
| `suspendOwnAgent` 中的参数合并逻辑 | **删除** — 不再需要在 suspend 时合并 accumulatedParams |
| 其他逻辑 | 基本不变 — suspendedAgents 只跟踪哪些意图被挂起，不存参数 |

---

## 六、子 Graph 适配分析

### 6.1 不需要改的部分

| 组件 | 原因 |
|------|------|
| `interruptBefore` 配置 | `createCompileConfig("askReceiver", "askAmount")` 不变 |
| `addAskConditionalEdges` | ask→CONTINUE/WAIT 不变。resume 时 _latestUserInput 由 updateState 设置，ask 节点读到后 CONTINUE→paramRouter |
| `extractParams` 节点 | 首次执行时正常运行；resume 时不会执行（只执行中断点之后的节点）|
| `paramRouter` 节点 | 不变 — 读 state 中的参数值做路由 |
| `askReceiver` / `askAmount` | 不变 — 读 _latestUserInput，提取参数，清空 _latestUserInput |
| `executeAction` 节点 | 不变 |
| `cancelAwareExtractParams` | 首次执行时运行；resume 时不会执行（在 ask 节点之后） |
| `createSaverConfig()` | 不变 — 每个 Graph 仍自带 MemorySaver |
| `registerCustomKeys()` | 不变 |
| 所有 `.st` 模板 | 不变 |

### 6.2 需要关注的细节

**细节1：ask 节点清空 `_latestUserInput`**

```java
// askReceiver 现有逻辑
result.put("_latestUserInput", "");  // 清空
```

resume 流程：
1. updateState 注入 `_latestUserInput="我妈"`
2. askReceiver 读到 "我妈"，设置 receiver="我妈"，清空 `_latestUserInput`
3. 条件边用 `getLatestInput()` → `_latestUserInput=""` → fallback 读 messages → 非空 → CONTINUE → paramRouter

✅ 不需要改。`getLatestInput()` 的 messages fallback 保证了 CONTINUE 路径。

**细节2：extractParams 只在首次执行时运行**

resume 时从 askReceiver 开始执行，extractParams 不会重新运行。
- 旧方式：每次重执行，extractParams 都用 LLM 提取参数
- 新方式：只有 ask 节点处理用户输入，更精准更高效

✅ 这是改进，不是问题。ask 节点就是为处理特定参数设计的。

**细节3：跨域切回时，同一 threadId 的 checkpoint 还在**

用户从转账切到账单，再切回转账：
- TransferGraph 的 MemorySaver["user-123"] 仍然有 receiver="我妈"
- GES.resumeGraph() 用 updateState + stream(null, config) → 从 askAmount 继续执行
- askAmount 读到新的 _latestUserInput → 处理 → paramRouter → ...

✅ 不需要改。checkpoint 自然保留。

### 6.3 同节点二次中断：框架会再次中断（已验证）

**场景**：用户在 askAmount 阶段输入了无法解析的内容（如 "嗯"），askAmount 没有设置 amount，paramRouter 再次路由到 askAmount。

**验证结果（Test6 通过）**：框架**会再次中断**。不会无限循环。

```
[R3] next=askAmount amount= _paramName=ASK_AMOUNT  ← ★ 框架再次中断！
[R4] next=__END__ receiver=mama amount=500           ← ★ 再次 resume 后正常完成
```

**源码级解释**：`shouldInterruptBefore(nodeId, previousNodeId)` 只检查两个条件：
1. `previousNodeId != null`（非首次启动）
2. `interruptsBefore.contains(nodeId)`（节点在中断列表中）

**不检查**：是否"同一节点"、是否"同一轮 resume"、是否"已经中断过"。

**对现有代码的影响**：

| 节点 | 当前行为 | 无效输入时 | 循环回同节点时 |
|------|---------|-----------|-------------|
| askReceiver | LLM提取 receiver，fallback 用原始输入 | 几乎不可能无效（任何文本都可作为收款人） | 不适用 |
| askAmount | LLM提取 amount，`parseDirectAmount` 兜底 | "嗯" → 无法解析 → **amount 不设置** | **paramRouter → ASK_AMOUNT → 再次中断 ✓** |
| askTime | LLM提取 timePeriod，fallback 用原始输入 | 类似 askReceiver | 不适用 |
| askType | LLM提取 expenseType，fallback 用原始输入 | 类似 askReceiver | 不适用 |

**askAmount 是唯一切实可能循环回同节点的场景**，因为金额有严格的格式要求（必须是数字）。其他 ask 节点的 fallback 总是用原始输入作为参数值，不会导致循环。

**不需要改代码**。当前行为正确：无效输入 → 不设值 → 再次中断 → 用户再次回答。

---

## 七、流程图

### 7.1 转账三轮对话（官方模式二）

```
Round 1: 用户说 "我要转账给我妈"
──────────────────────────────────────────────────────────
L0: DomainRouter → TRANSFER
L1: 无 activeAgent → handleNewIntention()
    IntentRouter → TRANSFER
    setOwnActiveAgent("user-123", "TRANSFER")
    executeNewAgent("user-123", "TRANSFER", "我要转账给我妈")

GES.executeGraph(transferGraph, "TRANSFER", "我要转账给我妈", "user-123"):
    config = {threadId: "user-123"}
    input = {messages: "我要转账给我妈", _latestUserInput: "我要转账给我妈", _question: null}
    transferGraph.stream(input, config)

    TransferGraph 执行:
      START → extractParams → LLM提取 → {receiver=null, amount=null}
             → paramRouter → ASK_RECEIVER → interruptBefore("askReceiver") ★

    MemorySaver(transfer)["user-123"] = Checkpoint{
        state = {receiver=null, amount=null, _question="转给谁", _paramName="ASK_RECEIVER"}
        nextNode = "askReceiver"
    }

    GES.checkGraphResult → INTERRUPTED, question="转给谁"

L1: saveL2Result → activeAgent.lastQuestion = "转给谁"

用户看到: "请问您要转给谁？"
```

```
Round 2: 用户回答 "我妈"
──────────────────────────────────────────────────────────
L0: DomainRouter → FOLLOW (lastQuestion="转给谁")
L1: activeAgent存在 + lastQuestion → FOLLOW
    resumeActiveAgent("user-123", "我妈", active)

GES.resumeGraph(transferGraph, "TRANSFER", "我妈", "user-123"):
    config = {threadId: "user-123"}

    Step 1: transferGraph.updateState(config,
                Map.of("_latestUserInput", "我妈"), null)
            → Checkpoint 中 _latestUserInput 更新为 "我妈"

    Step 2: transferGraph.stream(null, updatedConfig)
            → 从 askReceiver 继续执行

    TransferGraph 从 askReceiver 继续:
      askReceiver → 读 _latestUserInput="我妈" → receiver="我妈" → _latestUserInput=""
      → 条件边 CONTINUE → paramRouter
      → paramRouter → receiver="我妈", amount=null → ASK_AMOUNT
      → interruptBefore("askAmount") ★ 新中断点！

    MemorySaver(transfer)["user-123"] = Checkpoint{
        state = {receiver="我妈", amount=null, _question="转多少", _paramName="ASK_AMOUNT"}
        nextNode = "askAmount"
    }

    GES.checkGraphResult(config) → INTERRUPTED, question="转多少"
    ★ receiver="我妈" 在 checkpoint 中自动保留！无需 accumulatedParams！

L1: saveL2Result → activeAgent.lastQuestion = "转多少"

用户看到: "请问您要转多少金额？"
```

```
Round 3: 用户回答 "500"
──────────────────────────────────────────────────────────
L1: activeAgent + lastQuestion → FOLLOW
    resumeActiveAgent("user-123", "500", active)

GES.resumeGraph(transferGraph, "TRANSFER", "500", "user-123"):
    config = {threadId: "user-123"}
    updateState(config, Map.of("_latestUserInput", "500"), null)
    transferGraph.stream(null, updatedConfig)

    TransferGraph 从 askAmount 继续:
      askAmount → 读 _latestUserInput="500" → amount=500 → _latestUserInput=""
      → 条件边 CONTINUE → paramRouter
      → paramRouter → receiver="我妈", amount=500 → ALL_GOOD
      → executeAction → "已向我妈转账500元"
      → END

    GES.checkGraphResult(config) → COMPLETED, content="已向我妈转账500元"

L1: saveL2Result → activeAgent.lastQuestion = null
    clearOwnActiveAgent("user-123")

用户看到: "已向我妈转账500元"
```

### 7.2 跨域切换（转账 → 账单 → 切回转账）

```
Round 1-2: 转账两轮对话
    → TransferGraph checkpoint: {receiver="我妈", nextNode="askAmount"}

Round 3: 用户说 "算了，查下账单"
──────────────────────────────────────────────────────────
L0: DomainRouter → SWITCH → BILL
L1(BILL): 无 activeAgent → handleNewIntention()
    executeNewAgent("user-123", "BILL_QUERY", "查下账单")

GES.executeGraph(billQueryGraph, "BILL_QUERY", "查下账单", "user-123"):
    config = {threadId: "user-123"}    ← 同一个 sessionId！

    BillQueryGraph 执行:
      START → extractParams → paramRouter → ASK_TIME
      → interruptBefore("askTime") ★

    MemorySaver(bill)["user-123"] = Checkpoint{
        state = {timePeriod=null, expenseType=null, ...}
        nextNode = "askTime"
    }

    ★ TransferGraph 的 MemorySaver(transfer)["user-123"] 不受影响！
    ★ receiver="我妈" 仍然在 TransferGraph 的 checkpoint 中

L1(BILL): activeAgent = {intent=BILL_QUERY, lastQuestion="哪个时间段"}
```

```
Round 4-5: 账单两轮对话 → 完成
    → BillQueryGraph 完成

Round 6: 用户说 "继续转账"
──────────────────────────────────────────────────────────
L0: DomainRouter → SWITCH → TRANSFER
L1(TRANSFER): 无 activeAgent（之前清空了？还是保留了？）

    ★ 关键问题：转账完成后 activeAgent 已清空。
    但如果转账是中断状态切走的，activeAgent 应该还在。

    假设转账是中断状态切走的（Round 3 时未完成）：
    L1(TRANSFER): 有 activeAgent = {intent=TRANSFER, lastQuestion="转多少"}

    但问题是：L1 是 per-domain 的 service，切到 BILL 时 TRANSFER 的 L1 
    是否还保留着 activeAgent？

    是的！每个 L1 DomainService 有自己的 activeAgents Map，
    切走时不清理（只是 L0 路由到新的 L1，旧 L1 的状态保留）。

    所以切回时：
    L1(TRANSFER): activeAgent 仍在，lastQuestion="转多少"
    → FOLLOW → resumeActiveAgent("user-123", "继续转账", active)

GES.resumeGraph(transferGraph, "TRANSFER", "继续转账", "user-123"):
    config = {threadId: "user-123"}

    ★ TransferGraph 的 MemorySaver 仍有 ["user-123"] 的 checkpoint！
    ★ receiver="我妈" 在 checkpoint 中！

    updateState(config, Map.of("_latestUserInput", "继续转账"), null)
    transferGraph.stream(null, updatedConfig)

    TransferGraph 从 askAmount 继续:
      askAmount → 读 "继续转账" → parseDirectAmount → null → amount 不设置
      → addAskConditionalEdges: CONTINUE → paramRouter
      → paramRouter: amount=null → ASK_AMOUNT
      → shouldInterruptBefore("askAmount", "paramRouter") → true → ★ 再次中断！

    L1 收到 INTERRUPTED, question="请问您要转多少金额"
    用户看到: "请问您要转多少金额？"（再次提问，但 receiver="我妈" 仍在 checkpoint 中）
```

### 7.3 跨域切换的关键问题

**场景**：转账中断（等金额）→ 查账单 → 完成 → "继续转账"

**问题分析**：

"继续转账"不是金额回答。两种处理方式：

| 方式 | 处理 | 用户体验 |
|------|------|---------|
| A. 直接 resume | updateState("继续转账") → askAmount 尝试解析 → 失败 → **框架再次中断** | 用户再次看到"请问您要转多少金额" |
| B. L1 判断后传合适内容 | L1 识别出"继续转账"不是回答，而是意图恢复 → 不走 resume，走 executeNewAgent | 好，但丢失 checkpoint 中的参数 |

**方式 A 的实际行为（基于 Test6 验证）**：

```
resumeActiveAgent("user-123", "继续转账", active)
  → GES.resumeGraph(transferGraph, "TRANSFER", "继续转账", "user-123")
  → updateState("_latestUserInput"="继续转账")
  → stream(null, updatedConfig)
  → askAmount 执行: parseDirectAmount("继续转账") → null → amount 不设置
  → paramRouter: amount=null → ASK_AMOUNT
  → shouldInterruptBefore("askAmount", "paramRouter") → true → ★ 再次中断
  → L1 收到 INTERRUPTED, question="请问您要转多少金额"
```

方式 A 实际上是安全的——不会无限循环，框架会再次中断，用户会被再次提问。

**方式 B 的问题**：走 executeNewAgent 会丢失 checkpoint 中的 receiver="我妈"，用户需要重新提供。

**当前选择方式 A**：直接 resume，让框架自动处理无效输入的二次中断。用户体验虽然不完美（看到相同的提问），但功能正确且不丢失参数。

**后续 UX 改进方向（本次不实现）**：
- L1 可在 resume 前判断用户输入是否对 lastQuestion 有效回答
- 如果无效，L1 可以传空字符串让 ask 节点走到 WAIT→END，然后重新引导
- 或 paramRouter 检测"重入"并改写 _question 为"抱歉，无法识别金额，请重新输入转账金额"

---

## 八、askNode() 基类封装设计（已实现）

### 8.1 问题发现

官方模式二 resume 从中断点（ask节点）继续执行，**不再经过 extractParams 节点**。旧代码中 cancel 检测依赖 `cancelAwareExtractParams`，它在 extractParams 节点中执行，resume 时不会重新运行，导致 **cancel 意图检测被跳过**。

**旧流程（重新执行）**：
```
resume → START → extractParams (cancelAwareExtractParams 检测) → askNode → ...
```

**新流程（官方resume）**：
```
resume → askNode (无cancel检测！) → ...
```

### 8.2 解决方案：askNode() 自动封装

将 `cancelAwareAsk` 封装到基类 `askNode()` 方法中，所有 ask 节点自动获得 cancel 检测能力。子 Graph 实现者只需写纯业务逻辑。

```java
/**
 * 创建带取消检测 + 自动interruptBefore的ask节点
 *
 * 一站式完成三件事:
 * 1. 自动包装cancelAwareAsk取消检测
 * 2. 自动注册到interruptBefore列表
 * 3. 转换为AsyncNodeAction
 */
protected AsyncNodeAction askNode(String nodeName,
        Function<OverAllState, Map<String, Object>> askLogic) {
    interruptNodes.add(nodeName);  // 自动注册interruptBefore
    return node_async(state -> {
        Map<String, Object> cancelResult = cancelAwareAsk(state);  // 自动cancel检测
        if (cancelResult != null) return cancelResult;
        return askLogic.apply(state);  // 纯业务逻辑
    });
}
```

### 8.3 cancelAwareAsk 检测逻辑

```
ask节点被resume执行时:
1. 检查 _cancelSignal 信号 (由 GES.cancelGraph 注入)
2. 关键字匹配 (0ms): "取消""算了""不转了"等
3. LLM判断 (200-500ms): 关键字未命中时兜底

检测到cancel → 注入 _cancelSignal=true → paramRouter → CANCEL → cancelExecutionNode → END
未检测到cancel → 执行子类的askLogic (纯业务逻辑)
```

### 8.4 createInterruptCompileConfig() 自动构建

`askNode()` 每次调用时自动将节点名加入 `interruptNodes` 列表，编译时通过 `createInterruptCompileConfig()` 自动生成 interruptBefore 配置。

```java
// 旧方式: 手写节点名数组，容易遗漏/拼写错误
graph.compile(createCompileConfig("askReceiver", "askAmount"))

// 新方式: 自动从askNode()调用中收集
graph.compile(createInterruptCompileConfig())
```

### 8.5 子 Graph 实现者清单

**必须实现**：
- `getGraphName()` — Graph名称
- `buildExtractPrompt()` — 参数提取prompt
- `parseExtractResult()` — 解析LLM返回的JSON
- `registerCustomKeys()` — 注册Graph专用state keys
- `getCancelDetectionContext()` — 取消检测上下文描述

**可选覆盖**：
- `getCancelKeywords()` — 领域特定取消关键词
- `onCleanup()` — 取消时的自定义清理逻辑

**构建约定**：
```java
// ask节点: askNode("name", this::logic)  — 自动cancel + 自动interrupt
.addNode("askReceiver", askNode("askReceiver", this::askReceiverLogic))
.addNode("askAmount", askNode("askAmount", this::askAmountLogic))

// 编译: createInterruptCompileConfig() — 自动从askNode()注册收集
graph.compile(createInterruptCompileConfig())

// 取消路由
addCancelNode(graph)                    // cancelExecution节点 + END边
addCancelEdge(edges)                    // paramRouter的CANCEL路由
createCancelAwareRouter()               // paramRouter条件路由函数

// ask条件边
addAskConditionalEdges(graph, "askX")   // CONTINUE→paramRouter / WAIT→END

// extractParams: cancelAwareExtractParams(state) 包装
// paramRouter: cancelAwareParamRouter(state) 包装
```

### 8.6 cancelExecutionNode _question 清除 Bug

**发现**：cancel 后 graph 结束在 `__END__`，但 `_question` 仍保留旧值，导致 GES `checkGraphResult` 误判为 INTERRUPTED 而非 COMPLETED。

**修复**：`cancelExecutionNode` 中添加 `result.put("_question", null)`。

```java
protected Map<String, Object> cancelExecutionNode(OverAllState state) {
    ...
    result.put("_question", null);  // ← 清除，避免GES误判
    ...
}
```

---

## 九、belongs_to_domain 意图类型判断设计（已实现）

### 9.1 问题发现

"我刚才转账给谁了" 被错误路由到 TRANSFER 且 `belongs_to_domain=true`，因为 IntentRouter 仅匹配关键词"转账"，未区分"要求执行转账操作" vs "追问操作结果"。

**根因**：原始 prompt 的 `belongs_to_domain` 规则只覆盖了 FAQ 知识类问题（如"转账多久到账"），未覆盖"追问/回顾操作结果"模式。

### 9.2 解决方案：intentType 维度判断

在 l1-intention.st 中引入按 intentType 分类的判断规则，要求结合每个子智能体的 `[intentType]` `[description]` `[scope]` 三维信息判定。

**核心原则**：用户意图必须**匹配子智能体的 intentType** 才能判定 `belongs_to_domain=true`。

```
按intentType逐类判断:

[OPERATION]类型: 用户必须要求**执行该操作**才属于scope
  ✅ "我要转账" "帮我转500给小美" → 要求执行 → true
  ❌ "我刚才转账给谁了" "上次转了多少" → 追问操作结果 → false
  ❌ "转账多久到账" → 询问操作知识 → false

[QUERY]类型: 用户必须要求**查询数据**才属于scope
  ✅ "查账单" "上个月花了多少" → 要求查询 → true
  ❌ "账单在哪里看" → 询问知识/概念 → false

[CONSULTATION]类型: 用户必须要求**咨询/推荐/解读**才属于scope
  ✅ "推荐稳健理财" "解读朝朝盈" → 要求咨询/解读 → true
  ❌ "理财有风险吗" → 询问知识/概念 → false
```

### 9.3 belongs_to_domain=false 的处理

当 `belongs_to_domain=false` 时，触发 REROUTE：

1. L1 不处理此意图，返回 REROUTE 信号给 L0
2. L0 排除当前领域，从其他领域重新查找匹配的 domain
3. 若无其他 domain 匹配，路由到默认的 CHAT 领域

**注意**：不硬编码 REROUTE 目标为 CHAT，只触发 REROUTE，L0 负责排除当前域后重选。

### 9.4 Prompt 改动

**l1-intention.st** 中新增"归属判断"任务规则（第3条），要求逐 intentType 判断：

```
3. 归属判断(核心):
   判断用户意图是否属于本领域子智能体的处理范围，必须结合本领域每个
   子智能体的[类型][描述][范围]逐一比对。
   
   ★ 核心原则: 用户意图必须**匹配子智能体的intentType**才能判定belongs_to_domain=true
   
   按intentType逐类判断:
   - [OPERATION]类型: 用户必须要求**执行该操作**才属于scope
   - [QUERY]类型: 用户必须要求**查询数据**才属于scope
   - [CONSULTATION]类型: 用户必须要求**咨询/推荐/解读**才属于scope
   
   以下情况 → belongs_to_domain=false:
     a) 追问/回顾已完成的操作结果
     b) 询问本域相关的知识/概念/定义/分类等FAQ问题
     c) 用户意图明确属于其他已注册意图的scope范围
     d) 用户意图不属于本领域任何子智能体的scope范围
   - 无法确定时 → belongs_to_domain=true (保守策略)
```

**IntentRouter.java** 的 fallback prompt（`getDefaultRewritePrompt`）同步更新了相同规则。

### 9.5 回归测试

IntegrationFlowTest #32 验证：

```
场景: "我刚才转账给谁了"
期望: belongs_to_domain=false → REROUTE
结果: ✅ 通过
```

---

## 十、文件改动清单

### 10.1 新增文件

| 文件 | 说明 |
|------|------|
| 无 | 不需要新增组件（不需要 SessionStateStore、CleanableMemorySaver）|

### 10.2 修改文件

| 文件 | 改动 |
|------|------|
| **GraphExecutionEngine.java** | **核心重写**：executeGraph/resumeGraph/cancelGraph 封装 updateState+stream；删除 threadId/accumulatedParams 参数 |
| **AbstractDomainService.java** | ActiveThreadInfo→ActiveAgentInfo（删除 threadId、accumulatedParams）；resumeActiveAgent/executeNewAgent；tryAutoUpgradeFollowUp |
| **SingleSubAgentDomainService.java** | 适配 ActiveAgentInfo；调用 GES 新接口 |
| **MultiSubAgentDomainService.java** | SuspendedInfo 删除 threadId/accumulatedParams；DisambiguationState；5层决策流 |
| **WorkflowOutput.java** | 删除 threadId/accumulatedParams 字段 |
| **AbstractGraphConfig.java** | **askNode() 自动封装**：cancelAwareAsk + interruptBefore注册 + createInterruptCompileConfig()；cancelExecutionNode 清除 _question |
| **TransferGraphConfig.java** | 使用 askNode() + createInterruptCompileConfig()，纯业务逻辑 |
| **BillQueryGraphConfig.java** | 使用 askNode() + createInterruptCompileConfig()，纯业务逻辑 |
| **WealthConsultGraphConfig.java** | 使用 askNode() + createInterruptCompileConfig()，纯业务逻辑 |
| **WealthInterpretGraphConfig.java** | 使用 askNode() + createInterruptCompileConfig()，纯业务逻辑 |
| **l1-intention.st** | **增强**：按 intentType 维度的 belongs_to_domain 判断规则 |
| **IntentRouter.java** | fallback prompt 同步 intentType 规则 |

### 10.3 不变的文件

| 文件 | 原因 |
|------|------|
| **BankController.java** | L0 层不涉及状态存储改动 |
| **DomainServiceConfig.java** | Bean 配置不变 |
| **所有 L2 .st 模板** | 提取参数模板不变（l1-intention.st 除外） |
| **ContextRouter.java** | 不涉及 belongs_to_domain 判断 |
| **IntentResolver.java** | 不涉及状态存储 |
| **DomainRouter.java** | L0 路由不变 |
| **application.yml** | 配置不变 |

---

## 十一、与之前设计文档的对比

| 维度 | DESIGN-STATE-ARCHITECTURE.md（旧） | 本文档（新） |
|------|-----------------------------------|-------------|
| resume 方式 | 重新执行 + SessionStateStore 注入 | **官方模式二：updateState + stream(null, config)** |
| threadId | 随机（ephemeral） | **= sessionId（稳定）** |
| accumulatedParams | L1 保存 → GES 注入 | **不需要（checkpoint 自动保留）** |
| SessionStateStore | 需要（跨域共享） | **不需要（各 Graph 的 checkpoint 独立保留）** |
| CleanableMemorySaver | 需要（清理泄漏） | **不需要（同一 threadId 覆盖更新）** |
| MemorySaver 泄漏 | 有（随机 threadId 永不清理） | **无（同一 sessionId 覆盖更新）** |
| 子 Graph 改动 | 无 | **askNode() 封装 + createInterruptCompileConfig()，纯业务逻辑** |
| belongs_to_domain 判断 | 关键词匹配 | **intentType 三维判断** |
| L1 改动量 | 中（新增 SessionStateStore 管理） | **小（删除 accumulatedParams/threadId 管理）** |
| GES 改动量 | 中（注入 SessionStateStore） | **中（封装 updateState+resume）** |
| 执行效率 | 低（每次重执行所有节点） | **高（只执行中断点之后的节点）** |

---

## 十二、风险与待验证项

| 风险 | 说明 | 严重性 | 状态 |
|------|------|--------|------|
| ~~同节点二次中断~~ | ~~resume session 中循环回同一 interruptBefore 节点，不会再次中断~~ | ~~中~~ | **✅ 已验证：框架会再次中断（Test6 通过），不会无限循环** |
| ~~resume时cancel检测缺失~~ | ~~官方模式resume跳过extractParams，cancelAwareExtractParams不执行~~ | ~~高~~ | **✅ 已修复：askNode() 自动封装 cancelAwareAsk，所有ask节点自动获得cancel检测能力** |
| ~~cancelExecutionNode _question残留~~ | ~~cancel后_question未清除，GES误判为INTERRUPTED~~ | ~~中~~ | **✅ 已修复：cancelExecutionNode 中 result.put("_question", null)** |
| ~~belongs_to_domain误判~~ | ~~"我刚才转账给谁了"被误路由到TRANSFER~~ | ~~高~~ | **✅ 已修复：按intentType三维判断 + l1-intention.st规则增强 + IntegrationFlowTest #32回归** |
| 同节点二次中断 UX | 用户输入无效数据后看到相同提问，可能困惑 | 低 | 后续改进：paramRouter 可改写 _question |
| 跨域切回时 L1 activeAgent 状态 | 切走时 L1 的 activeAgent 是否保留？保留多久？ | 低 | L1 自管状态，切走不清理；过期清理机制已有 |
| "继续转账" 不是有效回答 | 用户输入无法解析为金额时的处理 | 低 | 框架会再次中断，功能正确；UX 可后续优化 |
| getState(config) 读到旧 checkpoint | 必须用原始 config（只有 threadId），不能用 updatedConfig | 低 | 已在验证测试中确认 |
| 转账完成后切回 | 转账已完成（activeAgent已清空），用户说"继续转账"→ 新执行 | 低 | 正确行为：新执行从 extractParams 开始 |
| Wealth 子 Graph 暂不改 | 本次只改 Transfer + BillQuery | 无 | Wealth 的 L1 和 GES 旧接口需要兼容期 |
| 无限重试风险 | 用户持续输入无效数据，graph 持续中断 | 低 | 可在 L1 层增加重试次数限制（如 3 次后主动取消）|
