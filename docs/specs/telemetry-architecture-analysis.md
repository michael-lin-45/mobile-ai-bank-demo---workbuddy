# Core 业务埋点架构分析：4种方式对比与统一方案

> 问题：为什么代码中有 ObservabilityMetrics、ObsChatModel、OTel Java Agent、SessionBridge 这4种插桩方式？它们有什么区别？能否用一种方式统一完成？

---

## 一、4种埋点方式全景对比

| 维度 | ObservabilityMetrics + MetricsRegistry | ObsChatModel | OTel Java Agent（自动插桩） | SessionBridge |
|------|--------------------------------------|-------------|--------------------------|--------------|
| **统计级别** | 请求级 / 会话级 | 每次 LLM 调用 | 请求级（HTTP Span） | 每次 /chat 请求结束时 |
| **采集内容** | 业务 Counter/Timer/Gauge（路由决策、意图准确率、工具调用、会话完成等22+项） | LLM 5 项指标（token输入/输出、TTFT、总耗时、错误次数） | HTTP Server/Client Span、数据库 Span、以及框架级 gen_ai.client.operation.duration Span | 用户输入、AI回复、意图、agent路径、置信度、耗时、tokens、traceId |
| **导出方式** | Micrometer → OTLP(指标) → Collector → 后端 H2 | 同左（指标部分）+ GlobalOpenTelemetry Tracer → OTLP(Span) → Collector → 后端 H2 | OTel javaagent → OTLP(Span+Metrics+Logs) → Collector → 后端 H2 | @Async HTTP POST /api/v1/sessions → 后端 H2 sessions/session_turns 表 |
| **所属管道** | Metrics 管道 | Metrics + Traces 双管道 | Traces 管道（为主） | Sessions 管道（独立） |
| **侵入性** | 业务代码显式调用 `.recordXxx()` | 装饰器模式，无业务代码改动 | 零侵入（javaagent 自动插桩） | 业务代码显式调用 `reportSession()` |
| **命名前缀** | agent.* / deepflux.* | llm.* | gen_ai.* / http.server.* / db.* | 无指标命名，直接写入 H2 表 |
| **代码行数** | ~644行 + ~131行 | ~595行 | 零代码（YAML 配置） | ~128行 |

### 1.1 ObservabilityMetrics（业务指标注册中心）

```java
// 显式声明静态 Counter/Timer/Gauge + 动态 tag 缓存
@Component
public class ObservabilityMetrics {
    // 静态 Counter（不灵活，每种 tag 组合都要声明）
    private Counter routerDecisionHit;     // agent.router.decision.outcome(tag:outcome=hit)
    private Counter routerDecisionLlm;     // agent.router.decision.outcome(tag:outcome=llm_fallback)
    private Counter routerDecisionFail;    // agent.router.decision.outcome(tag:outcome=fail)

    // 动态 Counter（灵活，但需维护缓存）
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    // 业务代码调用：
    obsMetrics.recordRouterDecision("L0", "hit", "TRANSFER");
    obsMetrics.recordIntentAccuracy("correct", "TRANSFER", "TRANSFER");
}
```

**特点**：面向业务语义的埋点，直接表达"发生了什么业务事件"。

**MetricsRegistry（设计§3.1①）**：新增指标的集中注册表，统一走 `deepflux.*` 前缀。本质上是 Micrometer 的一个包装层，避免分散注册。

### 1.2 ObsChatModel（LLM 指标装饰器 + 业务 Span）

```java
// 装饰 Spring AI ChatModel，拦截 call()/stream()
public class ObsChatModel implements ChatModel {

    // 1. 采集 llm.* 5项指标（走 Micrometer）
    Counter.builder("llm.token.input").tag("model", modelName).register(meterRegistry);
    Timer.builder("llm.first_token.latency").publishPercentileHistogram(true)...;
    Timer.builder("llm.operation.duration")...;

    // 2. 创建业务 OTel Span（走 GlobalOpenTelemetry Tracer）
    Span span = otelTracer.spanBuilder("L0:qwen-plus")
        .setSpanKind(SpanKind.INTERNAL)
        .startSpan();
    span.setAttribute("agent.layer", "L0");
    span.setAttribute("intent", "TRANSFER");
    span.setAttribute("session_id", sessionId);
}
```

**特点**：一次 LLM 调用同时做两件事——(1)通过 Micrometer 记录指标，(2)通过 OTel API 创建带业务属性的 Span。这是一个关键设计决策：**Metrics 和 Traces 在同一块代码中采集，但走两条不同的底层管道。**

