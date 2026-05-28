# 设计文档：Global OverAllState 架构

> 分支：`feat/global_overallstate`
> 目标：在保持 L0/L1 独立图架构的前提下，实现跨域状态共享

---

## 0. 实施状态（最新）

### 0.1 已完成

| 组件 | 状态 | 说明 |
|------|------|------|
| `GlobalSessionStore.java` | ✅ 已实现 | @Component, 管理 sessionId→GlobalSessionContext, 统一KeyStrategyFactory + 独立MemorySaver |
| `GlobalSessionContext.java` | ✅ 已实现 | 包装 OverAllState + MemorySaver, `mergeState()` 调用 `state.updateState()` |
| `AbstractGraphConfig.onGraphCompleted()` | ✅ 已实现 | 默认空实现, 子类可覆盖写入域数据到全局OverAllState |
| `TransferGraphConfig.onGraphCompleted()` | ✅ 已实现 | 提取 transfer.* 前缀的域数据写入全局OverAllState |
| `AbstractDomainService.syncGraphStateToGlobal()` | ✅ 已实现 | 读取L2 state → 调用graphConfig.onGraphCompleted(globalState, localStateData) |
| `AbstractDomainService.executeNewAgent()` | ✅ 已增强 | 执行前注入跨域上下文到input |
| `AbstractDomainService.getDomainPrefix()` | ✅ 已实现 | TRANSFER→transfer., BILL_QUERY→bill. 等 |
| `GlobalSessionStore.getCrossDomainData/Summary()` | ✅ 已实现 | 排除当前域和公共key, 返回跨域数据 |
| `IntentRegistry.bindGraphConfig()` | ✅ 已实现 | 将AbstractGraphConfig绑定到IntentConfig |
| `AppInitConfig` | ✅ 已修改 | 注入所有GraphConfig bean并调用bindGraphConfig |
| `SameThreadIdStateRetentionTest` | ✅ 通过 | 验证Checkpoint跨执行保留行为 |
| 编译 | ✅ 通过 | `mvn compile -q` 零错误 |

### 0.2 关键架构发现

#### 发现1：Checkpoint 合并行为（实验验证）

```
同一 CompiledGraph + 同一 MemorySaver + 同一 threadId:
- 新 stream() 调用时，getInitialState 从 Checkpoint 合并历史数据 ✅
- ReplaceStrategy: 新input有值则覆盖，无值则保留Checkpoint旧值 ✅
- AppendStrategy (messages): 历史消息 + 新消息追加 ✅

不同 CompiledGraph 实例:
- 每个实例有独立的 MemorySaver → Checkpoint 不互通
- @BeforeEach 创建新实例 → 之前的 Checkpoint 丢失
- 必须用 @BeforeAll 共享实例才能测试跨执行保留
```

**含义**：同域内"记忆"由框架自带，无需 GlobalSessionStore。GlobalSessionStore 只需处理**跨域**数据共享。

#### 发现2：回调架构变更

```
原设计: globalSessionStore.onGraphStateUpdate() 在 DomainService 中被调用
实际实现: AbstractGraphConfig.onGraphCompleted(globalState, localStateData) 
         由子Graph自己override，直接写入 globalState

原因: "只有子Graph知道它自己的数据" — 用户明确要求回调在子Graph中
```

#### 发现3：跨域注入方式

```
原设计: 通过 GraphExecutionEngine 的 crossDomainCtx 参数注入到 L2 input
实际实现: 在 AbstractDomainService.executeNewAgent() 中将跨域摘要附加到 rewrittenInput

原因: L2 的 KeyStrategyFactory 不注册跨域key（各图独立工厂），
      注入到input的跨域key会被框架忽略。
      改为以自然语言形式附加到input，让LLM理解跨域上下文。
```

### 0.3 待实现

| 项目 | 优先级 | 说明 |
|------|--------|------|
| 其他3个GraphConfig的onGraphCompleted | 中 | BillQuery, WealthConsult, WealthInterpret 各自实现域数据写入 |
| 跨域注入的实际效果验证 | 中 | 端到端测试: 转账后推荐理财能否感知转账金额 |
| 跨域数据摘要的自然语言格式优化 | 低 | 当前是 key=value 格式，可改为更自然的描述 |
| GlobalSessionStore 持久化 | 低 | 当前内存实现，重启丢失；未来可换Redis |

---

## 1. 问题定义

### 1.1 当前痛点

当前 L2 各子图（Transfer/Bill/WealthConsult/WealthInterpret）各自持有独立的
OverAllState + MemorySaver，状态完全隔离：

```
TransferGraph  → MemorySaver@A + OverAllState@A {transfer.receiver, transfer.amount, ...}
BillQueryGraph → MemorySaver@B + OverAllState@B {bill.timePeriod, bill.expenseType, ...}
WealthConsult  → MemorySaver@C + OverAllState@C {wealth.riskLevel, ...}
WealthInterpret→ MemorySaver@D + OverAllState@D {wealth.productName, ...}
```

**核心痛点**：跨域上下文断裂。

| 场景 | 期望 | 现状 |
|------|------|------|
| "刚转了500，推荐理财" | 理财推荐能感知转账金额 | 转账金额在 OverAllState@A，理财看不到 |
| "查完账单再转一笔" | 转账能感知账单中的支出模式 | 账单数据在 OverAllState@B，转账看不到 |
| "先看看账单，等下继续转账" | 转账恢复时保留已收集的参数 | ✅ 已支持（独立 MemorySaver） |

### 1.2 目标

1. **跨域状态可见**：L2 子图能读取其他域已产生的关键状态
2. **保持 L0/L1 不变**：L1 路由（FOLLOW/SWITCH/RESUME）仍在图外部
3. **保持 MemorySaver 独立**：避免 Checkpoint 冲突
4. **保持域内状态隔离**：transfer.receiver 不应意外覆盖 bill.timePeriod
5. **最小改动**：尽量复用现有代码，不重写架构

