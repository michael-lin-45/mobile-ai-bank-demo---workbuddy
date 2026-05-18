# AI手机银行 架构设计文档

> 基于 Spring AI Alibaba 1.1.2.0 | Master-Slave 多意图对话架构

---

## 一、系统概述

### 1.1 解决的核心问题

手机银行场景下的多轮多任务对话：用户在对话过程中可能**切换意图**、**中断当前操作**、**恢复之前的任务**，系统需要正确管理这些状态转换。

```
典型对话:
  用户: "转账给张三"          → 开始转账，发现缺金额，提问
  用户: "帮我查一下上个月账单"  → 切换！挂起转账，开始查账单
  用户: "回到转账"            → 恢复！回到转账的提问点继续
  用户: "500"                → 追问！回答转账金额，完成转账
```

### 1.2 核心能力

| 能力 | 说明 |
|------|------|
| **意图切换** | 用户从A任务跳到B任务，A被挂起，B启动 |
| **意图中断** | 子智能体提问时中断当前流程，等待用户回答 |
| **意图恢复** | 用户主动回到之前挂起的任务，从断点继续 |
| **追问回答** | 用户回答子智能体的提问，继续当前流程 |

---

## 二、架构总览

### 2.1 分层架构图

```mermaid
graph TB
    subgraph HTTP["HTTP Layer"]
        API["POST /api/bank/chat<br/>{sessionId, message}"]
    end

    subgraph Master["BankController (Master)"]
        Step1["Step1: 意图类型判断<br/>qwen-turbo ~4B"]
        Step2["Step2: 上下文改写+意图识别<br/>qwen-turbo ~8B"]
        Step3["Step3: 路由执行"]
        FOLLOW_UP["FOLLOW_UP<br/>resume当前线程"]
        SWITCH_NEW["SWITCH_NEW<br/>挂起当前+新建线程"]
        RESUME["RESUME<br/>恢复挂起线程"]
    end

    subgraph StateMgr["AgentStateManager (状态管理器)"]
        Active["activeThreads<br/>sessionId → threadId"]
        Suspended["suspendedAgents<br/>sessionId → {intent → threadId}"]
        Registry["graphRegistry<br/>intent → CompiledGraph"]
    end

    subgraph Slaves["Sub-Agent Graphs (Slaves)"]
        subgraph TG["TransferGraph"]
            TE["extractParams<br/>(32B模型)"]
            TR["paramRouter"]
            TAR["askReceiver<br/>⚡interruptBefore"]
            TAM["askAmount<br/>⚡interruptBefore"]
            TEX["executeTransfer"]
        end
        subgraph BG["BillQueryGraph"]
            BE["extractParams<br/>(32B模型)"]
            BR["paramRouter"]
            BAT["askTime<br/>⚡interruptBefore"]
            BAY["askType<br/>⚡interruptBefore"]
            BEX["executeBillQuery"]
        end
    end

    API --> Step1
    Step1 -->|FOLLOW_UP+有activeThread| FOLLOW_UP
    Step1 -->|SWITCH_NEW/RESUME/fallback| Step2
    Step2 --> Step3
    Step3 --> FOLLOW_UP
    Step3 --> SWITCH_NEW
    Step3 --> RESUME

    FOLLOW_UP --> Active
    SWITCH_NEW --> Active
    SWITCH_NEW --> Suspended
    RESUME --> Suspended
    RESUME --> Active
    Active --> Slaves
    Suspended --> Slaves
    Registry --> Slaves

    TE --> TR
    TR -->|ASK_RECEIVER| TAR
    TR -->|ASK_AMOUNT| TAM
    TR -->|ALL_GOOD| TEX
    TAR -->|循环| TR
    TAM -->|循环| TR

    BE --> BR
    BR -->|ASK_TIME| BAT
    BR -->|ASK_TYPE| BAY
    BR -->|ALL_GOOD| BEX
    BAT -->|循环| BR
    BAY -->|循环| BR
```

### 2.2 核心设计原则

