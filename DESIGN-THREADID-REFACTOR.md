# 设计文档：ThreadId 架构重构 + GlobalContext 注入

> 分支：`feat/global_overallstate`
> 前置文档：`DESIGN-GLOBAL-OVERALLSTATE.md`
> 目标：消除同 threadId 脏数据问题，建立显式跨域数据注入机制

---

## 1. 问题定义

### 1.1 脏数据问题（实验验证）

当前架构 `threadId = sessionId`，同一 CompiledGraph + 同一 MemorySaver 下，新 `stream()` 调用时
框架的 `getInitialState` 会自动从 Checkpoint 合并历史数据。

**测试验证** (`SameThreadIdStateRetentionTest`)：

```
第1次: "转账给我妈300" → receiver=我妈, amount=300 → 完成
第2次: "转账给李四"   → extractParams 提取 receiver=李四
                         但 state.amount=300（★ 来自第1次 Checkpoint 残留）
                         → paramRouter: receiver=李四 + amount=300 → ALL_GOOD
                         → ★ 直接完成，金额错误！用户没说转 300
```

**根因**：同 threadId 下 Checkpoint 自动合并是隐式的、不可控的。L2 图团队无法决定"我要不要上次的数据"。

### 1.2 回调机制的冗余

当前通过 `AbstractGraphConfig.onGraphCompleted()` 回调将 L2 数据写回 GlobalSessionStore：

```
L2 执行完成 → syncGraphStateToGlobal → graphConfig.onGraphCompleted(globalState, localStateData)
                                          ↑ 子Graph override，选择写什么
```

这要求：
- AbstractGraphConfig 加回调方法
- IntentRegistry 存储 graphConfig 引用
- AppInitConfig 绑定 graphConfig
- 每个 L2 GraphConfig override onGraphCompleted

**问题**：如果 DomainService 可以按域前缀过滤提取，就不需要 L2 图自己决定写什么。

### 1.3 目标

1. **消除脏数据**：每次新执行用新 threadId，干净启动
2. **显式注入**：跨域数据从 GlobalSessionStore 显式传入，L2 图"想用就用，不想用不用"
3. **去掉回调**：DomainService 按前缀提取写回，简化 L2 图开发
4. **Resume 不受影响**：用存储的 threadId 恢复 Checkpoint

---

## 2. 核心设计决策

### 2.1 threadId 策略：每次新执行生成新 threadId

```
当前:  threadId = sessionId                       → 同一 session 内所有执行共享
新:    threadId = sessionId + "-" + intent + "-" + shortSuffix  → 每次新执行唯一
```

| 场景 | threadId | 行为 |
|------|----------|------|
| 新执行 (SWITCH/首次) | 生成新 threadId | 干净 OverAllState，无历史残留 |
| FOLLOW (同意图继续) | 复用 ActiveAgentInfo 中的 threadId | 从 Checkpoint 恢复，继续中断流程 |
| RESUME (恢复挂起) | 复用 SuspendedInfo 中的 threadId | 从 Checkpoint 恢复，继续中断流程 |
| COMPLETED | ActiveAgentInfo 清除 | threadId 引用消失，Checkpoint 残留在 MemorySaver |

**为什么不用同一 threadId？**

| 维度 | 同 threadId | 不同 threadId |
|------|------------|--------------|
| 新执行时 OverAllState | 自动合并 Checkpoint（含脏数据） | 干净初始状态 |
| 同域"记忆" | 框架自动（不可控） | 显式从 GlobalSessionStore 注入（可控） |
| 脏数据风险 | 高（残留 amount/receiver） | 零（每次干净启动） |
| L2 图自主权 | 无法拒绝自动合并的数据 | "想用就用，不想用不用" |
| 数据来源透明度 | 隐式（框架合并，难调试） | 显式（注入什么一目了然） |

### 2.2 跨域数据注入：通过 input Map 传入

**决策：注入到 graph.stream(input, config) 的 input Map 中。**

