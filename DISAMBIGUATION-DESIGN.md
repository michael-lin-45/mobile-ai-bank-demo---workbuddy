# 意图消歧层设计文档

## 1. 问题定义

### 1.1 现状

当前系统中，Phase2 (8B模型) 将用户输入映射到唯一意图：
- `TRANSFER` → 转账Graph
- `BILL_QUERY` → 账单Graph

如果Phase2返回的意图不在注册表中 → `fuzzyMatchIntent` → 仍然找不到 → ERROR。

### 1.2 新增意图

| 意图名 | 描述 | 需要提取的参数 |
|--------|------|--------------|
| `WEALTH_CONSULT` | 理财咨询/理财推荐 | 用户风险偏好(激进/稳健/保守) |
| `WEALTH_INTERPRET` | 理财解读 | 理财产品名称(如"abcd产品") |

### 1.3 歧义场景

| 用户输入 | Phase2可能返回 | 问题 |
|---------|-------------|------|
| "理财" | WEALTH_CONSULT 或 WEALTH_INTERPRET 或 UNKNOWN | 分不清 → 需要消歧 |
| "理财咨询" | WEALTH_CONSULT | ✅ 无歧义 |
| "解读一下abcd产品" | WEALTH_INTERPRET | ✅ 无歧义 |
| "今天天气" | UNKNOWN | ✅ 完全无法识别 → "不支持该功能" |

### 1.4 设计原则

1. **完全无法识别** → 直接回复"不支持该功能"，不消歧
2. **能缩小到多个候选但分不清** → 进入消歧模式，追问用户
3. **消歧3次仍模糊** → 回复"不支持该功能"
4. **尽量不大改架构** — 在Phase2和Phase3之间插入消歧检查，新增轻量状态

---

## 2. 核心设计：IntentGroup + DisambiguationState

### 2.1 IntentGroup 概念

在 `IntentRegistry` 中引入**意图组(IntentGroup)**：共享同一前缀关键词的意图集合。

```java
// IntentRegistry 新增
public static class IntentGroup {
    private final String groupId;          // e.g., "WEALTH"
    private final String displayName;      // e.g., "理财"
    private final List<String> intentNames; // e.g., ["WEALTH_CONSULT", "WEALTH_INTERPRET"]
    private final String disambiguationQuestion; // e.g., "请问您需要理财咨询还是理财产品解读？"
}
```

注册时声明组关系：

```java
// IntentRegistry.init()
register("WEALTH_CONSULT", "理财咨询/推荐", "风险偏好(激进/稳健/保守)", false);
register("WEALTH_INTERPRET", "理财产品解读", "理财产品名称", false);
registerGroup("WEALTH", "理财", List.of("WEALTH_CONSULT", "WEALTH_INTERPRET"),
              "请问您需要理财咨询还是理财产品解读？");
```

**为什么不用动态前缀检测？** 动态检测"WEALTH_"前缀太脆弱。显式声明组关系更可靠，且消歧提问话术每个组不同。

### 2.2 DisambiguationState 状态

在 `AgentStateManager` 中新增消歧状态：

```java
public static class DisambiguationState {
    private String groupId;         // 哪个意图组有歧义
    private int attemptCount;       // 已追问次数
    private String originalInput;   // 用户的原始模糊输入
    private Instant createdAt;      // 创建时间
    private String suspendedIntent; // 消歧前被挂起的意图(可能为null)
}
```

**关键设计**: 消歧状态与activeThread互斥：
- 进入消歧模式时：如果有activeThread → 挂起，设置disambiguationState
- 消歧成功时：清除disambiguationState，正常路由到目标意图
- 消歧失败(3次)时：清除disambiguationState，恢复之前挂起的意图(如果有)

### 2.3 RoutingResult 扩展

```java
// RoutingResult 新增字段
private List<String> candidateIntents;  // Phase2返回的候选意图列表
private String groupId;                  // 候选意图所属的意图组

// 新增状态判断
public boolean isAmbiguous() {
    return candidateIntents != null && candidateIntents.size() > 1;
}
```

Phase2 LLM 输出格式变更：
```json
{
  "intent_name": "WEALTH",          // 可以返回组名
  "candidate_intents": ["WEALTH_CONSULT", "WEALTH_INTERPRET"],
  "rewritten_input": "理财",
  "confidence": 0.4,
  "is_ambiguous": true
}
```

---

## 3. 修改后的路由流程

### 3.1 完整流程图