### 1.3 硬性约束：域前缀命名规范

**所有 L2 子图写入 OverAllState 的业务 key 必须以意图前缀开头。**

```
意图前缀 = intentName 转小写 + "."

TRANSFER          → transfer.    → transfer.receiver, transfer.amount, transfer.purpose
BILL_QUERY        → bill.        → bill.timePeriod, bill.expenseType
WEALTH_CONSULT    → wealth.      → wealth.riskLevel, wealth.focusArea
WEALTH_INTERPRET  → wealth.      → wealth.productName, wealth.interpretResult
```

当前代码**已经遵循**此规范。这是 GlobalSessionStore 能工作的前提：

1. **跨域注入时排除本域 key**：通过 `key.startsWith(intentPrefix)` 过滤
2. **无 key 命名冲突**：不同域的 key 天然隔离
3. **新增域零影响**：新域使用新前缀，不影响已有域
4. **框架内部 key 不受影响**：`_latestUserInput`、`_question` 等以下划线开头，与前缀规范不冲突

**禁止出现**：
- 无前缀的业务 key（如 `amount`、`receiver`）→ 无法判断归属域，跨域注入时会误覆盖
- 跨域前缀的 key（如 BillGraph 写入 `transfer.xxx`）→ 违反域边界

---

## 2. 框架约束：OverAllState 为什么不能直接共享？

### 2.1 OverAllState 的生命周期（源码级分析）

```
CompiledGraph.stream(input, config)
  ① stateCreate(input)               → 用 KeyStrategyFactory + input Map 创建新 OverAllState
  ② 节点执行                         → 每个节点 mutate OverAllState
  ③ Checkpoint 保存                  → OverAllState.data() → Checkpoint.state (Map<String,Object>)
  ④ stream 返回

CompiledGraph.stream(null, updatedConfig)  // resume
  ① 从 Checkpoint 恢复 state          → Checkpoint.state (Map) + KeyStrategyFactory → 新 OverAllState
  ② 从 nextNodeId 继续执行
  ③ ...
```

**关键事实**：OverAllState 每次执行都是**新对象**。

- `StateGraph` 构造器只接受 `KeyStrategyFactory`，不接受 OverAllState
- `stream(input, config)` 只接受 `Map<String, Object>`，不接受 OverAllState
- 即使从 Checkpoint 恢复，也是从 `Map` 重新构造 OverAllState

**结论：OverAllState 对象无法共享。只能共享 OverAllState 的数据内容。**

### 2.2 共享数据的三层障碍

```
障碍1: 不同 KeyStrategyFactory
  TransferGraph 的 KeyStrategyFactory 不认识 bill.timePeriod
  → 注入 input{bill.timePeriod: "Q1"} 会被框架静默忽略

障碍2: 不同 MemorySaver 实例
  即使统一 threadId，各 MemorySaver 的 HashMap 物理隔离
  → Checkpoint 不互通

障碍3: Checkpoint 中的 nodeId/nextNodeId 是图结构相关的
  同一个 MemorySaver 中，TransferGraph 的 askAmount 和 BillGraph 的 askTime 会冲突
  → 如果共享 MemorySaver，resume 时 nextNodeId 无法解析
```

### 2.3 解决思路

```
障碍1 → 统一 KeyStrategyFactory（让所有图认识所有 key）
障碍2 → 保持独立 MemorySaver（避免 Checkpoint 冲突，通过 GlobalSessionStore 桥接数据）
障碍3 → 保持独立 MemorySaver（每个图有自己的 checkpoint 空间）
```

---

## 3. 架构设计

### 3.1 总体架构

```
┌─ GlobalSessionStore ─────────────────────────────────────────────────────┐
│  sessionId → SessionContext                                               │
│  ┌─ SessionContext ─────────────────────────────────────────────────────┐ │
│  │  globalState: Map<String, Object>     // 跨域共享的状态数据          │ │
│  │  activeIntent: String                 // 当前活跃意图                │ │
│  │  intentStates: Map<String, Map>       // 各意图最后的 state 快照     │ │
│  │  suspendedContexts: Map<String, SuspendedContext>  // 挂起意图上下文  │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘

         ↗ 注入跨域数据                ↗ 注入跨域数据
        ┌──────────┐                 ┌──────────┐
        │Transfer  │                 │  Bill    │
        │Graph     │                 │  Graph   │
        │          │                 │          │
        │MemorySaver@A               │MemorySaver@B
        │threadId=user-123           │threadId=user-123
        │                           │
        │OverAllState{               │OverAllState{
        │  transfer.receiver=张三,   │  bill.timePeriod=Q1,
        │  transfer.amount=500,      │  bill.expenseType=餐饮,
        │  bill.timePeriod=Q1, ←注入 │  transfer.amount=500, ←注入
        │  _crossDomainSummary=..,   │  _crossDomainSummary=..,
        │  ...                       │  ...
        │}                           │}
        └──────────┘                 └──────────┘
             │                           │
             └────── 回写 state ─────────┘
                    到 GlobalSessionStore
```

### 3.2 核心组件

#### 3.2.1 GlobalSessionStore

```java
@Component
public class GlobalSessionStore {

    // sessionId → SessionContext
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    /** 获取或创建 SessionContext */
    public SessionContext getOrCreate(String sessionId);

    /** 获取指定 session 的跨域上下文数据（注入到 L2 的 input/updateState） */
    public Map<String, Object> getCrossDomainContext(String sessionId, String currentIntent);

    /** 从 L2 执行结果同步状态到 GlobalSessionStore */
    public void syncFromGraph(String sessionId, String intent, Map<String, Object> stateData);

    /** 标记意图切换（挂起当前，激活新意图） */
    public void switchIntent(String sessionId, String fromIntent, String toIntent);

    /** 清理 session */
    public void clearSession(String sessionId);
}

@Data
public class SessionContext {
    private String sessionId;
    private String activeIntent;                              // 当前活跃意图
    private Map<String, Object> globalState = new HashMap<>(); // 跨域共享状态
    private Map<String, Map<String, Object>> intentStates = new HashMap<>(); // 各意图 state 快照
    private Instant lastAccessedAt;
}
```

