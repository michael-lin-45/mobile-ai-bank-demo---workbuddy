# Memory 架构分析

## 概览

本项目的 memory 层负责两类截然不同的持久化需求：

| 维度 | SessionStateStore | CheckpointSaver (MemorySaver) |
|------|-------------------|-------------------------------|
| **存什么** | 业务级状态（activeAgent、suspendedAgent、消歧等） | L2 Graph 的 OverAllState 快照 |
| **谁用** | L1 DomainService | L2 子图（StateGraph） |
| **生命周期** | 跨 L2 执行存活，session 级 | 单次 L2 图执行，resume 时读取 |
| **数据结构** | 自定义 Java 对象（泛型 V） | 框架定义的 Checkpoint（含 state map） |
| **隔离粒度** | namespace + sessionId | threadId（每次新执行生成唯一 ID） |

---

## 文件清单与职责

### 1. SessionStateStore.java — 业务状态存储接口

```
interface SessionStateStore<V>
  ├── get(namespace, key) → Optional<V>
  ├── put(namespace, key, value)
  ├── remove(namespace, key)
  ├── containsKey(namespace, key)
  ├── getAll(namespace) → Map<String, V>
  ├── clear(namespace)
  └── cleanExpired(namespace, isExpired)  // default 方法，带过期清理
```

**核心概念：namespace 隔离**

不同业务状态用不同的 namespace 隔离，key 通常是 sessionId：

| namespace 示例 | 值类型 V | 用途 |
|---|---|---|
| `active-agents:TransferService` | `ActiveAgentInfo` | 转账域当前活跃的子智能体 |
| `active-agents:WealthService` | `ActiveAgentInfo` | 理财域当前活跃的子智能体 |
| `suspended-agents:WealthService` | `Map<String, SuspendedInfo>` | 理财域挂起的子智能体 |
| `disambiguation:WealthService` | `DisambiguationState` | 理财域消歧状态 |

**注意**：之前的 `last-domains` namespace 已在本次重构中移除，lastDomain 改为存储在 `GlobalSessionContext` 的直接字段中。

### 2. InMemorySessionStateStore.java — 内存实现

```
ConcurrentHashMap<namespace, ConcurrentHashMap<key, V>>
```

- 双层 ConcurrentHashMap，namespace 级隔离
- 线程安全，无 TTL（过期由调用方在读取时判断）
- 单实例开发环境使用

### 3. RedisSessionStateStore.java — Redis 实现

```
Redis key: session-state:{namespace}:{key} → value JSON
```

- 每个 namespace+key 对应一个 Redis string key
- JSON 序列化/反序列化，通过 `TypeReference<V>` 支持泛型
- 默认 TTL 24 小时
- `getAll()` 使用 `KEYS` 命令扫描（生产环境注意数据量）
- 多实例部署 / 持久化场景使用

### 4. SessionStateStoreConfig.java — 业务状态存储配置

工厂模式，根据 `storage.type` 选择实现：

```
storage.type=in-memory (默认) → InMemorySessionStateStore
storage.type=redis           → RedisSessionStateStore
```

**工厂接口**：

```java
interface SessionStateStoreFactory {
    <V> SessionStateStore<V> create(String namespace, TypeReference<V> typeReference);
}
```

调用方（DomainServiceConfig）注入工厂，按需创建不同值类型的 store：

```java
SessionStateStore<ActiveAgentInfo> store = factory.create("active-agents:TransferService", new TypeReference<>() {});
```

### 5. CheckpointSaverConfig.java — Graph 检查点存储配置

工厂模式，根据 `storage.type` 选择实现：

```
storage.type=in-memory (默认) → MemorySaver（框架内置）
storage.type=redis           → RedisCheckpointSaver
```

**工厂接口**：

```java
@FunctionalInterface
interface CheckpointSaverFactory {
    BaseCheckpointSaver create();
}
```

**关键设计：每次调用 create() 返回独立实例**

不同的 L2 子图必须使用独立的 CheckpointSaver，否则 human-in-the-loop 的保存点会互相覆盖。工厂模式避免了单例共享问题。

### 6. RedisCheckpointSaver.java — Redis 检查点实现

```
Redis key: checkpoint:{threadId} → List<Checkpoint JSON>
```

- 每个 threadId 下维护一个有序的 checkpoint 列表（最新在尾部）
- `get()` 返回最新的 checkpoint（`LINDEX -1`）
- `put()` 追加到列表尾部（`RPUSH`）
- `release()` 删除整个 threadId 的 key
- 默认 TTL 24 小时
- Checkpoint 包含：id、nodeId、nextNodeId、state（Map）

---

## 三层状态体系

本项目存在三层状态，职责不同、生命周期不同：

### L0 — GlobalSessionContext（session 级）

