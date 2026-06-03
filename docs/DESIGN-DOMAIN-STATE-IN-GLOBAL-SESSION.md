# 设计文档：DomainState 统一存入 GlobalSessionContext

## 1. 目标

将 L1 域级别的运行时状态（activeAgent、suspendedAgents、disambiguation）和 L2 子图的业务数据快照（subAgentData）统一存入 `GlobalSessionContext.state`（OverAllState），删除现有的 `SessionStateStore` 基础设施。

### 改动前

```
SessionStateStore<V>          ← 独立的 KV 存储接口
├── InMemorySessionStateStore
└── RedisSessionStateStore

AbstractDomainService         ← 持有 SessionStateStore<ActiveAgentInfo>
MultiSubAgentDomainService    ← 额外持有 SessionStateStore<SuspendedInfo> + SessionStateStore<DisambiguationState>

GlobalSessionContext          ← 只有 messages 和 lastDomain
```

### 改动后

```
GlobalSessionContext.state (OverAllState)
├── messages           → AppendStrategy   (全局对话历史, 已有)
├── _lastDomain        → ReplaceStrategy  (最近路由领域, 已有)
├── _transferState     → ReplaceStrategy  (转账域 DomainState)
├── _billState         → ReplaceStrategy  (账单域 DomainState)
├── _wealthState       → ReplaceStrategy  (理财域 DomainState)
└── ...                → ReplaceStrategy  (新增域自动注册)

SessionStateStore 基础设施  ← 删除
```

---

## 2. 数据结构

### 2.1 DomainState — 统一的 L1 域状态

不管 Single 还是 Multi，都用同一个 `DomainState` 类。Single 域的 `suspendedAgents` 和 `disambiguation` 为 null。

```java
public class DomainState implements Serializable {

    /** 域名: "TRANSFER", "BILL", "WEALTH" */
    private String domain;

    /** 当前活跃子图信息 (Single/Multi 都有) */
    private ActiveAgentInfo activeAgent;

    /** 挂起的子图 (仅 Multi, Single 为 null) */
    private Map<String, SuspendedInfo> suspendedAgents;

    /** 意图消歧状态 (仅 Multi, Single 为 null) */
    private DisambiguationState disambiguation;

    /** L2 子图业务数据快照 */
    private Map<String, SubAgentState> subAgents;
}
```

### 2.2 ActiveAgentInfo — 当前活跃子图

与现有结构一致，从 `AbstractDomainService.ActiveAgentInfo` 迁移。

```java
public class ActiveAgentInfo implements Serializable {
    private String intent;         // "TRANSFER", "WEALTH_CONSULT"
    private String threadId;       // L2 子图的 threadId
    private long createdAt;        // 创建时间 (epoch millis)
    private long expiresAt;        // 过期时间 (epoch millis)
    private String lastQuestion;   // 中断时的提问内容
}
```

**注意**: 时间字段从 `Instant` 改为 `long`（epoch millis），统一序列化友好。

### 2.3 SuspendedInfo — 挂起的子图

与现有结构一致，从 `MultiSubAgentDomainService.SuspendedInfo` 迁移。

```java
public class SuspendedInfo implements Serializable {
    private String intent;         // "WEALTH_CONSULT"
    private String threadId;       // L2 子图的 threadId
    private long suspendedAt;      // 挂起时间 (epoch millis)
    private long expiresAt;        // 过期时间 (epoch millis)
}
```

### 2.4 DisambiguationState — 意图消歧

与现有结构一致，从 `MultiSubAgentDomainService.DisambiguationState` 迁移。

```java
public class DisambiguationState implements Serializable {
    private String groupId;        // 消歧意图组 ID
}
```

### 2.5 SubAgentState — L2 子图数据快照

**新增结构**。L2 子图执行过程中有自己的 OverAllState，子图结束前将关键数据打包为 `SubAgentState` 写回 GlobalSessionContext。

```java
public class SubAgentState implements Serializable {

    /** 子图名称 (intent name): "TRANSFER", "WEALTH_CONSULT", "WEALTH_INTERPRET" */
    private String name;

    /** 子图是否在中断状态 (interruptBefore 机制) */
    private boolean interrupted;

    /** 中断后下一个要执行的节点名称 (仅 interrupted=true 时有意义) */
    private String nextNode;

    /** 子图自己的业务数据 (由各子图自行定义 key-value) */
    private Map<String, Object> data;
}
```

#### SubAgentState 各域示例

**TRANSFER:**
```json
{
  "name": "TRANSFER",
  "interrupted": true,
  "nextNode": "askAmount",
  "data": {
    "receiver": "张三",
    "amount": null,
    "purpose": null
  }
}
```

**BILL_QUERY:**
```json
{
  "name": "BILL_QUERY",
  "interrupted": false,
  "nextNode": null,
  "data": {
    "timePeriod": "本月",
    "expenseType": "餐饮"
  }
}
```

**WEALTH_CONSULT (中断中):**
```json
{
  "name": "WEALTH_CONSULT",
  "interrupted": true,
  "nextNode": "askFocusArea",
  "data": {
    "riskLevel": "稳健",
    "focusArea": null
  }
}
```

**WEALTH_INTERPRET (已完成):**
```json
{
  "name": "WEALTH_INTERPRET",
  "interrupted": false,
  "nextNode": null,
  "data": {
    "productName": "朝朝盈",
    "interpretResult": "朝朝盈是招商银行推出的一款..."
  }
}
```