#### 3.2.2 UnifiedKeyStrategyFactory

当前各 L2 图的 KeyStrategyFactory 只注册自己的域内 key。需要改为**统一工厂**，
让所有图认识所有域的 key。

```java
// 当前：各图独立
TransferGraph  → createKeyStrategyFactory() = {messages, _latestUserInput, transfer.*}
BillGraph      → createKeyStrategyFactory() = {messages, _latestUserInput, bill.*}

// 改造：统一工厂
public class UnifiedKeyStrategyFactory implements KeyStrategyFactory {
    @Override
    public Map<String, KeyStrategy> get() {
        Map<String, KeyStrategy> strategies = new HashMap<>();

        // 公共 keys（原 AbstractGraphConfig.createKeyStrategyFactory 的公共部分）
        strategies.put("messages", new AppendStrategy());
        strategies.put("_latestUserInput", new ReplaceStrategy());
        strategies.put("_question", new ReplaceStrategy());
        strategies.put("_paramName", new ReplaceStrategy());
        strategies.put("_outputContent", new ReplaceStrategy());
        strategies.put("_outputType", new ReplaceStrategy());
        strategies.put("_isFinal", new ReplaceStrategy());
        strategies.put("_cancelSignal", new ReplaceStrategy());
        strategies.put("_crossDomainSummary", new ReplaceStrategy());  // 新增：跨域摘要

        // Transfer 域
        strategies.put("transfer.receiver", new ReplaceStrategy());
        strategies.put("transfer.amount", new ReplaceStrategy());
        strategies.put("transfer.purpose", new ReplaceStrategy());

        // Bill 域
        strategies.put("bill.timePeriod", new ReplaceStrategy());
        strategies.put("bill.expenseType", new ReplaceStrategy());

        // Wealth 域
        strategies.put("wealth.riskLevel", new ReplaceStrategy());
        strategies.put("wealth.focusArea", new ReplaceStrategy());
        strategies.put("wealth.productName", new ReplaceStrategy());
        strategies.put("wealth.interpretResult", new ReplaceStrategy());

        return strategies;
    }
}
```

**注意**：统一 KeyStrategyFactory 不会导致数据污染。原因是：
- TransferGraph 执行时，`bill.timePeriod` 在 OverAllState 中为 null（未注入则默认 null）
- 节点代码只读取自己认识的 key（如 `state.value("transfer.receiver")`）
- 其他域的 key 虽然"可见"但不会被误读，因为节点代码不引用它们

### 3.3 MemorySaver 策略：各图独立

**决策：每个 L2 子图保持独立的 MemorySaver 实例。**

原因分析：

| 策略 | Checkpoint 冲突 | 实现复杂度 | 跨域数据共享 |
|------|----------------|-----------|-------------|
| 共享 MemorySaver + 同一 threadId | ❌ 冲突：nextNodeId 跨图无意义 | 高（需 hack 框架） | ❌ 不解决 |
| 共享 MemorySaver + namespace threadId | ✅ 不冲突 | 中（threadId 编码） | ❌ 数据仍隔离 |
| **独立 MemorySaver + GlobalSessionStore** | ✅ 不冲突 | **低（纯业务层）** | ✅ 通过注入实现 |

**为什么共享 MemorySaver 不可行**（已在 DESIGN-ARCHITECTURE-ANALYSIS.md 3.5 节详述）：

```java
// 共享 MemorySaver + 同一 threadId 的问题：
SharedMemorySaver["user-123"] = LinkedList<Checkpoint>

时刻1: TransferGraph 中断 → Checkpoint{nextNodeId=askAmount, state={transfer.receiver=我妈}}
时刻2: BillGraph 中断   → Checkpoint{nextNodeId=askTime, state={bill.timePeriod=Q1}}

// cp2 覆盖 cp1 成为最新！resume TransferGraph 时读到 askTime → 节点不存在 → 崩溃
```

**即使用 namespace threadId**（`"user-123:TRANSFER"`）也不解决数据共享问题——
各图的 Checkpoint 空间虽然不冲突了，但 OverAllState 仍然是独立的。

**结论**：MemorySaver 保持各图独立，跨域数据通过 GlobalSessionStore 注入。

---

## 4. 数据流转机制

### 4.1 核心流程

