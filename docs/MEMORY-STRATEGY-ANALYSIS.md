# 记忆策略分析：GlobalSessionContext vs MemorySaver vs MemoryStore

> **状态**: v1.1 — 已整合 Spring AI Alibaba 框架真实代码示例
> **定位**: 分析手机银行智能助手项目中三类记忆机制的职责边界、适用场景和演进路径
> **前置文档**: [MEMORY-ARCHITECTURE.md](./MEMORY-ARCHITECTURE.md)（现有记忆架构详述）

---

## 1. 三种机制的本质区别

一句话总结：**GlobalSessionContext 是编排的"寄存器"，MemorySaver 是对话的"回放键"，MemoryStore 是用户的"长期记忆"。**

```
┌─────────────────────────────────────────────────────────────────┐
│                    三层记忆体系                                    │
│                                                                   │
│  GlobalSessionContext        MemorySaver          MemoryStore    │
│  ─────────────────          ───────────           ────────────  │
│  "寄存器"                    "回放键"               "长期记忆"    │
│                                                                   │
│  存什么: 结构化状态对象       存什么: 对话历史         存什么: 用户画像│
│  谁消费: Java 代码            谁消费: LLM (自动)      谁消费: LLM  │
│  怎么读: ctx.getDomainState() 怎么读: 同threadId自动   怎么读: Tool/Hook│
│  生命周期: 单会话              生命周期: 单对话         生命周期: 跨会话│
│  可靠性: 强一致               可靠性: 最终一致         可靠性: 最终一致│
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 逐个详解

### 2.1 GlobalSessionContext — 编排的"寄存器"

**源码位置**: `memory/GlobalSessionContext.java`
**内部结构**: 持有一个 `OverAllState`，通过 `KeyStrategy` 管理不同字段的读写策略

**存什么**:

| Key | 类型 | 写入方 | 读取方 | 用途 |
|-----|------|--------|--------|------|
| `messages` | `List<String>` (AppendStrategy) | BankController | DomainRouter, L1 | 对话历史 |
| `_lastDomain` | `LastDomainEntry` (ReplaceStrategy) | DomainRouter | DomainRouter | 最近路由域 |
| `_transferState` | `DomainState` (ReplaceStrategy) | TransferDomainService | TransferDomainService | 转账域状态 |
| `_billState` | `DomainState` (ReplaceStrategy) | BillDomainService | BillDomainService | 账单域状态 |
| `_wealthState` | `DomainState` (ReplaceStrategy) | WealthDomainService | WealthDomainService | 理财域状态 |
| `_reaState` | `ReaOrchestrationState` (ReplaceStrategy) | ReaAgent | ReaAgent | 编排状态 |

**DomainState 内部**:
```
_transferState: DomainState
  ├── activeAgent: {intent, threadId, expiresAt, lastQuestion}
  ├── suspendedAgents: Map<String, SuspendedInfo>
  ├── disambiguation: DisambiguationState
  └── subAgents: Map<String, SubAgentState>  ← L2 业务数据快照
        └── "TRANSFER": {receiver: "张三", amount: "500"}
```

**核心特点**:
- **Java 代码消费**: 编排循环通过 `getDomainState("_reaState")` 读取编排进度，DomainRouter 通过 `getLastDomain()` 决定路由
- **强一致性**: 编排循环的状态检查是确定性逻辑（`wasInterrupted`），必须精确
- **结构化**: 每个字段有明确的类型和语义，不是自然语言
- **单会话**: sessionId 维度，会话结束或超时后清理

**为什么叫"寄存器"**: CPU 寄存器存的是当前指令执行所需的即时状态——程序计数器、标志位、当前操作数。GlobalSessionContext 存的是当前编排执行所需的即时状态——当前步骤、等待域、已完成结果。寄存器不适合存长期数据，GlobalSessionContext 也不适合。

---

### 2.2 MemorySaver — 对话的"回放键"

**框架 API**: `BaseCheckpointSaver` 接口 → `MemorySaver` 实现
**配置方式**: `ReactAgent.builder().saver(memorySaver)`
**框架源码**: `spring-ai-alibaba-graph-core/.../checkpoint/savers/MemorySaver.java`

**存什么**:

每个 `Checkpoint` 包含:
```java
public class Checkpoint {
    private final String id;           // UUID
    private Map<String, Object> state; // 完整的 OverAllState.data()
    private String nodeId;             // 产生此 checkpoint 的图节点
    private String nextNodeId;         // 下一个要执行的节点
}
```

key 是 `threadId` → `LinkedList<Checkpoint>`（栈结构，最新在前）。

**核心特点**:
- **LLM 自动消费**: 同 threadId 的下一次 `agent.call()` 自动恢复上次的对话上下文
- **对话连续性**: 跨多次 `call()` 保持对话历史，LLM 能"记住"之前说了什么
- **框架自动管理**: 不需要手动读写，框架在每次图执行前后自动 save/restore
- **Per-agent 独立**: 每个 ReactAgent 可以有独立的 MemorySaver

**使用方式**:
```java
MemorySaver memorySaver = new MemorySaver();

ReactAgent agent = ReactAgent.builder()
    .name("planner")
    .model(chatModel)
    .saver(memorySaver)
    .build();

// 第一次调用 — 保存 checkpoint
RunnableConfig config = RunnableConfig.builder()
    .threadId("rea-plan-" + sessionId)
    .build();
agent.call(Map.of("input", "查余额，超9万就转账"), config);