```
                        GlobalSessionStore
                              │
                    getCrossDomainData(sessionId, "transfer.")
                    getCrossDomainSummary(sessionId, "transfer.")
                              │
                              ▼
┌─ DomainService.executeNewAgent() ─────────────────────────┐
│                                                            │
│  Map<String, Object> input = new HashMap<>();              │
│  input.put("messages", rewrittenInput);                    │
│  input.put("_latestUserInput", rewrittenInput);            │
│  input.put("_crossDomainSummary", summary);  // ① 摘要    │
│  input.putAll(crossDomainData);              // ② 结构化   │
│                                                            │
│  graph.stream(input, config)                               │
│       │                                                    │
│       ▼                                                    │
│  ┌─ KeyStrategyFactory = 天然白名单 ───────────────────┐   │
│  │  key 在工厂中注册 → 进入 OverAllState               │   │
│  │  key 未注册 → 框架静默丢弃 → 零影响                 │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

**两种注入方式并存**：

| 方式 | Key | 内容 | L2 图需要做什么 |
|------|-----|------|----------------|
| ① 自然语言摘要 | `_crossDomainSummary` | "bill.timePeriod=Q1, wealth.riskLevel=稳健型" | 零配置，AbstractGraphConfig 公共 key 已注册 |
| ② 结构化 key | `bill.timePeriod` 等 | 原始 key-value | 在 `registerCustomKeys()` 中注册该 key |

**"想用就用"机制**：

- **不想用跨域数据**：什么都不加 → 默认隔离，`_crossDomainSummary` 也在 OverAllState 中但 prompt 可以不引用
- **想用 LLM 理解的摘要**：在 extractParams prompt 中引用 `_crossDomainSummary` → 零配置
- **想用精确的结构化数据**：在 `registerCustomKeys()` 中加跨域 key → `state.value("bill.timePeriod")` 可读

### 2.3 写回方式：DomainService 按前缀提取，替代回调

```
旧: L2 图 override onGraphCompleted → 自己决定写什么到 GlobalSessionStore
新: DomainService 读 L2 最终 state → 按域前缀过滤 → mergeState 到 GlobalSessionStore
```

**按前缀过滤的正确性**：

- 域前缀命名规范（`transfer.*`、`bill.*`、`wealth.*`）已在项目中强制执行
- DomainService 知道当前 intent → 知道前缀 → 知道提取哪些 key
- 公共 key（`_question`、`_outputContent` 等）不写入 GlobalSessionStore（它们是 L2 内部协议）
- 如果 L2 图产生了未遵循前缀规范的 key，不会被提取（正确行为）

**对比**：

| | 旧 (onGraphCompleted) | 新 (前缀提取) |
|---|---|---|
| 谁决定写什么 | L2 图 | DomainService (按前缀) |
| L2 图需要改代码 | 是 (override) | 否 |
| IntentRegistry 需要 graphConfig | 是 | 否 |
| AppInitConfig 需要绑定 | 是 | 否 |
| 新增域的改动 | 加回调 + 绑定 | 只加 getDomainPrefix 映射 |
| 数据完整性 | 依赖 L2 图实现 | 由前缀命名规范保证 |

### 2.4 同域数据不注入

**决策：只注入跨域数据，不注入同域数据。**

```
场景: 用户先 "转账给我妈300"，再 "转账给李四"

如果注入同域数据 (transfer.receiver=我妈, transfer.amount=300):
  → amount=300 残留 → paramRouter: receiver=李四 + amount=300 → 直接完成 → ★ 金额错误

如果不注入同域数据:
  → amount=null → paramRouter: receiver=李四 + amount=null → ASK_AMOUNT → ★ 正确
```

同域"记忆"应由业务逻辑显式处理（如 L1 层 input rewriting），不应通过数据注入自动实现。

---

## 3. threadId 生命周期详细设计

### 3.1 生成规则

```java
/**
 * threadId 格式: {sessionId}-{intent}-{6位随机hex}
 * 
 * 示例:
 *   user-1-TRANSFER-a3f2b1
 *   user-1-BILL_QUERY-7e91c4
 *   user-1-WEALTH_CONSULT-d4a8e3
 * 
 * 设计考量:
 * - 包含 sessionId: 便于日志调试时关联 session
 * - 包含 intent: 便于识别哪个意图的 Checkpoint
 * - 随机后缀: 保证同一 session+intent 的多次执行不冲突
 * - 6位 hex: 16^6 = 16M 种组合，碰撞概率极低
 */
