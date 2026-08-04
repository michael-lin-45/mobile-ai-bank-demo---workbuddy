# Session 会话埋点方案综合对比与选型建议

> 日期：2026-07-28
> 背景：主人提出三个疑问——① OTLP Log 方案是否可信？② Spring AI Advisor 方案是否合理？③ 综合对比所有方案并给出选型建议。
> 参考来源：Spring AI 1.1 官方文档、豆包调研文档、Spring AI Alibaba 文档、Langfuse 官方文档、trpc-agent-go 框架实践、akjamie 博客实战。

---

## 一、先回答主人的三个核心问题

### Q1：OTLP Log 方案是否可信？

**结论：不可信。业界无先例，不建议采用。**

经查证多个业界实践来源，**没有任何一个**使用 OTLP Log 来传输 Session 数据：

| 来源 | Session 传输方式 | 用 OTLP Log？ |
|---|---|---|
| Spring AI 1.1 官方文档 | `conversation.id` 作为 Span 高基数标签 + Micrometer tracing | ❌ 未提及 |
| Langfuse 官方 OTLP 集成 | OTLP **traces** 端点 `/api/public/otel` + Baggage 传播 `session.id` 到 Span | ❌ 不用 Log |
| trpc-agent-go 框架 | OTel SDK → 自定义 SpanProcessor → Baggage 复制到 Span → OTLP traces | ❌ 不用 Log |
| akjamie Spring AI+Langfuse 实战 | ObservationFilter 注入 `langfuse.session.id` 到 Span → OTLP traces | ❌ 不用 Log |
| 豆包调研文档 | Spring AI ObservationConvention / OTel Baggage / SpanProcessor | ❌ 未提及 |

**Langfuse 官方明确推荐**的做法是：
> "Recommended: Use OpenTelemetry Baggage for Propagation... Configure a BaggageSpanProcessor to automatically copy baggage entries to span attributes."

即：**Baggage 传播 → BaggageSpanProcessor 复制到 Span attribute → OTLP traces 端点**。全程走 traces 管道，不碰 logs 管道。

**V6 生产版推荐 OTLP Log 的理由**是"session 必须 100% 捕获，trace 会采样导致丢失"。这个痛点是真实的，但**业界的解法不是绕道 Log，而是调整采样策略**——对包含 session 的业务 trace 设置 100% 采样（tail_sampling 针对性策略），而非全局降低采样后用 Log 补。

**V4-Demo 上线版 0728 的判断"OTLP Log 业内几乎没人这样做"是正确的。**

### Q2：Spring AI Advisor 方案是否合理？

**结论：合理。这是 Spring AI 官方支持的标准观测点，适合做 Graph 节点级追踪。**

Spring AI 1.1 官方文档明确为 Advisor 提供独立观测点：

| 属性 | 值 | 说明 |
|---|---|---|
| 观测点名 | `spring.ai.advisor` | 独立于 ChatModel 的 `gen_ai.client.operation` |
| `spring.ai.advisor.name` | advisor 名称（高基数） | 每个节点可区分 |
| `spring.ai.advisor.order` | 链中顺序（高基数） | 反映执行顺序 |
| `spring.ai.kind` | `advisor` | 低基数分类 |

**Advisor 方案的优势**：
- 每个 Advisor 节点有独立 Span，天然形成"节点调用链"视图
- Spring AI 1.1 的 `ToolCallAdvisor` 把工具调用循环纳入 Advisor 链，每次 tool call 都有 Span
- akjamie 博客实战验证：用 Advisor + ObservationFilter + LangfuseObservationHandler 实现了完整的 Agent → LLM → Tool 调用链追踪
- Spring AI Alibaba 也有 Graph 工作流观测示例（豆包文档提到 `spring-ai-alibaba-observability-example` 模块）

**注意事项**：
- Advisor 观测的是 ChatClient 内部的 Advisor 链，不等同于 LangGraph 式的多节点 Agent 图
- 如果项目用 Spring AI Alibaba 的 Graph/Node 抽象，需确认 Alibaba 是否提供 Graph 级观测（豆包文档说有示例）
- Advisor 的 Span 仍受 trace 采样影响（和 Baggage/ObservationConvention 一样在右下象限）

### Q3：综合对比与选型建议

见下文第二、三、四章。

---

## 二、方案全集归并与对比

### 2.1 去重归并后的 8 种方案

将豆包文档（4 种通用 + Spring AI 3 种 + OTel 3 种）、V4-Demo 上线版 5 种、Advisor 方案去重归并：