```
┌─────────────────────────────────────────────────────────────────────┐
│ Controller (L0)                                                      │
│   chat(sessionId, userInput)                                         │
│   → GlobalSessionStore.getOrCreate(sessionId)                       │
│   → DomainRouter.route()                                             │
│   → DomainHandler.handle()                                           │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ DomainService (L1)                                                   │
│   handle(sessionId, userInput, globalChatHistory)                    │
│   → ContextRouter / IntentRouter → FOLLOW / SWITCH / RESUME         │
│                                                                      │
│   FOLLOW:                                                            │
│     GES.resumeGraph(graph, intent, userInput, sessionId,             │
│                     globalSessionStore.getCrossDomainContext(...))    │
│                                                                      │
│   SWITCH:                                                            │
│     globalSessionStore.syncFromGraph(sessionId, oldIntent, stateData)│
│     globalSessionStore.switchIntent(sessionId, oldIntent, newIntent) │
│     GES.executeGraph(graph, intent, rewrittenInput, sessionId,       │
│                      globalSessionStore.getCrossDomainContext(...))   │
│                                                                      │
│   RESUME:                                                            │
│     GES.resumeGraph(graph, intent, userInput, sessionId,             │
│                     globalSessionStore.getCrossDomainContext(...))    │
│                                                                      │
│   执行后:                                                            │
│     globalSessionStore.syncFromGraph(sessionId, intent, stateData)   │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ GraphExecutionEngine (GES)                                           │
│                                                                      │
│   executeGraph(graph, intent, userInput, sessionId, crossDomainCtx): │
│     1. config = threadConfig(sessionId)                              │
│     2. input = {messages: userInput, _latestUserInput: userInput}    │
│     3. input.putAll(crossDomainCtx)   ← ★ 注入跨域数据              │
│     4. graph.stream(input, config)                                   │
│     5. return checkGraphResult(graph, config, intent)                │
│                                                                      │
│   resumeGraph(graph, intent, userInput, sessionId, crossDomainCtx):  │
│     1. config = threadConfig(sessionId)                              │
│     2. updateData = {_latestUserInput: userInput}                    │
│     3. updateData.putAll(crossDomainCtx) ← ★ 注入跨域数据           │
│     4. updatedConfig = graph.updateState(config, updateData, null)   │
│     5. graph.stream(null, updatedConfig)                             │
│     6. return checkGraphResult(graph, config, intent)                │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 跨域数据注入策略

GlobalSessionStore.getCrossDomainContext() 返回什么数据？

**策略选择**：

#### 策略 A：全量注入（推荐）

将所有域的当前状态全量注入到目标图的 input/updateState。

```java
public Map<String, Object> getCrossDomainContext(String sessionId, String currentIntent) {
    SessionContext ctx = sessions.get(sessionId);
    if (ctx == null) return Map.of();

    Map<String, Object> context = new HashMap<>();
    // 复制 globalState（包含所有域的 state 数据）
    context.putAll(ctx.getGlobalState());

    // 排除当前意图自己上次的数据（避免覆盖 checkpoint 恢复的状态）
    // 由 L2 节点代码负责"只读自己需要的 key"
    return context;
}
```

**优点**：简单，所有数据一步到位
**风险**：数据量大时可能有性能开销（当前 4 个域约 10 个域内 key，量级可忽略）

#### 策略 B：摘要注入

只注入跨域上下文的摘要描述，不注入原始 key-value。

```java
public Map<String, Object> getCrossDomainContext(String sessionId, String currentIntent) {
    SessionContext ctx = sessions.get(sessionId);
    if (ctx == null) return Map.of();

    String summary = buildCrossDomainSummary(ctx);
    return Map.of("_crossDomainSummary", summary);
    // 示例: "用户刚完成转账500元给张三；查询了Q1餐饮消费账单"
}

private String buildCrossDomainSummary(SessionContext ctx) {
    StringBuilder sb = new StringBuilder();
    Map<String, Object> state = ctx.getGlobalState();

    if (state.containsKey("transfer.amount")) {
        sb.append("用户").append(state.get("transfer.amount") != null ? "转账了" + state.get("transfer.amount") + "元" : "");
        if (state.get("transfer.receiver") != null) sb.append("给").append(state.get("transfer.receiver"));
        sb.append("；");
    }
    if (state.containsKey("bill.timePeriod")) {
        sb.append("用户查询了").append(state.get("bill.timePeriod")).append("的账单；");
    }
    // ...
    return sb.toString();
}
```

**优点**：不需要统一 KeyStrategyFactory，各图保持独立
**缺点**：信息是摘要而非原始数据，LLM 需要理解自然语言而非直接读字段

#### 策略 C：混合注入（推荐作为长期方案）

全量注入 + 摘要注入并存：

```java
public Map<String, Object> getCrossDomainContext(String sessionId, String currentIntent) {
    SessionContext ctx = sessions.get(sessionId);
    if (ctx == null) return Map.of();

    Map<String, Object> context = new HashMap<>();
    // 全量注入（给 KeyStrategyFactory 能识别的 key 用）
    context.putAll(ctx.getGlobalState());
    // 摘要注入（给 LLM prompt 用，更自然）
    context.put("_crossDomainSummary", buildCrossDomainSummary(ctx));
    return context;
}
```

### 4.3 State 同步时机

```
时刻1: 用户说"转账给张三500元"
  → L0: DomainRouter → TRANSFER
  → L1: SingleSubAgentDomainService.handle()
  → L1: executeNewAgent → GES.executeGraph(transferGraph, ..., crossDomainCtx)
    → crossDomainCtx = {} (首次, 无跨域数据)
  → TransferGraph 执行: extractParams → paramRouter → ALL_GOOD → executeTransfer → END
  → ★ 执行完成后: globalSessionStore.syncFromGraph(sessionId, "TRANSFER", stateData)
    → stateData = {transfer.receiver=张三, transfer.amount=500, _outputContent=...}

时刻2: 用户说"推荐理财"
  → L0: DomainRouter → WEALTH
  → L1: WealthService.handle() → executeNewAgent → GES.executeGraph(wealthConsultGraph, ...)
  → ★ 执行前: crossDomainCtx = globalSessionStore.getCrossDomainContext(sessionId, "WEALTH_CONSULT")
    → crossDomainCtx = {transfer.receiver=张三, transfer.amount=500, _crossDomainSummary="用户转账了500元给张三"}
  → WealthConsultGraph 执行: extractParams 读取 _crossDomainSummary 或 transfer.amount
    → "您刚转了500元，推荐您看下稳健型理财产品..."
  → WealthConsultGraph 中断 at askRiskLevel → 返回 INTERRUPTED
  → ★ 中断后: globalSessionStore.syncFromGraph(sessionId, "WEALTH_CONSULT", stateData)
    → stateData = {wealth.focusArea=null, _question="请问您的风险偏好是?"}