// 第二次调用 — 自动恢复上次的对话历史
agent.call(Map.of("input", "重新规划"), config);  // LLM 看到之前的对话
```

**为什么叫"回放键"**: 像游戏存档一样，按 threadId 读取存档点，从上次停下的地方继续。对话结束后，存档可以保留也可以删除。

**与 GlobalSessionContext 的关系**: 
- GlobalSessionContext 存的是**编排控制状态**（走到哪一步、哪个域在等待）
- MemorySaver 存的是**LLM 对话历史**（LLM 说了什么、调了什么工具、工具返回了什么）
- 两者互补：编排循环读 GlobalSessionContext 做决策，LLM 读 MemorySaver 做生成

---

### 2.3 MemoryStore — 用户的"长期记忆"

**框架 API**: `Store` 接口 → `MemoryStore` / `RedisStore` / `MongoStore` / `DatabaseStore`
**配置方式**: `RunnableConfig.builder().store(memoryStore)` — 注意：不是在 Agent builder 上，是在调用时的 config 上
**框架源码**: `spring-ai-alibaba-graph-core/.../store/stores/MemoryStore.java`

**存什么**:

```java
public class StoreItem {
    private List<String> namespace;       // 层级命名空间: ["users", "user123", "preferences"]
    private String key;                   // 键: "risk_profile"
    private Map<String, Object> value;    // 值: {"level": "稳健", "updated": "2025-06-14"}
    private long createdAt;
    private long updatedAt;
}
```

**核心特点**:
- **跨会话持久化**: userId 维度，用户下次来还在
- **结构化存储**: namespace + key 层级组织，不是自然语言
- **主动读写**: LLM 需要通过 Tool 或 Hook 主动读取，不像 MemorySaver 那样自动注入
- **多种后端**: MemoryStore (开发)、RedisStore (生产)、MongoStore (复杂查询)

**两种注入方式**:

**方式 A: 通过 Tool** — Agent 调用一个工具来查询 Store:
```java
ToolCallback getUserProfileTool = FunctionToolCallback.builder("getUserProfile",
    (BiFunction<ProfileRequest, ToolContext, String>) (req, ctx) -> {
        RunnableConfig config = (RunnableConfig) ctx.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
        Store store = config.store();
        Optional<StoreItem> item = store.getItem(List.of("users", userId), "profile");
        return item.map(i -> i.getValue().toString()).orElse("无用户画像");
    })
    .description("查询用户画像和偏好")
    .inputType(ProfileRequest.class)
    .build();
```

**方式 B: 通过 MessagesModelHook** — 自动注入到 prompt:
```java
class MemoryInterceptor extends MessagesModelHook {
    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        Store store = config.store();
        Optional<StoreItem> item = store.getItem(List.of("users", userId), "profile");
        if (item.isPresent()) {
            String context = "用户画像: " + item.get().getValue();
            // 注入 SystemMessage
            SystemMessage enhanced = new SystemMessage(
                existingSystemPrompt + "\n\n" + context);
            return new AgentCommand(enhancedMessages, UpdatePolicy.REPLACE);
        }
        return new AgentCommand(previousMessages);
    }
}
```

**为什么叫"长期记忆"**: 像人的长期记忆一样，存储跨场景、跨时间的知识和偏好。你今天告诉我"偏好稳健型理财"，三个月后我还能记得。但长期记忆需要"回忆"（主动检索），不像短期记忆那样一直在意识中。

---

## 3. 三者对比

| 维度 | GlobalSessionContext | MemorySaver | MemoryStore |
|------|---------------------|-------------|-------------|
| **类比** | CPU 寄存器 | 游戏存档 | 人的长期记忆 |
| **存什么** | 结构化状态对象 | 完整对话历史 | 用户画像/偏好/摘要 |
| **消费者** | Java 代码 | LLM (自动恢复) | LLM (Tool/Hook 读取) |
| **读写方式** | `ctx.getDomainState()` / `updateDomainState()` | 同 threadId 自动 | `store.getItem()` / `store.putItem()` |
| **生命周期** | 单会话 (sessionId) | 单对话 (threadId) | 跨会话 (userId/namespace) |
| **可靠性** | 强一致（编排逻辑依赖） | 最终一致（LLM 容错） | 最终一致（LLM 容错） |
| **注入方式** | Java 代码直接读取 | 框架自动恢复到 prompt | Tool 调用或 Hook 注入 |
| **存储后端** | InMemory / Redis | InMemory / Redis | InMemory / Redis / Mongo / DB |
| **适合存** | 编排进度、域状态、路由历史 | 对话上下文、工具调用记录 | 用户偏好、历史摘要、知识 |

---

## 4. 具体场景示例

### 场景 1: 编排进度追踪 — 用 GlobalSessionContext

```
用户: "查收入，超9万就转账，买理财"

ReaAgent 编排过程中:
  _reaState.status = EXECUTING
  _reaState.currentStepIndex = 1
  _reaState.waitingForDomain = "TRANSFER"
  _reaState.waitingQuestion = "确认转账？"
  _reaState.stepResults[0] = {COMPLETED, "收入95000元"}
```

**为什么不用 MemorySaver?** MemorySaver 存的是 LLM 对话历史，不是结构化状态。编排循环需要精确知道 `currentStepIndex = 1`，不能从对话历史中"推断"。

**为什么不用 MemoryStore?** MemoryStore 是跨会话的，编排进度是单会话的。编排完成就要清理，不应该持久化。

### 场景 2: 域 Agent 对话连续性 — 用 MemorySaver

```
Turn 1: TransferAgent 调用 TransferTool → INTERRUPTED "确认转账？"
        MemorySaver 保存: threadId="rea-step-{sid}-1"
          messages = [SystemMsg, UserMsg("向张三转账3000元"), 
                      AssistantMsg("需要确认: 确认转账？")]

