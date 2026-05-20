# L0→L1→L2 三层路由架构

## 1. 全景架构图

```mermaid
graph TB
    User["👤 用户输入"] --> BC["BankController<br/>入口 + L0调度"]

    subgraph L0["L0 - Domain Router（无状态）"]
        DR["DomainRouter<br/>qwen-turbo<br/>🧭 领域识别"]
        GCM["全局ChatMemory<br/>所有对话记录<br/>ReadOnlyMemoryAdvisor注入"]
        DR -.->|读取历史| GCM
    end

    BC --> DR
    DR -->|"WEALTH"| WS
    DR -->|"TRANSFER"| TS
    DR -->|"BILL"| BS
    DR -->|"CHAT"| CS

    subgraph L1["L1 - 领域路由层（各有状态）"]
        direction LR
        subgraph WL1["💰 理财L1 - WealthService"]
            WCM["理财ChatMemory"]
            WCR["ContextRouter<br/>F/S/R"]
            WIR["IntentionRouter<br/>推荐/解读"]
            WRS["RoutingService<br/>消歧"]
        end
        subgraph TL1["💸 转账L1 - TransferService"]
            TCM["转账ChatMemory"]
            TCR["ContextRouter<br/>F/S"]
        end
        subgraph BL1["📊 账单L1 - BillService"]
            BCM["账单ChatMemory"]
            BCR["ContextRouter<br/>F/S"]
        end
        subgraph CL1["💬 闲聊L1 - ChatService"]
            CCM["32B+ ChatModel<br/>直接对话"]
        end
    end

    subgraph L2["L2 - 子智能体（不变）"]
        WCG["WealthConsultGraph<br/>理财推荐"]
        WIG["WealthInterpretGraph<br/>理财解读"]
        TG["TransferGraph<br/>转账"]
        BG["BillQueryGraph<br/>账单查询"]
    end

    WRS --> WCG
    WRS --> WIG
    TCR --> TG
    BCR --> BG
```

## 2. 数据流 - 跨领域恢复

```mermaid
sequenceDiagram
    participant U as 用户
    participant BC as BankController
    participant L0 as DomainRouter(L0)
    participant TS as TransferService(L1)
    participant BS as BillService(L1)

    U->>BC: "我要转账"
    BC->>L0: route(history, "我要转账")
    L0-->>BC: TRANSFER
    BC->>TS: handle("我要转账", sessionId)
    TS->>TS: SWITCH_NEW → TransferGraph
    TS-->>BC: Q:"转给谁？"

    U->>BC: "张三"
    BC->>L0: route(history, "张三")
    L0-->>BC: TRANSFER
    BC->>TS: handle("张三", sessionId)
    TS->>TS: FOLLOW_UP → resumeGraph
    TS-->>BC: Q:"转多少？"

    U->>BC: "算了查账单"
    BC->>L0: route(history, "算了查账单")
    L0-->>BC: BILL
    BC->>BS: handle("算了查账单", sessionId)
    BS->>BS: SWITCH_NEW → BillQueryGraph
    Note over TS: activeThread保留<br/>ChatMemory只记本领域消息
    BS-->>BC: Q:"哪个时间段？"

    U->>BC: "继续转账吧，500块"
    BC->>L0: route(history, "继续转账吧，500块")
    L0-->>BC: TRANSFER
    BC->>TS: handle("继续转账吧，500块", sessionId)
    TS->>TS: FOLLOW_UP(有activeThread) → resumeGraph
    Note over TS: accumulatedParams含receiver=张三<br/>"500块"提取为amount
    TS-->>BC: ✅ 转账成功！向张三转账500元
```

## 3. ChatMemory 隔离架构

```mermaid
graph LR
    subgraph "全局 ChatMemory (L0用)"
        GM1["U:我要转账"]
        GM2["A:转给谁？"]
        GM3["U:张三"]
        GM4["A:转多少？"]
        GM5["U:算了查账单"]
        GM6["A:哪个时间段？"]
        GM7["U:继续转账吧，500块"]
    end

    subgraph "转账 ChatMemory"
        TM1["U:我要转账"]
        TM2["A:转给谁？"]
        TM3["U:张三"]
        TM4["A:转多少？"]
        TM5["U:继续转账吧，500块"]
    end

    subgraph "账单 ChatMemory"
        BM1["U:算了查账单"]
        BM2["A:哪个时间段？"]
    end

    subgraph "理财 ChatMemory"
        WM1["(空或理财相关消息)"]
    end
```

## 4. 各L1 Service对比