### 1.3 OTel Java Agent（自动插桩）

```
启动参数：
-javaagent:opentelemetry-javaagent.jar
-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4318
-Dotel.traces.exporter=otlp
-Dotel.metrics.exporter=otlp
-Dotel.logs.exporter=otlp
```

**自动创建**：
- **HTTP Server Span**：每个 `POST /api/bank/chat` 请求自动创建一个 `SERVER` 类型的根 Span（含 traceId）
- **HTTP Client Span**：SessionBridge 的 HttpClient 调用自动创建 `CLIENT` Span
- **Spring AI Span**：Spring AI 的 `gen_ai.client.operation.duration` Span（自动，但 DashScope 适配器缺 token Counter 和 TTFT）
- **Reactor 上下文传播**：`OtelContextConfig.enableAutomaticContextPropagation()` + Agent 的 reactor 插桩保证跨线程 trace context

**这是 4 种方式中唯一"零代码"的一种**，不需要在业务代码中写任何埋点。

### 1.4 SessionBridge（会话回放数据桥接）

```java
@Service
public class SessionBridge {
    @Async  // 异步，不阻塞 chat 响应
    public void reportSession(sessionId, userInput, aiResponse,
                               intent, agentPath, confidence,
                               durationMs, tokens, traceId, status, ...) {
        // HTTP POST → 后端 /api/v1/sessions
        // 写入 sessions 表 + session_turns 表
    }
}
```

**特点**：这是**完全独立于 OTel 管道的业务数据采集**。存储的不是"遥测信号"，而是"对话快照"——用户问了什么、AI 回了什么、花了多久、走了什么路径。属于 **Session Replay（会话回放）** 领域的结构化数据。

---

## 二、为什么需要 4 种方式？——按"数据类别"而非"方式数量"理解

真正理解这个问题，需要从**可观测性的 4 大类数据**（业界称"信号 Signal"）来看：

```
┌─────────────────────────────────────────────────────────┐
│              可观测数据全景（4类 Signal）                    │
├─────────────┬──────────────┬──────────────┬─────────────┤
│   Metrics   │    Traces     │    Logs      │   Events    │
│    指标      │    链路追踪    │    日志       │  (含Sessions)│
├─────────────┼──────────────┼──────────────┼─────────────┤
│ 采集方式：    │ 采集方式：     │ 采集方式：    │ 采集方式：    │
│ Micrometer   │ OTel Agent   │ SLF4J→OTLP  │ SessionBridge│
│ →OTLP        │ (auto+manual)│ + Logback    │ →HTTP POST   │
│              │ →OTLP        │ →OTLP        │ (独立管道)    │
├─────────────┼──────────────┼──────────────┼─────────────┤
│ agent.router │ L0:qwen-plus │ BankController│ userInput   │
│ .outcome     │ span+属性     │ 日志          │ aiResponse  │
│ llm.token.*  │ intent=TRSF  │ "L0 domain:  │ agentPath   │
│              │ session_id   │ TRANSFER"    │ confidence  │
└─────────────┴──────────────┴──────────────┴─────────────┘
```

**关键洞察**：这 4 种方式采集的是 **4 类不同性质的信号**，不是同一件事用 4 种方法做。

### 2.1 那为什么看起来"重复"？

**确实存在重复的地方**，但不是"4种方式做同一件事"，而是"不同信号间有交叉"：

| 交叉点 | 重复内容 | 严重程度 |
|--------|---------|---------|
| `llm.operation.duration`（ObsChatModel Timer） vs `gen_ai.client.operation.duration`（OTel Agent/Spring AI Span） | 都在测量 LLM 调用耗时，但一个是 Micrometer Timer，一个是 OTel Span。**数据不共享，各自导出。** | ⚠️ 中等 |
| sessionId 在 ObsChatModel Span 属性 和 SessionBridge sessions 表中都存在 | 同一标识符出现在两个独立管道中，但数据内容不同（span 属性是短字符串，session 记录包含完整对话） | ✅ 可接受（关联键不是重复） |
| ObservabilityMetrics 的 LLM Timer `gen_ai.client.operation.duration` 和 ObsChatModel 的 `llm.operation.duration` | 两个不同的 Micrometer Timer 测量同一件事 | ⚠️ 高（应删除一个） |
| MetricsRegistry (`deepflux.*`) 和 ObservabilityMetrics (`agent.*`) | 两套注册机制，但都走同一个 MeterRegistry | ✅ 可接受（命名空间区分合理） |