private String generateThreadId(String sessionId, String intent) {
    String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
    return sessionId + "-" + intent + "-" + suffix;
}
```

### 3.2 存储

**ActiveAgentInfo（单意图/多意图通用）**：

```java
@Data
public static class ActiveAgentInfo {
    private final String intent;
    private final String threadId;       // ★ 新增
    private final Instant createdAt;
    private final Instant expiresAt;
    private String lastQuestion;

    public ActiveAgentInfo(String intent, String threadId, 
                           Instant createdAt, Instant expiresAt) {
        this.intent = intent;
        this.threadId = threadId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
```

**SuspendedInfo（Multi 域挂起）**：

```java
@Data
public static class SuspendedInfo {
    private final String intent;
    private final String threadId;       // ★ 新增
    private final Instant suspendedAt;
    private final Instant expiresAt;

    public SuspendedInfo(String intent, String threadId,
                         Instant suspendedAt, Instant expiresAt) {
        this.intent = intent;
        this.threadId = threadId;
        this.suspendedAt = suspendedAt;
        this.expiresAt = expiresAt;
    }
}
```

### 3.3 传递链

```
生成点:     AbstractDomainService.executeNewAgent()
            → generateThreadId(sessionId, intent)
            → new ActiveAgentInfo(intent, threadId, ...)

执行点:     GraphExecutionEngine.executeGraph(graph, intent, input, threadId)
            → RunnableConfig config = RunnableConfig.builder().threadId(threadId).build()

恢复点:     GraphExecutionEngine.resumeGraph(graph, intent, userInput, threadId)
            → 从 ActiveAgentInfo 或 SuspendedInfo 取出 threadId

回写点:     AbstractDomainService.syncGraphStateToGlobal(sessionId, intent, threadId, graph)
            → 用 threadId 读 Checkpoint

挂起点:     MultiSubAgentDomainService.suspendOwnAgent(sessionId, intent, threadId)
            → new SuspendedInfo(intent, threadId, ...)

恢复挂起:   MultiSubAgentDomainService.handleResume(sessionId, intent, userInput)
            → suspendedInfo.getThreadId() → GES.resumeGraph(..., threadId)
```

---

## 4. 数据流详细设计

### 4.1 注入流（GlobalSessionStore → L2 Graph）

```
┌─ AbstractDomainService.executeNewAgent() ──────────────────────────┐
│                                                                     │
│  ① 生成新 threadId                                                 │
│    String threadId = generateThreadId(sessionId, intent);           │
│                                                                     │
│  ② 获取跨域数据                                                    │
│    String domainPrefix = getDomainPrefix(intent);                   │
│    Map<String, Object> crossDomainData =                            │
│        globalSessionStore.getCrossDomainData(sessionId, prefix);    │
│    String crossDomainSummary =                                      │
│        globalSessionStore.getCrossDomainSummary(sessionId, prefix); │
│                                                                     │
│  ③ 构建 input Map                                                  │
│    Map<String, Object> input = new HashMap<>();                     │
│    input.put("messages", rewrittenInput);                           │
│    input.put("_latestUserInput", rewrittenInput);                   │
│    input.put("_crossDomainSummary", crossDomainSummary);            │
│    input.putAll(crossDomainData);                                   │
│                                                                     │
│  ④ 存 threadId                                                     │
│    setOwnActiveAgent(sessionId, intent, threadId);                  │
│                                                                     │
│  ⑤ 执行                                                            │
│    WorkflowOutput result = graphExecutionEngine                     │
│        .executeGraph(graph, intent, input, threadId);               │
│                                                                     │
│  ⑥ 写回 GlobalSessionStore                                         │
│    syncGraphStateToGlobal(sessionId, intent, threadId, graph);      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 恢复流（FOLLOW / RESUME）

```
┌─ AbstractDomainService.resumeActiveAgent() ────────────────────────┐
│                                                                     │
│  ① 取出 threadId                                                   │
│    String threadId = active.getThreadId();                          │
│                                                                     │
│  ② 恢复执行（不需要注入跨域数据，Checkpoint 已有）                   │
│    WorkflowOutput result = graphExecutionEngine                     │
│        .resumeGraph(graph, intent, userInput, threadId);            │
│                                                                     │
│  ③ 写回 GlobalSessionStore                                         │
│    syncGraphStateToGlobal(sessionId, intent, threadId, graph);      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─ MultiSubAgentDomainService.handleResume() ────────────────────────┐
│                                                                     │
│  ① 取出 threadId                                                   │
│    String threadId = suspendedInfo.getThreadId();                   │
│                                                                     │
│  ② 恢复执行                                                        │
│    WorkflowOutput result = graphExecutionEngine                     │
│        .resumeGraph(graph, intent, userInput, threadId);            │
│                                                                     │
│  ③ 写回 GlobalSessionStore                                         │
│    syncGraphStateToGlobal(sessionId, intent, threadId, graph);      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**为什么 resume 不注入跨域数据？**

Resume 是从 Checkpoint 恢复，OverAllState 已包含执行时的状态。如果注入新的跨域数据，
可能与 Checkpoint 中的已有值冲突，导致不可预期的行为。Resume 应严格恢复中断时的状态。

如果业务上确实需要在 resume 时更新跨域上下文，应作为独立需求后续设计。

### 4.3 写回流（L2 Graph → GlobalSessionStore）

```java
/**
 * 新实现：按域前缀过滤提取，替代 onGraphCompleted 回调
 */
protected void syncGraphStateToGlobal(String sessionId, String intent, 
                                       String threadId, CompiledGraph graph) {
    try {
        // ① 用 threadId 读 L2 的最终 Checkpoint
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        var snapshot = graph.getState(config);
        if (snapshot == null || snapshot.state() == null) return;
        
        Map<String, Object> localStateData = snapshot.state().data();
        String domainPrefix = getDomainPrefix(intent);
        
        // ② 按域前缀过滤，只提取当前域的业务数据
        Map<String, Object> domainData = new HashMap<>();
        for (Map.Entry<String, Object> entry : localStateData.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(domainPrefix) && entry.getValue() != null) {
                domainData.put(key, entry.getValue());
            }
        }
        
        // ③ 合并进 GlobalSessionStore
        if (!domainData.isEmpty()) {
            globalSessionStore.getOrCreate(sessionId).mergeState(domainData);
            log.info("[{}] syncGraphStateToGlobal: intent={}, threadId={}, domainKeys={}",
                    logTag, intent, threadId, domainData.keySet());
        }
    } catch (Exception e) {
        log.warn("[{}] syncGraphStateToGlobal failed, non-critical", logTag, e);
    }
}
```

---

## 5. GraphExecutionEngine 接口变更

### 5.1 新签名

```java
// 旧
public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                    String userInput, String sessionId)

