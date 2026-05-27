# 状态架构重设计：SessionStateStore + 共享 OverAllState

## 一、现状问题

### 1.1 当前架构状态分布

```
用户请求 → BankController(L0) → DomainService(L1) → SubGraph(L2)
                │                      │                     │
                │                      │                     ├─ OverAllState (MemorySaver, 按threadId)
                │                      │                     │   {messages, _latestUserInput, transfer.receiver, ...}
                │                      │                     │
                │                      ├─ activeThread (ConcurrentHashMap, 按sessionId)  
                │                      │   {threadId, intent, accumulatedParams, lastQuestion}
                │                      │
                │                      ├─ suspendedAgents (ConcurrentHashMap, 按sessionId)
                │                      │   {intent → {threadId, accumulatedParams}}
                │                      │
                ├─ lastActiveDomain (ConcurrentHashMap, 按sessionId)
                │
                └─ ChatMemory (按sessionId)
```

**问题1：三份冗余状态**
- L1 的 `activeThread.accumulatedParams` = L2 的 `OverAllState` 的子集
- L1 的 `activeThread.lastQuestion` = L2 的 `OverAllState._question` 的副本
- 每次中断/恢复都要在两份状态之间同步

**问题2：threadId 与 sessionId 脱钩**
- threadId = `UUID.randomUUID()` → 每次执行/恢复都生成新的
- 同一个 session 的同一次转账，2轮对话产生2个不同threadId
- 旧 threadId 在 MemorySaver 中永远不被清理 → **内存泄漏**

**问题3：MemorySaver 无清理机制**
- `_checkpointsByThread: HashMap<String, LinkedList<Checkpoint>>`
- 只 put，永远不 remove
- 每个 threadId 对应一个 LinkedList，随节点执行不断追加

**问题4：跨域切换丢失参数**
- 用户"转账"→"查账单"→"继续转账"：转账的参数在切走时存到 suspendedAgents
- 每个领域各自存自己的参数，没有 session 级别的共享状态
- 无法实现"一次会话共用一个 OverAllState"

### 1.2 泄漏量估算（转账场景）

```
第1轮: "我要转账"  → threadId=aaa111 → MemorySaver["aaa111"] = [Ck1, Ck2, Ck3]
第2轮: "我妈"      → threadId=bbb222 → MemorySaver["bbb222"] = [Ck1, Ck2, Ck3]
第3轮: "500"      → threadId=ccc333 → MemorySaver["ccc333"] = [Ck1, Ck2, Ck3, Ck4, Ck5]

同一个session的一次转账 = 3个废弃threadId + ~11个Checkpoint → 永不释放
```

---

## 二、核心设计原则

### 2.1 参考方案分析

参考方案提出"一个 sessionId → 一个 threadId → 一个 OverAllState"，把 L0/L1/L2 全部放入一个根 Graph。

**我们的情况不同：**
- L0/L1 是 Java 代码（灵活、可配置、LLM 路由），不是 Graph 节点
- L1 不知道"我妈"应该放到哪个字段（这是 L2 的事）
- 不同领域的 L2 子 Graph 有完全不同的节点结构和 state schema

**不能照搬参考方案的原因：**
- 把 L0/L1 变成 Graph 节点 = 丧失灵活性，每次改路由逻辑要重写 Graph
- 一个根 Graph 包含所有领域 = 领域耦合，新增领域要改根 Graph
- L1 不知道参数名 = 无法在 resume 前手动注入 state 字段

### 2.2 我们的设计约束

| 约束 | 原因 |
|------|------|
| L0/L1 保持 Java 代码 | 灵活、可配置、LLM 路由 |
| L2 子 Graph 独立 | 不同领域不同 workflow，独立演进 |
| L1 不知道 L2 的参数名 | L2 自己提参，L1 只传 userInput |
| 不用框架 resume 机制 | interruptBefore resume 后跳过中断节点，多轮提问失败 |
| 一个 session 共享一个 OverAllState | 用户可以在子 Graph 间跳转，参数不丢失 |
| L1 resume 只传 userInput | 不注入 accumulatedParams，子 Graph 从共享状态中自己操作 |

### 2.3 关键技术分析：为什么不注入状态不行

**场景：转账3轮对话**

```
Round 1: "我要转账给我妈"
  → extractParams → {receiver=null, amount=null} → ASK_RECEIVER → 中断

Round 2: "我妈"
  → 重新执行 + 不注入任何状态
  → extractParams 从"我妈"提取 → {receiver="我妈", amount=null} → ASK_AMOUNT → 中断
  ✓ 这一轮不需要注入，LLM 直接从 userInput 提取

Round 3: "500"
  → 重新执行 + 不注入任何状态
  → extractParams 从"500"提取 → {receiver=null, amount=500} 
  → paramRouter → receiver=null → ASK_RECEIVER ← 又问一遍！❌
  ✗ LLM 从"500"无法提取 receiver，之前收集的参数丢失
```