Turn 2: 用户回复 "确认"
        TransferAgent.call(resumeInput, 同 threadId)
        MemorySaver 自动恢复: 
          messages = [SystemMsg, UserMsg, AssistantMsg, UserMsg("确认")]
        LLM 看到完整对话 → 知道之前问了什么 → 继续执行
```

**为什么不用 GlobalSessionContext?** GlobalSessionContext 不存 LLM 对话历史。它存的是 `_reaState.waitingQuestion = "确认转账？"`，但这是给 Java 代码看的，不是给 LLM 看的。LLM 需要的是完整的对话上下文（包括之前调用了什么工具、工具返回了什么）。

**为什么不用 MemoryStore?** 这是对话内的上下文延续，不需要跨会话持久化。对话结束，存档可以清理。

### 场景 3: 用户偏好记忆 — 用 MemoryStore

```
用户 A 第一次会话:
  用户: "我喜欢稳健型理财"
  ChatAgent 回答后，系统写入 MemoryStore:
    StoreItem {
      namespace: ["users", "userA", "preferences"]
      key: "risk_profile"
      value: {"level": "稳健", "source": "用户自述", "updatedAt": "2025-06-14"}
    }

用户 A 三个月后再次会话:
  用户: "推荐个理财"
  
  MemoryInterceptor (Hook) 自动注入:
    SystemMsg: "用户画像: 偏好稳健型理财"
  
  PlannerAgent 生成 Plan:
    Step[0]: WEALTH_CONSULT, rewrittenInput="推荐稳健型理财产品"
    (而不是激进型)
```

**为什么不用 GlobalSessionContext?** GlobalSessionContext 是单会话的，三个月后 session 已过期清理。而且 GlobalSessionContext 的数据结构（DomainState）不是为存用户偏好设计的——你不会把 `riskLevel: "稳健"` 塞进 `_wealthState.activeAgent` 里。

**为什么不用 MemorySaver?** MemorySaver 是按 threadId 的，不同会话有不同的 threadId。而且 MemorySaver 存的是完整对话历史，不是提炼后的结构化偏好。三个月的对话历史可能有几千条消息，全部加载进 prompt 不现实。

### 场景 4: 历史摘要 — MemorySaver + SummarizationHook

```
长对话中 (PlannerAgent 已经规划了5次):
  MemorySaver 中 threadId="rea-plan-{sid}" 的 messages 已有 40 条
  SummarizationHook 检测到 tokens >= 4000

  SummarizationHook 自动:
    1. 找安全截断点 (不拆分 AssistantMsg + ToolResponseMsg 对)
    2. 用 LLM 摘要 messages[0..cutoff]:
       "之前为用户规划了: 查收入(95000元)→转账(成功)→买理财(中断→恢复→成功)，
        用户偏好稳健型，当前无待处理操作。"
    3. 替换: [SystemMsg(summary)] + recentMessages[后20条]
    4. MemorySaver 保存新 checkpoint (含摘要)
```

**关键**: SummarizationHook 和 MemorySaver 是配合使用的——SummarizationHook 压缩对话，MemorySaver 保存压缩后的结果。这解决了"长对话 token 溢出"问题。

### 场景 5: 跨域上下文传递 — 用 GlobalSessionContext（Phase 1）

```
ReaAgent 编排:
  Step[0] BILL → stepResults[0] = "收入95000元"
  Step[1] TRANSFER (condition: "收入>90000")

编排循环把 stepResults[0] 传给 ConditionAgent:
  ConditionAgent 判断: "95000 > 90000 → SATISFIED"

编排循环把 stepResults 传给 SummaryAgent:
  SummaryAgent 生成: "✅ 收入: 95000元 ✅ 转账: 成功"
```

**为什么不用 MemoryStore?** 这是编排内的步骤间数据传递，不是跨会话的用户画像。步骤完成后，结果存入 `_reaState.stepResults`，编排结束后清理。

### 场景 6: 编排状态恢复 — GlobalSessionContext + DomainRouter

```
L0 DomainRouter 路由逻辑:
  1. 检查 _reaState → WAITING_USER → 强制路由 REA
  2. 检查 _lastDomain → TRANSFER → 路由 TRANSFER (无活跃编排时)
  3. 关键词匹配 → "余额" → 路由 BILL