public WorkflowOutput resumeGraph(CompiledGraph graph, String intent,
                                   String userInput, String sessionId)

// 新
public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                    Map<String, Object> input, String threadId)

public WorkflowOutput resumeGraph(CompiledGraph graph, String intent,
                                   String userInput, String threadId)
```

### 5.2 executeGraph 实现

```java
/**
 * 首次执行Graph — input 由调用方构建（含跨域数据）
 *
 * 变更:
 * - input 改为 Map<String, Object>（调用方自行构建，含 messages、_latestUserInput、跨域数据）
 * - threadId 由调用方传入（不再默认 sessionId）
 */
public WorkflowOutput executeGraph(CompiledGraph graph, String intent,
                                    Map<String, Object> input, String threadId) {
    try {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        // input 已由调用方构建完成，直接传入
        // KeyStrategyFactory 会过滤未注册的 key（天然白名单）
        log.info("[GraphExec] Executing graph: intent={}, threadId={}, inputKeys={}",
                intent, threadId, input.keySet());

        graph.stream(input, config).blockLast();
        return checkGraphResult(graph, config, intent);

    } catch (Exception e) {
        log.error("[GraphExec] Graph execution failed", e);
        return WorkflowOutput.error("执行出错: " + e.getMessage());
    }
}
```

### 5.3 resumeGraph 实现

```java
/**
 * 恢复执行Graph — 用存储的 threadId 从 Checkpoint 恢复
 *
 * 变更:
 * - threadId 由调用方传入（从 ActiveAgentInfo/SuspendedInfo 取出）
 */