**结论：重新执行从 scratch，必须注入之前已收集的参数，否则多轮提问失败。**

但"谁注入"可以变：

| 方案 | 谁注入 | L1 是否知道参数名 |
|------|--------|------------------|
| 当前 | L1 持有 accumulatedParams → 传给 GES | 是（L1 存了 transfer.receiver 等） |
| **新方案** | GES 从 SessionStateStore 加载 → 自己注入 | **否**（L1 只传 userInput） |

---

## 三、重设计方案

### 3.1 总体架构

```
sessionId = "user-123"

┌──────────────────────────────────────────────────────────┐
│ SessionStateStore (新增，session 级共享状态)                │
│                                                           │
│ ["user-123"] = {                                          │
│   transfer.receiver = "我妈",                              │
│   transfer.amount = 500,                                  │
│   bill.timePeriod = "上个月",       ← 不同域的参数共存       │
│   bill.expenseType = "支出",                               │
│   wealthConsult.riskLevel = "稳健型"                       │
│ }                                                          │
│                                                           │
│ ★ 一个 sessionId → 一个共享状态，所有子 Graph 的参数都在这里  │
│ ★ 每个子 Graph 只操作自己的区域（transfer.* / bill.* 等）    │
│ ★ 跨域切换时，其他域的参数自动保留                           │
└───────────┬──────────────────────────────────────────────┘
            │ GES 加载/保存
┌───────────▼──────────────────────────────────────────────┐
│ GraphExecutionEngine (GES)                                │
│                                                           │
│ executeGraph(graph, intent, userInput, sessionId):        │
│   1. 从 SessionStateStore[sessionId] 加载全部参数           │
│   2. 构建 input = {messages, _latestUserInput} + 全部参数  │
│   3. 生成 threadId (随机, ephemeral)                       │
│   4. graph.stream(input, config) → 重新执行               │
│   5. 读结果 → 提取业务参数 → 合并回 SessionStateStore       │
│   6. 清理 MemorySaver checkpoint                          │
│   7. 返回 WorkflowOutput (question / content)             │
│                                                           │
│ ★ L1 不需要传 accumulatedParams                           │
│ ★ GES 自动从共享状态中注入当前 Graph 区域的参数              │
│ ★ 子 Graph 不感知 SessionStateStore，只操作 OverAllState    │
└───────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ L0: BankController + DomainRouter                       │
│   状态: lastActiveDomain["user-123"] = "TRANSFER"       │
│   路由: userInput → domain                               │
└──────────────────────┬──────────────────────────────────┘
                       │ domain="TRANSFER"
┌──────────────────────▼──────────────────────────────────┐
│ L1: SingleSubAgentDomainService(转账)                    │
│   状态: activeThread["user-123"] = {                      │
│     intent: "TRANSFER",                                   │
│     lastQuestion: "请问您要转多少金额"                      │
│   }                                                       │
│   ★ 不再有 accumulatedParams                              │
│   ★ 不再有 threadId                                       │
│   路由: ContextRouter → IntentRouter                      │
└──────────────────────┬──────────────────────────────────┘
                       │ intent + userInput (只传这两个)
┌──────────────────────▼──────────────────────────────────┐
│ L2: TransferGraph (CompiledGraph)                        │
│   OverAllState (执行时临时):                               │
│     {messages, _latestUserInput, _question,               │
│      transfer.receiver, transfer.amount, transfer.purpose} │
│   MemorySaver: 临时使用，读完即清理                         │
│                                                           │
│   ★ 子 Graph 不变，只操作自己的 transfer.* 区域             │
│   ★ 不感知其他域的参数（bill.* 等在 OverAllState 中被忽略） │
└─────────────────────────────────────────────────────────┘
```

### 3.2 关键改动：SessionStateStore

**新增组件**，替代 L1 的 accumulatedParams 和 suspendedAgents：

```java
@Component
public class SessionStateStore {
    // sessionId → 全部业务参数 (所有域的参数都在这里)
    private final ConcurrentHashMap<String, Map<String, Object>> sessionStates = new ConcurrentHashMap<>();

    /** 加载 session 的全部参数 */
    public Map<String, Object> load(String sessionId);

    /** 合并更新: 将 graphOutput 中的参数合并到 session 状态 (不覆盖其他域) */
    public void merge(String sessionId, Map<String, Object> updates);

    /** 清除 session 的全部状态 (session 结束时) */
    public void clear(String sessionId);

    /** 定时清理过期 session */
    public void cleanupExpired();
}
```

**与当前 L1 状态的对比：**

| 状态 | 当前 | 改后 |
|------|------|------|
| accumulatedParams | L1 activeThread.accumulatedParams | SessionStateStore (GES 管理) |
| suspendedAgents | L1 suspendedAgents (含 accumulatedParams) | **删除** (SessionStateStore 自动保留) |
| lastQuestion | L1 activeThread.lastQuestion | L1 activeThread.lastQuestion (不变) |
| threadId | L1 activeThread.threadId | **删除** (GES 内部生成) |