---

## 三、业界优秀实践调研

### 3.1 OpenTelemetry���三信号统一的标准

OpenTelemetry（CNCF 毕业项目）的核心理念：

```
         旧世界（多套 SDK，多套协议）                 新世界（OTel 统一）
    ┌─────────┐  ┌─────────┐  ┌─────────┐     ┌─────────────────────┐
    │Prometheus│  │  Jaeger  │  │  ELK    │     │  OpenTelemetry SDK   │
    │  client  │  │  client  │  │ client  │     │  (统一 API)          │
    └────┬────┘  └────┬────┘  └────┬────┘     ├─────────────────────┤
         │            │            │           │  Traces │ Metrics │ Logs│
         ▼            ▼            ▼           └────┬────┴────┬────┴──┬──┘
    各自格式      各自格式      各自格式               │         │       │
                                                    ▼         ▼       ▼
                                              OTLP Protocol (统一格式)
                                                    │
                                                    ▼
                                           OTel Collector
                                           (统一网关：接收/处理/路由)
                                           ┌───┬───┬───┐
                                           │   │   │   │
                                           ▼   ▼   ▼   ▼
                                      Prometheus Jaeger Elastic ...
```

**关键实践**：
1. **一套 SDK 采集三种信号**（Traces + Metrics + Logs），通过同一 OTLP 协议上报
2. **Collector 作为统一网关**：接收、处理（采样/过滤/脱敏）、路由到不同后端
3. **语义约定（Semantic Conventions）**：标准化 Span 属性名、指标名，跨语言跨框架一致

### 3.2 Datadog / Grafana 的架构实践

| 厂商 | 架构特点 | 统一方式 |
|------|---------|---------|
| **Datadog** | Agent 层完成数据关联，Trace → Log → Metric 在采集端就已关联 | 统一 Agent + 统一数据模型 |
| **Grafana Labs** | 后端组合查询（Loki+Tempo+Mimir），数据在查询时关联 | 统一 OTLP 接收 + 不同存储引擎 |
| **Langfuse**（LLM 专用） | Traces + Sessions 一体，一个 trace 就是一次完整对话 | Traces 作为会话载体 |

**共同点**：都在往 **"一套 SDK 采集，一个 Collector 接收，通过语义属性关联"** 的方向收敛。

### 3.3 OTel GenAI 语义约定（2025 稳定版）

OpenTelemetry 已发布 GenAI 标准语义约定，定义了精确的指标名和 Span 属性：

| 标准指标名 | 类型 | 说明 | 本项目对应 |
|-----------|------|------|-----------|
| `gen_ai.client.token.usage` | Histogram | 输入/输出 token 数（`gen_ai.token.type` 区分） | `llm.token.input/output`（命名不一致） |
| `gen_ai.client.operation.duration` | Histogram | LLM 操作总耗时 | `llm.operation.duration`（命名不一致） |
| `gen_ai.client.operation.time_to_first_chunk` | Histogram | 首 chunk 延迟 | `llm.first_token.latency`（命名不一致） |
| `gen_ai.invoke_agent.duration` | Histogram | Agent 端到端耗时 | ❌ 缺失 |
| `gen_ai.invoke_agent.tool_calls` | Histogram | Agent 工具调用次数 | `agent.tool.call.count`（近似） |
| `gen_ai.execute_tool.duration` | Histogram | 工具执行耗时 | `agent.tool.call.duration`（近似） |

**命名差异小结**：本项目的 `llm.*` 和 `agent.*` 前缀不符合 OTel GenAI 语义约定，这会导致无法直接接入支持 OTel GenAI 标准的三方可观测平台（如 Grafana Cloud 的 LLM 监控面板、Langfuse）。

### 3.4 业界共识："Auto + Manual" 分层埋点策略

```
┌─────────────────────────────────────────────┐
│  Layer 3: 业务事件（手动埋点）                  │
│  意图识别准确率、业务结果、会话完成...            │
│  → 通过 Micrometer/OTel API 创建自定义指标/Span │
├─────────────────────────────────────────────┤
│  Layer 2: 框架增强（装饰器/拦截器）              │
│  Token 消耗、TTFT、TPOT、模型名...              │
│  → 通过 ChatModel 装饰器自动采集                │
├─────────────────────────────────────────────┤
│  Layer 1: 基础设施骨架（零侵入自动插桩）           │
│  HTTP 请求、DB 查询、RPC 调用...                 │
│  → 通过 OTel javaagent 自动采集                 │
└─────────────────────────────────────────────┘
```