public WorkflowOutput resumeGraph(CompiledGraph graph, String intent,
                                   String userInput, String threadId) {
    try {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        log.info("[GraphExec] Resume graph: intent={}, threadId={}, userInput={}",
                intent, threadId, userInput);

        // updateState 注入用户输入
        RunnableConfig updatedConfig = graph.updateState(
                config, Map.of("_latestUserInput", userInput), null);

        // stream(null, updatedConfig) 从中断点恢复
        graph.stream(null, updatedConfig).blockLast();

        // 用原始 config 读最新 checkpoint
        return checkGraphResult(graph, config, intent);

    } catch (Exception e) {
        log.error("[GraphExec] Resume graph failed", e);
        return WorkflowOutput.error("恢复执行出错: " + e.getMessage());
    }
}
```

### 5.4 cancelGraph 实现

```java
/**
 * 取消Graph执行 — 需要 threadId 来定位 Checkpoint
 *
 * 变更:
 * - threadId 由调用方传入
 */
public WorkflowOutput cancelGraph(CompiledGraph graph, String intent, 
                                   String threadId) {
    // ... 与当前逻辑相同，只是 config 用传入的 threadId
    RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
    // ...
}
```

---

## 6. 完整流程示例

### 6.1 场景："查完账单，转500给我妈"

```
=== 时刻1: BILL_QUERY 首次执行 ===

用户: "查一下最近账单"

MultiSubAgentDomainService.executeNewAgent("user-1", "BILL_QUERY", "查最近账单")
  → threadId = "user-1-BILL_QUERY-f3a1b2"           (新生成)
  → crossDomainData = {}                              (首次，无跨域数据)
  → crossDomainSummary = ""
  → input = {messages: "查最近账单", _latestUserInput: "查最近账单"}
  → GES.executeGraph(graph, "BILL_QUERY", input, "user-1-BILL_QUERY-f3a1b2")
  → BillQueryGraph: extractParams → paramRouter → ASK_TIME → 中断

  syncGraphStateToGlobal("user-1", "BILL_QUERY", "user-1-BILL_QUERY-f3a1b2", graph)
    → 读 Checkpoint → 过滤 "bill." → 无非空域数据 → 跳过

  ActiveAgentInfo = {intent=BILL_QUERY, threadId="user-1-BILL_QUERY-f3a1b2"}

=== 时刻2: FOLLOW "最近一个月" ===

用户: "最近一个月"

MultiSubAgentDomainService.resumeActiveAgent("user-1", "最近一个月", activeAgent)
  → threadId = activeAgent.getThreadId() = "user-1-BILL_QUERY-f3a1b2"  (复用!)
  → GES.resumeGraph(graph, "BILL_QUERY", "最近一个月", "user-1-BILL_QUERY-f3a1b2")
  → BillQueryGraph: askTime → paramRouter → ALL_GOOD → executeBillQuery → 完成

  syncGraphStateToGlobal("user-1", "BILL_QUERY", "user-1-BILL_QUERY-f3a1b2", graph)
    → 过滤 "bill." → {bill.timePeriod="最近一个月", bill.expenseType="餐饮"} → mergeState

  GlobalSessionStore: {bill.timePeriod="最近一个月", bill.expenseType="餐饮"}

  ActiveAgentInfo 清除 (COMPLETED)

=== 时刻3: SWITCH → TRANSFER ===

用户: "转500给我妈"