时刻3: 用户说"查账单"
  → L0: DomainRouter → BILL
  → L1: BillService.handle() → ContextRouter判SWITCH → executeNewAgent
  → ★ 执行前: crossDomainCtx = {transfer.receiver=张三, transfer.amount=500,
  →     wealth.focusArea=null, _crossDomainSummary="用户转账了500元给张三;正在理财咨询"}
  → BillQueryGraph 执行: extractParams → askTime → 中断

时刻4: 用户说"继续理财"
  → L0: DomainRouter → WEALTH
  → L1: WealthService.handle() → ContextRouter判RESUME
  → ★ resumeGraph 前: crossDomainCtx = globalSessionStore.getCrossDomainContext(...)
    → 可能包含账单查询的新数据
  → ★ updateState 时注入 crossDomainCtx
  → WealthConsultGraph 从 askRiskLevel 恢复
```

---

## 5. Human-in-the-loop 流程

### 5.1 当前流程（不变）

```
L2 子图 interruptBefore → MemorySaver 保存 Checkpoint → 返回 INTERRUPTED
用户输入 → Controller → L0 → L1 ContextRouter → FOLLOW/RESUME
→ GES.resumeGraph → updateState + stream(null) → 从 Checkpoint 恢复
```

### 5.2 增加跨域数据后的流程

```
L2 子图 interruptBefore → MemorySaver 保存 Checkpoint → 返回 INTERRUPTED
→ ★ GlobalSessionStore.syncFromGraph(sessionId, intent, stateData)

用户输入 → Controller → L0 → L1 ContextRouter

FOLLOW (同一意图):
  → GES.resumeGraph(graph, intent, userInput, sessionId, crossDomainCtx)
  → updateState 注入: {_latestUserInput: userInput} + crossDomainCtx
  → stream(null, updatedConfig) → 从 Checkpoint 恢复 + 跨域数据已注入

SWITCH (切换意图):
  → GlobalSessionStore.switchIntent(sessionId, oldIntent, newIntent)
  → GES.executeGraph(newGraph, intent, rewrittenInput, sessionId, crossDomainCtx)
  → input 注入: {messages, _latestUserInput} + crossDomainCtx
  → stream(input, config) → 新图执行，OverAllState 包含跨域数据

RESUME (恢复挂起意图):
  → GES.resumeGraph(graph, intent, userInput, sessionId, crossDomainCtx)
  → updateState 注入: {_latestUserInput: userInput} + crossDomainCtx
  → stream(null, updatedConfig) → 从 Checkpoint 恢复 + 跨域数据已注入
```

### 5.3 关键：跨域数据注入不影响 Checkpoint 恢复

**问题**：resume 时 updateState 注入跨域数据，会不会覆盖 Checkpoint 中已保存的本域数据？

**分析**：

```java
// resumeGraph 中的 updateState 调用:
graph.updateState(config, updateData, null)

// updateData 包含:
//   1. _latestUserInput: 用户最新输入（必须覆盖）
//   2. crossDomainCtx: 跨域数据（如 bill.timePeriod=Q1）

// Checkpoint 中已保存的数据:
//   transfer.receiver=张三, transfer.amount=null, _question="转多少钱?"

// updateState 的行为:
//   → 合并 updateData 到 Checkpoint.state
//   → updateData 中的 key 覆盖 Checkpoint 中的同 key
//   → Checkpoint 中存在但 updateData 中不存在的 key 保持不变

// 所以:
//   transfer.receiver=张三  (保持，因为 crossDomainCtx 不含此 key... 等等)
```

**⚠️ 严重问题**：如果 crossDomainCtx 包含了当前意图上次保存的 key（如 `transfer.receiver`），
而 GlobalSessionStore 中保存的是旧值，就会**回退覆盖** Checkpoint 中的新值！

**解决方案：crossDomainCtx 必须排除当前意图的域内 key**

这是 1.3 节"域前缀命名规范"的核心价值——通过 `key.startsWith(intentPrefix)`
可以**精确过滤**当前意图的 key，只注入其他域的数据。

```java
public Map<String, Object> getCrossDomainContext(String sessionId, String currentIntent) {
    SessionContext ctx = sessions.get(sessionId);
    if (ctx == null) return Map.of();

    Map<String, Object> context = new HashMap<>();
    String intentPrefix = getIntentPrefix(currentIntent);  // "TRANSFER" → "transfer."

    for (Map.Entry<String, Object> entry : ctx.getGlobalState().entrySet()) {
        String key = entry.getKey();
        // ★ 域前缀过滤：排除当前意图的域内 key，避免覆盖 Checkpoint 恢复的本域数据
        // 依赖 1.3 节的命名规范：业务 key 必须以 "{intentPrefix}." 开头
        if (!key.startsWith(intentPrefix) && !isFrameworkInternalKey(key)) {
            context.put(key, entry.getValue());
        }
    }

    context.put("_crossDomainSummary", buildCrossDomainSummary(ctx, currentIntent));
    return context;
}

/**
 * 意图名 → 域前缀
 * 
 * 约定: intentName 的第一段（_ 分隔）转小写即为前缀
 *   TRANSFER          → transfer.
 *   BILL_QUERY        → bill.
 *   WEALTH_CONSULT    → wealth.
 *   WEALTH_INTERPRET  → wealth.
 * 
 * 同一域内多个意图共享前缀（如 WEALTH_CONSULT/WEALTH_INTERPRET → wealth.）
 * 这意味着 wealth 域内的两个子图可以看到彼此的数据——这是有意的，
 * 因为理财咨询和理财解读共享用户偏好（如风险等级）是有价值的。
 */
private String getIntentPrefix(String intent) {
    if (intent == null) return "";
    String prefix = intent.split("_")[0].toLowerCase();
    return prefix + ".";
}