**Datadog、Honeycomb、Langfuse 都推荐这种分层模式**：
- Layer 1 是免费的骨架，Agent 自动搞定
- Layer 2 是对特定框架（LLM/DB/MQ）的语义增强
- Layer 3 是业务特有的补充，无法被通用工具理解

本项目的 4 种方式恰好对应了这个分层模型，**分层本身不是问题，问题在于同一层内的重复和命名不一致**。

---

## 四、重复采集量化分析

### 4.1 确认的重复：gen_ai.client.operation.duration

```java
// ❌ ObservabilityMetrics.java 第200行：
llmCallDuration = Timer.builder("gen_ai.client.operation.duration")
    .tag("gen_ai.operation.name", "chat")
    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
    .register(meterRegistry);

// ✅ ObsChatModel.java 第421行：
Timer.builder("llm.operation.duration")
    .tag("model", modelName)
    .publishPercentileHistogram(true)
    .register(meterRegistry)
    .record(valueMs, TimeUnit.MILLISECONDS);
```

**两个 Timer 都在测量 LLM 调用耗时**，但：
- `gen_ai.client.operation.duration` 在 ObservabilityMetrics.init() 初始化时注册但**从未被 record()**（只有 startLlmTimer/stopLlmTimer 方法，但 BankController 中未调用）
- `llm.operation.duration` 在 ObsChatModel 的每次 call()/stream() 中被 record

**结论**：`gen_ai.client.operation.duration` 是死代码（已注册从未使用），应删除。同时 `llm.operation.duration` 应重命名为 `gen_ai.client.operation.duration` 以符合 OTel 标准。

### 4.2 潜在重复：llm.token.input/output

如果未来 Spring AI 的 DashScope adapter 升级支持完整 token 上报，则 ObsChatModel 的 `llm.token.input/output` 和 OTel Agent 自动创建的 `gen_ai.client.token.usage` 会同时采集同一数据。**应在 ObsChatModel 的 token 采集逻辑中加判断——如果底层 adapter 已自动上报，则跳过自定义采集。**

### 4.3 SessionId 的双重存在（不是重复）

```
SessionBridge: sessions 表 ← 存储完整会话快照（userInput, aiResponse, agentPath...）
ObsChatModel:  span.setAttribute("session_id", sessionId) ← 仅作为 Trace 的查询键
```

这是**合理的关联键设计**，不是重复。类似 Datadog 的 `@trace_id` 注入日志、Grafana 的 exemplar。

---

## 五、能否用一种方式统一？

### 答案：不能也不应该用"一种方式"，但可以大幅简化到"两套统一管道"