```
GlobalSessionContext
├── OverAllState state         ← 全局状态，跨 L2 执行存活
├── CopyOnWriteArrayList messages  ← 对话历史（用户/助手一问一答）
├── lastDomain / lastDomainSetAt   ← 最近活跃领域 + 过期时间
└── BaseCheckpointSaver        ← 预留，当前未启用持久化
```

**数据流向**：
- 写入：BankController 写 messages/lastDomain
- 读取：DomainRouter 读 messages + lastDomain；AbstractDomainService 读 messages
- 注入：L1 调用 L2 前，`state.data()` 注入 L2 的 `_globalStateData`

### L1 — DomainService（session 级，通过 SessionStateStore）

```
SessionStateStore<ActiveAgentInfo>     ← 当前活跃子智能体（intent + threadId + 过期时间）
SessionStateStore<SuspendedInfo Map>   ← 挂起的子智能体（MultiSubAgent 专用）
SessionStateStore<DisambiguationState> ← 消歧状态（MultiSubAgent 专用）
```

**数据流向**：
- L1 Service 在 handle() 中读写这些状态
- 每次 L2 执行前设置 activeAgent（intent + threadId）
- resume 时从 activeAgent 取 threadId
- suspend 时保存到 suspendedAgentStore

### L2 — OverAllState + CheckpointSaver（thread 级）

```
OverAllState
├── messages: AppendStrategy     ← 子图输入流
├── _latestUserInput: ReplaceStrategy
├── _question: ReplaceStrategy
├── _paramName: ReplaceStrategy
├── transfer.receiver / amount / purpose
├── bill.timePeriod / expenseType
├── wealth.riskLevel / focusArea / productName
└── _isFinal / _outputContent / _outputType

BaseCheckpointSaver (per threadId)
└── Checkpoint: { id, nodeId, nextNodeId, state }
```

**数据流向**：
- executeGraph：传入 input map → OverAllState 初始化 → 节点逐步执行
- interruptBefore：CheckpointSaver 保存当前 state → 返回中断信号
- resumeGraph：从 CheckpointSaver 读取 checkpoint → 恢复 state → 从断点继续

---

## 关键交互时序

### 正常执行（无中断）

```
用户请求 → BankController
  ├── ctx.addUserMessage(input)           ← 写 messages
  ├── ctx.setLastDomain(domain)           ← 写 lastDomain
  ├── DomainRouter.route()                ← 读 messages + lastDomain
  ├── DomainService.handle()              ← 读 messages（L1 上下文）
  │   ├── contextRouter.route()           ← 读 messages
  │   ├── intentResolver.resolve()        ← 读 messages
  │   └── executeNewAgent()
  │       ├── buildGraphInput()           ← 注入 _globalStateData
  │       └── graphExecutionEngine.executeGraph()
  │           └── graph.stream(input)     ← L2 OverAllState 执行
  └── ctx.addAssistantMessage(reply)      ← 写 messages
```

### Human-in-the-loop（ask 节点中断 + 恢复）

```
第 1 次请求:
  executeGraph() → extractParams → paramRouter → askReceiver (interruptBefore)
    └── CheckpointSaver.put(checkpoint)   ← 保存 L2 state 快照
    └── 返回 INTERRUPTED chunk + _question

第 2 次请求（用户回答）:
  DomainService.handle()
    ├── contextRouter → FOLLOW（短回答）
    ├── resumeActiveAgent(threadId)        ← 从 activeAgentStore 取 threadId
    └── graphExecutionEngine.resumeGraph()
        ├── CheckpointSaver.get(threadId) ← 恢复 L2 state 快照
        └── graph.stream(input, checkpoint) → askReceiver → paramRouter → ...
```

---

## storage.type 切换矩阵

| storage.type | SessionStateStore | CheckpointSaver | 适用场景 |
|---|---|---|---|
| `in-memory`（默认） | InMemorySessionStateStore | MemorySaver | 单实例开发 |
| `redis` | RedisSessionStateStore | RedisCheckpointSaver | 多实例部署 / 持久化 |

切换方式：修改 `application.yml` 中 `storage.type`，重启即可，无需改 Java 代码。

---

## 当前 CheckpointSaver 使用情况

| 使用者 | 实例来源 | 用途 |
|---|---|---|
| GlobalSessionContext | `checkpointSaverFactory.create()` | 预留，当前未启用持久化 |
| TransferGraphConfig | `checkpointSaverFactory.create()` | 转账图中断/恢复 |
| BillQueryGraphConfig | `checkpointSaverFactory.create()` | 账单图中断/恢复 |
| WealthConsultGraphConfig | `checkpointSaverFactory.create()` | 理财咨询图中断/恢复 |
| WealthInterpretGraphConfig | `checkpointSaverFactory.create()` | 理财解读图中断/恢复 |

每个 L2 图都拥有独立的 CheckpointSaver 实例，通过不同的 threadId 隔离数据，避免 human-in-the-loop 保存点互相覆盖。