MultiSubAgentDomainService.executeNewAgent("user-1", "TRANSFER", "转500给我妈")
  → threadId = "user-1-TRANSFER-a7c3d9"              (新! 不会合并旧Checkpoint)
  → crossDomainData = {bill.timePeriod="最近一个月", bill.expenseType="餐饮"}
  → crossDomainSummary = "bill.timePeriod=最近一个月, bill.expenseType=餐饮"
  → input = {
      messages: "转500给我妈",
      _latestUserInput: "转500给我妈",
      _crossDomainSummary: "bill.timePeriod=最近一个月, bill.expenseType=餐饮",
      bill.timePeriod: "最近一个月",        // ★ 如果 Transfer KeyStrategyFactory 注册了
      bill.expenseType: "餐饮"             // ★ 如果 Transfer KeyStrategyFactory 注册了
    }
  → TransferGraph KeyStrategyFactory 没有 bill.* → 丢弃
  → 但 _crossDomainSummary 在 AbstractGraphConfig 公共 key 中 → 进入 OverAllState
  → extractParams 可在 prompt 中引用 _crossDomainSummary
  → receiver=我妈, amount=500 → ALL_GOOD → 完成

  syncGraphStateToGlobal("user-1", "TRANSFER", "user-1-TRANSFER-a7c3d9", graph)
    → 过滤 "transfer." → {transfer.receiver=我妈, transfer.amount=500} → mergeState

  GlobalSessionStore: {bill.timePeriod="最近一个月", bill.expenseType="餐饮",
                       transfer.receiver=我妈, transfer.amount=500}

=== 时刻4: 再转一笔 ===

用户: "再转一笔"

MultiSubAgentDomainService.executeNewAgent("user-1", "TRANSFER", "再转一笔")
  → threadId = "user-1-TRANSFER-e5f8a2"              (新! 干净启动)
  → crossDomainData = {bill.timePeriod="最近一个月", transfer.receiver=我妈, transfer.amount=500}
  → crossDomainSummary = "bill.timePeriod=最近一个月, transfer.receiver=我妈, transfer.amount=500"
  → input = {..., _crossDomainSummary: "...", ...}
  → OverAllState 干净启动 → extractParams 从 "再转一笔" 提取 → 无明确参数
  → receiver=null → ASK_RECEIVER → 中断 → ★ 正确！

  ★ 如果用旧的同 threadId 方案:
    Checkpoint 自动合并 → receiver=我妈, amount=500 残留
    → ALL_GOOD → 直接完成 → ★ 金额错误！
```

### 6.2 Multi 域挂起/恢复场景

```
=== 时刻1: TRANSFER 首次执行，中断 ===
  threadId = "user-1-TRANSFER-a3f2"
  ActiveAgentInfo = {intent=TRANSFER, threadId="user-1-TRANSFER-a3f2"}
  → 中断 at askAmount

=== 时刻2: SWITCH → WEALTH_CONSULT ===
  ① 挂起 Transfer:
    SuspendedInfo = {intent=TRANSFER, threadId="user-1-TRANSFER-a3f2"}
    ActiveAgentInfo 清除

  ② 新执行 WealthConsult:
    threadId = "user-1-WEALTH_CONSULT-b7e1"
    ActiveAgentInfo = {intent=WEALTH_CONSULT, threadId="user-1-WEALTH_CONSULT-b7e1"}
    → 中断 at askRiskLevel

=== 时刻3: 用户 "继续转账" → RESUME ===
  ① 从 SuspendedInfo 取:
    threadId = "user-1-TRANSFER-a3f2"
  ② GES.resumeGraph(transferGraph, "TRANSFER", "继续转账", "user-1-TRANSFER-a3f2")
  ③ Checkpoint 恢复 → 从 askAmount 继续 → 完成

  syncGraphStateToGlobal("user-1", "TRANSFER", "user-1-TRANSFER-a3f2", graph)
    → 用 "user-1-TRANSFER-a3f2" 读 Checkpoint → 过滤 "transfer." → mergeState