| 原则 | 说明 |
|------|------|
| **Master-Slave 分离** | Controller 只管"哪个workflow"+"何时切换"，Graph 只管"怎么执行" |
| **通信协议极简** | Graph 输出只有两种：完成结果 或 InterruptionMetadata |
| **状态前缀隔离** | 每个 Sub-Agent 用 `transfer.*` / `bill.*` 前缀管理自己的 OverAllState key |
| **中断不抛异常** | Spring AI Alibaba 通过 `InterruptionMetadata` 在 stream 中表达中断，不是异常 |
| **Checkpoint 驱动恢复** | 每个 threadId 对应一个 checkpoint 链，resume 从 checkpoint 断点继续 |

---

## 三、核心组件详解

### 3.1 BankController（Master 编排器）

**职责**：接收用户消息 → 意图判断 → 路由分发 → 处理中断/完成

**三阶段处理流程**：

```mermaid
flowchart TD
    Input["用户消息 + 会话上下文<br/>(currentAgent, pendingAgents)"]

    subgraph P1["Phase 1: 意图类型判断 (qwen-turbo ~4B)"]
        L0["只看用户最后一句 + 上下文<br/>不做改写，只判断类型"]
        L0_Out["输出: FOLLOW_UP / SWITCH_NEW / RESUME"]
        L0 --> L0_Out
    end

    subgraph P2["Phase 2: 上下文改写 + 意图识别 (qwen-turbo ~8B)"]
        L1["一个LLM调用同时完成:<br/>1. 上下文改写: '帮我查一下'→'查询账单明细'<br/>2. 意图识别: → intent=bill_query<br/>3. 路由判断: → SWITCH_NEW 或 RESUME"]
        L1_Out["输出: rewritten_input + intent_name<br/>+ route_type + resume_target"]
        L1 --> L1_Out
    end

    subgraph P3["Phase 3: 路由执行"]
        R_FOLLOW["FOLLOW_UP<br/>→ resume当前活跃线程"]
        R_SWITCH["SWITCH_NEW<br/>→ 挂起当前 + 创建新线程"]
        R_RESUME["RESUME<br/>→ 从suspendedAgents恢复线程"]
    end

    Input --> P1
    L0_Out -->|FOLLOW_UP + activeThread存在| R_FOLLOW
    L0_Out -->|SWITCH_NEW / RESUME<br/>或 FOLLOW_UP+activeThread为空| P2
    L1_Out --> R_SWITCH
    L1_Out --> R_RESUME
```

### 3.2 AgentStateManager（状态管理器）

**三张核心数据表**：

```mermaid
erDiagram
    Session ||--o| ActiveThread : has
    Session ||--o{ SuspendedAgent : has
    Intent ||--|| CompiledGraph : maps_to

    ActiveThread {
        string sessionId PK
        string threadId
    }

    SuspendedAgent {
        string sessionId PK
        string intent PK
        string threadId
        datetime suspendedAt
        datetime expiresAt
    }

    CompiledGraph {
        string intent PK
        string graphBean
    }
```

**生命周期说明**：

| 数据表 | 何时写入 | 何时读取 | 何时清除 |
|--------|----------|----------|----------|
| activeThreads | SWITCH_NEW/RESUME/中断时设置 | FOLLOW_UP时读取threadId | 任务完成时清空为null |
| suspendedAgents | 意图切换时挂起旧线程 | RESUME时按intent查找 | 恢复时移除 / 超时30min清理 |
| graphRegistry | 启动时注册 | 每次执行时按intent获取Graph | 永不清除 |

### 3.3 Sub-Agent Graph（Slave 工作流）

**TransferGraph 内部结构**：

```mermaid
stateDiagram-v2
    [*] --> extractParams: START
    extractParams --> paramRouter: 提取参数(32B模型)
    
    paramRouter --> askReceiver: ASK_RECEIVER<br/>(缺收款人,设question)
    paramRouter --> askAmount: ASK_AMOUNT<br/>(缺金额,设question)
    paramRouter --> executeTransfer: ALL_GOOD<br/>(参数齐全)
    
    askReceiver --> paramRouter: 提取receiver,循环检查
    askAmount --> paramRouter: 提取amount,循环检查
    executeTransfer --> [*]: END

    note right of askReceiver: ⚡ interruptBefore<br/>执行前中断,等待用户回答
    note right of askAmount: ⚡ interruptBefore<br/>执行前中断,等待用户回答
```