---

## 3. 完整示例：理财域 DomainState

```json
{
  "domain": "WEALTH",
  "activeAgent": {
    "intent": "WEALTH_CONSULT",
    "threadId": "session1-WEALTH_CONSULT-a3f2b1",
    "createdAt": 1748800000000,
    "expiresAt": 1748801200000,
    "lastQuestion": "请问您的风险偏好是？"
  },
  "suspendedAgents": {
    "WEALTH_INTERPRET": {
      "intent": "WEALTH_INTERPRET",
      "threadId": "session1-WEALTH_INTERPRET-c7d4e9",
      "suspendedAt": 1748799800000,
      "expiresAt": 1748801000000
    }
  },
  "disambiguation": null,
  "subAgents": {
    "WEALTH_CONSULT": {
      "name": "WEALTH_CONSULT",
      "interrupted": true,
      "nextNode": "askFocusArea",
      "data": {
        "riskLevel": "稳健",
        "focusArea": null
      }
    },
    "WEALTH_INTERPRET": {
      "name": "WEALTH_INTERPRET",
      "interrupted": false,
      "nextNode": null,
      "data": {
        "productName": "朝朝盈",
        "interpretResult": "朝朝盈是招商银行推出的一款..."
      }
    }
  }
}
```

---

## 4. 存储方案：方案 C（每个域一个 OverAllState key）

### 4.1 OverAllState 中的 key 布局

| Key | Strategy | 说明 |
|---|---|---|
| `messages` | AppendStrategy | 全局对话历史（已有） |
| `_lastDomain` | ReplaceStrategy | 最近路由领域（已有） |
| `_transferState` | ReplaceStrategy | 转账域 DomainState |
| `_billState` | ReplaceStrategy | 账单域 DomainState |
| `_wealthState` | ReplaceStrategy | 理财域 DomainState |
| `_xxxState` | ReplaceStrategy | 新增域自动注册 |

### 4.2 为什么选方案 C 而非方案 B（Map）

| | 方案 B (`_domainStates` → Map) | 方案 C (每域一个 key) |
|---|---|---|
| 更新一个域 | 重写整个 Map（含所有域） ✗ | 只写自己的 key ✓ |
| 轮询所有域 | `map.values().stream()` ✓ | 通过 `DomainServiceRegistry` 逐个取 |
| 跨域干扰 | 同一 Map 对象 ✗ | 完全隔离 ✓ |
| 规模扩展 | 9 域 × 6 SubAgent = 每次重写 54 份数据 ✗ | 每次只写 1 份 ✓ |

---

## 5. 动态注册机制

### 5.1 DomainStateAware 接口

声明域在 OverAllState 中的 key 和策略，供 `KeyStrategyFactory` 动态注册。

```java
public interface DomainStateAware {

    /** OverAllState 中的 key, 如 "_transferState" */
    String getStateKey();

    /** KeyStrategy, 固定 ReplaceStrategy */
    KeyStrategy getStateStrategy();

    /** 首次创建 DomainState 时的初始值 */
    DomainState initialState();
}
```

### 5.2 DomainStateAware 与 DomainService 解耦（重要）

**原设计**：DomainService 直接实现 DomainStateAware → 导致循环依赖：
```
GlobalSessionStateStore → KeyStrategyFactory → List<DomainStateAware>
  → DomainService → GlobalSessionStateStore (循环!)
```

**实际实现**：DomainStateAware 注册为独立的轻量级 Bean，与 DomainService 完全解耦。

在 `DomainServiceConfig` 中以匿名 Bean 注册，零依赖，打破循环：

```java
@Bean DomainStateAware transferStateAware() {
    return new DomainStateAware() {
        public String getStateKey() { return "_transferState"; }
        public KeyStrategy getStateStrategy() { return new ReplaceStrategy(); }
        public DomainState initialState() { return new DomainState("TRANSFER"); }
    };
}
// billStateAware, wealthStateAware 同理
```

**好处**：
- 无需 `@Lazy` workaround
- DomainService 无需关心注册逻辑
- 新增域只需在 DomainServiceConfig 中添加一个匿名 Bean

### 5.3 KeyStrategyFactory 独立为 Bean

`KeyStrategyFactoryConfig` 作为独立的 `@Bean` 产出手动注册的 `KeyStrategyFactory`，由 `GlobalSessionStateStore` 和 `RedisGlobalSessionStorage` 共同注入使用。

### 5.4 新增域流程

1. 创建 L1 域服务类（无需实现 DomainStateAware）
2. 在 `DomainServiceConfig` 中注册一个轻量级 `DomainStateAware` Bean
3. KeyStrategyFactory 自动收集 → OverAllState 自动注册 key
4. **零改动 GlobalSessionStateStore / KeyStrategyFactoryConfig**

---

## 6. 读写 API

### 6.1 GlobalSessionContext 增加的 helper 方法

```java
// 读取 DomainState
public DomainState getDomainState(String stateKey) {
    return state.value(stateKey)
            .filter(DomainState.class::isInstance)
            .map(DomainState.class::cast)
            .orElse(null);
}

// 更新 DomainState (read-modify-write 封装)
public void updateDomainState(String stateKey, Consumer<DomainState> modifier) {
    DomainState ds = state.value(stateKey)
            .filter(DomainState.class::isInstance)
            .map(DomainState.class::cast)
            .orElseGet(() -> new DomainState(domainKeyFromStateKey(stateKey)));
    modifier.accept(ds);
    state.updateState(Map.of(stateKey, ds));
}
```