```
用户输入
  │
  ▼
Phase1: 类型判断 ──→ CANCEL ──→ handleCancel
  │
  ├── FOLLOW_UP + activeThread
  │     │
  │     ├── activeThread在消歧状态? ──→ 消歧回答处理
  │     └── 正常FOLLOW_UP ──→ resumeGraph
  │
  └── SWITCH_NEW / RESUME / 无activeThread的FOLLOW_UP
        │
        ▼
Phase2: 改写+意图识别
  │
  ├── intent_name明确(非歧义) ──→ Phase3路由
  │
  ├── is_ambiguous=true(有候选但分不清)
  │     │
  │     ├── 进入消歧模式
  │     │   - 挂起activeThread(如有)
  │     │   - 设置disambiguationState
  │     │   - 返回 INTERRUPTED + 消歧提问
  │     └── 下次用户回答
  │         │
  │         ├── attemptCount < 3
  │         │   - 重新Phase2(带消歧上下文)
  │         │   - 如果识别出唯一意图 → 路由
  │         │   - 如果仍然歧义 → 再次消歧
  │         └── attemptCount >= 3
  │             - 清除消歧状态
  │             - 返回 "不支持该功能"
  │
  └── intent_name=UNKNOWN(完全无法识别)
        │
        └── 返回 ERROR "不支持该功能"
```

### 3.2 消歧回答处理详细流程

```
用户回答 "解读一下abcd产品吧"
  │
  ▼
Phase1: FOLLOW_UP (因为disambiguationState存在)
  │
  ▼
检测到 disambiguationState != null
  │
  ▼
disambiguationState.attemptCount++
  │
  ├── attemptCount >= 3?
  │   └── 清除消歧状态 → 返回 "不支持该功能"
  │
  └── attemptCount < 3
      │
      ▼
重新Phase2 (带消歧上下文):
  - 原始模糊输入: "理财"
  - 消歧回答: "解读一下abcd产品吧"
  - 候选意图: [WEALTH_CONSULT, WEALTH_INTERPRET]
  │
  ├── Phase2返回唯一意图 WEALTH_INTERPRET
  │   - 清除disambiguationState
  │   - 恢复之前挂起的意图(如有)到suspended
  │   - 路由到 WEALTH_INTERPRET graph
  │   - 用户输入用 "解读一下abcd产品" (改写后的)
  │
  └── Phase2仍然返回歧义
      - 更新disambiguationState
      - 再次追问
```

### 3.3 与现有流程的兼容性

**不需要修改的部分**:
- Sub-agent Graph结构 (Transfer/BillQuery不变)
- interruptBefore + accumulatedParams re-execution机制
- resumeGraph / executeGraph / checkGraphResult
- CANCEL逻辑

**需要修改的部分**:
- `IntentRegistry`: 新增IntentGroup + registerGroup
- `RoutingResult`: 新增candidateIntents/groupId/isAmbiguous
- `ContextRewriter`: Phase2 prompt增加歧义识别，支持返回候选列表
- `AgentStateManager`: 新增DisambiguationState
- `BankController.chat()`: Phase2后插入消歧检查，FOLLOW_UP分支检查消歧状态

---

## 4. 新增子Graph设计

### 4.1 WEALTH_INTERPRET (理财解读)

```
START → extractParams → paramRouter → askProductName (interruptBefore)
                                        ↘ executeWealthInterpret → END
askProductName → paramRouter (循环)

参数:
  wealth.productName: 理财产品名称 (如"abcd产品")

paramRouter:
  - productName为空 → ASK_PRODUCT_NAME
  - productName非空 → ALL_GOOD
```

### 4.2 WEALTH_CONSULT (理财咨询)

```
START → extractParams → paramRouter → askRiskLevel (interruptBefore)
                                        ↘ executeWealthConsult → END
askRiskLevel → paramRouter (循环)

参数:
  wealth.riskLevel: 风险偏好 (激进/稳健/保守)

paramRouter:
  - riskLevel为空 → ASK_RISK_LEVEL
  - riskLevel非空 → ALL_GOOD
```

### 4.3 extractAccumulatedParams 扩展

```java
String prefix = switch (intent) {
    case "TRANSFER" -> "transfer.";
    case "BILL_QUERY" -> "bill.";
    case "WEALTH_CONSULT", "WEALTH_INTERPRET" -> "wealth.";  // 新增
    default -> "";
};
```

---

## 5. 数据结构变更汇总

### 5.1 IntentRegistry

```java
// 新增
private final Map<String, IntentGroup> groups = new LinkedHashMap<>();

public static class IntentGroup {
    private final String groupId;
    private final String displayName;
    private final List<String> intentNames;
    private final String disambiguationQuestion;
}

// 新增方法
public void registerGroup(String groupId, String displayName,
                          List<String> intentNames, String question);
public IntentGroup findGroupByIntent(String intentName);
public IntentGroup findGroupByPrefix(String userInput);
public boolean isAmbiguous(String intentName); // 该意图是否属于某个歧义组
```

### 5.2 RoutingResult