**BillQueryGraph 内部结构**：

```mermaid
stateDiagram-v2
    [*] --> extractParams: START
    extractParams --> paramRouter: 提取参数(32B模型)
    
    paramRouter --> askTime: ASK_TIME<br/>(缺时间范围,设question)
    paramRouter --> askType: ASK_TYPE<br/>(缺支出类型,设question)
    paramRouter --> executeBillQuery: ALL_GOOD<br/>(参数齐全)
    
    askTime --> paramRouter: 提取timePeriod,循环检查
    askType --> paramRouter: 提取expenseType,循环检查
    executeBillQuery --> [*]: END

    note right of askTime: ⚡ interruptBefore<br/>执行前中断,等待用户回答
    note right of askType: ⚡ interruptBefore<br/>执行前中断,等待用户回答
```

---

## 四、时序图：6种场景

### 4.1 场景1：全新意图（SWITCH_NEW，参数齐全，直接完成）

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as Controller
    participant SM as AgentStateManager
    participant G as TransferGraph
    participant LLM as LLM(32B)

    U->>C: "转账给张三500元"
    C->>C: Step1: 4B判断 → SWITCH_NEW
    C->>C: Step2: 8B改写+识别 → intent=transfer
    C->>SM: setActiveThread(session, t1)
    C->>G: stream({messages:"转账给张三500元"}, config[t1])
    G->>LLM: 32B提取参数
    LLM-->>G: receiver=张三, amount=500
    G->>G: paramRouter → ALL_GOOD
    G->>G: executeTransfer
    G-->>C: stream完成(无InterruptionMetadata)
    C->>SM: completeAgent() → activeThread=null
    C-->>U: {COMPLETED, "转账成功！已向张三转账500元"}
```

### 4.2 场景2：全新意图（SWITCH_NEW，参数不全，触发中断）

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as Controller
    participant SM as AgentStateManager
    participant G as TransferGraph
    participant LLM as LLM(32B)

    U->>C: "转账给张三"
    C->>C: Step1: 4B判断 → SWITCH_NEW
    C->>C: Step2: 8B改写+识别 → intent=transfer
    C->>SM: setActiveThread(session, t1)
    C->>G: stream({messages:"转账给张三"}, config[t1])
    G->>LLM: 32B提取参数
    LLM-->>G: receiver=张三, amount=null
    G->>G: paramRouter → ASK_AMOUNT<br/>question="请问您要转多少金额？"
    G->>G: 走向askAmount → ⚡interruptBefore!
    G-->>C: InterruptionMetadata
    C->>SM: suspendAgent(session, TRANSFER, t1)
    C->>SM: setActiveThread(session, t1) [保持活跃,等回答]
    C-->>U: {INTERRUPTED, question:"请问您要转多少金额？"}
```

### 4.3 场景3：Follow-Up（回答子智能体提问，直接resume）

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as Controller
    participant SM as AgentStateManager
    participant G as TransferGraph
    participant LLM as LLM(32B)

    Note over SM: 当前状态: activeThread=t1<br/>suspended={TRANSFER→t1}

    U->>C: "500"
    C->>C: Step1: 4B判断 → FOLLOW_UP
    C->>SM: getActiveThread(session) → t1 (存在!)
    Note over C: 直接resume,不改写!

    C->>G: updateState(config[t1],<br/>{messages:"500", _needsUserInput:false})
    C->>G: stream(null, config[t1].withResume())
    G->>G: askAmount执行
    G->>LLM: 32B提取参数 from "500"
    LLM-->>G: amount=500
    G->>G: paramRouter → ALL_GOOD
    G->>G: executeTransfer → END
    G-->>C: stream完成
    C->>SM: completeAgent() → activeThread=null<br/>移除TRANSFER from suspended
    C-->>U: {COMPLETED, "转账成功！已向张三转账500元"}