```

**为什么用 GlobalSessionContext?** 路由逻辑是 Java 代码，需要精确读取 `_reaState.status == WAITING_USER`。这不是 LLM 需要的信息，是代码逻辑需要的信息。

---

## 5. 对 ReaAgent 的影响

### 5.1 当前 ReaAgent 的记忆使用

| 记忆类型 | 使用位置 | 用途 |
|----------|---------|------|
| GlobalSessionContext | `_reaState` | 编排进度、步骤结果、等待状态 |
| GlobalSessionContext | `_transferState` 等 | 域 Agent 的 L1 状态（INTERRUPTED 标记） |
| MemorySaver | PlannerAgent, ChatAgent | 对话历史（同一 threadId 跨 call 保持上下文） |
| MemoryStore | **未使用** | — |
| SummarizationHook | **未使用** | — |

### 5.2 各 Agent 的记忆需求

| Agent | GlobalSessionContext | MemorySaver | MemoryStore |
|-------|---------------------|-------------|-------------|
| PlannerAgent | 读取 `_reaState` (REPLAN 时) | ✅ 对话历史 | Phase 2: 用户偏好 |
| ConditionAgent | 不需要 | ❌ 无状态 | ❌ 无状态 |
| SummaryAgent | 不需要 | ✅ 对话历史 | ❌ 无状态 |
| ChatAgent | 不需要 | ✅ 多轮对话 | Phase 2: 金融知识 |
| RelevanceAgent | 不需要 | ❌ 无状态 | ❌ 无状态 |
| TransferAgent | 通过 DomainTool 间接触发 `_transferState` | ✅ 同 threadId 恢复 | ❌ 无状态 |
| BillAgent | 通过 DomainTool 间接触发 `_billState` | ✅ 同 threadId 恢复 | ❌ 无状态 |
| WealthAgent | 通过 DomainTool 间接触发 `_wealthState` | ✅ 同 threadId 恢复 | ❌ 无状态 |

### 5.3 Phase 分期

| Phase | MemorySaver | MemoryStore | SummarizationHook |
|-------|-------------|-------------|-------------------|
| **Phase 1** | PlannerAgent + ChatAgent (对话连续性) | ❌ 不引入 | ❌ 不引入 |
| **Phase 2** | 全部 Agent (对话连续性) | ChatAgent + PlannerAgent (用户偏好/金融知识) | PlannerAgent (防长 prompt 溢出) |
| **Phase 3** | 全部 Agent | 全部 Agent (跨会话画像) | 全部长对话 Agent |

---

## 6. 什么时候该用哪个？— 决策树

```
需要存储信息?
  │
  ├── 信息是给 Java 代码消费的（路由、编排控制、状态检查）?
  │   └── ✅ GlobalSessionContext
  │       示例: 编排进度、域状态、路由历史
  │
  ├── 信息是给 LLM 消费的，且只在本对话内有效?
  │   └── ✅ MemorySaver (同 threadId 自动恢复)
  │       示例: 对话历史、工具调用记录、之前讨论的上下文
  │
  ├── 信息是给 LLM 消费的，且需要跨会话持久化?
  │   └── ✅ MemoryStore (Tool/Hook 读取)
  │       示例: 用户偏好、历史摘要、金融知识
  │
  └── 对话太长，token 快超了?
      └── ✅ SummarizationHook (配合 MemorySaver)
          示例: 长编排会话、多轮咨询对话
```

---

## 7. 常见误区

### 误区 1: "GlobalSessionContext 已经有 messages，不需要 MemorySaver"

**错误**。GlobalSessionContext 的 `messages` 是简化的文本记录（`"用户: 转账"`, `"助手: 确认转账？"`），而 MemorySaver 存的是完整的 LLM Message 对象（包括 SystemMessage、AssistantMessage with tool_calls、ToolResponseMessage 等）。前者是给人看的日志，后者是给 LLM 看的上下文。

**正确的理解**: GlobalSessionContext.messages 用于 DomainRouter 做路由判断（看最近几轮对话判断用户意图），MemorySaver 的 checkpoint 用于 LLM 恢复完整对话上下文。

### 误区 2: "MemoryStore 可以替代 GlobalSessionContext"

**错误**。MemoryStore 是给 LLM 用的长期记忆，不是给 Java 代码用的控制状态。编排循环无法从 MemoryStore 中读取 `_reaState.status == WAITING_USER` 来决定是否暂停——MemoryStore 的接口是 `getItem(namespace, key)`，返回的是 `StoreItem`（自然语言或结构化数据），不是 `DomainState`（状态机）。

**正确的理解**: 两者解决不同问题，不能替代。

### 误区 3: "MemorySaver 会自动解决所有上下文问题"

**错误**。MemorySaver 只保存和恢复同一 threadId 的对话历史。但 ReaAgent 的编排循环中，不同步骤的域 Agent 用不同的 threadId：

```java
// Step 0: BillAgent
threadId = "rea-step-{sid}-0"

// Step 1: TransferAgent
threadId = "rea-step-{sid}-1"  // 不同的 threadId！
```

所以 BillAgent 的对话历史不会自动出现在 TransferAgent 的上下文中。这正是 **rewrittenInput 自包含** 的设计理由——域 Agent 不依赖其他域 Agent 的对话历史，只依赖 PlannerAgent 为它生成的 rewrittenInput。

### 误区 4: "把所有东西都存到 MemoryStore 就好了"

**错误**。MemoryStore 需要主动读取（通过 Tool 或 Hook），如果什么都往里存，LLM 需要知道什么时候该读、读什么。过多的存储项会导致检索效率低下和 LLM 困惑。

**正确的做法**: 只存"提炼后的结构化知识"（用户偏好、历史摘要、关键决策），不存原始对话日志。

---

## 8. MemoryStore 的 Namespace 设计建议（Phase 2+）

如果 Phase 2 引入 MemoryStore，建议的 namespace 组织：

```
users/{userId}/
  ├── profile          → {"name": "张三", "accountType": "储蓄卡", "joinDate": "2024-01"}
  ├── preferences      → {"riskLevel": "稳健", "defaultRecipient": "妈妈", "language": "zh-CN"}
  ├── recent_summary   → {"lastSession": "2025-06-14", "summary": "查了收入95000，转了3000给妈妈，买了朝朝盈"}
  └── feedback         → {"transferSuccess": 12, "transferFail": 1, "preferredTime": "工作日9-18点"}

domains/{domain}/
  ├── faq              → {"Q: 朝朝盈赎回几天到账? A: T+1"}
  └── knowledge        → {"稳健型推荐": ["朝朝盈", "天添利"], "风险等级说明": "..."}

sessions/{sessionId}/
  └── orchestration_summary → {"steps": 3, "result": "全部成功", "timestamp": "..."}