### 6.2 场景一：L1 设置/清除 ActiveAgent

**场景：用户说"给我妈转500"，L0 路由到 TRANSFER，L1 执行新子图**

```java
// ===== 改动前 (AbstractDomainService) =====
// 依赖 SessionStateStore<ActiveAgentInfo>
activeAgentStore.put(logTag, sessionId,
    new ActiveAgentInfo("TRANSFER", threadId, now, expiresAt));

// 读取
ActiveAgentInfo info = activeAgentStore.get(logTag, sessionId).orElse(null);

// 清除
activeAgentStore.remove(logTag, sessionId);

// ===== 改动后 =====
String stateKey = getStateKey();  // "_transferState", 由 DomainStateAware 提供
GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);

// 设置 ActiveAgent
ctx.updateDomainState(stateKey, ds ->
    ds.setActiveAgent(new ActiveAgentInfo("TRANSFER", threadId, now, expiresAt)));

// 读取 ActiveAgent
DomainState ds = ctx.getDomainState(stateKey);
ActiveAgentInfo active = (ds != null) ? ds.getActiveAgent() : null;

// 清除 ActiveAgent
ctx.updateDomainState(stateKey, ds -> ds.setActiveAgent(null));
```

**场景：L2 子图中断，更新 lastQuestion**

```java
// ===== 改动前 =====
ActiveAgentInfo active = getOwnActiveAgent(sessionId);
if (active != null) {
    active.setLastQuestion(chunk.getQuestion());
}

// ===== 改动后 =====
ctx.updateDomainState(stateKey, ds -> {
    if (ds.getActiveAgent() != null) {
        ds.getActiveAgent().setLastQuestion(chunk.getQuestion());
    }
});
```

**场景：检查过期 ActiveAgent**

```java
// ===== 改动前 =====
ActiveAgentInfo info = activeAgentStore.get(logTag, sessionId).orElse(null);
if (info != null && info.isExpired()) {
    activeAgentStore.remove(logTag, sessionId);
    return null;
}
return info;

// ===== 改动后 =====
DomainState ds = ctx.getDomainState(stateKey);
if (ds == null || ds.getActiveAgent() == null) return null;
if (ds.getActiveAgent().isExpired()) {
    ctx.updateDomainState(stateKey, d -> d.setActiveAgent(null));
    return null;
}
return ds.getActiveAgent();
```

### 6.3 场景二：L1 操作 SuspendedAgent（Multi 域专用）

**场景：理财域，用户从"理财咨询"切换到"理财解读"，挂起咨询子图**

```java
// ===== 改动前 (MultiSubAgentDomainService) =====
private void suspendOwnAgent(String sessionId, String intent, String threadId) {
    Map<String, SuspendedInfo> sessionMap = suspendedAgentStore
        .get(SUSPENDED_NS + ":" + logTag, sessionId).orElseGet(HashMap::new);

    if (sessionMap.containsKey(intent)) return;

    if (sessionMap.size() >= maxSuspendedDepth) {
        String oldestKey = sessionMap.entrySet().stream()
            .min(Comparator.comparing(e -> e.getValue().getSuspendedAt()))
            .map(Map.Entry::getKey).orElse(null);
        if (oldestKey != null) sessionMap.remove(oldestKey);
    }

    SuspendedInfo info = new SuspendedInfo(intent, threadId, Instant.now(),
        Instant.now().plusSeconds(suspendedExpireMinutes * 60));
    sessionMap.put(intent, info);
    suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
}

// ===== 改动后 =====
private void suspendOwnAgent(String sessionId, String intent, String threadId) {
    ctx.updateDomainState(stateKey, ds -> {
        Map<String, SuspendedInfo> suspended = ds.getSuspendedAgents();
        if (suspended == null) {
            suspended = new HashMap<>();
            ds.setSuspendedAgents(suspended);
        }

        if (suspended.containsKey(intent)) return;  // 已存在

        // 超过最大深度，淘汰最早的
        if (suspended.size() >= maxSuspendedDepth) {
            suspended.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().getSuspendedAt()))
                .map(Map.Entry::getKey)
                .ifPresent(suspended::remove);
        }

        long now = System.currentTimeMillis();
        suspended.put(intent, new SuspendedInfo(intent, threadId, now,
            now + suspendedExpireMinutes * 60 * 1000));
    });
}
```

**场景：恢复挂起子图，从 suspendedAgents 中移除**

```java
// ===== 改动前 =====
private void resumeOwnAgent(String sessionId, String intent) {
    Map<String, SuspendedInfo> sessionMap = suspendedAgentStore
        .get(SUSPENDED_NS + ":" + logTag, sessionId).orElse(null);
    if (sessionMap != null) {
        sessionMap.remove(intent);
        if (sessionMap.isEmpty()) {
            suspendedAgentStore.remove(SUSPENDED_NS + ":" + logTag, sessionId);
        } else {
            suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
        }
    }
}

// ===== 改动后 =====
private void resumeOwnAgent(String sessionId, String intent) {
    ctx.updateDomainState(stateKey, ds -> {
        Map<String, SuspendedInfo> suspended = ds.getSuspendedAgents();
        if (suspended != null) {
            suspended.remove(intent);
            if (suspended.isEmpty()) {
                ds.setSuspendedAgents(null);  // 清空时设 null，节省空间
            }
        }
    });
}
```