```

### 4.4 场景4：意图切换（挂起当前任务 + 启动新任务）

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as Controller
    participant SM as AgentStateManager
    participant TG as TransferGraph
    participant BG as BillQueryGraph
    participant LLM as LLM(32B)

    Note over SM: 当前状态: activeThread=t1<br/>(TRANSFER等待askAmount回答)

    U->>C: "查一下上个月账单"
    C->>C: Step1: 4B判断 → SWITCH_NEW
    C->>C: Step2: 8B改写+识别 → intent=bill_query

    C->>SM: suspendAgent(session, TRANSFER, t1)
    Note over SM: suspended={TRANSFER→t1}

    C->>SM: setActiveThread(session, t2)
    C->>BG: stream({messages:"查一下上个月账单"}, config[t2])
    BG->>LLM: 32B提取参数
    LLM-->>BG: timePeriod=null, expenseType=null
    BG->>BG: paramRouter → ASK_TIME<br/>question="请问查哪个时间段的账单？"
    BG->>BG: 走向askTime → ⚡interruptBefore!
    BG-->>C: InterruptionMetadata

    C->>SM: suspendAgent(session, BILL_QUERY, t2)
    Note over SM: suspended={TRANSFER→t1, BILL_QUERY→t2}
    C->>SM: setActiveThread(session, t2) [保持活跃]
    C-->>U: {INTERRUPTED, question:"请问查哪个时间段的账单？"}
```

### 4.5 场景5：意图恢复（RESUME，回到挂起任务）

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as Controller
    participant SM as AgentStateManager
    participant TG as TransferGraph
    participant BG as BillQueryGraph

    Note over SM: 当前状态: activeThread=t2<br/>suspended={TRANSFER→t1, BILL_QUERY→t2}

    U->>C: "回到转账"
    C->>C: Step1: 4B判断 → RESUME
    C->>C: Step2: 8B改写+识别 → intent=transfer, resume_target=TRANSFER

    C->>SM: suspendAgent(session, BILL_QUERY, t2)
    Note over SM: suspended={TRANSFER→t1, BILL_QUERY→t2}<br/>(BILL_QUERY重新挂起)

    C->>SM: getSuspendedThread(session, TRANSFER) → t1
    C->>SM: resumeAgent(session, TRANSFER)
    Note over SM: suspended={BILL_QUERY→t2}<br/>(TRANSFER已移除)
    C->>SM: setActiveThread(session, t1)

    C->>TG: updateState(config[t1],<br/>{messages:"回到转账", _needsUserInput:false})
    C->>TG: stream(null, config[t1].withResume())

    TG->>TG: askAmount执行
    Note over TG: 32B提取: "回到转账" → amount=null<br/>(这不是金额回答)
    TG->>TG: paramRouter → 仍缺amount → ASK_AMOUNT<br/>question="请问您要转多少金额？"
    TG->>TG: 走向askAmount → ⚡interruptBefore再次触发!
    TG-->>C: InterruptionMetadata

    C->>SM: suspendAgent(session, TRANSFER, t1) [重新挂起]
    C->>SM: setActiveThread(session, t1) [保持活跃]
    C-->>U: {INTERRUPTED, question:"请问您要转多少金额？"}
```

### 4.6 场景6：Follow-Up 但 activeThread 为空（Fallback 到 SWITCH_NEW）

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as Controller
    participant SM as AgentStateManager
    participant LLM8 as LLM(8B)

    Note over SM: 当前状态: activeThread=null<br/>suspended={} (无任何任务)

    U->>C: "那昨天的呢"
    C->>C: Step1: 4B判断 → FOLLOW_UP
    C->>SM: getActiveThread(session) → null!
    C->>SM: 无suspendedAgents

    Note over C: Fallback! 走Step2改写+识别

    C->>LLM8: Step2: 8B改写+识别
    Note over LLM8: 输入: "那昨天的呢"<br/>改写: → "查询昨天的账单明细"<br/>识别: intent=bill_query<br/>路由: SWITCH_NEW
    LLM8-->>C: rewritten="查询昨天的账单明细"<br/>intent=bill_query, route=SWITCH_NEW

    Note over C: → 走SWITCH_NEW流程<br/>创建新线程启动billQueryGraph
```