```

**读写策略**:

| 时机 | 操作 | 存什么 |
|------|------|--------|
| 编排完成后 | 写入 | 用户本次操作的摘要（非原始对话） |
| ChatAgent 回答后 | 写入 | 用户表达的偏好（如"我喜欢稳健型"） |
| 新会话开始 | 读取 | 用户画像 + 最近摘要，注入 PlannerAgent/ChatAgent |
| 用户偏好变更 | 更新 | 覆盖旧偏好（ReplaceStrategy） |

---

## 9. Spring AI Alibaba 实战代码示例

> **来源**: alibaba/spring-ai-alibaba 官方仓库 examples/ 和 test/ 目录
> **核心文件**:
> - `examples/documentation/.../advanced/MemoryExample.java` — 用户画像注入 + 偏好学习 Hook
> - `examples/documentation/.../tutorials/MemoryExample.java` — 对话摘要 Hook
> - `.../agent/memory/LongTermMemoryTest.java` — Tool 方式跨会话记忆
> - `.../store/GraphStoreIntegrationTest.java` — 偏好更新 load-merge-write 模式

### 9.1 用户偏好记忆 — MessagesModelHook BEFORE_MODEL 注入

**完整流程**: 预填充 Store → Hook 在每次 LLM 调用前自动注入 → Agent 无感知地获得用户上下文

#### Step 1: 预填充用户画像到 MemoryStore

```java
MemoryStore memoryStore = new MemoryStore();

// 模拟数据，预先填充用户画像
Map<String, Object> profileData = new HashMap<>();
profileData.put("name", "王小明");
profileData.put("age", 28);
profileData.put("email", "wang@example.com");
profileData.put("preferences", List.of("喜欢咖啡", "喜欢阅读"));

StoreItem profileItem = StoreItem.of(List.of("user_profiles"), "user_001", profileData);
memoryStore.putItem(profileItem);
```

#### Step 2: 创建 Hook 自动注入用户画像到 SystemMessage

```java
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
class MemoryInterceptor extends MessagesModelHook {
    @Override
    public String getName() {
        return "memory_interceptor";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        String userId = (String) config.metadata("user_id").orElse(null);
        if (userId == null) {
            return new AgentCommand(previousMessages);
        }

        Store store = config.store();
        // 从记忆存储中加载用户画像
        Optional<StoreItem> itemOpt = store.getItem(List.of("user_profiles"), userId);
        if (itemOpt.isPresent()) {
            Map<String, Object> profile = itemOpt.get().getValue();

            // 将用户上下文注入系统消息
            String userContext = String.format(
                "用户信息：姓名=%s, 年龄=%s, 邮箱=%s, 偏好=%s",
                profile.get("name"),
                profile.get("age"),
                profile.get("email"),
                profile.get("preferences")
            );

            // 查找是否已存在 SystemMessage，更新它
            SystemMessage existingSystemMessage = null;
            int systemMessageIndex = -1;
            for (int i = 0; i < previousMessages.size(); i++) {
                Message msg = previousMessages.get(i);
                if (msg instanceof SystemMessage) {
                    existingSystemMessage = (SystemMessage) msg;
                    systemMessageIndex = i;
                    break;
                }
            }

            SystemMessage enhancedSystemMessage;
            if (existingSystemMessage != null) {
                enhancedSystemMessage = new SystemMessage(
                    existingSystemMessage.getText() + "\n\n" + userContext
                );
            } else {
                enhancedSystemMessage = new SystemMessage(userContext);
            }

            // 构建新的消息列表
            List<Message> newMessages = new ArrayList<>();
            if (systemMessageIndex >= 0) {
                for (int i = 0; i < previousMessages.size(); i++) {
                    if (i == systemMessageIndex) {
                        newMessages.add(enhancedSystemMessage);
                    } else {
                        newMessages.add(previousMessages.get(i));
                    }
                }
            } else {
                newMessages.add(enhancedSystemMessage);
                newMessages.addAll(previousMessages);
            }

            return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
        }

        return new AgentCommand(previousMessages);
    }

    @Override
    public AgentCommand afterModel(List<Message> previousMessages, RunnableConfig config) {
        return new AgentCommand(previousMessages);
    }
}
```

#### Step 3: 组装 Agent + 传入 Store

```java
ReactAgent agent = ReactAgent.builder()
    .name("memory_agent")
    .model(chatModel)
    .hooks(memoryInterceptor)   // Hook injects user profile into SystemMessage
    .saver(new MemorySaver())   // Short-term memory (checkpoint)
    .build();

RunnableConfig config = RunnableConfig.builder()
    .threadId("session_001")
    .addMetadata("user_id", "user_001")  // Hook 用此查找用户画像
    .store(memoryStore)                   // Long-term memory store
    .build();

agent.invoke("请介绍一下我的信息。", config);
// Agent 回复: "王小明, 28岁, 喜欢咖啡和阅读"
```

**关键点**:
- `store` 是通过 `RunnableConfig.builder().store(memoryStore)` 传入的，不是在 Agent builder 上
- Hook 通过 `config.store()` 获取 Store 实例
- `userId` 通过 `config.metadata("user_id")` 传入，Hook 用它查找对应用户的画像
- `UpdatePolicy.REPLACE` 表示整个消息列表替换，不是追加

---

### 9.2 对话摘要 — MessagesModelHook BEFORE_MODEL 压缩

**场景**: 长对话中 LLM 上下文即将溢出，需要压缩历史消息

```java
@HookPositions({HookPosition.BEFORE_MODEL})
public static class MessageSummarizationHook extends MessagesModelHook {

    private final ChatModel summaryModel;
    private final int maxTokensBeforeSummary;
    private final int messagesToKeep;

    public MessageSummarizationHook(
            ChatModel summaryModel,
            int maxTokensBeforeSummary,
            int messagesToKeep
    ) {
        this.summaryModel = summaryModel;
        this.maxTokensBeforeSummary = maxTokensBeforeSummary;
        this.messagesToKeep = messagesToKeep;
    }