**场景：检查是否有挂起子图（含过期清理）**

```java
// ===== 改动前 =====
private boolean hasOwnSuspendedAgents(String sessionId) {
    Map<String, SuspendedInfo> sessionMap = suspendedAgentStore
        .get(SUSPENDED_NS + ":" + logTag, sessionId).orElse(null);
    if (sessionMap == null || sessionMap.isEmpty()) return false;
    sessionMap.entrySet().removeIf(e -> e.getValue().isExpired());
    if (sessionMap.isEmpty()) {
        suspendedAgentStore.remove(SUSPENDED_NS + ":" + logTag, sessionId);
        return false;
    }
    suspendedAgentStore.put(SUSPENDED_NS + ":" + logTag, sessionId, sessionMap);
    return true;
}

// ===== 改动后 =====
private boolean hasOwnSuspendedAgents(String sessionId) {
    DomainState ds = ctx.getDomainState(stateKey);
    if (ds == null || ds.getSuspendedAgents() == null || ds.getSuspendedAgents().isEmpty()) {
        return false;
    }
    // 惰性清理过期项
    ds.getSuspendedAgents().entrySet().removeIf(e -> e.getValue().isExpired());
    if (ds.getSuspendedAgents().isEmpty()) {
        ctx.updateDomainState(stateKey, d -> d.setSuspendedAgents(null));
        return false;
    }
    return true;
}
```

### 6.4 场景三：L1 操作 Disambiguation（Multi 域专用）

```java
// ===== 改动前 =====
private boolean isInDisambiguation(String sessionId) {
    return disambiguationStore.containsKey(DISAMBIG_NS + ":" + logTag, sessionId);
}
private void setDisambiguationState(String sessionId, DisambiguationState state) {
    disambiguationStore.put(DISAMBIG_NS + ":" + logTag, sessionId, state);
}
private void clearDisambiguationState(String sessionId) {
    disambiguationStore.remove(DISAMBIG_NS + ":" + logTag, sessionId);
}

// ===== 改动后 =====
private boolean isInDisambiguation(String sessionId) {
    DomainState ds = ctx.getDomainState(stateKey);
    return ds != null && ds.getDisambiguation() != null;
}
private void setDisambiguationState(String sessionId, DisambiguationState state) {
    ctx.updateDomainState(stateKey, ds -> ds.setDisambiguation(state));
}
private void clearDisambiguationState(String sessionId) {
    ctx.updateDomainState(stateKey, ds -> ds.setDisambiguation(null));
}
```

### 6.5 场景四：L2 子图写回 SubAgent 快照

**场景：转账子图中断（askAmount），写回当前参数快照**

```java
// 在 AbstractDomainService.handleActiveAgentState() 中
if (chunk.getType() == ChunkType.INTERRUPTED) {
    // 1. 从 L2 Graph snapshot 提取业务数据
    //    调用 AbstractGraphConfig.extractSubAgentDataSnapshot()
    Map<String, Object> data = extractFromL2Graph(sessionId, chunk.getIntent());

    // 2. 构建 SubAgentState
    SubAgentState snapshot = new SubAgentState(
        chunk.getIntent(),      // "TRANSFER"
        true,                   // interrupted = true
        chunk.getNextNode(),    // "askAmount"
        data                    // {receiver: "张三", amount: null, purpose: null}
    );

    // 3. 写回 GlobalSessionContext
    ctx.updateDomainState(stateKey, ds ->
        ds.getSubAgents().put(chunk.getIntent(), snapshot));

    // 4. 更新 activeAgent.lastQuestion
    ctx.updateDomainState(stateKey, ds -> {
        if (ds.getActiveAgent() != null) {
            ds.getActiveAgent().setLastQuestion(chunk.getQuestion());
        }
    });
}
```

**场景：转账子图完成，写回最终结果快照**

```java
if (chunk.getType() == ChunkType.COMPLETE) {
    // 1. 从 L2 Graph snapshot 提取业务数据
    Map<String, Object> data = extractFromL2Graph(sessionId, chunk.getIntent());

    // 2. 构建 SubAgentState
    SubAgentState snapshot = new SubAgentState(
        "TRANSFER",             // intent name
        false,                  // interrupted = false
        null,                   // nextNode = null
        data                    // {receiver: "张三", amount: "600", purpose: "家用"}
    );

    // 3. 写回 GlobalSessionContext
    ctx.updateDomainState(stateKey, ds ->
        ds.getSubAgents().put("TRANSFER", snapshot));

    // 4. 清除 activeAgent
    ctx.updateDomainState(stateKey, ds -> ds.setActiveAgent(null));
}
```

**场景：读取 SubAgent 快照用于上下文注入**

```java
// L1 执行新子图时，将已有 SubAgent 数据注入 L2 的 _globalStateData
DomainState ds = ctx.getDomainState(stateKey);
if (ds != null && ds.getSubAgents() != null) {
    SubAgentState transferSnapshot = ds.getSubAgents().get("TRANSFER");
    if (transferSnapshot != null) {
        // 将快照数据注入 L2 graph input
        input.put("_globalStateData", Map.of(
            "transfer", transferSnapshot.getData()
        ));
    }
}
```

**场景：修改 SubAgent 内部的某个字段**

SubAgentState.data 是 `Map<String, Object>`，修改就是标准 Map 操作：