```
┌──────────────────────────────────────────────────────────────┐
│                    统一后的架构                                │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌─────────────────── OTel 管道（指标 + 链路 + 日志）────────┐│
│   │                                                         ││
│   │  Layer 1: OTel Java Agent (auto)                        ││
│   │    └─ HTTP Server/Client Span, DB Span, Spring AI Span  ││
│   │                                                         ││
│   │  Layer 2: ObsChatModel (ChatModel 装饰器)               ││
│   │    └─ gen_ai.client.token.usage (标准名)                ││
│   │    └─ gen_ai.client.operation.duration                  ││
│   │    └─ gen_ai.client.operation.time_to_first_chunk       ││
│   │    └─ 业务 Span 属性 (intent/agent.layer/session_id)    ││
│   │                                                         ││
│   │  Layer 3: ObservabilityMetrics (精简，指标统一到         ││
│   │           MetricsRegistry + OTel 标准命名)              ││
│   │    └─ agent.router.decision.outcome                     ││
│   │    └─ agent.intent.accuracy                             ││
│   │    └─ agent.tool.call.count/duration                    ││
│   │    └─ agent.session.completed                           ││
│   │    └─ agent.business.outcome                            ││
│   │    └─ deepflux.rag.*                                    ││
│   │                                                         ││
│   │  全部通过 Micrometer OTLP → Collector → 后端 H2          ││
│   └─────────────────────────────────────────────────────────┘│
│                                                              │
│   ┌───────────────── Sessions 管道（独立）───────────────────┐│
│   │                                                         ││
│   │  SessionBridge @Async                                    ││
│   │    └─ HTTP POST /api/v1/sessions                         ││
│   │    └─ sessions 表 + session_turns 表                     ││
│   │    └─ 业务数据（非遥测信号），独立管道合理               ││
│   │                                                         ││
│   │  增强关联：session_turns.traceId ↔ traces.trace_id       ││
│   └─────────────────────────────────────────────────────────┘│
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 简化清单

| 序号 | 操作 | 效果 |
|------|------|------|
| **1** | 删除 `ObservabilityMetrics` 中的死代码 `gen_ai.client.operation.duration` Timer（已注册从未使用） | 减少无效注册 |
| **2** | 将 `llm.*` 指标重命名为 OTel 标准 `gen_ai.client.*` 命名 | 与业界标准对齐，可直接接入三方平台 |
| **3** | 废除 `ObservabilityMetrics` 中的静态 Counter 声明（如 `routerDecisionHit/Llm/Fail`），统一走动态 `recordCounter()` | 减少代码行数，统一维护模型 |
| **4** | 合并 `ObservabilityMetrics` 和 `MetricsRegistry` 为一个类（或明确职责：ObservabilityMetrics 仅做便捷方法封装，MetricsRegistry 管注册） | 消除"两套注册入口"的困惑 |
| **5** | 增强 SessionBridge 与 OTel Trace 的双向关联：除 `session_turns.traceId` 外，在 `sessions` 表加 `trace_count` 字段，或后端支持 `GET /api/v1/sessions?traceId=xxx` | 会话回放和链路追踪可互跳 |
| **6** | ObsChatModel token 采集加"去重"逻辑：检测底层 adapter 是否已自动上报 `gen_ai.client.token.usage`，若是则跳过自定义采集 | 防止未来升级 Spring AI 后双重上报 |

---

## 六、业界对标：你的架构比想象的更合理

| 项目 | 对应业界实践 | 评价 |
|------|-------------|------|
| OTel Java Agent 自动 Span | = Datadog Agent / OTel Auto-Instrumentation | ✅ 标准做法 |
| ObsChatModel 装饰器采集 LLM 指标 | = Langfuse / OpenLIT 的 LLM wrapper | ✅ 符合 LLM 专用观测模式 |
| ObservabilityMetrics 业务指标 | = Datadog Custom Metrics / Prometheus 自定义指标 | ✅ 必要补充 |
| SessionBridge 会话回放 | = Datadog Session Replay / Langfuse Sessions | ✅ 独立管道合理 |
| OTel Collector 统一网关 | = Datadog Agent / Grafana Alloy | ✅ 架构正确 |

**结论**：你的架构做到了业界推荐的分层采集，问题的核心不是"方式太多"而是：
1. **命名未对齐 OTel 标准**（`llm.*` vs `gen_ai.client.*`）
2. **同一指标有死代码重复注册**
3. **两套指标注册入口（ObservabilityMetrics + MetricsRegistry）增加心智负担**

---

## 七、行动计划（P0/P1/P2）

### P0（立即修，消除重复浪费）
- [ ] 删除 `ObservabilityMetrics.init()` 中的 `gen_ai.client.operation.duration` Timer 注册（行200-204）——该 Timer 注册后从未被 record
- [ ] 删除 `startLlmTimer()`/`stopLlmTimer()` 方法（行504-510）——调用方不存在

### P1（这个迭代做，对齐标准）
- [ ] 重命名 `llm.token.input` → `gen_ai.client.token.usage`（tag `gen_ai.token.type=input`）
- [ ] 重命名 `llm.token.output` → `gen_ai.client.token.usage`（tag `gen_ai.token.type=output`）
- [ ] 重命名 `llm.first_token.latency` → `gen_ai.client.operation.time_to_first_chunk`
- [ ] 重命名 `llm.operation.duration` → `gen_ai.client.operation.duration`
- [ ] 重命名 `llm.error.count` → 合并到 `gen_ai.client.operation.duration` + `error.type` 属性

### P2（下个迭代，合并注册入口）
- [ ] 将 `ObservabilityMetrics` 中所有静态 Counter 声明迁移为统一动态 `recordCounter()` 模式
- [ ] 评估是否将 `ObservabilityMetrics` 完全合并到 `MetricsRegistry`（或明确其"业务语义封装层"定位）
- [ ] 后端 OtlpParser 同步更新解析规则以适配新指标名

### P3（架构增强）
- [ ] ObsChatModel 增加去重逻辑：检测底层 adapter 是否已自动上报 token usage
- [ ] 前端支持 Session → Trace 互跳（点击某轮对话跳转到对应 Trace Timeline）
- [ ] 评估是否将 `agent.*` 指标逐步迁移到更适合业务语义的命名（如 `banking.agent.*`）