### 3.3 关键改动：GES 接管状态注入/保存

**现状：**
```java
// L1 调用
GES.executeGraph(graph, intent, threadId, userInput, sessionId, accumulatedParams)
GES.resumeGraph(intent, newThreadId, userInput, sessionId, accumulatedParams)
```

**改为：**
```java
// L1 调用 — 不传 accumulatedParams, 不传 threadId
GES.executeGraph(graph, intent, userInput, sessionId)
```

**GES 内部流程：**
```
executeGraph(graph, intent, userInput, sessionId):
  1. sessionState = sessionStateStore.load(sessionId)
     // e.g. {transfer.receiver="我妈", bill.timePeriod="上个月"}
  
  2. input = {
       "messages": userInput,
       "_latestUserInput": userInput,
       "_question": null
     } + sessionState
     // 框架会忽略不属于当前 graph KeyStrategy 的 key
  
  3. threadId = generateThreadId()  // 随机，ephemeral
  
  4. graph.stream(input, config)  // 从头执行
  
  5. result = checkGraphResult(graph, config, intent, threadId)
  
  6. if result has business params:
       sessionStateStore.merge(sessionId, result.accumulatedParams)
       // 只更新当前 graph 的参数，其他域参数不变
  
  7. cleanup MemorySaver checkpoint for threadId
  
  8. return result (question / content)
```

### 3.4 关键改动：L1 简化

**ActiveThreadInfo 简化：**

```java
// 现状
ActiveThreadInfo {
    threadId: "aaa111",               // ← 删除
    intent: "TRANSFER",
    accumulatedParams: {transfer.receiver="我妈"},  // ← 删除 (在 SessionStateStore)
    lastQuestion: "请问您要转多少金额"
}

// 改后
ActiveThreadInfo {
    intent: "TRANSFER",
    lastQuestion: "请问您要转多少金额"
}
```

**suspendedAgents 简化（Multi 域）：**

```java
// 现状
SuspendedInfo {
    threadId: "bbb222",               // ← 删除
    intent: "WEALTH_CONSULT",
    accumulatedParams: {...}          // ← 删除 (在 SessionStateStore)
}

// 改后
SuspendedInfo {
    intent: "WEALTH_CONSULT"
    // accumulatedParams 不需要了，SessionStateStore 自动保留所有域的参数
}
```

**resumeActiveThread 简化：**

```java
// 现状
WorkflowOutput resumeActiveThread(sessionId, userInput, active) {
    String newThreadId = generateThreadId();
    setOwnActiveThread(sessionId, newThreadId, active.getIntent());
    return GES.resumeGraph(active.getIntent(), newThreadId, userInput, sessionId, active.getAccumulatedParams());
}

// 改后
WorkflowOutput resumeActiveThread(sessionId, userInput, active) {
    CompiledGraph graph = intentRegistry.getGraph(active.getIntent());
    return GES.executeGraph(graph, active.getIntent(), userInput, sessionId);
}
```

### 3.5 关键改动：MemorySaver 清理

MemorySaver 现在是临时存储，每次 graph 执行后清理：

```
executeGraph() {
    threadId = random UUID          // 临时，不持久化
    graph.stream(input, config)     // 执行
    result = checkGraphResult()     // 读结果 → 提取参数 → 存 SessionStateStore
    saver.remove(threadId)          // 清理 MemorySaver checkpoint
    return result
}
```

不再需要 CleanableMemorySaver（因为每次执行后都清理，不会积累）。
但仍需给 MemorySaver 加 remove 能力，或在 GES 中通过反射/internal 访问清理。

### 3.6 框架兼容性：注入全部参数 vs 只注入当前 graph 的参数

当 GES 从 SessionStateStore 加载全部参数并注入 graph input 时：
- TransferGraph 的 KeyStrategy 只定义了 transfer.* keys
- 注入的 bill.* keys 不在 KeyStrategy 中
- **框架行为：不在 KeyStrategy 中的 key 会被丢弃**

这意味着：
1. 注入全部参数不会出错（未知 key 被忽略）
2. 但其他域的参数不会出现在 graph 的 OverAllState 中
3. 执行后提取参数时，只能拿到当前 graph 的参数
4. **合并回 SessionStateStore 时用 merge 而非 replace**，其他域参数自然保留

所以**注入全部参数**和**只注入当前 graph 的参数**效果一样。为了简洁，注入全部。

---

## 四、流程图：跨域场景验证

### 4.1 场景：转账 → 切到查账单 → 切回转账