```java
// 例1: 转账子图参数被 LLM 提取后更新到 SubAgent 快照
ctx.updateDomainState("_transferState", ds -> {
    SubAgentState sub = ds.getSubAgents().computeIfAbsent("TRANSFER",
        k -> new SubAgentState("TRANSFER", false, null, new HashMap<>()));
    sub.getData().put("receiver", "张三");
    sub.getData().put("amount", "600");
});

// 例2: 理财咨询子图中断时更新 riskLevel 和 interrupted 状态
ctx.updateDomainState("_wealthState", ds -> {
    SubAgentState sub = ds.getSubAgents().computeIfAbsent("WEALTH_CONSULT",
        k -> new SubAgentState("WEALTH_CONSULT", true, "askFocusArea", new HashMap<>()));
    sub.getData().put("riskLevel", "稳健");
    sub.setInterrupted(true);
    sub.setNextNode("askFocusArea");
});

// 例3: 只修改 SubAgent 的一个字段，不影响其他字段
ctx.updateDomainState("_transferState", ds -> {
    SubAgentState sub = ds.getSubAgents().get("TRANSFER");
    if (sub != null) {
        sub.getData().put("amount", "800");  // 用户改了金额
    }
});

// 例4: 读取 SubAgent 的某个字段
DomainState ds = ctx.getDomainState("_transferState");
if (ds != null && ds.getSubAgents() != null) {
    SubAgentState sub = ds.getSubAgents().get("TRANSFER");
    if (sub != null) {
        String amount = (String) sub.getData().get("amount");  // "800"
        boolean isInterrupted = sub.isInterrupted();            // true/false
        String nextNode = sub.getNextNode();                     // "askAmount" / null
    }
}

// 例5: 删除某个 SubAgent（子图完成且数据不再需要）
ctx.updateDomainState("_transferState", ds -> {
    ds.getSubAgents().remove("TRANSFER");
});

// 例6: 遍历某个域下所有 SubAgent，找中断中的
DomainState ds = ctx.getDomainState("_wealthState");
if (ds != null && ds.getSubAgents() != null) {
    for (var entry : ds.getSubAgents().entrySet()) {
        SubAgentState sub = entry.getValue();
        if (sub.isInterrupted()) {
            log.info("子图 {} 在中断状态，下一个节点: {}", sub.getName(), sub.getNextNode());
        }
    }
}
```

### 6.6 轮询所有域

```java
for (String domain : domainServiceRegistry.getDomainNames()) {
    String stateKey = "_" + domain.toLowerCase() + "State";
    DomainState ds = ctx.getDomainState(stateKey);
    if (ds != null && ds.getActiveAgent() != null) {
        // 找到活跃域
    }
}
```

### 6.7 Session 清理

```java
// ===== 改动前 (BankController.clearSession) =====
// 1. 遍历所有 domain service，各自清理自己的 SessionStateStore
for (String domain : domainServiceRegistry.getDomainNames()) {
    DomainHandler handler = domainServiceRegistry.getHandler(domain);
    if (handler instanceof AbstractDomainService ads) {
        ads.clearSession(sessionId);
    }
}
// 2. 清理 GlobalSessionStore
globalSessionStore.clearSession(sessionId);

// ===== 改动后 =====
// GlobalSessionContext.state.clear() 一把清，所有域的 DomainState 全部销毁
// 不需要遍历每个域分别清理
globalSessionStore.clearSession(sessionId);
```

---

## 7. L2 子图数据回写

> **当前状态**: 接口已预留，流程未接入。L2 开发团队可自行决定回写时机和策略。
>
> - `AbstractGraphConfig.extractSubAgentDataSnapshot()` 模板方法已定义，4个子图已实现
> - `DomainState.subAgents` 字段和 `SubAgentState` 数据模型已就绪
> - `GlobalSessionContext.updateDomainState()` API 可直接操作 subAgents
> - L1 域服务的 `handleActiveAgentState()` 暂不调用写回，L2 团队自行接入

### 7.1 回写时机

| 时机 | 操作 | interrupted | nextNode |
|---|---|---|---|
| L2 中断 (interruptBefore) | 打包当前参数快照写回 | `true` | 被中断的节点名 |
| L2 正常完成 | 打包最终结果写回 | `false` | `null` |
| L2 取消 | 不写回 | — | — |

### 7.2 回写代码位置

在 `AbstractDomainService.handleActiveAgentState()` 中：

```java
protected void handleActiveAgentState(String sessionId, StreamChunk chunk) {
    if (!chunk.isTerminal()) return;

    if (chunk.getType() == ChunkType.INTERRUPTED) {
        // 中断时: 写回 SubAgent 快照 (interrupted=true, nextNode=被中断节点)
        SubAgentState snapshot = buildSubAgentSnapshot(chunk.getIntent(), true, chunk.getNextNode());
        ctx.updateDomainState(stateKey, ds -> ds.getSubAgents().put(chunk.getIntent(), snapshot));

        // 更新 activeAgent.lastQuestion
        ctx.updateDomainState(stateKey, ds -> {
            if (ds.getActiveAgent() != null) {
                ds.getActiveAgent().setLastQuestion(chunk.getQuestion());
            }
        });
    } else if (chunk.getType() == ChunkType.COMPLETE) {
        // 完成时: 写回 SubAgent 快照 (interrupted=false, nextNode=null)
        SubAgentState snapshot = buildSubAgentSnapshot(chunk.getIntent(), false, null);
        ctx.updateDomainState(stateKey, ds -> ds.getSubAgents().put(chunk.getIntent(), snapshot));

        // 清除 activeAgent
        ctx.updateDomainState(stateKey, ds -> ds.setActiveAgent(null));
    }
}
```