---

## 五、架构图+流程结合图

### 5.1 完整请求处理流程（架构视角）

```mermaid
flowchart TB
    Input["用户消息<br/>{sessionId, message}"]

    subgraph Routing["意图路由层"]
        PreCheck["L0PreChecker<br/>确定性规则<br/>(取消关键词/短回答)"]
        ModelRoute["L0ModelRouter<br/>4B模型路由<br/>(FOLLOW_UP/SWITCH_NEW/RESUME)"]
        Rewrite["L1IntentRewriter<br/>8B模型改写+识别<br/>(上下文改写→意图识别)"]
    end

    subgraph Dispatch["路由执行层"]
        D_FU["FOLLOW_UP<br/>(有activeThread)"]
        D_SN["SWITCH_NEW"]
        D_RM["RESUME"]
        D_CA["CANCEL"]
        D_AQ["ANSWER_QUESTION<br/>(短回答直接resume)"]
    end

    subgraph Exec["Graph 执行层"]
        Start["stream(input, newConfig)"]
        Resume["updateState() +<br/>stream(null, withResume())"]

        subgraph Graphs["Sub-Agent Graphs"]
            Transfer["TransferGraph"]
            Bill["BillQueryGraph"]
        end
    end

    subgraph Output["输出处理"]
        Complete["✅ 正常完成<br/>→ 清理activeThread<br/>→ 返回结果"]
        Interrupt["⚡ 中断提问<br/>→ suspendAgent<br/>→ 保持activeThread<br/>→ 返回question"]
    end

    Input --> PreCheck
    PreCheck -->|未命中| ModelRoute
    PreCheck -->|命中确定性规则| D_CA
    PreCheck -->|命中短回答| D_AQ
    ModelRoute -->|FOLLOW_UP+有activeThread| D_FU
    ModelRoute -->|SWITCH_NEW/RESUME/fallback| Rewrite
    Rewrite -->|SWITCH_NEW| D_SN
    Rewrite -->|RESUME| D_RM

    D_FU --> Resume
    D_SN --> Start
    D_RM --> Resume
    D_AQ --> Resume

    Start --> Graphs
    Resume --> Graphs
    Graphs -->|无InterruptionMetadata| Complete
    Graphs -->|InterruptionMetadata| Interrupt
```

### 5.2 AgentStateManager 状态机

```mermaid
stateDiagram-v2
    [*] --> IDLE: 初始状态

    IDLE --> RUNNING: SWITCH_NEW<br/>(创建线程+设活跃)

    RUNNING --> WAITING: 子智能体中断提问<br/>(interruptBefore触发)
    RUNNING --> IDLE: 任务完成<br/>(清空activeThread)

    WAITING --> RUNNING: FOLLOW_UP<br/>(用户回答→resume)
    WAITING --> SUSPENDED: SWITCH_NEW<br/>(用户切换意图→挂起当前)

    SUSPENDED --> RUNNING: RESUME<br/>(用户说"回到xxx"→恢复线程)
    SUSPENDED --> EXPIRED: 超时30分钟

    RUNNING --> SUSPENDED: SWITCH_NEW<br/>(被新意图替换→挂起)

    note right of WAITING: activeThread保持<br/>(等待用户回答)
    note right of SUSPENDED: 加入suspendedAgents<br/>(按intent索引)
    note left of RUNNING: 1个活跃线程<br/>+ N个挂起线程(最多3个)
```

### 5.3 Graph 内部执行与中断恢复流程