| 特性 | 💰 理财 L1 | 💸 转账 L1 | 📊 账单 L1 | 💬 闲聊 L1 |
|------|-----------|-----------|-----------|-----------|
| **Service** | WealthService | TransferService | BillService | ChatService |
| **ChatMemory** | 独立实例 | 独立实例 | 独立实例 | 无(用L0全局) |
| **ContextRouter** | F/S/R 三种 | F/S 两种 | F/S 两种 | 不需要 |
| **IntentionRouter** | ✅ 推荐/解读 | ❌ | ❌ | ❌ |
| **RoutingService** | ✅ 消歧 | ❌ | ❌ | ❌ |
| **activeThread** | ✅ 有 | ✅ 有 | ✅ 有 | ❌ 无 |
| **suspendedAgents** | ✅ 有(推荐↔解读) | ❌ 无 | ❌ 无 | ❌ 无 |
| **L2子智能体** | 推荐Graph + 解读Graph | TransferGraph | BillQueryGraph | 无 |
| **FOLLOW_UP时** | resumeGraph/回答追问 | resumeGraph | resumeGraph | N/A |
| **SWITCH_NEW时** | 新建thread+graph | 新建thread+graph | 新建thread+graph | N/A |
| **完成后** | activeThread=null | activeThread=null | activeThread=null | N/A |

## 5. L0 DomainRouter 设计

```
输入: 全局ChatMemory历史 + 当前用户消息
模型: qwen-turbo (轻量快速)
输出: { domain: "WEALTH" | "TRANSFER" | "BILL" | "CHAT", confidence: 0.0-1.0 }

判断要点:
- 看用户当前消息的核心意图属于哪个领域
- 结合对话历史判断上下文
- 完全无状态，不管理threadId
- 每轮都重新判断（闲聊中说"查账单"会自动路由到BILL）
```

## 6. 转账/账单 L1 的 FOLLOW_UP 判定

```mermaid
graph TD
    L1["L1 Service 收到消息"] --> CR["ContextRouter<br/>FOLLOW_UP or SWITCH_NEW?"]
    CR -->|"FOLLOW_UP"| AT{"有activeThread?"}
    AT -->|是| RG["resumeGraph<br/>恢复L2子智能体"]
    AT -->|否| SN2["降级为SWITCH_NEW<br/>新建thread+graph"]
    CR -->|"SWITCH_NEW"| SN["新建threadId<br/>执行L2 graph<br/>设为activeThread"]
    RG --> DONE["L2完成?"]
    SN --> DONE
    SN2 --> DONE
    DONE -->|"是"| CLEAR["activeThread = null"]
    DONE -->|"否(追问)"| RET["返回追问给用户"]
```

## 7. 理财 L1 内部路由（复用现有逻辑）

```mermaid
graph TD
    WS["WealthService 收到消息"] --> WCR["ContextRouter<br/>FOLLOW_UP/SWITCH_NEW/RESUME"]
    WCR -->|"FOLLOW_UP"| FH["有activeThread?<br/>是→resumeGraph<br/>否→降级SWITCH_NEW"]
    WCR -->|"SWITCH_NEW"| WIR["IntentionRouter<br/>推荐? 解读?"]
    WCR -->|"RESUME"| RH["恢复suspended thread"]
    WIR -->|"明确"| EXE["执行对应L2 graph"]
    WIR -->|"歧义"| DIS["RoutingService消歧<br/>追问用户"]
    DIS -->|"回答后"| EXE
    FH --> EXE
    RH --> EXE
    EXE --> DONE["完成→activeThread=null"]
```

## 8. 文件变更清单

### 新增文件
| 文件 | 说明 |
|------|------|
| `domain/DomainRouter.java` | L0领域路由器 |
| `domain/WealthService.java` | 理财L1（复用现有路由逻辑） |
| `domain/TransferService.java` | 转账L1（极简F/S路由） |
| `domain/BillService.java` | 账单L1（极简F/S路由） |
| `domain/ChatService.java` | 闲聊L1（32B+模型） |
| `prompts/l0-domain.st` | L0领域路由prompt |

### 修改文件
| 文件 | 说明 |
|------|------|
| `BankController.java` | 重构: L0调度→分发L1 |
| `ModelConfig.java` | 新增domainChatClient, chatChatClient |
| `application.yml` | 新增models.domain, models.chat配置 |

### 不变文件
| 文件 | 说明 |
|------|------|
| `ContextRouter.java` | 被L1 Service复用 |
| `IntentionRouter.java` | 被WealthService复用 |
| `RoutingService.java` | 被WealthService复用 |
| `GraphExecutionService.java` | 被所有L1 Service复用 |
| `AgentStateManager.java` | 被L1 Service使用 |
| 4个L2 Graph Config | 完全不变 |