| 编号 | 方案 | 来源 | 核心机制 |
|---|---|---|---|
| S1 | SessionBridge HTTP POST | V4-Demo 方案1 | @Async 独立 HTTP 管道写 session_turns 表 |
| S2 | OTLP Log | V6 推荐 / V4-Demo 方案2 | 结构化日志 → logback OTLP appender → OTLP logs pipeline |
| S3 | ObservationConvention | 豆包 Spring AI 方式1 / V4-Demo 方案3 | 实现 ChatClientObservationConvention 注入 session.id 到 Span |
| S4 | OTel Baggage ⭐ | 豆包 OTel 方式1 / V4-Demo 方案4 / Langfuse 推荐 | Baggage 全链路透传 session.id → BaggageSpanProcessor 复制到 Span |
| S5 | Spring AI Advisor | V4-Demo 方案5 / 主人询问 | Advisor 链节点级观测，每节点独立 Span |
| S6 | SpanProcessor 全局注入 | 豆包 OTel 方式2 / trpc-agent-go 实践 | 自定义 SpanProcessor.onStart 统一注入 session 属性 |
| S7 | MDC 日志埋点 | 豆包通用方案1 | SLF4J MDC 注入 sessionId，仅关联日志 |
| S8 | AOP/拦截器+ThreadLocal | 豆包通用方案2,4 / Spring AI 方式2 | Web 拦截器提取 sessionId 存 ThreadLocal 透传 |

### 2.2 六维度对比矩阵

| 维度 | S1 Bridge | S2 OTLP Log | S3 ObsConv | S4 Baggage⭐ | S5 Advisor | S6 SpanProc | S7 MDC | S8 AOP |
|---|---|---|---|---|---|---|---|---|
| **标准化程度** | 项目自创 | OTel 信号但非 session 专用 | Spring AI 标准 | OTel 官方标准 | Spring AI 标准 | OTel 标准 | 业界通用 | 通用但非标准 |
| **100% 捕获** | ✅ 独立管道 | ✅ logs 独立 pipeline | ❌ 受采样影响 | ❌ 受采样影响 | ❌ 受采样影响 | ❌ 受采样影响 | ✅ 日志独立 | ❌ 单应用内 |
| **跨服务透传** | ❌ | ✅ OTLP | ⚠️ 需桥接 OTel | ✅ Baggage 原生 | ❌ 仅 Spring AI 内 | ✅ Context 传播 | ❌ | ❌ |
| **代码侵入** | SessionBridge 类 | logback 配置 | Convention 实现类 | 拦截器 3 行代码 | Advisor 链配置 | Processor 注册 | 拦截器 | 拦截器+ThreadLocal |
| **业界采用** | 项目独有 | ⚠️ 无先例 | Spring AI 社区 | Langfuse/trpc-agent/akjamie | Spring AI 官方 | trpc-agent-go 实践 | 通用 | 通用 |
| **AI 语义适配** | 自定义 | 无 AI 语义 | ✅ gen_ai.* 对齐 | ✅ session.id 标准 | ✅ spring.ai.* 标准 | ⚠️ 需手动对齐 | ❌ | ❌ |

### 2.3 核心矛盾图解

参见上方象限图。核心矛盾在于：

**"100% 捕获"与"业界标准"不可兼得**：
- 所有业界标准方案（S3/S4/S5/S6）都在右下象限——受 trace 采样影响
- 唯一能 100% 独立捕获的方案要么是项目自创（S1 左上），要么无业界先例（S2 中上）

**业界实际解法**：不绕道 Log，而是 **Baggage + 采样策略调整**：
- 对包含 session 的业务 trace 设置 100% 采样（tail_sampling 策略）
- 或对 LLM 相关 trace 整体 100% 采样（LLM 调用频率不高，全量采样的成本可接受）

---

## 三、不同场景的方案选型建议

### 场景 A：单 Java 应用、Spring AI 技术栈、快速落地（Demo / POC）

**推荐：S4 OTel Baggage + S3 ObservationConvention**

| 层 | 方案 | 作用 |
|---|---|---|
| 透传层 | S4 OTel Baggage | session.id 全链路自动传播到所有 Span |
| 语义层 | S3 ObservationConvention | 注入 gen_ai.* 业务属性到 Span |
| 存储 | trace 100% 采样（Demo 量小） | 直接从 Span 表按 session_id 聚合 |

理由：Demo 期 trace 采样率通常 100%，不存在采样丢失问题。Baggage 3 行代码即可实现，最轻量。

### 场景 B：生产环境、需要 100% 会话审计（银行场景）

**推荐：S4 OTel Baggage + tail_sampling 业务策略 + S1 SessionBridge 兜底**

| 层 | 方案 | 作用 |
|---|---|---|
| 透传层 | S4 OTel Baggage | session.id 传播到 Span，支持标准平台聚合 |
| 采样层 | tail_sampling 业务策略 | 对含 session.id 的业务 trace 100% 采样，非业务 trace 10% |
| 兜底层 | S1 SessionBridge（保留 HTTP POST 或改标准方式） | 审计级 100% 捕获，不依赖 trace |
| 语义层 | S3 ObservationConvention | gen_ai.* 业务属性 |