```mermaid
sequenceDiagram
    participant C as Controller
    participant G as TransferGraph
    participant LLM as LLM(32B)
    participant CP as MemorySaver<br/>(Checkpoint)

    rect rgb(230, 245, 255)
        Note over C,CP: 首次执行: "转账给张三"
        C->>G: stream({messages:"转账给张三"}, config[t1])
        G->>LLM: extractParams: 提取receiver和amount
        LLM-->>G: receiver=张三, amount=null
        G->>G: paramRouter: 缺amount → ASK_AMOUNT<br/>设置question="转多少?"
        Note over G: 走向askAmount → ⚡interruptBefore触发!
        G->>CP: 保存checkpoint<br/>{nextNode:"askAmount", state:{receiver:"张三"}}
        G-->>C: InterruptionMetadata
    end

    rect rgb(255, 245, 230)
        Note over C,CP: 恢复执行: 用户回答"500"
        C->>G: updateState(config[t1],<br/>{messages:"500", _needsUserInput:false})
        G->>CP: 更新checkpoint状态
        C->>G: stream(null, config[t1].withResume())
        G->>CP: 读取checkpoint → nextNode="askAmount"
        Note over G: shouldInterruptBefore("askAmount", null)<br/>→ previousNodeId=null → 不中断!
        G->>LLM: askAmount: 从"500"提取amount
        LLM-->>G: amount=500
        G->>G: paramRouter: 参数齐全 → ALL_GOOD
        G->>G: executeTransfer → "转账成功!"
        G-->>C: stream完成(无InterruptionMetadata)
    end
```

---

## 六、数据模型

### 6.1 OverAllState Key 规范

#### 公共 Key（所有 Graph 共享）

| Key | Strategy | 类型 | 说明 |
|-----|----------|------|------|
| `messages` | Append | String | 用户输入历史 |
| `_needsUserInput` | Replace | Boolean | 是否需要中断提问 |
| `_question` | Replace | String | 向用户提问的话术 |
| `_paramName` | Replace | String | 当前缺的参数名 |
| `_userAnswer` | Replace | String | 用户对提问的回答 |
| `_outputContent` | Replace | String | 输出内容 |
| `_outputType` | Replace | Enum | TEXT/CONFIRMATION/PROGRESS/ERROR |
| `_isFinal` | Replace | Boolean | 是否最终输出 |
| `_isWriteOp` | Replace | Boolean | 是否写操作 |

#### TransferGraph 专用 Key

| Key | Strategy | 类型 | 说明 |
|-----|----------|------|------|
| `transfer.receiver` | Replace | String | 收款人 |
| `transfer.amount` | Replace | BigDecimal | 转账金额 |
| `transfer.purpose` | Replace | String | 用途(可选) |

#### BillQueryGraph 专用 Key

| Key | Strategy | 类型 | 说明 |
|-----|----------|------|------|
| `bill.timePeriod` | Replace | String | 时间范围 |
| `bill.expenseType` | Replace | String | 支出类型 |

### 6.2 API 请求/响应格式

**请求**:
```json
POST /api/bank/chat?sessionId=s123
{
  "message": "转账给张三500元"
}
```

**响应 — 正常完成**:
```json
{
  "status": "COMPLETED",
  "content": "转账成功！已向张三转账500元",
  "intent": "TRANSFER"
}
```

**响应 — 中断提问**:
```json
{
  "status": "INTERRUPTED",
  "question": "请问您要转多少金额？",
  "intent": "TRANSFER",
  "threadId": "s123_a1b2c3d4"
}
```

**响应 — 错误**:
```json
{
  "status": "ERROR",
  "message": "未识别的意图",
  "intent": null
}
```

---

## 七、LLM 调用规范

### 7.1 模型分配

| 用途 | 模型 | 大小 | 配置项 |
|------|------|------|--------|
| 意图类型判断 | qwen-turbo | ~4B | `routing.model.routing-model` |
| 上下文改写+意图识别 | qwen-turbo | ~8B | `routing.model.rewrite-model` |
| 参数提取 | qwen-plus | ~32B | `routing.model.planning-model` |
| 主对话模型 | qwen-plus | 中等 | `spring.ai.dashscope.chat.options.model` |

### 7.2 Prompt 模板