```

---

## 7. 改动清单

### 7.1 删除（简化）

| 文件 | 删什么 | 原因 |
|------|--------|------|
| `AbstractGraphConfig.java` | `onGraphCompleted()` 方法及注释 | 不再需要回调，DomainService 按前缀提取 |
| `TransferGraphConfig.java` | `onGraphCompleted()` override 及 `extractDomainData()` | 同上 |
| `IntentRegistry.java` | `graphConfig` 字段、`bindGraphConfig()` 方法、IntentConfig 中的 graphConfig | 不再需要存 graphConfig 引用 |
| `AppInitConfig.java` | 4 个 GraphConfig 注入、4 行 `bindGraphConfig()` 调用 | 同上 |
| `GlobalSessionStore.java` | `onGraphStateUpdate()` 方法 | 同步逻辑已移入 syncGraphStateToGlobal |
| `AbstractDomainService.java` | `syncGraphStateToGlobal` 中调用 `graphConfig.onGraphCompleted` 的逻辑 | 替换为按前缀提取 |

### 7.2 新增

| 文件 | 加什么 | 原因 |
|------|--------|------|
| `AbstractDomainService.java` | `generateThreadId(sessionId, intent)` | 生成新 threadId |
| `AbstractDomainService.java` | `generateThreadId` 的实现 | `sessionId + "-" + intent + "-" + hexSuffix` |

### 7.3 修改

| 文件 | 改什么 | 原因 |
|------|--------|------|
| `ActiveAgentInfo` | 加 `threadId` 字段，改构造器 | resume 需要取 threadId |
| `SuspendedInfo` | 加 `threadId` 字段，改构造器 | 恢复挂起需要取 threadId |
| `GraphExecutionEngine.executeGraph()` | 签名改为 `(graph, intent, input, threadId)` | 接受外部 threadId 和完整 input |
| `GraphExecutionEngine.resumeGraph()` | 签名改为 `(graph, intent, userInput, threadId)` | 接受外部 threadId |
| `GraphExecutionEngine.cancelGraph()` | 签名改为 `(graph, intent, threadId)` | 接受外部 threadId |
| `GraphExecutionEngine` | 删除 `threadConfig(sessionId)` 私有方法 | 不再默认用 sessionId |
| `AbstractDomainService.executeNewAgent()` | 生成 threadId + 构建完整 input + 注入跨域数据 | 核心改动 |
| `AbstractDomainService.resumeActiveAgent()` | 从 active 取 threadId 传给 GES | threadId 来源变更 |
| `AbstractDomainService.syncGraphStateToGlobal()` | 接收 threadId + 按前缀提取 + 直接 mergeState | 替代回调 |
| `SingleSubAgentDomainService` | 传递 threadId，构建 input Map | 串联 |
| `MultiSubAgentDomainService` | 传递 threadId + SuspendedInfo 加 threadId + 构建 input | 串联 |
| `DomainServiceConfig` | 构建参数适配（不再注入 GlobalSessionStore 到 GES） | GES 不再需要 GlobalSessionStore |
| `BankController.clearSession()` | 不变 | 已有清理逻辑 |

### 7.4 不变

| 文件 | 原因 |
|------|------|
| `GlobalSessionStore.getOrCreate()` | 继续使用 |
| `GlobalSessionStore.getCrossDomainData()` | 继续使用 |
| `GlobalSessionStore.getCrossDomainSummary()` | 继续使用 |
| `GlobalSessionStore.clearSession()` | 继续使用 |
| `GlobalSessionContext.mergeState()` | 继续使用 |
| 各 L2 GraphConfig 的节点逻辑 | 不受影响 |
| 各 L2 GraphConfig 的 KeyStrategyFactory / registerCustomKeys | 不受影响（团队可选加跨域 key） |
| `SameThreadIdStateRetentionTest` | 保留作为参考测试 |

---

## 8. 关键设计细节

### 8.1 _crossDomainSummary 的注入方式

**决策：作为 input Map 的独立 key 注入，不拼到 userInput 字符串中。**

```java
input.put("_crossDomainSummary", crossDomainSummary);  // 方案A: 独立 key
// 而不是:
// enrichedInput = userInput + " (跨域上下文: " + summary + ")";  // 方案B: 拼接
```

原因：
- 方案A 更干净，_crossDomainSummary 在 AbstractGraphConfig 公共 key 中已注册
- L2 图团队可以选择在 prompt 中引用或忽略
- 不干扰 _latestUserInput 的原始语义

### 8.2 input Map 的构建职责

**决策：由 AbstractDomainService 构建，GES 只负责执行。**

```
职责划分:
  AbstractDomainService:  构建 input Map（messages, _latestUserInput, 跨域数据）
  GraphExecutionEngine:   接收 input Map，执行 stream，返回结果