### 7.3 SubAgentState.data 的提取

各 L2 子图在 `AbstractGraphConfig` 中提供模板方法：

```java
/** 子类覆盖: 从 L2 OverAllState 提取业务数据快照 */
protected abstract Map<String, Object> extractSubAgentDataSnapshot(OverAllState state);
```

各子图实现：

```java
// TransferGraphConfig
@Override
protected Map<String, Object> extractSubAgentDataSnapshot(OverAllState state) {
    Map<String, Object> data = new HashMap<>();
    getStringValue(state, "transfer.receiver").ifPresent(v -> data.put("receiver", v));
    getStringValue(state, "transfer.amount").ifPresent(v -> data.put("amount", v));
    getStringValue(state, "transfer.purpose").ifPresent(v -> data.put("purpose", v));
    return data;
}
```

---

## 8. 文件改动清单

### 8.1 新增文件

| 文件 | 说明 |
|---|---|
| `memory/DomainState.java` | 统一域状态类 |
| `memory/ActiveAgentInfo.java` | 活跃子图信息 (从 AbstractDomainService 迁出) |
| `memory/SuspendedInfo.java` | 挂起子图信息 (从 MultiSubAgentDomainService 迁出) |
| `memory/DisambiguationState.java` | 消歧状态 (从 MultiSubAgentDomainService 迁出) |
| `memory/SubAgentState.java` | L2 子图数据快照 |
| `memory/DomainStateAware.java` | 动态注册接口 (轻量级Bean, 与DomainService解耦) |
| `memory/KeyStrategyFactoryConfig.java` | KeyStrategyFactory 独立 @Bean |
| `memory/GlobalSessionStorage.java` | 存储后端抽象接口 |
| `memory/GlobalSessionStorageConfig.java` | 存储后端配置 (InMemory/Redis切换) |
| `memory/impl/InMemoryGlobalSessionStorage.java` | InMemory 存储实现 |
| `memory/impl/RedisGlobalSessionStorage.java` | Redis 存储实现 |
| `memory/impl/RedisSubGraphCheckpointSaver.java` | Redis SubGraphCheckpointSaver (移至impl/) |

### 8.2 删除文件

| 文件 | 说明 |
|---|---|
| `memory/SessionStateStore.java` | 旧 KV 存储接口 |
| `memory/SessionStateStoreConfig.java` | 旧 KV 存储配置 |
| `memory/InMemorySessionStateStore.java` | 旧 InMemory 实现 |
| `memory/RedisSessionStateStore.java` | 旧 Redis 实现 |

### 8.3 重命名 + 移动文件

| 原位置 | 新位置 | 说明 |
|---|---|---|
| `execution/GlobalSessionContext.java` | `memory/GlobalSessionContext.java` | 移动到 memory 包 |
| `execution/GlobalSessionStore.java` | `memory/GlobalSessionStateStore.java` | 重命名 + 移动 |

### 8.4 存储后端切换（storage.type）

改完后 `storage.type` 配置项控制的范围：

| 组件 | 受 `storage.type` 控制 | 说明 |
|---|---|---|
| ~~SessionStateStore~~ | ~~是~~ | **删除** |
| `SubGraphCheckpointSaverConfig` | 是（不变） | L2 子图 checkpoint，保持 InMemory/Redis 切换 |
| **`GlobalSessionStateStore`** | **是（新增）** | Session 级状态存储，新增 InMemory/Redis 切换 |

#### 8.4.1 抽象存储后端

```java
/**
 * GlobalSessionStateStore 的存储后端接口
 *
 * 通过 storage.type 配置切换实现:
 * - in-memory: ConcurrentHashMap (默认, 单实例开发)
 * - redis:     StringRedisTemplate (多实例/持久化)
 */
public interface GlobalSessionStorage {

    /** 获取 GlobalSessionContext, 不存在返回 null */
    GlobalSessionContext get(String sessionId);

    /** 保存 GlobalSessionContext */
    void put(String sessionId, GlobalSessionContext ctx);

    /** 删除 GlobalSessionContext */
    void remove(String sessionId);
}
```

#### 8.4.2 InMemory 实现（默认）

```java
public class InMemoryGlobalSessionStorage implements GlobalSessionStorage {
    private final Map<String, GlobalSessionContext> store = new ConcurrentHashMap<>();

    @Override
    public GlobalSessionContext get(String sessionId) {
        return store.get(sessionId);
    }

    @Override
    public void put(String sessionId, GlobalSessionContext ctx) {
        store.put(sessionId, ctx);
    }

    @Override
    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
```

#### 8.4.3 Redis 实现