**关于兜底层 S1 的传输方式**：
- 如果银行已有统一日志平台 → 可走结构化日志（非 OTLP Log 管道，而是银行标准日志采集）
- 如果无统一日志平台 → 保留 SessionBridge HTTP POST 写 OceanBase（项目已有实现）
- **不推荐改 OTLP Log**（无业界先例，增加 Collector logs pipeline 复杂度）

### 场景 C：分布式架构、多语言混合、企业级统一可观测

**推荐：S4 OTel Baggage + S6 SpanProcessor + S5 Advisor**

| 层 | 方案 | 作用 |
|---|---|---|
| 透传层 | S4 OTel Baggage | 跨服务跨语言传播 session.id |
| 注入层 | S6 SpanProcessor | 全局自动注入，业务零感知 |
| 节点层 | S5 Advisor | Spring AI 内部 Advisor 链节点级追踪 |
| 后端 | Langfuse / Grafana | 按 session.id 聚合查询 |

理由：分布式场景跨服务是刚需，Baggage 是唯一原生跨服务方案。SpanProcessor 实现企业级统一规范。

### 场景 D：Agent 复杂调用链、需要 Graph 节点级追踪

**推荐：S5 Spring AI Advisor + S4 OTel Baggage + S3 ObservationConvention**

| 层 | 方案 | 作用 |
|---|---|---|
| 节点层 | S5 Advisor | 每个 Advisor/ToolCall 节点独立 Span，展示 Agent→LLM→Tool 调用链 |
| 透传层 | S4 OTel Baggage | session.id 关联所有节点 |
| 语义层 | S3 ObservationConvention | gen_ai.* 对齐，token/latency/error 等指标 |

理由：Spring AI 1.1 的 ToolCallAdvisor 把工具调用循环纳入 Advisor 链，每个 tool call 有独立 Span，天然形成 Graph 视图。Advisor 是 Spring AI 官方支持的 Graph 节点观测标准方案。

---

## 四、对当前项目两个文档矛盾的处理建议

### 4.1 矛盾定位

| 文档 | OTLP Log 立场 | 理由 |
|---|---|---|
| V6 生产上线版 0728 | ✅ 推荐 | session 必须 100% 捕获，trace 采样会丢，OTLP Log 独立 pipeline |
| V4-Demo 上线版 0728 | ❌ 不推荐 | OTLP Log 非 session 专用，业界无先例，Span 已结构化 |

**两份文档都标 0728，结论却相反。**

### 4.2 裁决建议

**采纳 V4-Demo 上线版的判断：不推荐 OTLP Log。**

但需补全 V6 生产版提出"100% 捕获"痛点的解决方案：

```
V6 的痛点：session 必须 100% 捕获，trace 采样 10% 会丢 90%
     ↓
业界解法（非 OTLP Log）：
  ① tail_sampling 对业务 trace 100% 采样（含 session.id 的 trace 不采样）
  ② 保留 SessionBridge 写 session_turns 表做审计兜底（传输方式可选）
     ↓
而非：用 OTLP Log 绕过采样（无业界先例，增加复杂度）
```

### 4.3 具体修改建议

**V6 生产上线版**：
1. §数据流图：将"Core Session 数据 [目标] OTLP Log"改为"Core Session 数据 [目标] OTel Baggage + 业务 trace 100% 采样"
2. §独立管道说明：保留"sessions 管道独立于 traces 采样"的原则，但传输方式从 OTLP Log 改为"Baggage 传播 + tail_sampling 业务策略 + SessionBridge 审计兜底"
3. 删除"OTLP Log 替代 HTTP POST"的改造计划

**V4-Demo 上线版**：
- §4.5 当前方案（Baggage 为主 + SessionBridge 兜底）**基本正确**，建议补充：
  - tail_sampling 增加"业务 trace 100% 采样"策略（确保含 session.id 的 trace 不被采样丢弃）
  - 明确 SessionBridge 的传输方式选择（HTTP POST 或银行标准日志采集，不用 OTLP Log）

**telemetry-target-architecture.md**：
- Phase 1"Session 数据改走 OTLP Log"应改为"Session 数据走 Baggage + 业务 trace 100% 采样 + SessionBridge 审计兜底"
- 删除"用 OTLP Log 替代 HTTP POST"的协议统一论

---

## 五、总结

| 问题 | 结论 |
|---|---|
| OTLP Log 可信吗？ | ❌ 不可信，业界无先例，Spring AI/Langfuse/OTel 均未推荐 |
| Advisor 方案合理吗？ | ✅ 合理，Spring AI 官方标准观测点，适合 Graph 节点追踪 |
| 最佳方案？ | Baggage（透传）+ ObservationConvention（语义）+ Advisor（节点）+ 采样策略（100%捕获）+ SessionBridge（审计兜底） |
| V4 vs V6 矛盾？ | 采纳 V4 判断（不用 OTLP Log），用"采样策略调整"解决 V6 的 100% 捕获痛点 |