    @Override
    public String getName() {
        return "message_summarization";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        // 估算 token 数量（简化版）
        int estimatedTokens = previousMessages.stream()
                .mapToInt(m -> m.getText().length() / 4)
                .sum();

        if (estimatedTokens < maxTokensBeforeSummary) {
            return new AgentCommand(previousMessages);
        }

        int messagesToSummarize = previousMessages.size() - messagesToKeep;
        if (messagesToSummarize <= 0) {
            return new AgentCommand(previousMessages);
        }

        List<Message> oldMessages = previousMessages.subList(0, messagesToSummarize);
        List<Message> recentMessages = previousMessages.subList(
                messagesToSummarize, previousMessages.size()
        );

        // 生成摘要
        String summary = generateSummary(oldMessages);

        // 创建摘要消息
        SystemMessage summaryMessage = new SystemMessage(
                "## 之前对话摘要:\n" + summary
        );

        // 构建新的消息列表：摘要消息 + 最近的消息
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(summaryMessage);
        newMessages.addAll(recentMessages);

        return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
    }

    private String generateSummary(List<Message> messages) {
        StringBuilder conversation = new StringBuilder();
        for (Message msg : messages) {
            conversation.append(msg.getMessageType())
                    .append(": ")
                    .append(msg.getText())
                    .append("\n");
        }

        String summaryPrompt = "请简要总结以下对话:\n\n" + conversation;

        ChatResponse response = summaryModel.call(
                new Prompt(new UserMessage(summaryPrompt))
        );

        return response.getResult().getOutput().getText();
    }
}
```

**使用方式**:

```java
ChatModel summaryModel = chatModel; // 可以用更便宜的模型

MessageSummarizationHook summarizationHook = new MessageSummarizationHook(
    summaryModel,
    4000,  // 4000 tokens 时触发摘要
    20     // 摘要后保留最近 20 条消息
);

ReactAgent agent = ReactAgent.builder()
    .name("my_agent")
    .model(chatModel)
    .hooks(summarizationHook)
    .saver(new MemorySaver())
    .build();

RunnableConfig config = RunnableConfig.builder().threadId("1").build();

agent.call("你好，我叫 bob", config);
agent.call("写一首关于猫的短诗", config);
agent.call("现在对狗做同样的事情", config);
AssistantMessage finalResponse = agent.call("我叫什么名字？", config);
// 输出: "你的名字是 Bob！" — 摘要保留了名字信息
```

**关键点**:
- 摘要用另一个 LLM 调用生成，可以用更便宜的模型
- `messagesToKeep` 控制保留最近多少条消息（避免丢失当前上下文）
- `estimatedTokens` 是简化估算（实际应使用 tokenizer）
- 与 MemorySaver 配合：摘要后 MemorySaver 保存新的 checkpoint

---

### 9.3 偏好学习 — MessagesModelHook AFTER_MODEL 写入 Store

**场景**: 从对话中自动提取用户偏好并持久化

```java
@HookPositions({HookPosition.AFTER_MODEL})
class PreferenceLearningHook extends MessagesModelHook {
    private final MemoryStore store;

    public PreferenceLearningHook(MemoryStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "preference_learning";
    }

    @Override
    public AgentCommand afterModel(List<Message> previousMessages, RunnableConfig config) {
        String userId = (String) config.metadata("user_id").orElse(null);
        if (userId == null) {
            return new AgentCommand(previousMessages);
        }

        // 加载现有偏好
        Optional<StoreItem> prefsOpt = store.getItem(
            List.of("user_data"), userId + "_preferences"
        );
        List<String> prefs = new ArrayList<>();
        if (prefsOpt.isPresent()) {
            Map<String, Object> prefsData = prefsOpt.get().getValue();
            prefs = (List<String>) prefsData.getOrDefault("items", new ArrayList<>());
        }

        // 简单的偏好提取（实际应用中使用NLP）
        for (Message msg : previousMessages) {
            String content = msg.getText().toLowerCase();
            if (content.contains("喜欢") || content.contains("偏好")) {
                prefs.add(msg.getText());

                Map<String, Object> prefsData = new HashMap<>();
                prefsData.put("items", prefs);
                StoreItem item = StoreItem.of(
                    List.of("user_data"), userId + "_preferences", prefsData
                );
                store.putItem(item);
            }
        }

        return new AgentCommand(previousMessages);  // 不修改消息
    }
}
```

**使用方式**:

```java
MemoryStore memoryStore = new MemoryStore();
MessagesModelHook preferenceLearningHook = new PreferenceLearningHook(memoryStore);

ReactAgent agent = ReactAgent.builder()
    .name("learning_agent")
    .model(chatModel)
    .hooks(preferenceLearningHook)
    .saver(new MemorySaver())
    .build();

RunnableConfig config = RunnableConfig.builder()
    .threadId("learning_thread")
    .addMetadata("user_id", "user_004")
    .build();

agent.invoke("我喜欢喝绿茶。", config);
agent.invoke("我偏好早上运动。", config);

// 验证偏好已持久化
Optional<StoreItem> savedPrefs = memoryStore.getItem(
    List.of("user_data"), "user_004_preferences"
);
// savedPrefs.get().getValue() => {items: ["我喜欢喝绿茶。", "我偏好早上运动。"]}
```

**关键点**:
- `AFTER_MODEL` 位置：在 LLM 回复之后执行，不干扰生成过程
- 返回 `new AgentCommand(previousMessages)` 不修改消息——只做"副作用"写入
- 简单示例用关键词匹配，生产应用该用 LLM 提取偏好

---

### 9.4 Tool 方式跨会话记忆 — Agent 主动读写 Store

**场景**: Agent 通过工具主动决定何时读、何时写长期记忆

```java
// 定义请求/响应 record
public record SaveMemoryRequest(List<String> namespace, String key, Map<String, Object> value) {}
public record GetMemoryRequest(List<String> namespace, String key) {}
public record MemoryResponse(String message, Map<String, Object> value) {}