```java
public class RedisGlobalSessionStorage implements GlobalSessionStorage {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KeyStrategyFactory keyStrategyFactory;

    private static final String KEY_PREFIX = "global-session:";
    private static final long DEFAULT_TTL_HOURS = 24;

    @Override
    public GlobalSessionContext get(String sessionId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        if (json == null) return null;
        try {
            // 从 Redis 反序列化 state.data()
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            // 重建 OverAllState + 注册 KeyStrategy + 回填数据
            OverAllState state = new OverAllState();
            state.registerKeyAndStrategy(keyStrategyFactory.apply());
            state.updateState(data);
            return new GlobalSessionContext(sessionId, state);
        } catch (Exception e) {
            log.warn("[RedisGlobalSessionStorage] Failed to deserialize: sessionId={}", sessionId, e);
            return null;
        }
    }

    @Override
    public void put(String sessionId, GlobalSessionContext ctx) {
        try {
            // 序列化 state.data() 到 Redis
            String json = objectMapper.writeValueAsString(ctx.data());
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, json, DEFAULT_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[RedisGlobalSessionStorage] Failed to serialize: sessionId={}", sessionId, e);
        }
    }

    @Override
    public void remove(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}
```

#### 8.4.4 GlobalSessionStateStore 通过配置切换

```java
@Slf4j
@Component
public class GlobalSessionStateStore {

    private final KeyStrategyFactory keyStrategyFactory;
    private final GlobalSessionStorage storage;

    public GlobalSessionStateStore(
            SubGraphCheckpointSaverFactory subGraphCheckpointSaverFactory,
            List<DomainStateAware> domainStateProviders,
            @Autowired(required = false) GlobalSessionStorage storage) {
        this.keyStrategyFactory = createKeyStrategyFactory(domainStateProviders);
        this.storage = storage;  // Spring 注入, 由 GlobalSessionStorageConfig 根据 storage.type 决定实现
    }

    public GlobalSessionContext getOrCreate(String sessionId) {
        GlobalSessionContext ctx = storage.get(sessionId);
        if (ctx != null) return ctx;
        return createAndStore(sessionId);
    }

    private GlobalSessionContext createAndStore(String sessionId) {
        OverAllState state = new OverAllState();
        state.registerKeyAndStrategy(keyStrategyFactory.apply());
        GlobalSessionContext ctx = new GlobalSessionContext(sessionId, state);
        storage.put(sessionId, ctx);
        return ctx;
    }

    public void clearSession(String sessionId) {
        storage.remove(sessionId);
    }
}
```

#### 8.4.5 配置类

```java
@Configuration
public class GlobalSessionStorageConfig {

    @Bean("inMemoryGlobalSessionStorage")
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public GlobalSessionStorage inMemoryStorage() {
        return new InMemoryGlobalSessionStorage();
    }

    @Bean("redisGlobalSessionStorage")
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public GlobalSessionStorage redisStorage(StringRedisTemplate redisTemplate,
                                               ObjectMapper objectMapper,
                                               KeyStrategyFactory keyStrategyFactory) {
        return new RedisGlobalSessionStorage(redisTemplate, objectMapper, keyStrategyFactory);
    }
}
```

#### 8.4.6 关键区别：InMemory vs Redis

| | InMemory | Redis |
|---|---|---|
| 存储方式 | 直接持有 GlobalSessionContext 引用 | 序列化 `state.data()` → Redis JSON |
| 读取 | 直接返回引用，零开销 | 反序列化 → 重建 OverAllState → 返回新实例 |
| 写入 | 直接修改 OverAllState 内存对象 | 修改后需调 `storage.put()` 同步到 Redis |
| 数据一致性 | 天然一致（同一对象） | 需显式同步（修改 state 后 put 回 Redis） |
| 多实例 | ✗（单实例） | ✓（多实例共享） |

**Redis 模式的同步问题**：InMemory 模式下 `ctx.addUserMessage()` 直接修改 OverAllState，不需要额外同步。Redis 模式下，修改 OverAllState 后需要调 `storage.put()` 将最新数据写回 Redis。

解决方案：在 `GlobalSessionContext` 的写操作方法中增加自动同步：

```java
// GlobalSessionContext 中
public void addUserMessage(String content) {
    state.updateState(Map.of("messages", "用户: " + content));
    storage.put(sessionId, this);  // Redis 模式自动同步, InMemory 模式为空操作
}
```

或者由 `GlobalSessionStateStore` 提供包装方法统一处理同步。

SubGraphCheckpointSaverConfig 不受影响，继续为 L2 子图提供 InMemory/Redis 的 SubGraphCheckpointSaver。

### 8.5 修改文件