```

GES 保持纯粹的执行职责，不关心数据来源。这样 GES 不需要依赖 GlobalSessionStore。

### 8.3 Checkpoint 残留问题

不同 threadId 会产生多个 Checkpoint 残留在 MemorySaver 中：

```
MemorySaver@TransferGraph: {
  "user-1-TRANSFER-a3f2": Checkpoint{...},  // 第1次转账
  "user-1-TRANSFER-b7e1": Checkpoint{...},  // 第2次转账
  "user-1-TRANSFER-c9d4": Checkpoint{...},  // 第3次转账
}
```

**当前评估**：可接受。原因：
1. MemorySaver 是纯内存 HashMap，短期 session 内 Checkpoint 量很小（单 session 约 10-20 个）
2. session 结束时 BankController.clearSession() 可清理
3. 每个 Checkpoint 大小有限（Map<String, Object>，约 10 个 key）

**未来优化**（非本次范围）：
- 将 MemorySaver 提升为 Spring Bean，暴露清理 API
- 在 clearSession 时按 sessionId 前缀批量删除
- 或在 COMPLETED 后主动清理对应 threadId 的 Checkpoint

### 8.4 同域"记忆"的替代方案

旧方案中同 threadId 自动合并提供了同域"记忆"（如"再转一笔"自动继承上次参数）。
新方案中每次新执行是干净启动，同域记忆需要显式处理。

**方案**：由 L1 层 input rewriting 负责。

```
用户: "再转一笔"
L1: ContextRouter → FOLLOW (如果有 activeAgent) → resumeGraph (用存储的 threadId)
    → Checkpoint 中已有上次的 transfer.receiver, transfer.amount

或者: 如果 activeAgent 已清除 (上次已完成)
L1: ContextRouter → SWITCH → executeNewAgent
    → 干净启动 → extractParams 提取 → 无明确参数 → 追问
    → 这是正确行为！每次新转账应确认参数
```

**关键**：如果用户真的想"再转一笔给同一人"，应该走 FOLLOW（resume）路径，
而不是新执行。这由 L1 的 ContextRouter 路由决策保证。

---

## 9. 风险评估

| # | 风险 | 影响 | 缓解 |
|---|------|------|------|
| R1 | threadId 生成碰撞 | 两次执行用同一 threadId → Checkpoint 冲突 | 6位 hex 碰撞概率 1/16M，可忽略 |
| R2 | Checkpoint 残留内存泄漏 | 长期 session 积累大量 Checkpoint | 当前可接受，未来加清理机制 |
| R3 | 跨域 key 注入但 L2 KeyStrategyFactory 未注册 | 数据被框架静默丢弃，调试困惑 | 日志记录注入的 key 和实际进入 OverAllState 的 key |
| R4 | DomainService 按前缀提取时遗漏非标准命名的 key | 数据未写入 GlobalSessionStore | 强制前缀命名规范，代码审查确保 |
| R5 | Resume 时 threadId 找不到 | ActiveAgentInfo 过期被清除 | 与当前 sessionId 丢失问题相同，已有过期机制 |

---

## 10. 与旧设计的对比

| 维度 | 旧设计 (同 threadId + 回调) | 新设计 (不同 threadId + 注入) |
|------|---------------------------|------------------------------|
| threadId | sessionId（固定） | 每次新执行唯一 |
| 脏数据 | ✗ Checkpoint 自动合并 | ✓ 干净启动 |
| 跨域注入 | 隐式（拼到 input 字符串） | 显式（input Map 独立 key） |
| 写回方式 | L2 图 override 回调 | DomainService 按前缀提取 |
| L2 图改动 | 需要 override onGraphCompleted | 无需改动 |
| 新增域改动 | 加回调 + 注册 + 绑定 | 只加 getDomainPrefix 映射 |
| 组件依赖 | IntentRegistry 存 graphConfig | 不需要 |
| AppInitConfig | 绑定 graphConfig | 不需要 |
| 复杂度 | 较高（回调链 + 注册绑定） | 较低（注入提取，对称设计） |