```
用户: "我要转账给我妈"
  │
  ▼
L1: 无 activeThread → handleNewIntention()
  │
GES.executeGraph(transferGraph, "TRANSFER", "我要转账给我妈", "user-123")
  │  SessionStateStore["user-123"] = {} (空)
  │  input = {messages="我要转账给我妈", _latestUserInput="我要转账给我妈"}
  │  threadId = random("aaa111")
  │
  │  TransferGraph 执行:
  │    extractParams → {receiver=null, amount=null}
  │    paramRouter → ASK_RECEIVER → interruptBefore("askReceiver") ★
  │
  │  GES 读结果: question="转给谁", accumulatedParams={}(null被过滤)
  │  GES 合并回 SessionStateStore: {} (无更新)
  │  GES 清理 MemorySaver["aaa111"]
  │
L1: saveL2Result() → activeThread={intent=TRANSFER, lastQuestion="转给谁"}
  │
  ▼
用户看到: "请问您要转给谁？"
```

```
用户: "我妈"
  │
  ▼
L1: activeThread存在 + lastQuestion → FOLLOW
  │
GES.executeGraph(transferGraph, "TRANSFER", "我妈", "user-123")
  │  SessionStateStore["user-123"] = {} (仍为空)
  │  input = {messages="我妈", _latestUserInput="我妈"}
  │  threadId = random("bbb222")
  │
  │  TransferGraph 执行 (从头):
  │    extractParams → LLM从"我妈"提取 → {receiver="我妈"}
  │    paramRouter → receiver="我妈", amount=null → ASK_AMOUNT → interruptBefore ★
  │
  │  GES 读结果: question="转多少", accumulatedParams={transfer.receiver="我妈"}
  │  GES 合并回 SessionStateStore: {transfer.receiver="我妈"}  ← 存入了！
  │  GES 清理 MemorySaver["bbb222"]
  │
L1: saveL2Result() → activeThread={intent=TRANSFER, lastQuestion="转多少"}
  │
  ▼
用户看到: "请问您要转多少金额？"
```

```
用户: "算了，查下账单"
  │
  ▼
L0: DomainRouter → SWITCH → BILL (转账领域切走)
L1 (BILL): 无 activeThread → handleNewIntention()
  │
GES.executeGraph(billQueryGraph, "BILL_QUERY", "查下账单", "user-123")
  │  SessionStateStore["user-123"] = {transfer.receiver="我妈"}  ← 转账参数还在！
  │  input = {messages="查下账单", _latestUserInput="查下账单", transfer.receiver="我妈"}
  │  注: bill.* key 不在 BillQueryGraph KeyStrategy → 被框架忽略，不影响
  │  threadId = random("ccc333")
  │
  │  BillQueryGraph 执行:
  │    extractParams → {timePeriod=null, expenseType=null}
  │    paramRouter → ASK_TIME → interruptBefore ★
  │
  │  GES 读结果: question="哪个时间段", accumulatedParams={}(null被过滤)
  │  GES 合并回 SessionStateStore: {transfer.receiver="我妈"} (无新非null参数，不变)
  │  GES 清理 MemorySaver["ccc333"]
  │
L1 (BILL): activeThread={intent=BILL_QUERY, lastQuestion="哪个时间段"}
  │
  ▼
用户看到: "请问您要查询哪个时间段的账单？"
```

```
用户: "上个月"
  │
  ▼
L1 (BILL): FOLLOW
  │
GES.executeGraph(billQueryGraph, "BILL_QUERY", "上个月", "user-123")
  │  SessionStateStore["user-123"] = {transfer.receiver="我妈"}  ← 还在！
  │  input = {messages="上个月", _latestUserInput="上个月", transfer.receiver="我妈"}
  │  threadId = random("ddd444")
  │
  │  BillQueryGraph 执行:
  │    extractParams → {timePeriod="上个月", expenseType=null}
  │    paramRouter → ASK_TYPE → interruptBefore ★
  │
  │  GES 合并回 SessionStateStore: {transfer.receiver="我妈", bill.timePeriod="上个月"}
  │  GES 清理 MemorySaver["ddd444"]
  │
  ▼
用户看到: "请问您要查询支出、收入还是收支？"
```

```
用户: "继续转账"
  │
  ▼
L0: DomainRouter → SWITCH → TRANSFER
L1 (TRANSFER): 无 activeThread → handleNewIntention()
  │  IntentRouter → TRANSFER, belongsToDomain=true
  │
GES.executeGraph(transferGraph, "TRANSFER", "继续转账", "user-123")
  │  SessionStateStore["user-123"] = {transfer.receiver="我妈", bill.timePeriod="上个月"}
  │  input = {messages="继续转账", _latestUserInput="继续转账",
  │           transfer.receiver="我妈", bill.timePeriod="上个月"}
  │  注: bill.* key 不在 TransferGraph KeyStrategy → 被框架忽略
  │  但 transfer.receiver="我妈" 在 KeyStrategy 中 → 注入成功！
  │  threadId = random("eee555")
  │
  │  TransferGraph 执行:
  │    extractParams → LLM从"继续转账"提取 → {receiver=null, amount=null}
  │      但 state 中已有 receiver="我妈" → mergeExtractedWithoutOverwrite 保留！
  │    paramRouter → receiver="我妈", amount=null → ASK_AMOUNT → interruptBefore ★
  │
  │  GES 合并回 SessionStateStore: {transfer.receiver="我妈", bill.timePeriod="上个月"}
  │  GES 清理 MemorySaver["eee555"]
  │
L1 (TRANSFER): activeThread={intent=TRANSFER, lastQuestion="转多少"}
  │
  ▼
用户看到: "请问您要转多少金额？"  ← 接续之前的转账，不问收款人了！✓
```