**l0-routing.st** — Step1 意图类型判断:
- 输入: 用户消息 + currentAgent + pendingAgents
- 输出: `{route_type: FOLLOW_UP/SWITCH_NEW/RESUME, confidence, reasoning}`
- 注意: 需要在运行时从 IntentRegistry 填充 `{intent_list}`

**l1-rewrite.st** — Step2 上下文改写+意图识别(合并):
- 输入: 用户消息 + 上下文 + 已注册意图
- 输出: `{rewritten_input, intent_name, route_type, resume_target, confidence}`
- 一个调用完成改写和识别

**transfer-extract.st** — 转账参数提取(32B):
- 输入: 用户消息
- 输出: `{receiver, amount, purpose}`
- 只提取明确提到的参数，不猜测

**bill-extract.st** — 账单参数提取(32B):
- 输入: 用户消息
- 输出: `{timePeriod, expenseType}`
- 保留用户原始时间表述

---

## 八、Spring AI Alibaba 关键 API 用法

### 8.1 创建 Graph 并执行

```java
// 1. 定义 KeyStrategies
StateGraph graph = new StateGraph(() -> {
    Map<String, KeyStrategy> s = new HashMap<>();
    s.put("messages", new AppendStrategy());
    s.put("_needsUserInput", new ReplaceStrategy());
    s.put("transfer.receiver", new ReplaceStrategy());
    // ... 注册所有用到的key
    return s;
});

// 2. 添加节点和边
graph.addNode("extractParams", this::extractParamsNode);
graph.addNode("paramRouter", this::paramRouterNode);
graph.addNode("askReceiver", this::askReceiverNode);
// ...
graph.addEdge(StateGraph.START, "extractParams");
graph.addConditionalEdges("paramRouter", ..., Map.of("ASK_RECEIVER","askReceiver",...));
graph.addEdge("askReceiver", "paramRouter");
graph.addEdge("executeTransfer", StateGraph.END);

// 3. 编译(含interruptBefore + checkpoint)
SaverConfig saverConfig = SaverConfig.builder().register(new MemorySaver()).build();
CompiledGraph compiled = graph.compile(CompileConfig.builder()
    .saverConfig(saverConfig)
    .interruptBefore(List.of("askReceiver", "askAmount"))
    .recursionLimit(50)
    .build());

// 4. 首次执行
RunnableConfig config = RunnableConfig.builder().threadId("t1").build();
Flux<NodeOutput> flux = compiled.stream(Map.of("messages", "转账给张三"), config);

// 5. 检测中断
AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
for (NodeOutput output : flux.toIterable()) {
    lastOutput.set(output);
}
if (lastOutput.get() instanceof InterruptionMetadata) {
    // 中断! 读取question
    OverAllState state = compiled.stateOf(config)
        .map(StateSnapshot::getState).orElse(null);
    String question = (String) state.value("_question", null);
}
```

### 8.2 恢复执行

```java
// 1. 注入用户回答
RunnableConfig updatedConfig = compiled.updateState(config,
    Map.of("messages", "500", "_needsUserInput", false), null);

// 2. 带 resume 恢复
RunnableConfig resumeConfig = updatedConfig.withResume();
Flux<NodeOutput> flux = compiled.stream(null, resumeConfig);

// 3. 处理输出
for (NodeOutput output : flux.toIterable()) {
    if (output instanceof InterruptionMetadata) {
        // 再次中断(多轮提问)
    }
    // 正常处理...
}
```

---

## 九、已知限制与未来优化

| 项目 | 当前 | 未来 |
|------|------|------|
| 状态持久化 | ConcurrentHashMap(内存) | Redis/DB |
| 参数提问策略 | 每次缺一个问一个 | 可合并多参数一次问 |
| Graph 实例化 | 每次执行新建 | Bean 池复用 |
| 版本 Bug | 1.1.2.0 resume 状态丢失(需注册所有KeyStrategy规避) | 升级到含 PR#4526 修复的版本 |
| 并发安全 | 单 JVM ConcurrentHashMap | 分布式锁 |
| PlanningAgent | 未实现 | SWITCH_COMPLEX 场景支持 |