/**
 * 框架内部 key 不注入——由 resume 机制自行管理
 * 
 * 以 "_" 开头的 key 是框架/基类使用的协议 key：
 *   _latestUserInput, _question, _paramName, _cancelSignal, _outputContent, _outputType, _isFinal
 * 唯一例外: _crossDomainSummary 是我们新增的跨域摘要 key，需要注入
 */
private boolean isFrameworkInternalKey(String key) {
    return key.startsWith("_") && !key.equals("_crossDomainSummary");
}
```

### 5.4 syncFromGraph 时机详解

**何时从 L2 子图同步状态到 GlobalSessionStore？**

```java
// 在 GraphExecutionEngine.checkGraphResult 之后，返回 WorkflowOutput 之前
// 由 L1 调用

// AbstractDomainService.resumeActiveAgent 增强:
protected WorkflowOutput resumeActiveAgent(String sessionId, String userInput, ActiveAgentInfo active) {
    addUserMessage(sessionId, userInput);
    var graph = intentRegistry.getGraph(active.getIntent());
    WorkflowOutput resumeResult = graphExecutionEngine.resumeGraph(
            graph, active.getIntent(), userInput, sessionId,
            globalSessionStore.getCrossDomainContext(sessionId, active.getIntent()));

    // ★ 执行后同步状态
    syncGraphStateToGlobal(sessionId, active.getIntent(), graph);

    saveL2Result(sessionId, resumeResult);
    recordSystemReply(sessionId, resumeResult);
    if (WorkflowStatus.COMPLETED.equals(resumeResult.getStatus())) {
        clearOwnActiveAgent(sessionId);
    }
    return resumeResult;
}

// AbstractDomainService.executeNewAgent 增强:
protected WorkflowOutput executeNewAgent(String sessionId, String intent, String rewrittenInput) {
    var graph = intentRegistry.getGraph(intent);
    setOwnActiveAgent(sessionId, intent);

    WorkflowOutput result = graphExecutionEngine.executeGraph(
            graph, intent, rewrittenInput, sessionId,
            globalSessionStore.getCrossDomainContext(sessionId, intent));

    // ★ 执行后同步状态
    syncGraphStateToGlobal(sessionId, intent, graph);

    saveL2Result(sessionId, result);
    if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
        clearOwnActiveAgent(sessionId);
    }
    return result;
}

// 新增的同步方法
private void syncGraphStateToGlobal(String sessionId, String intent, CompiledGraph graph) {
    try {
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
        var snapshot = graph.getState(config);
        if (snapshot != null && snapshot.state() != null) {
            globalSessionStore.syncFromGraph(sessionId, intent, snapshot.state().data());
        }
    } catch (Exception e) {
        log.warn("[{}] Failed to sync graph state to GlobalSessionStore", logTag, e);
    }
}
```

---

## 6. 兼容性分析

### 6.1 与当前架构的兼容性

| 组件 | 当前 | 改造后 | 兼容性 |
|------|------|--------|--------|
| BankController (L0) | 不变 | 不变 | ✅ 完全兼容 |
| DomainRouter | 不变 | 不变 | ✅ 完全兼容 |
| AbstractDomainService (L1) | 不变 | 增加 GlobalSessionStore 依赖 | ✅ 向后兼容 |
| SingleSubAgentDomainService | resumeActiveAgent / executeNewAgent | 增加跨域数据注入 + 同步 | ✅ 向后兼容 |
| MultiSubAgentDomainService | 同上 + handleResume | 增加跨域数据注入 + 同步 | ✅ 向后兼容 |
| GraphExecutionEngine | executeGraph / resumeGraph | 增加 crossDomainCtx 参数 | ✅ 向后兼容（重载方法） |
| AbstractGraphConfig | createKeyStrategyFactory | 改为使用 UnifiedKeyStrategyFactory | ⚠️ 改动基类 |
| 各 L2 GraphConfig | 继承 AbstractGraphConfig | 无需改动 | ✅ 完全兼容 |
| MemorySaver | 各图独立 | 各图独立（不变） | ✅ 完全兼容 |
| threadId | sessionId | sessionId（不变） | ✅ 完全兼容 |

### 6.2 与 Spring AI Alibaba 框架的兼容性

| 框架特性 | 是否使用 | 兼容性 |
|----------|---------|--------|
| interruptBefore | ✅ 使用 | 不受影响 |
| MemorySaver Checkpoint | ✅ 使用 | 不受影响（各图独立） |
| updateState + resume | ✅ 使用 | 不受影响（增加注入数据） |
| KeyStrategyFactory | ✅ 使用 | 改为统一工厂（框架允许） |
| CompiledGraph.stream() | ✅ 使用 | 不受影响（input Map 增加跨域 key） |

**核心兼容性结论**：
- 不修改框架源码
- 不改变框架的使用模式（interruptBefore + updateState + resume）
- 只在业务层（L1 + GES）增加数据注入/同步逻辑
- **完全兼容**

### 6.3 与 uniform_graph 方案的对比

| 维度 | global_overallstate（本方案） | uniform_graph |
|------|------|------|
| L0/L1 位置 | 图外部（不变） | 图内部（L1→图内节点） |
| Human-in-the-loop 意图切换 | ✅ L1 外部路由，无绕过问题 | ❌ resume 绕过 L1 路由 |
| 跨域状态共享 | ✅ GlobalSessionStore 注入 | ✅ 天然共享 OverAllState |
| MemorySaver | 各图独立 | 单一实例 |
| 改动量 | 中（增加组件+注入逻辑） | 大（重写 L0/L1 为图节点） |
| 多意图挂起/恢复 | ✅ 保留（独立 MemorySaver） | ❌ 单一 Checkpoint 无法多流 |
| 框架兼容性 | ✅ 完全兼容 | ⚠️ 需 hack 意图切换 |

**结论：global_overallstate 方案在保持现有架构优势的同时，解决了跨域状态共享问题。**

---

## 7. 代码改动分析

### 7.1 新增文件

| 文件 | 说明 |
|------|------|
| `GlobalSessionStore.java` | 全局会话状态存储 |
| `SessionContext.java` | 会话上下文数据类 |
| `UnifiedKeyStrategyFactory.java` | 统一 KeyStrategy 工厂（可选，取决于注入策略） |

### 7.2 修改文件

| 文件 | 改动 | 影响范围 |
|------|------|---------|
| `AbstractGraphConfig.java` | `createKeyStrategyFactory()` 改为使用统一工厂或注入统一工厂 | 所有 L2 子图 |
| `GraphExecutionEngine.java` | `executeGraph` / `resumeGraph` 增加 `crossDomainCtx` 参数 | GES |
| `AbstractDomainService.java` | 增加 `GlobalSessionStore` 依赖，增加 `syncGraphStateToGlobal` | L1 基类 |
| `SingleSubAgentDomainService.java` | `resumeActiveAgent` / `executeNewAgent` 增加注入+同步 | 转账、账单域 |
| `MultiSubAgentDomainService.java` | 同上 + `handleResume` 增加注入+同步 | 理财域 |
| `DomainServiceConfig.java` | 构建时注入 `GlobalSessionStore` | 配置 |
| `BankController.java` | `clearSession` 时清理 `GlobalSessionStore` | Controller |

### 7.3 不需修改的文件

| 文件 | 原因 |
|------|------|
| `TransferGraphConfig.java` | 节点逻辑不变，自动继承统一 KeyStrategyFactory |
| `BillQueryGraphConfig.java` | 同上 |
| `WealthConsultGraphConfig.java` | 同上 |
| `WealthInterpretGraphConfig.java` | 同上 |
| `ContextRouter.java` | 路由逻辑不变 |
| `IntentRouter.java` / `IntentResolver.java` | 意图识别逻辑不变 |
| `DomainRouter.java` | 领域路由不变 |
| `IntentRegistry.java` | 注册表不变 |

### 7.4 GES 改动示例

```java
// 当前
public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                    String userInput, String sessionId)