// 创建 saveMemory 工具
BiFunction<SaveMemoryRequest, ToolContext, MemoryResponse> saveMemoryFunction =
    (request, context) -> {
        // 通过 ToolContext 获取 RunnableConfig，再获取 Store
        RunnableConfig runnableConfig = (RunnableConfig) context.getContext()
            .get(AGENT_CONFIG_CONTEXT_KEY);
        Store store = runnableConfig.store();
        StoreItem item = StoreItem.of(request.namespace(), request.key(), request.value());
        store.putItem(item);
        return new MemoryResponse("成功保存到记忆", request.value());
    };

ToolCallback saveMemoryTool = FunctionToolCallback.builder("saveMemory", saveMemoryFunction)
    .description("保存信息到长期记忆。参数：namespace=命名空间列表, key=键, value=值的Map")
    .inputType(SaveMemoryRequest.class)
    .build();

// 创建 getMemory 工具
BiFunction<GetMemoryRequest, ToolContext, MemoryResponse> getMemoryFunction =
    (request, context) -> {
        RunnableConfig runnableConfig = (RunnableConfig) context.getContext()
            .get(AGENT_CONFIG_CONTEXT_KEY);
        Store store = runnableConfig.store();
        Optional<StoreItem> itemOpt = store.getItem(request.namespace(), request.key());
        if (itemOpt.isPresent()) {
            return new MemoryResponse("找到记忆", itemOpt.get().getValue());
        } else {
            return new MemoryResponse("未找到记忆", Map.of());
        }
    };

ToolCallback getMemoryTool = FunctionToolCallback.builder("getMemory", getMemoryFunction)
    .description("从长期记忆中获取信息。参数：namespace=命名空间列表, key=键")
    .inputType(GetMemoryRequest.class)
    .build();
```

**跨会话使用** — 同一个 `memoryStore` 实例，不同 `threadId`:

```java
ReactAgent agent = ReactAgent.builder()
    .name("session_agent")
    .model(chatModel)
    .tools(saveMemoryTool, getMemoryTool)  // Agent 获得读写记忆的能力
    .saver(new MemorySaver())
    .build();

// === 会话 1: 保存信息 ===
RunnableConfig session1 = RunnableConfig.builder()
    .threadId("session_morning")     // ← 会话 1 的 threadId
    .addMetadata("user_id", "user_003")
    .store(memoryStore)              // ← 同一个 Store
    .build();

agent.invoke(
    "记住我的密码是 secret123。用 saveMemory 保存，namespace=[\"credentials\"], " +
    "key=\"user_003_password\", value={\"password\": \"secret123\"}。",
    session1
);

// === 会话 2: 跨会话读取信息 ===
RunnableConfig session2 = RunnableConfig.builder()
    .threadId("session_afternoon")   // ← 会话 2 的不同 threadId
    .addMetadata("user_id", "user_003")
    .store(memoryStore)              // ← 同一个 Store
    .build();

agent.invoke(
    "我的密码是什么？用 getMemory 获取，namespace=[\"credentials\"], key=\"user_003_password\"。",
    session2
);
// Agent 回复: "你的密码是 secret123" — 长期记忆跨会话持久化成功
```

**关键点**:
- Tool 通过 `context.getContext().get(AGENT_CONFIG_CONTEXT_KEY)` 获取 `RunnableConfig`
- 从 `RunnableConfig.store()` 获取 Store 实例
- **同一个 MemoryStore 实例**跨会话共享数据（不同 threadId 但同一个 store 对象）
- 生产环境应使用 `RedisStore` / `DatabaseStore` 代替 `MemoryStore`

---

### 9.5 偏好更新 — load-merge-write 模式

**场景**: 用户偏好变更，需要合并更新而非全量覆盖

```java
// 框架测试中的 load-merge-write 模式
// 来自 GraphStoreIntegrationTest.runUserPreferencesUpdateGraph

// Node 1: 加载现有偏好
.addNode("loadExistingPrefs", node_async(state -> {
    String userId = state.value("userId", "");
    Store prefStore = state.getStore();

    Map<String, Object> currentPrefs = new HashMap<>();
    if (prefStore != null) {
        Optional<StoreItem> existing = prefStore.getItem(
            List.of("users", userId), "preferences"
        );
        if (existing.isPresent()) {
            currentPrefs = new HashMap<>(existing.get().getValue());  // 复制已有值
        }
    }
    return Map.of("currentPreferences", currentPrefs);
}))

