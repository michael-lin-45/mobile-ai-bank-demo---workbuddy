# Memory 架构文档

> 本文档描述手机银行智能助手项目的完整 Memory 架构，包括状态模型、存储抽象、切换机制、数据流和实现细节。

---

## 目录

1. [架构概览](#1-架构概览)
2. [三层状态体系](#2-三层状态体系)
3. [GlobalSessionContext.state 全景图](#3-globalsessioncontextstate-全景图)
4. [L2 子图与 GlobalSessionContext 的双向数据流](#4-l2-子图与-globalsessioncontext-的双向数据流)
5. [核心数据模型](#5-核心数据模型)
6. [存储抽象与切换机制](#6-存储抽象与切换机制)
7. [类关系图](#7-类关系图)
8. [文件/类详细清单](#8-文件类详细清单)
9. [DomainState 注册机制](#9-domainstate-注册机制)
10. [KeyStrategy 体系](#10-keystrategy-体系)
11. [数据流详解](#11-数据流详解)
12. [关键交互时序](#12-关键交互时序)
13. [Redis 存储细节](#13-redis-存储细节)
14. [配置参考](#14-配置参考)

---

## 1. 架构概览

本项目的 Memory 层管理两类截然不同的持久化需求：

| 维度 | GlobalSessionStateStore (Session 级) | SubGraphCheckpointSaver (Thread 级) |
|------|--------------------------------------|--------------------------------------|
| **存什么** | 全局会话状态：对话历史、lastDomain、各域 DomainState | L2 子图的 OverAllState 快照 |
| **谁用** | L0 BankController、L1 DomainService | L2 子图 (StateGraph) |
| **生命周期** | 跨 L2 执行存活，session 级，默认 24h TTL | 单次 L2 图执行，resume 时读取，完成后释放 |
| **数据结构** | OverAllState (带 KeyStrategy) | 框架定义的 Checkpoint (含 state map) |
| **隔离粒度** | sessionId | threadId (每次新执行生成唯一 ID) |
| **存储后端** | GlobalSessionRepository (InMemory / Redis) | BaseCheckpointSaver (MemorySaver / Redis) |

### 架构图

```
┌──────────────────────────────────────────────────────────────────────┐
│                         storage.type 控制                            │
│                    ┌──────────┬──────────┐                           │
│                    │in-memory │  redis   │                           │
│                    └────┬─────┴────┬─────┘                           │
│                         │          │                                  │
│  ┌──────────────────────┼──────────┼──────────────────────────┐     │
│  │ Session 级存储        │          │                          │     │
│  │  GlobalSessionRepository │          │                          │     │
│  │  ┌────────────────────┴──┐  ┌───┴─────────────────────┐   │     │
│  │  │ InMemoryGlobalSession │  │ RedisGlobalSession      │   │     │
│  │  │ Storage               │  │ Storage                 │   │     │
│  │  │ (ConcurrentHashMap)   │  │ (StringRedisTemplate)   │   │     │
│  │  └───────────────────────┘  └─────────────────────────┘   │     │
│  └────────────────────────────────────────────────────────────┘     │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │ Thread 级存储 (L2 Checkpoint)                              │     │
│  │  SubGraphCheckpointSaverFactory                            │     │
│  │  ┌────────────────────┴──┐  ┌───┴─────────────────────┐   │     │
│  │  │ MemorySaver           │  │ RedisSubGraphCheckpoint  │   │     │
│  │  │ (框架内置 HashMap)     │  │ Saver                   │   │     │
│  │  │                       │  │ (StringRedisTemplate)    │   │     │
│  │  └───────────────────────┘  └─────────────────────────┘   │     │
│  └────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. 三层状态体系

本项目存在三层状态，职责不同、生命周期不同：

```
┌────────────────────────────────────────────────────────────────────┐
│ L0 — GlobalSessionContext (Session 级)                             │
│                                                                    │
│ GlobalSessionContext                                               │
│ └── OverAllState state          ← 所有会话状态统一存储在此         │
│     ├── messages (AppendStrategy)       ← 用户/助手一问一答        │
│     ├── _lastDomain (ReplaceStrategy)   ← 最近活跃领域+过期时间    │
│     ├── _transferState (ReplaceStrategy) ← 转账域 DomainState      │
│     ├── _billState (ReplaceStrategy)     ← 账单域 DomainState      │
│     └── _wealthState (ReplaceStrategy)   ← 理财域 DomainState      │
│                                                                    │
│ 生命周期: 与 sessionId 绑定, 跨 L2 执行存活                        │
│ 写入: BankController 写 messages/lastDomain                        │
│ 读取: DomainRouter 读 messages + lastDomain                        │
│       AbstractDomainService 读 messages (L1 上下文)                │
│ 注入: L1 调用 L2 前, state.data() → L2 的 _globalStateData        │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│ L1 — DomainService (Session 级, 通过 DomainState)                  │
│                                                                    │
│ DomainState (每个域一个 OverAllState key)                           │
│ ├── activeAgent: ActiveAgentInfo      ← 当前活跃子图               │
│ │   ├── intent: "TRANSFER"                                      │
│ │   ├── threadId: "sess1-TRANSFER-a3f2b1"                        │
│ │   ├── expiresAt: 1717400000000                                │
│ │   └── lastQuestion: "请问您要转给谁？"                          │
│ ├── suspendedAgents: Map<String, SuspendedInfo>  ← 挂起子图(Multi) │
│ ├── disambiguation: DisambiguationState          ← 消歧状态(Multi) │
│ └── subAgents: Map<String, SubAgentState>        ← L2 业务数据快照 │
│                                                                    │
│ 生命周期: 与 session 绑定, 存储在 GlobalSessionContext.state 中     │
│ 写入: AbstractDomainService 通过 ctx.updateDomainState()           │
│ 读取: AbstractDomainService 通过 ctx.getDomainState()              │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│ L2 — OverAllState + SubGraphCheckpointSaver (Thread 级)            │
│                                                                    │
│ OverAllState (每个 L2 子图独立)                                     │
│ ├── messages: AppendStrategy             ← 子图输入流              │
│ ├── _latestUserInput: ReplaceStrategy    ← 最新用户输入            │
│ ├── _question: ReplaceStrategy           ← 当前提问               │
│ ├── _paramName: ReplaceStrategy          ← 路由目标               │
│ ├── _cancelSignal: ReplaceStrategy       ← 取消信号               │
│ ├── _globalStateData: ReplaceStrategy    ← L1 注入的全局状态       │
│ ├── _outputContent / _outputType / _isFinal  ← 输出               │
│ └── 业务参数 (transfer.receiver, bill.timePeriod 等)               │
│                                                                    │
│ BaseCheckpointSaver (per threadId)                                 │
│ └── Checkpoint: { id, nodeId, nextNodeId, state }                  │
│                                                                    │
│ 生命周期: 与 threadId 绑定, 单次图执行, 完成后 release             │
│ 中断: interruptBefore → SubGraphCheckpointSaver.put() 保存 state   │
│ 恢复: resumeGraph → SubGraphCheckpointSaver.get() 读取 checkpoint  │
└────────────────────────────────────────────────────────────────────┘
```

### 三层状态对比

| 对比维度 | L0 GlobalSessionContext | L1 DomainState | L2 OverAllState + Checkpoint |
|----------|------------------------|----------------|------------------------------|
| 存储位置 | GlobalSessionContext.state | L0 的 OverAllState 内 | 子图独立的 OverAllState |
| 绑定键 | sessionId | sessionId + stateKey | threadId |
| 生命周期 | Session 级, 24h TTL | Session 级, 随 L0 | Thread 级, 图完成后释放 |
| 存储后端 | GlobalSessionRepository | 同 L0 | BaseCheckpointSaver |
| KeyStrategy | 注册在 KeyStrategyFactory | ReplaceStrategy | 注册在子图 KeyStrategyFactory |
| 主要消费者 | BankController, DomainRouter | DomainService | L2 Graph 节点 |

---

## 3. GlobalSessionContext.state 全景图

GlobalSessionContext 持有一个 OverAllState，所有 Session 级状态统一存储在这个 state 中。下面是一次真实会话中 state.data() 的完整内容示例：

### 3.1 state.data() 完整数据示例

```json
{
  "messages": [
    "用户: 给张三转500",
    "助手: 请问您要转给谁？",
    "用户: 张三",
    "助手: 请问您要转多少金额？",
    "用户: 500",
    "助手: 已成功向张三转账500元"
  ],

  "_lastDomain": {
    "domain": "TRANSFER",
    "expireAt": 1717400000000
  },

  "_transferState": {
    "domain": "TRANSFER",
    "activeAgent": {
      "intent": "TRANSFER",
      "threadId": "sess1-TRANSFER-a3f2b1",
      "createdAt": 1717399700000,
      "expiresAt": 1717400900000,
      "lastQuestion": "请问您要转多少金额？"
    },
    "suspendedAgents": null,
    "disambiguation": null,
    "subAgents": {
      "TRANSFER": {
        "name": "TRANSFER",
        "interrupted": true,
        "nextNode": "askAmount",
        "data": {
          "receiver": "张三",
          "amount": null,
          "purpose": null
        }
      }
    }
  },

  "_billState": {
    "domain": "BILL",
    "activeAgent": null,
    "suspendedAgents": null,
    "disambiguation": null,
    "subAgents": {}
  },

  "_wealthState": {
    "domain": "WEALTH",
    "activeAgent": {
      "intent": "WEALTH_CONSULT",
      "threadId": "sess1-WEALTH_CONSULT-b7e4c2",
      "createdAt": 1717399900000,
      "expiresAt": 1717401100000,
      "lastQuestion": null
    },
    "suspendedAgents": {
      "WEALTH_INTERPRET": {
        "intent": "WEALTH_INTERPRET",
        "threadId": "sess1-WEALTH_INTERPRET-d2f8a1",
        "suspendedAt": 1717399850000,
        "expiresAt": 1717401050000
      }
    },
    "disambiguation": null,
    "subAgents": {
      "WEALTH_CONSULT": {
        "name": "WEALTH_CONSULT",
        "interrupted": false,
        "nextNode": null,
        "data": {
          "riskLevel": "稳健",
          "focusArea": "科技"
        }
      }
    }
  }
}
```

### 3.2 state 中每个 key 的来源和消费者

| key | KeyStrategy | 写入方 | 读取方 | 数据类型 | 说明 |
|-----|-------------|--------|--------|---------|------|
| `messages` | Append | BankController (addUserMessage/addAssistantMessage) | DomainRouter, ContextRouter, AbstractDomainService (formatRecentMessages) | `List<String>` | 对话历史，每条以"用户: "/"助手: "开头 |
| `_lastDomain` | Replace | DomainRouter (setLastDomain) | DomainRouter (getLastDomain) | `LastDomainEntry` record | 最近一次路由的领域 + 过期时间 |
| `_transferState` | Replace | AbstractDomainService (updateDomainState) | AbstractDomainService (getDomainState) | `DomainState` | 转账域的全部状态 |
| `_billState` | Replace | AbstractDomainService (updateDomainState) | AbstractDomainService (getDomainState) | `DomainState` | 账单域的全部状态 |
| `_wealthState` | Replace | AbstractDomainService (updateDomainState) | AbstractDomainService (getDomainState) | `DomainState` | 理财域的全部状态 |

**注意:** DomainState 是嵌套结构，一个 key 下包含了 activeAgent / suspendedAgents / disambiguation / subAgents 四个子结构。

### 3.3 DomainState 内部数据详解

以 `_transferState` (Single 域) 为例：

```
_transferState: DomainState
  ├── domain: "TRANSFER"                         ← 固定值，初始化时设置
  ├── activeAgent: ActiveAgentInfo | null        ← L2 执行中非null，完成后清null
  │     ├── intent: "TRANSFER"                   ← 子图意图
  │     ├── threadId: "sess1-TRANSFER-a3f2b1"    ← L2 Checkpoint 的唯一标识
  │     ├── createdAt: 1717399700000             ← 创建时间 (epoch ms)
  │     ├── expiresAt: 1717400900000             ← 过期时间 (默认 +20min)
  │     └── lastQuestion: "请问要转多少？"         ← interruptBefore 时的提问 (恢复后清null)
  ├── suspendedAgents: null                      ← Single 域不使用
  ├── disambiguation: null                       ← Single 域不使用
  └── subAgents: Map<String, SubAgentState>      ← L2 业务数据快照
        └── "TRANSFER": SubAgentState
              ├── name: "TRANSFER"
              ├── interrupted: true              ← 是否在中断状态
              ├── nextNode: "askAmount"          ← 中断后下一个要执行的节点
              └── data: { receiver: "张三" }     ← L2 子图自定义的业务数据
```

以 `_wealthState` (Multi 域) 为例：

```
_wealthState: DomainState
  ├── domain: "WEALTH"
  ├── activeAgent: ActiveAgentInfo               ← 当前活跃子图 (如 WEALTH_CONSULT)
  ├── suspendedAgents: Map<String, SuspendedInfo> ← 挂起的子图 (如 WEALTH_INTERPRET)
  │     └── "WEALTH_INTERPRET": SuspendedInfo
  │           ├── intent: "WEALTH_INTERPRET"
  │           ├── threadId: "sess1-WEALTH_INTERPRET-d2f8a1"
  │           ├── suspendedAt: 1717399850000
  │           └── expiresAt: 1717401050000
  ├── disambiguation: DisambiguationState | null ← 消歧状态 (用户在"咨询/解读"间选择时)
  │     └── groupId: "WEALTH"
  └── subAgents: Map<String, SubAgentState>
        ├── "WEALTH_CONSULT": { riskLevel: "稳健", focusArea: "科技" }
        └── "WEALTH_INTERPRET": { productName: "朝朝盈" }
```

---

## 4. L2 子图与 GlobalSessionContext 的双向数据流

L2 子图和 GlobalSessionContext 之间存在双向数据流：**L1→L2 注入** 和 **L2→L1 回写**。

### 4.1 方向一：L1 → L2 注入（_globalStateData）

L1 在执行或恢复 L2 子图前，将 GlobalSessionContext.state 的全量数据注入到 L2 的 OverAllState 中，key 为 `_globalStateData`。

#### 注入时机和代码

```
AbstractDomainService.buildGraphInput()           ← 首次执行
  │
  ├── input.put("messages", rewrittenInput)       ← L2 自己的 messages
  ├── input.put("_latestUserInput", rewrittenInput)
  │
  └── input.put("_globalStateData", ctx.data())   ← 注入全局状态快照
        │  ctx.data() = state.data() = 整个 OverAllState 的 Map
        │  包含: messages, _lastDomain, _transferState, _billState, _wealthState
        │
        └── L2 子图通过 state.value("_globalStateData") 即可读取全局上下文

GraphExecutionEngine.resumeBlocking()              ← 恢复执行
  │
  └── updateData.put("_globalStateData", globalStateData)
        │  globalStateData = ctx.data() = 重新获取最新全局状态
        │
        └── graph.updateState(config, updateData, null)
              // 框架将 _globalStateData 写入 L2 的 OverAllState
```

#### 注入的数据结构

`_globalStateData` 的值就是 `state.data()` 的完整 Map，即 Section 3.1 中展示的整个 JSON 对象。L2 子图可以通过如下方式读取：

```java
// L2 节点中读取全局状态
Map<String, Object> globalData = (Map<String, Object>) state.value("_globalStateData").orElse(Map.of());

// 例如：读取对话历史
List<String> messages = (List<String>) globalData.get("messages");

// 例如：读取转账域的状态
Map<String, Object> transferState = (Map<String, Object>) globalData.get("_transferState");
```

#### 注入时机的对比

| 场景 | 注入方式 | 代码位置 |
|------|---------|---------|
| 首次执行 (executeGraph) | `input.put("_globalStateData", ctx.data())` | `AbstractDomainService.buildGraphInput()` |
| 恢复执行 (resumeGraph, 非流式) | `updateData.put("_globalStateData", globalStateData)` | `GraphExecutionEngine.resumeBlocking()` |
| 恢复执行 (resumeGraph, 流式) | `updateData.put("_globalStateData", globalStateData)` | `GraphExecutionEngine.resumeStreaming()` |

**关键设计:** 每次 L2 执行或恢复前，都重新获取最新的 `ctx.data()`，确保 L2 看到的全局状态是最新的（而不是缓存旧值）。

### 4.2 方向二：L2 → L1 回写（extractSubAgentDataSnapshot + DomainState.subAgents）

L2 子图执行中断或完成时，可以将自己的业务数据打包回写到 GlobalSessionContext 的 `DomainState.subAgents` 中。

#### 回写机制架构

```
┌──────────────────────────────────────────────────────────────────────┐
│ L2 子图 (如 TransferGraphConfig)                                      │
│                                                                      │
│ extractSubAgentDataSnapshot(OverAllState state)                      │
│   └── 从 L2 的 OverAllState 提取业务数据:                             │
│       { receiver: "张三", amount: "500", purpose: "房租" }            │
│                                                                      │
│   返回 Map<String, Object>                                           │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│ L1 DomainService (调用方)                                             │
│                                                                      │
│ SubAgentState snapshot = new SubAgentState(                          │
│     "TRANSFER",           ← name: 子图意图                           │
│     true,                 ← interrupted: 是否在中断状态               │
│     "askAmount",          ← nextNode: 中断后下一个节点                │
│     extractedData         ← data: extractSubAgentDataSnapshot() 返回 │
│ );                                                                   │
│                                                                      │
│ ctx.updateDomainState("_transferState", ds ->                        │
│     ds.getSubAgents().put("TRANSFER", snapshot));                    │
│                                                                      │
│ → 写入 GlobalSessionContext.state._transferState.subAgents           │
└──────────────────────────────────────────────────────────────────────┘
```

#### 各子图的 extractSubAgentDataSnapshot 实现

| 子图 | 提取的 data 内容 | 实现代码 |
|------|----------------|---------|
| TransferGraph | `{receiver, amount, purpose}` | `getStringValue(state, "transfer.receiver")` → `data.put("receiver", v)` |
| BillQueryGraph | `{timePeriod, expenseType}` | `getStringValue(state, "bill.timePeriod")` → `data.put("timePeriod", v)` |
| WealthConsultGraph | `{riskLevel, focusArea}` | `getStringValue(state, "wealthConsult.riskLevel")` → `data.put("riskLevel", v)` |
| WealthInterpretGraph | `{productName}` | `getStringValue(state, "wealthInterpret.productName")` → `data.put("productName", v)` |

#### 当前实现状态

> ⚠️ **接口已预留，流程尚未集成。**

目前 `extractSubAgentDataSnapshot()` 的 4 个子图实现已经写好，每个子图都声明了要提取哪些业务数据。但是 **L1 DomainService 中还没有调用此方法**——即 L2 的数据还没有实际回写到 GlobalSessionContext。

**原因：** 按照项目规划，L2→L1 的回写由 L2 开发团队自行决定何时集成。当前阶段 L1 已经通过 `_globalStateData` 注入获取了 L2 需要的全局上下文，L2 的回写是增强功能而非必需功能。

**集成方式（预留）：** L1 DomainService 在处理 L2 执行结果的 `doOnNext` 回调中，可以在 `INTERRUPTED` 或 `COMPLETE` 时调用 `graphConfig.extractSubAgentDataSnapshot(state)`，将返回值封装为 `SubAgentState` 写入 `DomainState.subAgents`。

#### L2 如何新增自定义数据回写

如果 L2 开发团队要让子图回写更多数据，只需修改 `extractSubAgentDataSnapshot()`：

```java
// TransferGraphConfig.java
@Override
protected Map<String, Object> extractSubAgentDataSnapshot(OverAllState state) {
    Map<String, Object> data = new HashMap<>();
    // 已有的业务参数
    String receiver = getStringValue(state, "transfer.receiver");
    if (receiver != null && !receiver.isEmpty()) data.put("receiver", receiver);
    Object amount = state.value("transfer.amount").orElse(null);
    if (amount != null) data.put("amount", amount.toString());
    String purpose = getStringValue(state, "transfer.purpose");
    if (purpose != null && !purpose.isEmpty()) data.put("purpose", purpose);

    // 新增自定义数据 (示例)
    // Boolean confirmed = (Boolean) state.value("transfer.confirmed").orElse(null);
    // if (confirmed != null) data.put("confirmed", confirmed);

    return data;
}
```

### 4.3 双向数据流总结图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     GlobalSessionContext.state                           │
│                                                                          │
│  messages ──────────────────────────────────────┐                        │
│  _lastDomain ───────────────────────────────────┤                        │
│  _transferState ────────────────────────────────┤                        │
│  _billState ────────────────────────────────────┤                        │
│  _wealthState ──────────────────────────────────┤                        │
│                                                  │                        │
│              ctx.data() ─── 完整 Map ────────────┤                        │
│                                                  │                        │
│  ┌───────────────────────────────────────────────┘                        │
│  │                         注入 (L1 → L2)                                 │
│  │  buildGraphInput() / resumeBlocking() / resumeStreaming()              │
│  │  input.put("_globalStateData", ctx.data())                             │
│  │                                                                        │
│  ▼                                                                        │
│  ┌────────────────────────────────────────────────────────────┐           │
│  │ L2 子图 OverAllState                                       │           │
│  │                                                            │           │
│  │ _globalStateData ← 全局状态快照 (只读, L2 按需读取)         │           │
│  │ messages, _latestUserInput ← L2 自己的输入流               │           │
│  │ transfer.receiver, bill.timePeriod 等 ← L2 自己的业务参数  │           │
│  │ _question, _paramName, _cancelSignal ← L2 路由控制         │           │
│  │ _outputContent, _outputType, _isFinal ← L2 输出            │           │
│  │                                                            │           │
│  │ extractSubAgentDataSnapshot(state) ──────────────────────┐  │           │
│  │   提取 {receiver, amount, ...} 等业务数据                  │  │           │
│  └──────────────────────────────────────────────────────────┼──┘           │
│                                                             │              │
│  ┌──────────────────────────────────────────────────────────┘              │
│  │                     回写 (L2 → L1, 接口预留)                            │
│  │  SubAgentState snapshot = new SubAgentState(name, interrupted,          │
│  │                                              nextNode, extractedData)  │
│  │  ctx.updateDomainState("_transferState", ds ->                          │
│  │      ds.getSubAgents().put("TRANSFER", snapshot))                       │
│  │                                                                        │
│  │  → 写入 DomainState.subAgents                                          │
│  └────────────────────────────────────────────────────────────────────────│
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 5. 核心数据模型

### 5.1 DomainState — 统一的 L1 域状态

```java
@Data
public class DomainState implements Serializable {
    private String domain;                          // "TRANSFER", "BILL", "WEALTH"
    private ActiveAgentInfo activeAgent;            // 当前活跃子图
    private Map<String, SuspendedInfo> suspendedAgents;  // 挂起子图 (仅 Multi)
    private DisambiguationState disambiguation;     // 消歧状态 (仅 Multi)
    private Map<String, SubAgentState> subAgents;   // L2 业务数据快照
}
```

**设计要点：**
- **Single 域** (TRANSFER, BILL): `suspendedAgents=null`, `disambiguation=null`，只有 `activeAgent`
- **Multi 域** (WEALTH): 全部字段可用，支持子图切换、挂起、消歧
- 实现 `Serializable`，Redis 序列化需要
- 存储在 GlobalSessionContext 的 OverAllState 中，key 如 `_transferState`

### 5.2 ActiveAgentInfo — 当前活跃子图

```java
@Data
public class ActiveAgentInfo implements Serializable {
    private String intent;       // "TRANSFER", "WEALTH_CONSULT"
    private String threadId;     // L2 子图的 threadId
    private long createdAt;      // 创建时间 (epoch millis)
    private long expiresAt;      // 过期时间 (epoch millis)
    private String lastQuestion; // 中断时的提问内容
}
```

**关键方法：** `isExpired()` — 过期后自动清理

### 5.3 SuspendedInfo — 挂起的子图 (仅 Multi)

```java
@Data
public class SuspendedInfo implements Serializable {
    private String intent;       // "WEALTH_CONSULT"
    private String threadId;     // L2 子图的 threadId
    private long suspendedAt;    // 挂起时间 (epoch millis)
    private long expiresAt;      // 过期时间 (epoch millis)
}
```

### 5.4 DisambiguationState — 意图消歧 (仅 Multi)

```java
@Data
public class DisambiguationState implements Serializable {
    private String groupId;      // 消歧意图组 ID, 如 "WEALTH"
}
```

### 5.5 SubAgentState — L2 子图数据快照

```java
@Data
public class SubAgentState implements Serializable {
    private String name;                  // 子图名称: "TRANSFER"
    private boolean interrupted;          // 是否在中断状态
    private String nextNode;              // 中断后下一个节点
    private Map<String, Object> data;     // 子图业务数据
}
```

**data 字段示例：**

| 子图 | data 内容 |
|------|----------|
| TransferGraph | `{receiver: "张三", amount: "500", purpose: "房租"}` |
| BillQueryGraph | `{timePeriod: "上个月", expenseType: "支出"}` |
| WealthConsultGraph | `{riskLevel: "稳健", focusArea: "科技"}` |
| WealthInterpretGraph | `{productName: "朝朝盈"}` |

### 5.6 LastDomainEntry — 最近路由领域

```java
public record LastDomainEntry(String domain, long expireAt) implements Serializable {
    boolean isExpired() { return System.currentTimeMillis() > expireAt; }
}
```

---

## 6. 存储抽象与切换机制

### 6.1 为什么引入 GlobalSessionRepository

**重构前的问题：** `GlobalSessionStateStore` 内部直接 `new ConcurrentHashMap<>()` 存储所有 GlobalSessionContext，与 InMemory 实现强绑定。如果要在生产环境多实例部署，所有 session 状态需要共享到 Redis，但代码里没有替换点。

**引入 GlobalSessionRepository 的目标：** 将"用什么存"这个决策从 `GlobalSessionStateStore` 中抽离出来，让上层只依赖接口，底层实现通过配置切换：

```
重构前:                              重构后:
GlobalSessionStateStore              GlobalSessionStateStore
  │                                    │
  └── new ConcurrentHashMap<>()        └── GlobalSessionRepository (接口)
  (写死 InMemory)                            ├── InMemory (ConcurrentHashMap)
                                             └── Redis (StringRedisTemplate)
```

**设计约束：**
- `GlobalSessionRepository` 是纯粹的后端接口，只有 3 个方法：`get` / `put` / `remove`
- `GlobalSessionStateStore` 不关心底层是 InMemory 还是 Redis，只通过接口操作
- 切换实现只需改配置，`GlobalSessionStateStore` 的代码完全不动

### 6.2 切换原理总览

整个 Memory 层通过 **一个配置项** `storage.type` 统一控制 **两组** 存储实现：

```yaml
# application.yml
storage:
  type: in-memory    # in-memory | redis
```

**改这一个值，重启即可，零代码改动。** 两组存储同步切换：

| 存储组 | 接口 | in-memory 实现 | redis 实现 | 受影响的组件 |
|--------|------|---------------|-----------|-------------|
| Session 级 | `GlobalSessionRepository` | `InMemoryGlobalSessionRepository` | `RedisGlobalSessionRepository` | GlobalSessionStateStore → 所有 L0/L1 状态 |
| Thread 级 | `BaseCheckpointSaver` | `MemorySaver` | `RedisSubGraphCheckpointSaver` | 4 个 L2 子图的 interruptBefore |

### 6.3 完整调用链：从配置到存储实例

#### 路径1: Session 级存储 (GlobalSessionRepository)

```
application.yml
  │  storage.type=in-memory
  │
  ▼
GlobalSessionRepositoryConfig (@Configuration)
  │  @ConditionalOnProperty(name="storage.type", havingValue="in-memory", matchIfMissing=true)
  │  → 激活 inMemoryStorage() 方法
  │
  ▼
new InMemoryGlobalSessionRepository()
  │  内部: ConcurrentHashMap<String, GlobalSessionContext>
  │
  ▼
Spring 容器注入到 GlobalSessionStateStore
  │  GlobalSessionStateStore(GlobalSessionRepository repository)
  │
  ▼
所有 GlobalSessionStateStore.getOrCreate() 调用
└── repository.get(sessionId)   ← 实际走 InMemory / Redis
└── repository.put(sessionId, ctx)
```

```
application.yml
  │  storage.type=redis
  │
  ▼
GlobalSessionRepositoryConfig (@Configuration)
  │  @ConditionalOnProperty(name="storage.type", havingValue="redis")
  │  → 激活 redisStorage() 方法
  │
  ▼
new RedisGlobalSessionRepository(redisTemplate, objectMapper, keyStrategyFactory)
  │  内部: StringRedisTemplate → 序列化/反序列化 OverAllState.data()
  │  Redis key: "global-session:{sessionId}"
  │
  ▼
Spring 容器注入到 GlobalSessionStateStore
  │  (同上，GlobalSessionStateStore 完全不知道底层换了)
```

#### 路径2: Thread 级存储 (SubGraphCheckpointSaverFactory)

```
application.yml
  │  storage.type=in-memory
  │
  ▼
SubGraphCheckpointSaverConfig (@Configuration)
  │  @ConditionalOnProperty(name="storage.type", havingValue="in-memory", matchIfMissing=true)
  │  → 激活 inMemorySubGraphCheckpointSaverFactory() 方法
  │
  ▼
SubGraphCheckpointSaverFactory = MemorySaver::new
  │  每次调用 factory.create() → new MemorySaver()
  │  内部: HashMap<String, List<Checkpoint>>
  │
  ▼
注入到 AbstractGraphConfig 及 4 个子图
  │  每个子图编译时调用 subGraphCheckpointSaverFactory.create()
  │  → 得到独立的 MemorySaver 实例
```

```
application.yml
  │  storage.type=redis
  │
  ▼
SubGraphCheckpointSaverConfig (@Configuration)
  │  @ConditionalOnProperty(name="storage.type", havingValue="redis")
  │  → 激活 redisSubGraphCheckpointSaverFactory() 方法
  │
  ▼
SubGraphCheckpointSaverFactory = () -> new RedisSubGraphCheckpointSaver(redisTemplate, om)
  │  每次调用 factory.create() → new RedisSubGraphCheckpointSaver(...)
  │  内部: StringRedisTemplate → Redis List + JSON 序列化
  │  Redis key: "checkpoint:{threadId}"
  │
  ▼
注入到 AbstractGraphConfig 及 4 个子图
  │  (同上，AbstractGraphConfig 完全不知道底层换了)
```

### 6.4 Spring Boot 自动切换原理

切换机制基于 Spring Boot 的 `@ConditionalOnProperty` 注解实现：

#### 组1: GlobalSessionRepositoryConfig (Session 级)

```java
@Configuration
public class GlobalSessionRepositoryConfig {

    @Bean("inMemoryGlobalSessionRepository")
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public GlobalSessionRepository inMemoryStorage() {
        return new InMemoryGlobalSessionRepository();  // ConcurrentHashMap
    }

    @Bean("redisGlobalSessionRepository")
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public GlobalSessionRepository redisStorage(RedisConnectionFactory cf, ObjectMapper om,
                                              @Qualifier("globalKeyStrategyFactory") KeyStrategyFactory ksf) {
        return new RedisGlobalSessionRepository(redisTemplate, om, ksf);
    }
}
```

#### 组2: SubGraphCheckpointSaverConfig (L2 Checkpoint)

```java
@Configuration
public class SubGraphCheckpointSaverConfig {

    @Bean("inMemorySubGraphCheckpointSaverFactory")
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public SubGraphCheckpointSaverFactory inMemorySubGraphCheckpointSaverFactory() {
        return MemorySaver::new;  // 框架内置 InMemory
    }

    @Bean("redisSubGraphCheckpointSaverFactory")
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public SubGraphCheckpointSaverFactory redisSubGraphCheckpointSaverFactory(
            RedisConnectionFactory cf, ObjectMapper om) {
        return () -> new RedisSubGraphCheckpointSaver(redisTemplate, om);
    }
}
```

### 6.5 @ConditionalOnProperty 工作流程

```
应用启动
  │
  ├── 读取 application.yml → storage.type=in-memory
  │
  ├── Spring 扫描 @Configuration 类
  │
  ├── GlobalSessionRepositoryConfig:
  │   ├── @ConditionalOnProperty(name="storage.type", havingValue="in-memory", matchIfMissing=true)
  │   │   → 匹配! 创建 InMemoryGlobalSessionRepository Bean
  │   └── @ConditionalOnProperty(name="storage.type", havingValue="redis")
  │       → 不匹配, 跳过
  │
  └── SubGraphCheckpointSaverConfig:
      ├── @ConditionalOnProperty(name="storage.type", havingValue="in-memory", matchIfMissing=true)
      │   → 匹配! 创建 inMemorySubGraphCheckpointSaverFactory Bean
      └── @ConditionalOnProperty(name="storage.type", havingValue="redis")
          → 不匹配, 跳过
```

**关键参数说明：**
- `matchIfMissing = true`: 当配置文件中没有 `storage.type` 时，视为 `in-memory`，即默认 InMemory
- `havingValue`: 必须精确匹配才激活，`in-memory` 和 `redis` 互斥，只有一个会被创建

### 6.6 切换矩阵

| `storage.type` | Session 级存储 (GlobalSessionRepository) | Thread 级存储 (SubGraphCheckpointSaver) | 适用场景 |
|---|---|---|---|
| `in-memory` (默认) | `InMemoryGlobalSessionRepository` (ConcurrentHashMap) | `MemorySaver` (框架内置 HashMap) | 单实例开发 |
| `redis` | `RedisGlobalSessionRepository` (StringRedisTemplate) | `RedisSubGraphCheckpointSaver` (StringRedisTemplate) | 多实例部署 / 持久化 |

### 6.7 Redis 模式的依赖条件

切换到 `redis` 时，需要确保：

1. **Redis 服务可用**: `storage.redis.host` 和 `storage.redis.port` 正确
2. **Spring Data Redis 自动配置生效**: 项目依赖了 `spring-boot-starter-data-redis`，Spring Boot 自动创建 `RedisConnectionFactory` Bean
3. **密码可选**: `storage.redis.password` 不配置时默认无密码

```yaml
# 切换到 Redis 的最小配置
storage:
  type: redis
  redis:
    host: your-redis-host
    port: 6379
    password: your-password  # 可选
```

### 6.8 RedisGlobalSessionRepository 的特殊处理

Redis 实现比 InMemory 多了一个关键步骤：**反序列化时必须重新注册 KeyStrategy**。

原因：InMemory 模式下 `GlobalSessionContext` 对象一直存活在内存中，`OverAllState` 的 KeyStrategy 只在创建时注册一次。但 Redis 模式下，每次 `get()` 都是从 JSON 反序列化重建对象：

```java
// RedisGlobalSessionRepository.get()
public GlobalSessionContext get(String sessionId) {
    String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
    Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});

    // 关键: 必须重建 OverAllState 并注册 KeyStrategy
    OverAllState state = new OverAllState();
    state.registerKeyAndStrategy(keyStrategyFactory.apply());  // ← 必不可少
    state.updateState(data);                                    // 才能正确恢复

    return new GlobalSessionContext(sessionId, state);
}
```

如果不注册 KeyStrategy，`state.updateState(data)` 会把 `messages` 当作 ReplaceStrategy（覆盖而非追加），导致对话历史丢失。因此 `RedisGlobalSessionRepository` 的构造函数需要注入 `KeyStrategyFactory`：

```java
public RedisGlobalSessionRepository(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   KeyStrategyFactory keyStrategyFactory) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.keyStrategyFactory = keyStrategyFactory;  // 反序列化时用
}
```

`InMemoryGlobalSessionRepository` 不需要 KeyStrategyFactory，因为对象始终在内存中，不需要重建。

---

## 7. 类关系图

### 7.1 存储层类图

```
┌─────────────────────────────────────────────────────────────────┐
│                     GlobalSessionRepository (接口)                  │
│  + get(sessionId): GlobalSessionContext                         │
│  + put(sessionId, ctx)                                          │
│  + remove(sessionId)                                            │
└──────────────┬──────────────────────────┬───────────────────────┘
               │                          │
       ┌───────┴───────┐          ┌───────┴───────┐
       │ InMemory      │          │ Redis         │
       │ GlobalSession │          │ GlobalSession │
       │ Storage       │          │ Storage       │
       ├───────────────┤          ├───────────────┤
       │ ConcurrentHashMap│      │ StringRedis   │
       │ <String,       │          │ Template      │
       │  GlobalSession │          │ ObjectMapper  │
       │  Context>      │          │ KeyStrategy   │
       │ store          │          │ Factory       │
       └───────────────┘          └───────────────┘

┌─────────────────────────────────────────────────────────────────┐
│              BaseCheckpointSaver (框架接口)                       │
│  + list(config): Collection<Checkpoint>                         │
│  + get(config): Optional<Checkpoint>                            │
│  + put(config, checkpoint): RunnableConfig                      │
│  + release(config): Tag                                         │
└──────────────┬──────────────────────────┬───────────────────────┘
               │                          │
       ┌───────┴───────┐          ┌───────┴───────┐
       │ MemorySaver   │          │ RedisSubGraph │
       │ (框架内置)     │          │ Checkpoint    │
       │               │          │ Saver         │
       ├───────────────┤          ├───────────────┤
       │ HashMap       │          │ StringRedis   │
       │ <threadId,    │          │ Template      │
       │  List<Check   │          │ ObjectMapper  │
       │  point>>      │          │               │
       └───────────────┘          └───────────────┘
```

### 7.2 核心管理类关系

```
BankController
  │
  ├── DomainRouter (读 ctx.messages + ctx._lastDomain)
  │
  └── GlobalSessionStateStore ←── GlobalSessionRepository (接口)
        │                            ├── InMemory / Redis 实现
        │
        ├── getOrCreate(sessionId)
        │     └── GlobalSessionContext
        │           └── OverAllState state
        │                 ├── messages (AppendStrategy)
        │                 ├── _lastDomain (ReplaceStrategy)
        │                 ├── _transferState (ReplaceStrategy → DomainState)
        │                 ├── _billState (ReplaceStrategy → DomainState)
        │                 └── _wealthState (ReplaceStrategy → DomainState)
        │
        └── clearSession(sessionId)

AbstractDomainService
  │
  ├── globalSessionStore: GlobalSessionStateStore
  │
  ├── getOwnActiveAgent(sessionId) ─→ ctx.getDomainState(stateKey).getActiveAgent()
  ├── setOwnActiveAgent(sessionId) ─→ ctx.updateDomainState(stateKey, ds -> ...)
  ├── getFormattedChatHistory() ──→ ctx.formatRecentMessages(l1DomainPairs)
  │
  └── executeNewAgent(sessionId, intent, input)
        ├── buildGraphInput() ─→ ctx.data() → input._globalStateData
        └── graphExecutionEngine.executeGraph(graph, intent, input, threadId)
              │
              └── SubGraphCheckpointSaverFactory.create()
                    ├── InMemory: MemorySaver::new
                    └── Redis: new RedisSubGraphCheckpointSaver(...)
```

### 7.3 DomainState 注册链

```
DomainServiceConfig                KeyStrategyFactoryConfig
  │                                  │
  ├── @Bean transferDomainStateAware │
  │   DomainStateAware.of(           │
  │     "_transferState","TRANSFER") │
  │                                  │
  ├── @Bean billDomainStateAware     │  @Bean globalKeyStrategyFactory
  │   DomainStateAware.of(           │  (List<DomainStateAware> providers)
  │     "_billState", "BILL")        │    │
  │                                  │    ├── 公共 keys: messages, _lastDomain, ...
  ├── @Bean wealthDomainStateAware   │    │
  │   DomainStateAware.of(           │    └── for (provider : providers)
  │     "_wealthState","WEALTH")     │          strategies.put(
  │                                  │            provider.getStateKey(),     ← "_transferState"
  │                                  │            provider.getStateStrategy() ← ReplaceStrategy
  │         Spring 自动收集 ──────────┘          )
  │                                  │
  │                                  │  return () → strategies
  │                                  │
  │                                  └── GlobalSessionStateStore 注入此 Bean
                                       │
                                       └── new OverAllState() → state.registerKeyAndStrategy(factory.apply())
```

---

## 8. 文件/类详细清单

### 8.1 memory/ 包 — 核心管理类

| 文件 | 类/接口 | 作用 | 典型用法 | 关键方法 |
|------|---------|------|---------|---------|
| `GlobalSessionContext.java` | `GlobalSessionContext` | 全局会话上下文，封装 OverAllState 的所有读写操作，是对 OverAllState 的业务层门面 | `ctx.addUserMessage("转账")`, `ctx.getDomainState("_transferState")`, `ctx.formatRecentMessages(6)` | `addUserMessage()`, `addAssistantMessage()`, `formatRecentMessages()`, `setLastDomain()`, `getLastDomain()`, `getDomainState()`, `updateDomainState()`, `data()`, `value()`, `clear()` |
| `GlobalSessionStateStore.java` | `GlobalSessionStateStore` (@Component) | Session 级状态管理器，负责创建/获取/清理 GlobalSessionContext，内部委托 GlobalSessionRepository 做持久化 | `store.getOrCreate(sessionId)` → 获取或创建 ctx, `store.clearSession(sessionId)` → 清理 | `getOrCreate(sessionId)`, `clearSession(sessionId)` |
| `GlobalSessionRepository.java` | `GlobalSessionRepository` (接口) | 存储后端抽象，将"用什么存"的决策从 GlobalSessionStateStore 中解耦。只定义 get/put/remove 三个方法 | `repository.get(sessionId)`, `repository.put(sessionId, ctx)` | `get(sessionId)`, `put(sessionId, ctx)`, `remove(sessionId)` |
| `GlobalSessionRepositoryConfig.java` | `GlobalSessionRepositoryConfig` (@Configuration) | 根据 `storage.type` 用 `@ConditionalOnProperty` 选择激活哪个 GlobalSessionRepository 实现 | 无直接调用，Spring 容器自动根据配置激活对应 Bean | `inMemoryRepository()` → InMemory, `redisRepository()` → Redis |
| `DomainStateAware.java` | `DomainStateAware` (接口) | 域状态注册接口，声明每个域在 OverAllState 中的 key 和策略。用轻量级匿名 Bean 注册，与 DomainService 解耦，避免循环依赖 | `DomainStateAware.of("_transferState", "TRANSFER")` | `getStateKey()`, `getStateStrategy()`, `initialState()`, `static of(stateKey, domain)` |
| `KeyStrategyFactoryConfig.java` | `KeyStrategyFactoryConfig` (@Configuration) | 全局 KeyStrategyFactory Bean 定义。自动收集所有 DomainStateAware 实现，将公共 key + 域 key 统一注册 | 无直接调用，Spring 注入到 GlobalSessionStateStore 和 RedisGlobalSessionRepository | `globalKeyStrategyFactory(List<DomainStateAware>)` |
| `SubGraphCheckpointSaverConfig.java` | `SubGraphCheckpointSaverConfig` (@Configuration) + `SubGraphCheckpointSaverFactory` (内部接口) | L2 子图 Checkpoint 工厂配置。根据 `storage.type` 选择 InMemory/Redis 的工厂 Bean。工厂模式：每次 `create()` 返回独立实例，避免多图共享 checkpoint 覆盖 | `factory.create()` → 每个图编译时创建独立的 saver | `inMemorySubGraphCheckpointSaverFactory()`, `redisSubGraphCheckpointSaverFactory()`, `SubGraphCheckpointSaverFactory.create()` |

### 8.2 memory/model/ 包 — 数据模型

| 文件 | 类 | 作用 | 典型用法 | 关键字段 |
|------|-----|------|---------|---------|
| `DomainState.java` | `DomainState` (Serializable) | 统一的 L1 域状态模型，Single/Multi 域共用。存储在 OverAllState 中，key 如 `_transferState` | `ctx.updateDomainState("_transferState", ds -> ds.setActiveAgent(...))` | `domain`, `activeAgent`, `suspendedAgents`, `disambiguation`, `subAgents` |
| `ActiveAgentInfo.java` | `ActiveAgentInfo` (Serializable) | 当前活跃子图信息，记录 L2 子图的 intent 和 threadId，以及中断时的提问内容 | `new ActiveAgentInfo("TRANSFER", threadId, now, expiresAt)` | `intent`, `threadId`, `createdAt`, `expiresAt`, `lastQuestion` |
| `SuspendedInfo.java` | `SuspendedInfo` (Serializable) | 挂起的子图信息（仅 Multi 域），当用户切换意图时，当前子图被挂起 | `new SuspendedInfo("WEALTH_CONSULT", threadId, now, expiresAt)` | `intent`, `threadId`, `suspendedAt`, `expiresAt` |
| `DisambiguationState.java` | `DisambiguationState` (Serializable) | 意图消歧状态（仅 Multi 域），记录当前处于哪个消歧意图组 | `new DisambiguationState("WEALTH")` | `groupId` |
| `SubAgentState.java` | `SubAgentState` (Serializable) | L2 子图业务数据快照，由各子图 `extractSubAgentDataSnapshot()` 生成 | `new SubAgentState("TRANSFER", true, "askReceiver", data)` | `name`, `interrupted`, `nextNode`, `data` |

### 8.3 memory/impl/ 包 — 存储实现

| 文件 | 类 | 作用 | 典型用法 | 底层存储 |
|------|-----|------|---------|---------|
| `InMemoryGlobalSessionRepository.java` | `InMemoryGlobalSessionRepository` | GlobalSessionRepository 的 InMemory 实现，基于 ConcurrentHashMap | `repository.get(sessionId)` → 从 Map 取, `repository.put(sessionId, ctx)` → 放入 Map | `ConcurrentHashMap<String, GlobalSessionContext>` |
| `RedisGlobalSessionRepository.java` | `RedisGlobalSessionRepository` | GlobalSessionRepository 的 Redis 实现，序列化 state.data() 到 Redis | `repository.get(sessionId)` → 从 Redis 读 JSON → 反序列化 → 重建 OverAllState | Redis String: `global-session:{sessionId}` → JSON, TTL 24h |
| `RedisSubGraphCheckpointSaver.java` | `RedisSubGraphCheckpointSaver` | BaseCheckpointSaver 的 Redis 实现，将 L2 Checkpoint 存储到 Redis List | 框架自动调用: `put()` 中断时保存, `get()` 恢复时读取 | Redis List: `checkpoint:{threadId}` → Checkpoint JSON 数组, TTL 24h |

### 8.4 config/ 包 — Memory 相关配置

| 文件 | 类 | 作用 | 典型用法 | 关键 Bean |
|------|-----|------|---------|----------|
| `StorageProperties.java` | `StorageProperties` | `@ConfigurationProperties(prefix="storage")`，映射 application.yml 中 storage.* 配置到 Java 对象 | `props.getType()` → "in-memory", `props.getRedis().getHost()` | 无 (配置映射类，不注册 Bean) |
| `DomainServiceConfig.java` | `DomainServiceConfig` (@Configuration) | 3 个 DomainStateAware 注册 Bean（零依赖，避免循环依赖）+ 3 个 DomainService Bean + ChatService 注册 | Spring 自动收集 DomainStateAware Bean 列表 | `transferDomainStateAware`, `billDomainStateAware`, `wealthDomainStateAware`, `transferDomainService`, `billDomainService`, `wealthDomainService` |

### 8.5 类之间的依赖关系速查

```
BankController
  └── GlobalSessionStateStore ──── GlobalSessionRepository (接口)
  │                                      ├── InMemoryGlobalSessionRepository
  │                                      └── RedisGlobalSessionRepository
  └── DomainRouter (读 ctx.messages + ctx._lastDomain)

AbstractDomainService
  └── GlobalSessionStateStore (注入)
  │     └── 通过 ctx.getDomainState() / ctx.updateDomainState() 操作 DomainState
  └── GraphExecutionEngine
        └── SubGraphCheckpointSaverFactory (接口)
               ├── MemorySaver::new (InMemory)
               └── () -> new RedisSubGraphCheckpointSaver(...) (Redis)

GlobalSessionStateStore
  ├── KeyStrategyFactory (@Qualifier("globalKeyStrategyFactory"))
  │     └── KeyStrategyFactoryConfig (收集 DomainStateAware 列表)
  └── GlobalSessionRepository (接口, Spring 注入)

RedisGlobalSessionRepository
  ├── StringRedisTemplate (new, 手动设置 ConnectionFactory)
  ├── ObjectMapper (Spring 共享 Bean)
  └── KeyStrategyFactory (@Qualifier("globalKeyStrategyFactory"))
        ↑ 反序列化时必须重新注册 KeyStrategy
```

---

## 9. DomainState 注册机制

### 9.1 设计原则

**核心问题**: 每个 L1 域需要在 GlobalSessionContext 的 OverAllState 中注册自己的 key (如 `_transferState`)，但 DomainService 依赖 GlobalSessionStateStore，如果 DomainService 直接实现注册接口会导致循环依赖：

```
DomainService → GlobalSessionStateStore → KeyStrategyFactory → DomainService (循环!)
```

**解决方案**: 将注册逻辑从 DomainService 中分离出来，用轻量级匿名 Bean 注册，零依赖：

```
DomainServiceConfig                KeyStrategyFactoryConfig
  DomainStateAware Bean (零依赖) ←── Spring 自动收集 ──→ globalKeyStrategyFactory Bean
                                                           ↑
GlobalSessionStateStore 注入 globalKeyStrategyFactory       │
DomainService 注入 GlobalSessionStateStore                  │
                                                           无循环
```

### 9.2 注册流程

**Step 1**: `DomainServiceConfig` 定义注册 Bean

```java
@Bean
public DomainStateAware transferDomainStateAware() {
    return DomainStateAware.of("_transferState", "TRANSFER");
}

@Bean
public DomainStateAware billDomainStateAware() {
    return DomainStateAware.of("_billState", "BILL");
}

@Bean
public DomainStateAware wealthDomainStateAware() {
    return DomainStateAware.of("_wealthState", "WEALTH");
}
```

**Step 2**: `KeyStrategyFactoryConfig` 自动收集所有 DomainStateAware

```java
@Bean
public KeyStrategyFactory globalKeyStrategyFactory(List<DomainStateAware> domainStateProviders) {
    return createKeyStrategyFactory(domainStateProviders);
}
```

**Step 3**: 创建 GlobalSessionContext 时，注册所有 key

```java
// GlobalSessionStateStore.createAndStore()
OverAllState state = new OverAllState();
state.registerKeyAndStrategy(keyStrategyFactory.apply());  // 包含所有公共 key + 域 key
```

### 9.3 DomainStateAware 接口

```java
public interface DomainStateAware {
    String getStateKey();       // "_transferState"
    KeyStrategy getStateStrategy();  // ReplaceStrategy
    DomainState initialState();     // new DomainState("TRANSFER")

    static DomainStateAware of(String stateKey, String domain) {
        return new DomainStateAware() {
            @Override public String getStateKey() { return stateKey; }
            @Override public KeyStrategy getStateStrategy() { return new ReplaceStrategy(); }
            @Override public DomainState initialState() { return new DomainState(domain); }
        };
    }
}
```

### 9.4 新增域只需两步

1. 在 `DomainServiceConfig` 添加一个注册 Bean:

```java
@Bean
public DomainStateAware newDomainStateAware() {
    return DomainStateAware.of("_newDomainState", "NEW_DOMAIN");
}
```

2. 添加对应的 DomainService Bean。**框架层零改动。**

---

## 10. KeyStrategy 体系

### 10.1 两种策略

| 策略 | 行为 | 适用 key |
|------|------|---------|
| `AppendStrategy` | 追加到 List | `messages` (对话历史) |
| `ReplaceStrategy` | 整体替换 | 其他所有 key (lastDomain, DomainState, 参数等) |

### 10.2 全局 KeyStrategyFactory 注册的 key

| key | 策略 | 用途 | 消费者 |
|-----|------|------|--------|
| `messages` | Append | 对话历史 | L0, L1, L2 |
| `_lastDomain` | Replace | 最近路由领域 | L0 DomainRouter |
| `_latestUserInput` | Replace | 最新用户输入 | L2 节点 |
| `_question` | Replace | 当前提问 | L1, L2 |
| `_paramName` | Replace | 路由目标 | L2 paramRouter |
| `_outputContent` | Replace | 输出内容 | L1, L2 |
| `_outputType` | Replace | 输出类型 | L1, L2 |
| `_isFinal` | Replace | 是否终结 | L1, L2 |
| `_cancelSignal` | Replace | 取消信号 | L2 取消流程 |
| `_globalStateData` | Replace | L1→L2 全局状态注入 | L2 节点 |
| `_transferState` | Replace | 转账域 DomainState | L1 TransferService |
| `_billState` | Replace | 账单域 DomainState | L1 BillService |
| `_wealthState` | Replace | 理财域 DomainState | L1 WealthService |

### 10.3 L2 子图独立的 KeyStrategyFactory

每个 L2 子图有自己的 KeyStrategyFactory (由 `AbstractGraphConfig.createKeyStrategyFactory()` 创建)，注册了子图专用的业务参数 key：

| 子图 | 额外注册的 key |
|------|--------------|
| TransferGraph | `transfer.receiver`, `transfer.amount`, `transfer.purpose` |
| BillQueryGraph | `bill.timePeriod`, `bill.expenseType` |
| WealthConsultGraph | `wealthConsult.riskLevel`, `wealthConsult.focusArea` |
| WealthInterpretGraph | `wealthInterpret.productName` |

---

## 11. 数据流详解

### 11.1 GlobalSessionContext 的读写路径

#### 写入路径

```
BankController.chat()
  │
  ├── ctx.addUserMessage(input)
  │     └── state.updateState(Map.of("messages", "用户: " + input))  ← AppendStrategy
  │
  ├── DomainRouter.route()
  │     └── ctx.setLastDomain(domain, expireAt)
  │           └── state.updateState(Map.of("_lastDomain", new LastDomainEntry(...)))  ← ReplaceStrategy
  │
  └── AssistantAccumulator.onChunk(terminalChunk)
        └── ctx.addAssistantMessage(fullReply)
              └── state.updateState(Map.of("messages", "助手: " + fullReply))  ← AppendStrategy
```

#### L1 DomainService 读写路径

```
AbstractDomainService
  │
  ├── 读取对话历史:
  │   getFormattedChatHistory(sessionId)
  │     └── ctx.formatRecentMessages(l1DomainPairs)
  │           └── state.value("messages") → List<String> → subList → join("\n")
  │
  ├── 读取 activeAgent:
  │   getOwnActiveAgent(sessionId)
  │     └── ctx.getDomainState("_transferState")
  │           └── state.value("_transferState") → DomainState → .getActiveAgent()
  │
  ├── 写入 activeAgent:
  │   setOwnActiveAgent(sessionId, intent, threadId)
  │     └── ctx.updateDomainState("_transferState", ds ->
  │           ds.setActiveAgent(new ActiveAgentInfo(...)))
  │           └── state.updateState(Map.of("_transferState", ds))  ← ReplaceStrategy
  │
  └── 注入 L2 全局状态:
      buildGraphInput(sessionId, rewrittenInput)
        └── ctx.data() → Map<String, Object>
              └── input.put("_globalStateData", globalData)  ← 整体注入
```

#### updateDomainState 的 read-modify-write 封装

```java
public void updateDomainState(String stateKey, Consumer<DomainState> modifier) {
    // 1. 读取 (不存在则创建)
    DomainState ds = state.value(stateKey)
            .filter(DomainState.class::isInstance)
            .map(DomainState.class::cast)
            .orElseGet(() -> new DomainState(domainKeyFromStateKey(stateKey)));
    // 2. 修改
    modifier.accept(ds);
    // 3. 写回 (ReplaceStrategy 整体替换)
    state.updateState(Map.of(stateKey, ds));
}
```

### 11.2 L2 Checkpoint 读写路径

#### 首次执行 (executeGraph)

```
AbstractDomainService.executeNewAgent(sessionId, intent, rewrittenInput)
  │
  ├── String threadId = generateThreadId(sessionId, intent)
  │     // "sess1-TRANSFER-a3f2b1"
  │
  ├── Map<String, Object> input = buildGraphInput(sessionId, rewrittenInput)
  │     // { messages, _latestUserInput, _globalStateData, ... }
  │
  ├── setOwnActiveAgent(sessionId, intent, threadId)
  │     // 写入 DomainState.activeAgent
  │
  └── graphExecutionEngine.executeGraph(graph, intent, input, threadId)
        │
        ├── RunnableConfig config = threadConfig(threadId)
        │
        └── graph.stream(input, config)
              │
              ├── 正常完成:
              │   └── clearCheckpoint() → saver.release(config)  ← 释放 checkpoint
              │
              └── interruptBefore 中断 (ask 节点):
                  └── SubGraphCheckpointSaver 自动 put(checkpoint)
                        // 保存 {id, nodeId, nextNodeId, state}
                        // InMemory: HashMap.put()
                        // Redis: RPUSH checkpoint:{threadId} → Checkpoint JSON
```

#### 恢复执行 (resumeGraph)

```
AbstractDomainService.resumeActiveAgent(sessionId, userInput, active)
  │
  ├── graph = subGraphRegistry.getGraph(active.getIntent())
  ├── Map<String, Object> globalStateData = ctx.data()
  │
  └── graphExecutionEngine.resumeGraph(graph, intent, userInput, active.getThreadId(), globalStateData)
        │
        ├── RunnableConfig config = threadConfig(threadId)
        │
        ├── graph.updateState(config, updateData, null)
        │     // updateData = { _globalStateData, _latestUserInput }
        │     // 框架从 SubGraphCheckpointSaver.get(threadId) 读取 checkpoint
        │     // InMemory: HashMap.get(threadId)
        │     // Redis: LINDEX checkpoint:{threadId} -1 → 反序列化
        │
        ├── graph.stream(null, updatedConfig)
        │     // null 表示使用 checkpoint 中的 state
        │
        └── 正常完成:
            └── clearCheckpoint() → saver.release(config)
```

---

## 12. 关键交互时序

### 12.1 正常执行（无中断）

```
用户: "给张三转500"
  │
  ├── BankController.chat()
  │   ├── ctx.addUserMessage("给张三转500")          ← AppendStrategy
  │   ├── DomainRouter.route() → TRANSFER
  │   ├── ctx.setLastDomain("TRANSFER", +5min)       ← ReplaceStrategy
  │   │
  │   └── TransferService.handle()
  │       ├── ctx.formatRecentMessages(6)            ← 读 messages
  │       ├── contextRouter.route() → SWITCH
  │       ├── subGraphRouter.rewriteAndIdentify() → TRANSFER
  │       │
  │       └── executeNewAgent(sessionId, "TRANSFER", "给张三转账500元")
  │           ├── threadId = "sess1-TRANSFER-a3f2b1"
  │           ├── ctx.updateDomainState("_transferState", ds -> 
  │           │     ds.setActiveAgent(new ActiveAgentInfo("TRANSFER", threadId, ...)))
  │           ├── input._globalStateData = ctx.data()
  │           │
  │           └── graphExecutionEngine.executeGraph(transferGraph, ...)
  │               ├── graph.stream(input, config)
  │               │   └── extractParams → paramRouter → ALL_GOOD → executeTransfer → END
  │               └── clearCheckpoint()
  │
  └── ctx.addAssistantMessage("转账成功!")            ← AppendStrategy
```

### 12.2 Human-in-the-loop（ask 节点中断 + 恢复）

```
用户第1次: "转账"
  │
  ├── BankController → DomainRouter → TRANSFER → TransferService
  │   └── executeNewAgent() → threadId="sess1-TRANSFER-a3f2b1"
  │       └── graph.stream() → extractParams → paramRouter → ASK_RECEIVER
  │           └── [interruptBefore: askReceiver]
  │               ├── SubGraphCheckpointSaver.put(checkpoint)
  │               │   // 保存: {nodeId="askReceiver", nextNodeId="askReceiver", state={...}}
  │               ├── ctx.updateDomainState("_transferState", ds ->
  │               │     ds.getActiveAgent().setLastQuestion("请问您要转给谁？"))
  │               └── 返回 INTERRUPTED chunk + question="请问您要转给谁？"
  │
  └── ctx.addAssistantMessage("请问您要转给谁？")

用户第2次: "张三"
  │
  ├── BankController → DomainRouter → TRANSFER → TransferService
  │   ├── getOwnActiveAgent() → ActiveAgentInfo(lastQuestion != null)
  │   ├── contextRouter.route() → FOLLOW
  │   │
  │   └── resumeActiveAgent(sessionId, "张三", active)
  │       ├── graph = subGraphRegistry.getGraph("TRANSFER")
  │       ├── globalStateData = ctx.data()
  │       └── graphExecutionEngine.resumeGraph(graph, "TRANSFER", "张三", threadId, globalStateData)
  │           ├── graph.updateState(config, {_globalStateData, _latestUserInput:"张三"}, null)
  │           │   // 框架从 SubGraphCheckpointSaver.get(threadId) 恢复 checkpoint
  │           ├── graph.stream(null, updatedConfig)
  │           │   └── askReceiver → paramRouter → ASK_AMOUNT → [interruptBefore]
  │           │       ├── SubGraphCheckpointSaver.put(newCheckpoint)
  │           │       └── 返回 INTERRUPTED + "请问您要转多少金额？"
  │           └── 
  │
  └── ctx.addAssistantMessage("请问您要转多少金额？")

用户第3次: "500"
  │
  └── (同上 resume 流程) → askAmount → paramRouter → ALL_GOOD → executeTransfer → END
      └── clearCheckpoint() → saver.release(threadId)
```

---

## 13. Redis 存储细节

### 13.1 GlobalSessionRepository — Redis 实现

**Redis Key 格式:** `global-session:{sessionId}`

**序列化:** 整个 `state.data()` 序列化为 JSON

**读取流程:**
```java
public GlobalSessionContext get(String sessionId) {
    String json = redisTemplate.opsForValue().get("global-session:" + sessionId);
    Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
    OverAllState state = new OverAllState();
    state.registerKeyAndStrategy(keyStrategyFactory.apply());  // 注册 KeyStrategy
    state.updateState(data);                                    // 恢复数据
    return new GlobalSessionContext(sessionId, state);
}
```

**写入流程:**
```java
public void put(String sessionId, GlobalSessionContext ctx) {
    String json = objectMapper.writeValueAsString(ctx.data());
    redisTemplate.opsForValue().set("global-session:" + sessionId, json, 24, TimeUnit.HOURS);
}
```

**关键细节:**
- 读取时必须重新注册 KeyStrategy，否则 `state.updateState()` 不知道如何处理每个 key
- TTL 默认 24 小时
- KeyStrategyFactory 通过 `@Qualifier("globalKeyStrategyFactory")` 注入

### 13.2 SubGraphCheckpointSaver — Redis 实现

**Redis Key 格式:** `checkpoint:{threadId}`

**数据结构:** Redis List，每个元素是一个 Checkpoint JSON

```
checkpoint:sess1-TRANSFER-a3f2b1
  ├── [0] Checkpoint JSON (最早的)
  ├── [1] Checkpoint JSON
  └── [2] Checkpoint JSON (最新的)  ← LINDEX -1
```

**各操作对应的 Redis 命令:**

| 操作 | Redis 命令 | 说明 |
|------|-----------|------|
| `list(config)` | `LRANGE key 0 -1` | 获取所有 checkpoint |
| `get(config)` | `LINDEX key -1` | 获取最新 checkpoint |
| `put(config, cp)` | `RPUSH key json` + `EXPIRE key 24h` | 追加 + 刷新 TTL |
| `release(config)` | `DEL key` | 删除所有 checkpoint |

**Checkpoint JSON 结构:**
```json
{
  "id": "cp-001",
  "nodeId": "askReceiver",
  "nextNodeId": "askReceiver",
  "state": {
    "messages": ["用户: 转账"],
    "transfer.receiver": null,
    "transfer.amount": null,
    "_latestUserInput": "转账",
    "_question": "请问您要转给谁？",
    "_paramName": "ASK_RECEIVER"
  }
}
```

### 13.3 Redis Key 汇总

| Key 模式 | 类型 | TTL | 内容 | 产生者 |
|----------|------|-----|------|--------|
| `global-session:{sessionId}` | String | 24h | OverAllState.data() 的 JSON | GlobalSessionStateStore |
| `checkpoint:{threadId}` | List | 24h | Checkpoint JSON 列表 | SubGraphCheckpointSaver |

---

## 14. 配置参考

### 14.1 application.yml 完整 Memory 相关配置

```yaml
# 状态存储配置 — 改 type 后重启即可切换 in-memory / redis
storage:
  type: in-memory    # in-memory | redis
  redis:
    host: localhost
    port: 6379
    # password:
    database: 0

# 路由历史配置
routing:
  history:
    l0-max-pairs: 10    # L0 域路由使用的对话对数
    l1-max-pairs: 6     # L1 意图路由使用的对话对数
    chat-max-pairs: 10  # ChatService 使用的对话对数

# Session 配置
session:
  last-domain:
    expire-minutes: 5   # lastDomain 过期时间
```

### 14.2 StorageProperties 类

```java
@Data
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private String type = "in-memory";     // 默认 InMemory
    private Redis redis = new Redis();

    @Data
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password;           // 可选
        private int database = 0;
    }
}
```

### 14.3 常见配置场景

**场景1: 本地开发 (默认)**
```yaml
storage:
  type: in-memory
```
- 所有状态存内存，重启丢失
- 无需启动 Redis

**场景2: 多实例部署**
```yaml
storage:
  type: redis
  redis:
    host: redis.internal
    port: 6379
    password: ${REDIS_PASSWORD}
```
- 状态存 Redis，多实例共享
- 支持滚动重启、持久化

**场景3: Docker Compose**
```yaml
storage:
  type: redis
  redis:
    host: redis
    port: 6379
```
- Redis 服务名作为 host

### 14.4 Bean 激活条件速查

| `storage.type` 值 | 激活的 GlobalSessionRepository Bean | 激活的 SubGraphCheckpointSaverFactory Bean |
|---|---|---|
| 未配置 (默认) | `inMemoryGlobalSessionRepository` | `inMemorySubGraphCheckpointSaverFactory` |
| `in-memory` | `inMemoryGlobalSessionRepository` | `inMemorySubGraphCheckpointSaverFactory` |
| `redis` | `redisGlobalSessionRepository` | `redisSubGraphCheckpointSaverFactory` |

---

## 附录: 关键代码位置索引

| 功能 | 文件 | 行号参考 |
|------|------|---------|
| storage.type 配置定义 | `application.yml` | 117-123 |
| StorageProperties | `config/StorageProperties.java` | 全文 |
| GlobalSessionRepository 接口 | `memory/GlobalSessionRepository.java` | 全文 |
| InMemory 实现 | `memory/impl/InMemoryGlobalSessionRepository.java` | 全文 |
| Redis 实现 (Session) | `memory/impl/RedisGlobalSessionRepository.java` | 全文 |
| Redis 实现 (Checkpoint) | `memory/impl/RedisSubGraphCheckpointSaver.java` | 全文 |
| Session 级 ConditionalOnProperty | `memory/GlobalSessionRepositoryConfig.java` | 23-28, 30-40 |
| Checkpoint 级 ConditionalOnProperty | `memory/SubGraphCheckpointSaverConfig.java` | 31-36, 43-51 |
| DomainStateAware 注册 | `config/DomainServiceConfig.java` | 38-51 |
| KeyStrategyFactory 构建 | `memory/KeyStrategyFactoryConfig.java` | 26-56 |
| GlobalSessionContext 读写 | `memory/GlobalSessionContext.java` | 58-131 |
| updateDomainState 封装 | `memory/GlobalSessionContext.java` | 124-131 |
| DomainState 模型 | `memory/model/DomainState.java` | 全文 |
| L2 Checkpoint 创建 | `workflow/AbstractGraphConfig.java` | 500-506 |
| L2 Checkpoint 释放 | `execution/GraphExecutionEngine.java` | 274-286 |
| 全局状态注入 L2 | `domain/AbstractDomainService.java` | 197-210 |