### 4.2 关键验证：跨域切换不丢参数

```
时间线: ───转账R1───转账R2───切账单R1───账单R2───切回转账───

SessionStateStore 变化:
  转账R1后:  {}                                           (null被过滤)
  转账R2后:  {transfer.receiver="我妈"}                     ← 存入
  账单R1后:  {transfer.receiver="我妈"}                     ← 不变
  账单R2后:  {transfer.receiver="我妈", bill.timePeriod="上个月"}  ← 追加
  切回转账:  {transfer.receiver="我妈", bill.timePeriod="上个月"}  ← 两个域共存
             ↑ receiver 被注入 TransferGraph，paramRouter 看到 → 跳过 ASK_RECEIVER ✓
```

---

## 五、状态生命周期

### 5.1 各层状态对比

```
                         ┌─────────────────────────────────────────────┐
                         │           用户 session: user-123              │
                         └─────────────────────────────────────────────┘

时间 ──────────────────────────────────────────────────────────────────▶

L0 lastActiveDomain:    ──────[TRANSFER]──────[BILL]──────────[TRANSFER]──
                              创建            切换             切回

L1 activeThread:        ──────[TRANSFER]──────[BILL]──────────[TRANSFER]──
                         (intent, lastQ)    (intent, lastQ)  (intent, lastQ)
                         ★ 不再存 accumulatedParams 和 threadId

SessionStateStore:      ──────{}──{receiver}──{receiver}──{receiver,timePeriod}──
                        (GES管理,跨域共享,自动保留)

MemorySaver:            ──────[aaa]──清理──[bbb]──清理──[ccc]──清理──
                        (每次执行临时使用,读完即清理,永不积累)
```

### 5.2 跨域切换状态

```
用户: "我要转账" → 中断(转给谁) → "算了，查下账单" → 中断(哪个时间段) → "上个月"

SessionStateStore 变化:
  1. {}                                ← 转账R1, receiver=null被过滤
  2. {transfer.receiver="我妈"}        ← 转账R2
  3. {transfer.receiver="我妈"}        ← 切到账单,转账参数保留！
  4. {transfer.receiver="我妈",         ← 账单R2
     bill.timePeriod="上个月"}

用户: "继续转账"
  SessionStateStore 仍有 transfer.receiver="我妈"
  → GES 注入 → paramRouter 跳过 ASK_RECEIVER → 直接问 ASK_AMOUNT ✓
```

---

## 六、改动的文件清单

### 6.1 新增文件

| 文件 | 说明 |
|------|------|
| `SessionStateStore.java` | session 级共享状态，key=sessionId, value=Map<String,Object> |
| `CleanableMemorySaver.java` | 给 MemorySaver 加 remove(threadId) 能力，支持执行后清理 |

### 6.2 修改文件

| 文件 | 改动 |
|------|------|
| `GraphExecutionEngine.java` | **核心改动**: 注入 SessionStateStore; 删除 accumulatedParams 参数; 执行后 merge + 清理; 接口简化为 `executeGraph(graph, intent, userInput, sessionId)` |
| `AbstractDomainService.java` | ActiveThreadInfo 删除 threadId、accumulatedParams; saveL2Result 只存 lastQuestion; resumeActiveThread 简化; 删除 generateThreadId() |
| `SingleSubAgentDomainService.java` | 适配新的 GES 接口; 不再传 accumulatedParams |
| `MultiSubAgentDomainService.java` | 适配新接口; SuspendedInfo 删除 threadId、accumulatedParams; handleResume 从 SessionStateStore 获取参数 |
| `AbstractGraphConfig.java` | createSaverConfig() 使用 CleanableMemorySaver |
| `BankController.java` | clearSession 时调 sessionStateStore.clear(sessionId) |

### 6.3 不变的文件

| 文件 | 原因 |
|------|------|
| 所有 L2 Graph Config | 节点/边/KeyStrategy 不变; 子 Graph 不感知 SessionStateStore |
| 所有 .st 模板 | 提示词不变 |
| L0/L1 路由逻辑 | ContextRouter/IntentRouter 不涉及状态存储 |
| application.yml | 配置不变 |

---

## 七、方案对比

### 7.1 与参考方案的对比