// 改造后：增加重载方法保持向后兼容
public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                    String userInput, String sessionId) {
    return executeGraph(graph, intent, userInput, sessionId, Map.of());
}

public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                    String userInput, String sessionId,
                                    Map<String, Object> crossDomainCtx) {
    try {
        RunnableConfig config = threadConfig(sessionId);
        Map<String, Object> input = new HashMap<>();
        input.put("messages", userInput);
        input.put("_latestUserInput", userInput);
        input.put("_question", null);
        input.putAll(crossDomainCtx);  // ★ 注入跨域数据
        graph.stream(input, config).blockLast();
        return checkGraphResult(graph, config, intent);
    } catch (Exception e) {
        log.error("[GraphExec] Graph execution failed", e);
        return WorkflowOutput.error("执行出错: " + e.getMessage());
    }
}

// resumeGraph 同理
public WorkflowOutput resumeGraph(CompiledGraph graph, String intent,
                                   String userInput, String sessionId,
                                   Map<String, Object> crossDomainCtx) {
    try {
        RunnableConfig config = threadConfig(sessionId);
        Map<String, Object> updateData = new HashMap<>();
        updateData.put("_latestUserInput", userInput);
        updateData.putAll(crossDomainCtx);  // ★ 注入跨域数据
        RunnableConfig updatedConfig = graph.updateState(config, updateData, null);
        graph.stream(null, updatedConfig).blockLast();
        return checkGraphResult(graph, config, intent);
    } catch (Exception e) {
        log.error("[GraphExec] Resume graph failed", e);
        return WorkflowOutput.error("恢复执行出错: " + e.getMessage());
    }
}
```

---

## 8. 注入策略决策：统一 KeyStrategyFactory vs 独立 KeyStrategyFactory

### 8.1 关键区别

| 维度 | 统一 KeyStrategyFactory | 独立 KeyStrategyFactory + _crossDomainSummary |
|------|------------------------|----------------------------------------------|
| 跨域数据精度 | 原始 key-value（`transfer.amount=500`） | 自然语言摘要（"用户转账了500元"） |
| L2 节点读取方式 | `state.value("transfer.amount")` | `state.value("_crossDomainSummary")` + LLM 理解 |
| 新增域影响 | 需更新统一工厂（加 key） | 只需更新摘要构建逻辑 |
| KeyStrategy 膨胀 | 所有域 key 在所有图可见 | 只加一个 `_crossDomainSummary` key |
| L2 节点代码改动 | 可选（可以选择性读取跨域 key） | 需要 LLM prompt 中使用摘要 |
| 数据一致性 | 强一致（结构化数据） | 弱一致（自然语言可能丢失细节） |

### 8.2 推荐：统一 KeyStrategyFactory（方案 A/C）

理由：
1. **结构化数据优于自然语言摘要**：`transfer.amount=500` 比"用户转账了500元"更可靠
2. **L2 节点可选择性地使用跨域数据**：不用的 key 只是 null，不影响逻辑
3. **KeyStrategyFactory 是编译时配置**，运行时无额外开销
4. **新增域只需加 key 定义**，改动量小且集中
5. **可以同时保留 _crossDomainSummary** 作为 LLM prompt 的补充信息

### 8.3 AbstractGraphConfig 改造

```java
// 当前: 各子图调 createKeyStrategyFactory() 创建独立工厂
// 改造: 注入统一工厂

@Slf4j
public abstract class AbstractGraphConfig {

    protected final ChatModel chatModel;
    protected final ObjectMapper objectMapper;
    protected final KeyStrategyFactory keyStrategyFactory;  // ★ 新增：统一工厂