// Node 2: 合并新偏好并写回
.addNode("updatePrefs", node_async(state -> {
    Map<String, Object> updatedPrefs = new HashMap<>(currentPrefs);  // 从已有值开始
    if (state.data().containsKey("theme")) {
        updatedPrefs.put("theme", state.value("theme").orElse(null));  // 覆盖特定字段
    }
    if (state.data().containsKey("notifications")) {
        updatedPrefs.put("notifications", state.value("notifications").orElse(null));
    }
    updatedPrefs.put("updatedAt", System.currentTimeMillis());

    prefStore.putItem(StoreItem.of(
        List.of("users", userId), "preferences", updatedPrefs
    ));
    return Map.of("status", "preferences_updated");
}))
```

**关键点**:
- `putItem` 同 namespace+key 会**全量覆盖**，没有 partial update API
- 需要先 `getItem` 读取现有值 → 合并变更 → 再 `putItem` 写回
- 这与 GlobalSessionContext 的 `ReplaceStrategy` 思路一致，但 MemoryStore 需要手动 load-merge-write

---

### 9.6 Store 后端选型

```java
List<Store> stores = Arrays.asList(
    new MemoryStore(),          // 开发环境：内存，重启丢失
    new FileSystemStore(dir),   // 文件系统：简单持久化
    createDatabaseStore(),      // JDBC (H2/MySQL)：关系型持久化
    new RedisStore(),           // Redis：高性能，适合生产
    new MongoStore()            // MongoDB：复杂查询，适合用户画像
);
```

| 后端 | 适用场景 | 持久化 | 查询能力 |
|------|---------|--------|---------|
| `MemoryStore` | 开发/测试 | ❌ 重启丢失 | namespace+key 精确查找 |
| `FileSystemStore` | 单机部署 | ✅ 文件 | namespace+key 精确查找 |
| `DatabaseStore` | 传统部署 | ✅ 数据库 | SQL 查询 |
| `RedisStore` | 生产推荐 | ✅ Redis | namespace+key + TTL |
| `MongoStore` | 复杂画像 | ✅ MongoDB | 丰富查询，适合用户画像场景 |

---

### 9.7 框架 API 速查

```java
// StoreItem 创建
StoreItem item = StoreItem.of(
    List.of("users", "user123", "preferences"),  // 层级命名空间
    "ui_settings",                                 // namespace 内的 key
    Map.of("theme", "dark", "fontSize", 14)       // 值
);

// StoreItem 更新（设置 updatedAt 时间戳）
item.updateValue(Map.of("theme", "light", "fontSize", 16));

// StoreItem 读取
item.getNamespace();   // List<String>
item.getKey();         // String
item.getValue();       // Map<String, Object>
item.getCreatedAt();   // long (epoch ms)
item.getUpdatedAt();   // long (epoch ms)

// Store 读写
Store store = config.store();
Optional<StoreItem> item = store.getItem(List.of("namespace"), "key");
store.putItem(StoreItem.of(List.of("namespace"), "key", Map.of(...)));

// RunnableConfig 中传入 Store
RunnableConfig config = RunnableConfig.builder()
    .threadId("session_001")
    .addMetadata("user_id", "user_001")
    .store(memoryStore)
    .build();
```

---

### 9.8 对 ReaAgent 的映射建议

将上述框架代码模式映射到 ReaAgent 场景：

| 框架模式 | ReaAgent 应用 | Phase |
|----------|--------------|-------|
| **9.1 用户画像注入 (Hook)** | Phase 2: `PlannerAgent` 的 `MemoryInterceptor` 自动注入用户风险偏好 | Phase 2 |
| **9.2 对话摘要 (Hook)** | Phase 2: `PlannerAgent` 的 `SummarizationHook` 防长 prompt 溢出 | Phase 2 |
| **9.3 偏好学习 (Hook)** | Phase 2: `ChatAgent` 的 `PreferenceLearningHook` 从对话提取偏好 | Phase 2 |
| **9.4 Tool 跨会话记忆** | Phase 3: 域 Agent 通过 `getUserProfile` Tool 读取用户画像 | Phase 3 |
| **9.5 load-merge-write** | Phase 2: 编排完成后更新 `recent_summary`（非覆盖） | Phase 2 |

**Phase 1 不引入 MemoryStore，但 MemorySaver 立即需要**：

```java
// Phase 1: 仅 MemorySaver — 对话连续性
MemorySaver plannerSaver = new MemorySaver();
MemorySaver chatSaver = new MemorySaver();

ReactAgent plannerAgent = ReactAgent.builder()
    .name("planner")
    .model(chatModel)
    .saver(plannerSaver)   // ← Phase 1 只需这个
    // .hooks(memoryInterceptor)  ← Phase 2 加
    // .store(memoryStore)        ← Phase 2 加（通过 config）
    .build();

// Phase 2: MemorySaver + MemoryStore + Hook — 用户偏好
MemoryStore memoryStore = new MemoryStore();  // 或 RedisStore
MemoryInterceptor memoryInterceptor = new MemoryInterceptor();

ReactAgent plannerAgent = ReactAgent.builder()
    .name("planner")
    .model(chatModel)
    .saver(plannerSaver)
    .hooks(memoryInterceptor)     // ← Phase 2 新增
    .build();

RunnableConfig config = RunnableConfig.builder()
    .threadId("rea-plan-" + sessionId)
    .addMetadata("user_id", userId)
    .store(memoryStore)            // ← Phase 2 新增
    .build();
```

---

## 10. 总结

| 问题 | 回答 |
|------|------|
| GlobalSessionContext 够用吗？ | **Phase 1 完全够用**。编排控制 + 域状态 + 步骤间上下文传递，都在寄存器层面 |
| 需要引入 MemorySaver 吗？ | **Phase 1 就需要**。PlannerAgent 和 ChatAgent 的对话连续性依赖它 |
| 需要引入 MemoryStore 吗？ | **Phase 2 引入**。用户偏好、历史摘要、跨会话知识 |
| 三者是替代关系吗？ | **不是**。寄存器、存档、长期记忆各司其职，解决不同问题 |
| 最大的风险是什么？ | 把不该存的东西存错地方——比如把编排状态存到 MemoryStore（LLM 无法消费），或把用户偏好存到 GlobalSessionContext（会话结束丢失） |

**一句话**: 用正确的工具做正确的事。寄存器存状态，存档存对话，长期记忆存偏好。Phase 1 用寄存器+存档，Phase 2 加长期记忆。