| 维度 | 参考方案（单根 Graph） | 本方案（SessionStateStore + 共享状态） |
|------|----------------------|--------------------------------------|
| L0/L1 实现 | Graph 节点 | Java 代码（保持现状） |
| OverAllState | 全局共享一份（一个 Graph） | SessionStateStore 共享 + 子 Graph 各自 OverAllState（临时） |
| threadId | sessionId（全局唯一） | 随机（ephemeral，每次执行后清理） |
| L1 是否知道参数名 | 是（手动注入 payee） | **否**（GES 从 SessionStateStore 自动注入） |
| resume 方式 | 框架 resume | 重新执行（保持现状） |
| MemorySaver 泄漏 | 无（单 threadId） | 无（每次清理，不积累） |
| 新增领域改动 | 改根 Graph | 只加 L1 Bean + L2 Graph（不变） |
| 跨域参数保留 | 天然支持 | SessionStateStore 自动保留 |
| 架构改动量 | 大（重写 L0/L1 为 Graph） | 中（改 GES + L1 接口 + 新增 SessionStateStore） |

### 7.2 选择本方案的理由

1. **一个 session 共享一个状态** — SessionStateStore 实现，不需要把所有域合并为一个 Graph
2. **每个子 Graph 只操作自己的区域** — transfer.* / bill.* 天然隔离，子 Graph 代码不变
3. **L1 不知道参数名** — GES 从 SessionStateStore 自动注入，L1 只传 intent + userInput
4. **重新执行而非框架 resume** — 规避 spring-ai-alibaba 的 shouldInterruptBefore 缺陷
5. **MemorySaver 无泄漏** — ephemeral threadId + 每次执行后清理
6. **跨域切换不丢参数** — SessionStateStore 自动保留所有域的参数

---

## 八、框架官方 Human-in-the-Loop 机制分析

> 来源：https://java2ai.com/docs/frameworks/graph-core/examples/human-in-the-loop/

### 8.1 模式二（interruptBefore）官方流程

框架官方定义的 interruptBefore 中断-恢复三步法：

```
┌─────────────────────────────────────────────────────────────────────┐
│ Step 1: 首次执行 → 中断                                               │
│                                                                      │
│   graph.stream(initialInput, config)                                 │
│       │                                                              │
│       ├── START → step_1 → [interruptBefore("human_feedback")] → 停  │
│       │                                                ↑             │
│       │                                        框架自动保存           │
│       │                                        Checkpoint            │
│       │                                        nextNode=human_feedback│
│                                                                      │
│   getState(config) → {nextNode="human_feedback", state={...}}        │
│   ★ Checkpoint 保留了完整 state + 下一个要执行的节点                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ Step 2: 注入用户输入                                                  │
│                                                                      │
│   graph.updateState(config, Map.of("human_feedback", "back"), null)  │
│       │                                                              │
│       ├── 框架将 user input 写入 Checkpoint 中的 state                │
│       ├── 返回 updatedConfig（包含更新后的 checkpoint 引用）            │
│       │                                                              │
│   ★ 不创建新 threadId，不重新执行 graph，只是更新 checkpoint 的 state  │
│   ★ updateState 的第三个参数 nodeId：interruptBefore 模式传 null      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ Step 3: 恢复执行 → 从中断节点继续                                      │
│                                                                      │
│   graph.stream(null, updatedConfig)                                  │
│       │                                                              │
│       ├── human_feedback 执行 ✓（中断的节点正常执行）                    │
│       ├── 条件路由 → "back" → step_1                                   │
│       ├── step_1 → human_feedback                                    │
│       │   （重新经过 interruptBefore 节点，但不再中断——处于同一 resume 中） │
│       ├── 条件路由 → "next" → step_3                                  │
│       └── step_3 → END                                               │
│                                                                      │
│   ★ input=null：不提供新输入，使用 checkpoint 中已有的 state             │
│   ★ updatedConfig：来自 updateState 的返回值，框架知道这是 resume        │
│   ★ 中断的节点在 resume 时正常执行（不被跳过）                           │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.2 官方 API 调用时序图

```
时间 ──────────────────────────────────────────────────────────────────▶

应用代码:  stream(input,config)  getState()  updateState()  stream(null,config)
               │                    │             │                │
框架内部:       │                    │             │                │
               ▼                    ▼             ▼                ▼
Checkpoint:  ──[Ck1: next=hf]───────┤──[Ck2: +feedback]──────────[Ck3: hf已执行]──
                                  读state       写state           resume执行

MemorySaver: ──["Thread1"]───────────────────────────────────────────────────────
              同一个 threadId，Checkpoint 被 updateState 更新，而非重新创建

Node执行:    START→step_1─☆停止     ☆等待用户     human_feedback→step_1→...→END
                              ↑ 中断点                    ↑ 从这里恢复