    private final List<String> interruptNodes = new ArrayList<>();

    // 改造后的构造器
    protected AbstractGraphConfig(ChatModel chatModel, KeyStrategyFactory keyStrategyFactory) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
        this.keyStrategyFactory = keyStrategyFactory;
    }

    // 保留 createKeyStrategyFactory() 供子类注册自定义 key
    // 但实际创建 StateGraph 时使用注入的统一工厂
    protected KeyStrategyFactory createKeyStrategyFactory() {
        return keyStrategyFactory;
    }

    // 子类仍然注册自定义 key，但通过统一工厂的 builder
    protected abstract void registerCustomKeys(Map<String, KeyStrategy> strategies);
}
```

**替代方案**：保持 `createKeyStrategyFactory()` 不变，在内部合并到统一工厂：

```java
// 更简洁的方案：基类负责合并
protected KeyStrategyFactory createKeyStrategyFactory() {
    return () -> {
        // 先获取统一基础 keys
        Map<String, KeyStrategy> strategies = UnifiedKeyStrategyFactory.getBaseStrategies();
        // 子类注册自定义 keys
        registerCustomKeys(strategies);
        return strategies;
    };
}
```

这种方式子类无需感知统一工厂的存在，改动最小。

---

## 9. 风险与开放问题

### 9.1 风险

| # | 风险 | 影响 | 缓解措施 |
|---|------|------|---------|
| R1 | 跨域数据注入覆盖 Checkpoint 中同域数据 | L2 恢复时参数被旧值覆盖 | getCrossDomainContext 排除当前意图域内 key |
| R2 | 统一 KeyStrategyFactory 导致 key 命名冲突 | 两个域定义相同 key 名 | 强制 `{domain}.{field}` 前缀命名规范（1.3节），代码审查确保 |
| R3 | GlobalSessionStore 内存泄漏 | session 不清理导致内存增长 | 定时清理 + BankController.clearSession 清理 |
| R4 | 大量跨域 key 注入的性能影响 | updateState Map 过大 | 当前仅约 10 个域内 key，量级可忽略 |
| R5 | 跨域数据的一致性延迟 | syncFromGraph 是异步的，可能不是最新 | syncFromGraph 在 GES 执行后同步调用，非异步 |

### 9.2 开放问题

| # | 问题 | 需要讨论 |
|---|------|---------|
| Q1 | **跨域数据注入的粒度**：全量注入 vs 按需注入？当前推荐全量（数据量小），但未来域增多时需重新评估 | — |
| Q2 | **GlobalSessionStore 持久化**：当前设计为内存（ConcurrentHashMap），是否需要 Redis？对多实例部署的影响 | — |
| Q3 | **L2 节点如何消费跨域数据**：是直接读 `state.value("transfer.amount")` 还是通过 `_crossDomainSummary` 让 LLM 理解？建议两者都支持 | — |
| Q4 | **统一 KeyStrategyFactory 的维护**：新增域时需更新统一工厂，是否可改为自动扫描注册？ | — |
| Q5 | **transfer.amount 类型**：当前是 BigDecimal，但 GlobalSessionStore 存为 Object，跨图注入时类型是否一致？ | — |

### 9.3 不解决的问题

| 问题 | 原因 |
|------|------|
| L2 子图内部的多意图并发 | 各图独立 MemorySaver 已天然支持 |
| L1 路由逻辑 | 不变，FOLLOW/SWITCH/RESUME 仍在图外 |
| Checkpoint 管理优化 | 不变，各图独立管理 |

---

## 10. 实施计划

### Phase 1：基础设施（无业务影响）

1. 创建 `GlobalSessionStore` + `SessionContext`
2. 创建 `UnifiedKeyStrategyFactory`
3. 改造 `AbstractGraphConfig` 支持注入统一工厂
4. 改造 `GraphExecutionEngine` 增加 crossDomainCtx 参数（重载，向后兼容）

### Phase 2：注入+同步逻辑

5. 改造 `AbstractDomainService` 增加 GlobalSessionStore 依赖 + `syncGraphStateToGlobal`
6. 改造 `SingleSubAgentDomainService` 的 `resumeActiveAgent` / `executeNewAgent`
7. 改造 `MultiSubAgentDomainService` 的 `resumeActiveAgent` / `executeNewAgent` / `handleResume`
8. 改造 `BankController.clearSession` 清理 GlobalSessionStore

### Phase 3：验证

9. 验证跨域场景："转账→推荐理财" 理财推荐能感知转账金额
10. 验证恢复场景："转账中断→查账单→继续转账" 转账参数不丢失
11. 验证隔离场景：transfer.key 不影响 bill 节点逻辑

---

## 11. 总结

### 核心决策

1. **OverAllState 对象不能共享**——框架每次 stream() 都新建，无法注入
2. **OverAllState 数据可以共享**——通过 GlobalSessionStore 在 L1 层注入/同步
3. **MemorySaver 保持独立**——避免 Checkpoint 冲突，保留多流挂起/恢复能力
4. **统一 KeyStrategyFactory**——让跨域 key 可被目标图识别
5. **注入时排除当前意图域内 key**——避免覆盖 Checkpoint 恢复的本域数据

### 架构本质

```
当前架构:  L1 手动编排 + L2 独立状态（完全隔离）
改造后:    L1 手动编排 + L2 独立 MemorySaver + 共享数据注入（逻辑共享，物理隔离）
uniform:   L1 图内编排 + L2 共享一切（物理共享，但失去多流能力）
```

global_overallstate 是当前架构和 uniform_graph 之间的"第三条路"：
- 保留了当前架构的多流挂起/恢复优势
- 借鉴了 uniform_graph 的跨域状态共享思路
- 通过业务层的 GlobalSessionStore 实现数据桥接，不依赖框架改造