| 文件 | 改动 |
|---|---|
| `memory/GlobalSessionContext.java` | 从 `execution/` 迁移到 `memory/`; 删除 `checkpointSaver` 字段; 增加 `getDomainState()` / `updateDomainState()` helper; 增加 `LastDomainEntry` record |
| `memory/GlobalSessionStateStore.java` | 重命名自 GlobalSessionStore; 接收 `KeyStrategyFactory` + `GlobalSessionStorage`; 删除 `checkpointSaverFactory` |
| `domain/AbstractDomainService.java` | 删除 `SessionStateStore<ActiveAgentInfo>` 依赖; 改用 `ctx.updateDomainState()` |
| `domain/SingleSubAgentDomainService.java` | 删除 `SessionStateStore` 依赖; **不实现** `DomainStateAware` (解耦) |
| `domain/MultiSubAgentDomainService.java` | 删除 3 个 `SessionStateStore` 依赖; **不实现** `DomainStateAware` (解耦) |
| `domain/ChatService.java` | 更新 import (GlobalSessionStore → GlobalSessionStateStore) |
| `config/DomainServiceConfig.java` | 删除 `SessionStateStoreFactory` 依赖; 增加 3 个轻量级 `DomainStateAware` Bean (匿名类, 零依赖) |
| `controller/BankController.java` | 更新 import; `clearSession` 改为 `globalSessionStore.clearSession()` |
| `router/DomainRouter.java` | 更新 import; `setLastDomain(domain, expireAt)` |
| `memory/SubGraphCheckpointSaverConfig.java` | 重命名自 CheckpointSaverConfig; RedisSubGraphCheckpointSaver import 路径 (移至 `impl/`) |
| `workflow/AbstractGraphConfig.java` | 增加 `extractSubAgentDataSnapshot()` 模板方法 (默认返回空Map) |
| `workflow/TransferGraphConfig.java` | 实现 `extractSubAgentDataSnapshot()` — 提取 receiver/amount/purpose |
| `workflow/BillQueryGraphConfig.java` | 实现 `extractSubAgentDataSnapshot()` — 提取 timePeriod/expenseType |
| `workflow/WealthConsultGraphConfig.java` | 实现 `extractSubAgentDataSnapshot()` — 提取 riskLevel/focusArea |
| `workflow/WealthInterpretGraphConfig.java` | 实现 `extractSubAgentDataSnapshot()` — 提取 productName |
| `resources/prompts/l0-domain.st` | 规则2增加"直接回答vs反问"区分; 新增Case6/7排除域场景 |

---

## 9. OverAllState KeyStrategyFactory 最终设计

```java
private KeyStrategyFactory createKeyStrategyFactory(List<DomainStateAware> providers) {
    return () -> {
        Map<String, KeyStrategy> strategies = new HashMap<>();

        // 公共 keys
        strategies.put("messages", new AppendStrategy());
        strategies.put("_lastDomain", new ReplaceStrategy());
        strategies.put("_latestUserInput", new ReplaceStrategy());
        strategies.put("_question", new ReplaceStrategy());
        strategies.put("_paramName", new ReplaceStrategy());
        strategies.put("_outputContent", new ReplaceStrategy());
        strategies.put("_outputType", new ReplaceStrategy());
        strategies.put("_isFinal", new ReplaceStrategy());
        strategies.put("_cancelSignal", new ReplaceStrategy());
        strategies.put("_globalStateData", new ReplaceStrategy());

        // 动态注册: L1 域的 DomainState key
        for (DomainStateAware provider : providers) {
            strategies.put(provider.getStateKey(), provider.getStateStrategy());
        }

        return strategies;
    };
}
```

**删除**：原来硬编码的 `transfer.*`、`bill.*`、`wealth.*` 等 L2 业务 key。这些数据现在通过 `DomainState.subAgents` 存储，不再作为 OverAllState 的顶层 key。

---

## 10. 待确认事项

1. **StreamChunk 是否需要携带 nextNode 信息**：当前 `StreamChunk` 的 `INTERRUPTED` 类型只有 `question` 字段，没有 `nextNode`。需要确认是否在 StreamChunk 中增加 `nextNode` 字段，还是从 L2 Graph 的 snapshot 中提取。L2 开发团队接入回写时可决定。

2. **L2 取消时的处理**：L2 子图因取消信号结束时，是否写回 SubAgentState？建议不写回（取消 = 放弃本次操作）。

3. **SuspendedInfo 的定时清理**：已采用惰性清理方案 — 在 `hasOwnSuspendedAgents()` / `getOwnSuspendedAgent()` 读取时检查 `isExpired()` 并移除，无需 `@Scheduled` 定时任务。

4. **Redis 持久化**：InMemory + Redis 两种模式均已实现。`DomainState` 及其嵌套对象已实现 `Serializable`，字段使用 `long` 替代 `Instant`，兼容性已保证。Redis 模式下修改 OverAllState 后需调 `storage.put()` 同步（见 8.4.6）。

5. **L2 回写接入**：接口已预留（`extractSubAgentDataSnapshot()` + `DomainState.subAgents` + `SubAgentState`），L2 开发团队可自行决定回写时机和策略，无需修改框架层代码。

---

## 11. 实现状态汇总

| 阶段 | 状态 | 说明 |
|---|---|---|
| 数据模型 (DomainState/SubAgentState等) | ✅ 完成 | `memory/model/` 下 5 个类 |
| DomainStateAware 解耦 | ✅ 完成 | 轻量级匿名 Bean, 打破循环依赖 |
| GlobalSessionContext 迁移 | ✅ 完成 | 从 `execution/` 迁到 `memory/`, 增加 helper |
| GlobalSessionStorage 抽象 | ✅ 完成 | InMemory/Redis 双实现 + Config 切换 |
| KeyStrategyFactory 独立 | ✅ 完成 | `KeyStrategyFactoryConfig` @Bean |
| DomainService 重写 | ✅ 完成 | Abstract/Single/Multi 全部改用 ctx.updateDomainState() |
| SessionStateStore 删除 | ✅ 完成 | 6 个文件已删除 |
| L0 提示词修复 | ✅ 完成 | 规则2 "直接回答vs反问" + Case6/7 |
| 包结构分层 | ✅ 完成 | `model/` + `impl/` |
| L2 extractSubAgentDataSnapshot | ✅ 完成 | AbstractGraphConfig 模板方法 + 4 子类实现 |
| L2 回写流程接入 | ⏳ 待 L2 团队 | 接口已预留, 流程未接入 |
| 设计文档更新 | ✅ 完成 | 反映 DomainStateAware 解耦 + L2 接口预留 |