```

### 8.3 官方模式 vs 我们的实现：关键差异

| 维度 | 官方模式二 | 我们的实现 |
|------|-----------|-----------|
| **resume 方式** | `stream(null, updatedConfig)` | `stream(newInput, newConfig)` |
| **是否用 updateState** | ✅ 用 `updateState()` 注入用户输入 | ❌ 从未使用 |
| **threadId** | 稳定，resume 复用同一 threadId | 每次生成新 UUID |
| **input 参数** | `null`（使用 checkpoint 中已有的 state） | 完整的 input map（messages + accumulatedParams） |
| **中断节点是否执行** | ✅ 正常执行（官方文档明确证明） | ❌ 假设不执行，所以重新执行整个 graph |
| **状态传递** | Checkpoint 自动保留，无需手动传递 | 手动提取 accumulatedParams → L1 保存 → 下次注入 |
| **MemorySaver 积累** | 同一 threadId，updateState 更新（不积累） | 每次新 threadId，旧 Checkpoint 永不清理（泄漏！）|

### 8.4 我们的理解偏差

**偏差1：假设 resume 后中断节点不执行**

我们之前的分析结论：
> "interruptBefore 在 resume 后不会重新触发，导致多轮提问失败"

官方文档明确证明这是**错误的**。模式二 resume 后，`human_feedback` 节点正常执行：
```
NodeOutput{node=human_feedback, state={messages=[Step 0, Step 1], human_feedback=back}}
```

**可能的原因**：
- 我们从未实际验证 `updateState() + stream(null, config)` 这条路径
- 我们直接走了 `stream(newInput, newConfig)` 的重执行路径
- `shouldInterruptBefore()` 返回 false 的场景可能是我们没有正确使用 `updateState()` 导致的

**偏差2：每次 resume 生成新 threadId**

官方模式强调 **同一 threadId 复用**。resume 的核心就是：
- 同一个 threadId → 找到同一个 Checkpoint → 从中断点继续
- `updateState()` 修改这个 Checkpoint → `stream(null, config)` 从修改后的 Checkpoint 继续

我们的做法（新 threadId + 新 input + 重执行）完全绕过了框架的 checkpoint 机制，等于是自己实现了"状态传递"。

**偏差3：手动管理 accumulatedParams**

因为绕过了 checkpoint，我们不得不：
1. 从 graph state 手动提取 accumulatedParams
2. L1 保存到 activeThread
3. 下次 resume 时手动注入

如果用官方 resume 机制，checkpoint 自动保留完整 state，不需要手动传递。

### 8.5 如果用官方 resume 机制，转账场景会怎样？

```
Round 1: "我要转账给我妈"
  │
  ▼
graph.stream({messages="我要转账给我妈", _latestUserInput="我要转账给我妈"}, config)
  │  config.threadId = "user-123"
  │
  │  TransferGraph 执行:
  │    START → extractParams → {receiver=null, amount=null}
  │           → paramRouter → ASK_RECEIVER → interruptBefore("askReceiver") ★ 中断
  │
  │  MemorySaver["user-123"] = Checkpoint{
  │    state = {messages, _latestUserInput="我要转账给我妈", receiver=null, amount=null}
  │    nextNode = "askReceiver"
  │  }
  │
  ▼
GES 读结果: getState() → INTERRUPTED, question="转给谁"
  ★ 不需要提取 accumulatedParams！state 在 checkpoint 中完好保留
```

```
Round 2: "我妈"
  │
  ▼
graph.updateState(config, Map.of("_latestUserInput", "我妈"), null)
  │  ★ 注入用户输入到 checkpoint 的 state
  │  ★ 不创建新 threadId，不重执行 graph
  │  MemorySaver["user-123"] 的 state 变为:
  │    {messages, _latestUserInput="我妈", receiver=null, amount=null}
  │
  ▼
graph.stream(null, updatedConfig)
  │  ★ input=null，使用 checkpoint 中的 state
  │  ★ 从 askReceiver 继续执行
  │
  │  TransferGraph 从 askReceiver 继续:
  │    askReceiver → 处理 "我妈" → receiver="我妈"
  │    → paramRouter → receiver="我妈", amount=null → ASK_AMOUNT
  │    → interruptBefore("askAmount") ★ 中断
  │
  │  MemorySaver["user-123"] = Checkpoint{
  │    state = {messages, _latestUserInput="", receiver="我妈", amount=null}
  │    nextNode = "askAmount"
  │  }
  │
  ▼
GES 读结果: getState() → INTERRUPTED, question="转多少"
  ★ receiver="我妈" 在 checkpoint 中自动保留！不需要 accumulatedParams！
```

```
Round 3: "500"
  │
  ▼
graph.updateState(config, Map.of("_latestUserInput", "500"), null)
  │
  ▼
graph.stream(null, updatedConfig)
  │  从 askAmount 继续:
  │    askAmount → 处理 "500" → amount=500
  │    → paramRouter → receiver="我妈", amount=500 → ALL_GOOD
  │    → executeTransfer → 转账成功
  │    → END
  │
  ▼