```java
// 新增字段
private List<String> candidateIntents;  // 候选意图列表
private String groupId;                  // 歧义组ID
private boolean ambiguous;              // 是否歧义
```

### 5.3 AgentStateManager

```java
// 新增
private final Map<String, DisambiguationState> disambiguationStates = new ConcurrentHashMap<>();

public static class DisambiguationState {
    private String groupId;
    private int attemptCount;
    private String originalInput;
    private Instant createdAt;
}

// 新增方法
public void setDisambiguationState(String sessionId, DisambiguationState state);
public DisambiguationState getDisambiguationState(String sessionId);
public void clearDisambiguationState(String sessionId);
public boolean isInDisambiguation(String sessionId);
```

### 5.4 WorkflowOutput

```java
// 新增factory方法
public static WorkflowOutput disambiguation(String question, List<String> candidates) {
    return WorkflowOutput.builder()
            .status("DISAMBIGUATION")
            .question(question)
            .candidateIntents(candidates)
            .build();
}
```

---

## 6. 关键边界场景

| # | 场景 | 用户输入 | 系统行为 |
|---|------|---------|---------|
| D1 | 直接模糊 | "理财" | 消歧追问 |
| D2 | 消歧后明确 | "解读abcd产品" | Phase2重识别 → WEALTH_INTERPRET |
| D3 | 消歧后仍模糊 | "还是理财" | 再次消歧(attemptCount++) |
| D4 | 3次消歧失败 | (第3次模糊) | "不支持该功能" + 清除状态 |
| D5 | 完全无法识别 | "今天天气" | 直接"不支持该功能" |
| D6 | 消歧中切换意图 | "算了查账单" | Phase1=SWITCH_NEW → 清除消歧 → 正常路由 |
| D7 | 有activeThread时消歧 | 转账中→"理财" | 挂起Transfer → 消歧 |
| D8 | 消歧成功后恢复 | D7→消歧成功 | WEALTH执行,Transfer保持挂起 |
| D9 | 消歧中取消 | "算了" | Phase1=CANCEL → 清除消歧 → 恢复之前挂起 |
| D10 | 已有意图明确 | "理财咨询" | Phase2直接识别 → 无消歧 |

---

## 7. 修改文件清单 (按依赖顺序)

| # | 文件 | 变更类型 | 说明 |
|---|------|---------|------|
| 1 | `IntentRegistry.java` | 修改 | +IntentGroup, +registerGroup, +findGroupByIntent |
| 2 | `RoutingResult.java` | 修改 | +candidateIntents, +groupId, +ambiguous |
| 3 | `WorkflowOutput.java` | 修改 | +DISAMBIGUATION status, +candidateIntents |
| 4 | `AgentStateManager.java` | 修改 | +DisambiguationState, +消歧状态管理方法 |
| 5 | `ContextRewriter.java` | 修改 | Phase2 prompt支持歧义识别+候选列表 |
| 6 | `l1-rewrite.st` | 修改 | prompt模板增加歧义判断 |
| 7 | `BankController.java` | 修改 | Phase2后插入消歧检查, FOLLOW_UP检查消歧状态 |
| 8 | `WealthConsultGraphConfig.java` | **新增** | 理财咨询Graph |
| 9 | `WealthInterpretGraphConfig.java` | **新增** | 理财解读Graph |
| 10 | `MockBankingService.java` | 修改 | +理财模拟数据 |
| 11 | `IntentRouter.java` | 修改 | 确定性规则增加"理财"关键词 |
| 12 | `AppInitConfig.java` | 修改 | 绑定新Graph到IntentRegistry |
| 13 | `application.yml` | 修改 | 无(使用默认模型) |

**不变**: TransferGraphConfig, BillQueryGraphConfig, executeGraph/resumeGraph/checkGraphResult

---

## 8. 实现步骤

### Wave 1: 数据层 (无行为变更,编译安全)
1. 修改 `IntentRegistry` — +IntentGroup
2. 修改 `RoutingResult` — +candidateIntents/groupId/ambiguous
3. 修改 `WorkflowOutput` — +DISAMBIGUATION/candidateIntents
4. 修改 `AgentStateManager` — +DisambiguationState

### Wave 2: 消歧逻辑
5. 修改 `ContextRewriter` + `l1-rewrite.st` — Phase2支持歧义检测
6. 修改 `BankController` — 消歧检查 + 消歧回答处理
7. 修改 `IntentRouter` — "理财"确定性规则

### Wave 3: 新增Graph
8. 新增 `WealthConsultGraphConfig`
9. 新增 `WealthInterpretGraphConfig`
10. 修改 `MockBankingService` — +理财模拟数据
11. 修改 `AppInitConfig` — 绑定新Graph

### Wave 4: 验证
12. 编译 + 启动 + 测试全部场景