GES 读结果: COMPLETED, content="转账成功"
```

### 8.6 官方 resume 机制的关键特性验证

**特性1：resume 后中断节点是否执行？**
- ✅ 官方文档明确证明：`human_feedback` 节点在 resume 后正常执行

**特性2：resume 后遇到新的 interruptBefore 节点是否中断？**
- 官方文档未直接展示，但逻辑推导：
  - `interruptBefore("askReceiver", "askAmount")` 定义了两个中断点
  - Round 1 中断在 askReceiver → Round 2 resume 从 askReceiver 继续
  - askReceiver 执行后 → paramRouter → 路由到 askAmount
  - askAmount 也在 interruptBefore 列表中，且这是首次遇到 → **应该中断**
- ⚠️ 需验证

**特性3：resume 后图循环回同一 interruptBefore 节点是否再次中断？**
- 官方示例中，resume 后 `human_feedback` 循环回到自身，**没有再次中断**
- 原因：框架在 resume session 中标记已恢复的节点，不会重复中断
- ⚠️ 对我们的影响：如果 paramRouter 路由回同一个 ask 节点，不会再次中断
- 但我们的设计中，paramRouter 不会路由回同一 ask 节点（一次只问一个参数），所以无影响

**特性4：跨域切换后 resume 是否可行？**
- 每个 GraphConfig 有自己的 MemorySaver 实例
- TransferGraph 的 checkpoint 在 TransferGraph 的 MemorySaver 中
- 切换到 BillQueryGraph 不影响 TransferGraph 的 checkpoint
- 切回 TransferGraph 时，用同一 threadId 的 config → 可以找到原 checkpoint → resume
- ✅ 天然支持跨域保留状态

### 8.7 官方 resume 对我们架构的影响

如果采用官方 `updateState() + stream(null, config)` 机制：

| 维度 | 当前方案（重执行 + SessionStateStore） | 官方 resume 方案 |
|------|--------------------------------------|-----------------|
| **accumulatedParams** | 需要（SessionStateStore 保存 + GES 注入） | **不需要**（checkpoint 自动保留） |
| **SessionStateStore** | 需要（跨域共享状态） | **可能不需要**（每个 graph 的 checkpoint 各自保留） |
| **threadId 策略** | 随机（ephemeral） | **稳定**（sessionId，复用同一 checkpoint） |
| **MemorySaver 泄漏** | 需手动清理 | **无**（同一 threadId 覆盖更新） |
| **执行效率** | 低（每次从头执行所有节点） | **高**（只执行从中断点开始的节点） |
| **L1 复杂度** | 中（需管理 SessionStateStore） | **低**（只传 userInput，GES 调 updateState） |
| **跨域切换** | SessionStateStore 保留参数 | 各 graph 的 checkpoint 独立保留参数 |
| **框架兼容性风险** | 低（绕过 resume，用 stream 新执行） | ⚠️ 中（依赖 resume 机制，需验证多中断点） |

### 8.8 待验证的关键问题

| # | 问题 | 验证方式 | 影响 |
|---|------|---------|------|
| 1 | resume 后遇到新的 interruptBefore 节点，是否正常中断？ | 构建双中断点 graph，Round1 中断 A → resume → 是否在 B 前中断？ | **决定性**：如果不能，官方 resume 无法用于多轮提问 |
| 2 | updateState 注入 _latestUserInput 后，ask 节点能否读到？ | 在 askReceiver 中读 _latestUserInput，验证值是否正确 | 关键：否则无法处理用户输入 |
| 3 | 跨域切换后，用原 threadId resume TransferGraph，checkpoint 是否完好？ | 转账中断 → 执行账单 → 用原 config resume 转账 | 决定是否需要 SessionStateStore |
| 4 | 同一 resume session 中，paramRouter 路由到另一 ask 节点，interruptBefore 是否触发？ | Round1 中断 askReceiver → resume → paramRouter 路由到 askAmount → 是否中断？ | 决定多轮提问是否可行 |

---

## 九、风险与待验证项

| 风险 | 说明 | 验证方式 |
|------|------|---------|
| 注入全部参数时框架行为 | 不在 KeyStrategy 中的 key 是否被丢弃？丢弃是否影响 graph 执行？ | 需验证：注入 bill.* 到 TransferGraph，是否正常运行 |
| MemorySaver.remove() | 框架的 MemorySaver 没有 remove 方法，需要扩展 | 需实现 CleanableMemorySaver 或通过 SaverConfig 访问内部 |
| sessionStateStore.merge 一致性 | 两个请求同时修改同一 session 的状态 | 当前架构已有此风险；可加 synchronized per sessionId |
| extractAccumulatedParams null 过滤 | null 值不存入 SessionStateStore，下次注入时缺少该 key | 当前行为正确：null 表示未收集，不应注入 |
| mergeExtractedWithoutOverwrite | 子 Graph 已有此逻辑，state 中已有值时不被 LLM null 覆盖 | 不变，继续工作 |
