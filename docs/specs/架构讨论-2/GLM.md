主人这个问题很硬核，我先去翻一下 Spring AI / Spring AI Alibaba 官方最新的可观测体系文档，确认业界主流方案的准确细节，避免凭记忆瞎说。

先并行调研几条线。
已有一些官方实践。我再补两条关键信息：Spring AI 官方链接 + OpenTelemetry GenAI 语义规范。
资料够了。直接给主人干货。

---

# 主人，这份诊断报告请收好

调研了一圈官方文档（java2ai.com / Spring AI Alibaba official observability practice）和 OpenTelemetry GenAI 语义规范（v1.41），先给四个问题逐条回答，最后给迁移建议。

---

## Q1. 怎么基于现有 Advisor 架构继续做业务指标埋点？

**关键认知：Advisor 不是埋点的唯一抓手，Spring AI 在每一层都开了观察口子，Advisor 只是其中一层。** 你现在用 Advisor 拦截 All of Graph，本质是在「会话边界」埋点；但要细到 Agent 节点 / LLM 单次调用 / Tool 调用，那不是 Advisor 的活，是 `ObservationRegistry` + `MeterBinder` 的活。

Spring AI Alibaba 1.0.x+ 的可观测栈是这样的（官方实践文档原话）：

```
埋点层  → Micrometer 在 ChatModel / ToolCall / Embedding / VectorStore 自动 hook
导出层  → micrometer-tracing-bridge-otel (或 brave) → OTLP / Zipkin
存储层  → Prometheus + Jaeger / Langfuse / SkyWalking
扩展层  → ObservationHandler / ObservationConvention / MeterBinder 自定义
```

所以你下一步的埋点策略，建议拆成三层：

| 层级 | 采集器 | 采集对象 | 示例 |
|---|---|---|---|
| LLM 模型层 | Spring AI 内置 `ChatModelObservation` | 已经自动埋好，不用你写 | `gen_ai.client.token.usage`、`gen_ai.client.operation.duration` |
| Agent/场景层 | 自己的 Advisor + `MeterBinder` | 入口请求数、场景路由命中数、Agent 成功率 | `bank.scene.qps{scene=转账}`、`agent.success_rate{agent=理财助手}` |
| 业务行为层 | AOP @AfterReturning / 手动 timer | 按钮点击、结果采纳、转人工、卡片曝光 | `business.card.click_total{card=产品推荐}` |

**对应到代码骨架**（业务指标），不是在 Advisor 里硬塞 Counter，而是写一个 `MeterBinder` 把场景维度注册进 `MeterRegistry`，然后 Advisor 里只做"读取场景标签 → increment"：

```java
@Component
public class BusinessMeterBinder implements MeterBinder {
    @Override
    public void bindTo(MeterRegistry registry) {
        // 场景入口 QPS + 结果：counter + tag(scene, agent, result)
        Counter.builder("bank.scene.requests")
               .tag("scene", "").tag("agent", "").tag("result", "")
               .register(registry);   // 占位，实际由代码 increment
    }
}

// Advisor 里只做记数
public static void recordScene(MeterRegistry reg, String scene, String agent, String ok) {
    Counter.builder("bank.scene.requests").tag("scene", scene).tag("agent", agent)
           .tag("result", ok ? "success" : "fail").register(reg).increment();
}
```

**自定义业务指标（点击率、采纳率）走 AOP**，不要混进 Advisor，Advisor 关心的是 LLM/graph，业务埋点跟 LLM 无关，别耦合：

```java
@Aspect
@Component
public class BusinessEventAspect {
    private final MeterRegistry reg;
    @AfterReturning("@annotation(CardExpose)") public void expose() {
        Counter.builder("business.card.expose").tag("card", ...).register(reg).increment();
    }
    @AfterReturning("@annotation(CardClick)")  public void click() {
        Counter.builder("business.card.click").tag("card", ...).register(reg).increment();
    }
}
```

然后点读率 = click / expose，在 Grafana 里算，不在代码里算。

---

## Q2. 当前方案有什么问题？

说实话，主人你现在这套「Advisor 拦截一切 → Kafka → 落 OceanBase 自建大屏」的做法，**短期凑合、长期是债**。具体四个小问题：

1. **Advisor 被 Spark/Graph 这种场景绕过怎么办？** Advisor 只挂在 ChatClient 调用链上。如果 Graph 内部直接走 `ChatModel.call()`（不是 `ChatClient.prompt()`），你的拦截**根本不触发**——你只拦了一半。Spring AI 的 `ChatModelObservation` 是在模型适配器内部 hook 的，比 Advisor 根更深，啥场景都拦得到。

2. **Trace 没有"标准"概念。** 你自己存的 trace 没有 OTel span 树、没有 traceId 透传、没有外部调用关联。一旦业务接了下游服务（查账户、查产品），你的 trace 断在 LLM 这一段，看不到一头扎到底的链路。OTel 的 `invoke_agent → chat → tool_call → downstream http` span 树你拿不到。

3. **指标和 trace 走同一个数据通道（Advisor→Kafka），但二者生命周期完全不一样。** 指标是时序聚合（每 15/60s 刷一次），trace 是一条一条事件。揉进一个数据库做两套查询，大屏慢、查询贵。业界共识：**metrics 走 Prometheus/Mimir，traces 走 Tempo/Jaeger/SkyWalking**，两层不同存储。

4. **会话回放只能自己写 UI。** 成本别低估——Langfuse、Phoenix、Aspire Dashboard 这些现成 GenAI trace viewer，能直接把 `gen_ai.input.messages` 渲染成聊天框视图，你不用自己写一遍 chat-bubble 组件。

---

## Q3. 业界主流方案长啥样（Spring AI / Spring AI Alibaba）

Spring AI Alibaba 1.1.x 已经把可观测做成「加依赖 + 改 YAML」的零代码水位（这是 aliyun 官方文章原话："零代码改造 + 全链路追踪"）：

**依赖（一行不多加）**：
```xml
spring-boot-starter-actuator
micrometer-registry-otlp            <!-- 指标 OTLP -->
micrometer-tracing-bridge-otel      <!-- trace 桥 OTel -->
opentelemetry-exporter-otlp         <!-- OTLP exporter -->
spring-ai-alibaba-starter-dashscope <!-- 这个你本来就有 -->
```

**配置（application.yml）**：
```yaml
management:
  tracing:
    sampling.probability: 1.0   # 全采，生产建议 0.1
  endpoints.web.exposure.include: prometheus,health,metrics

spring.ai.chat.client.observations:
  log-prompt: true
  log-completion: true
  include-error-logging: true
```

**数据流**：
```
ChatClient/ChatModel/Tool/Embedding
        ↓ (Micrometer 自动埋点)
   ObservationRegistry
   ├── Metrics → MeterRegistry → OTLP → OTel Collector → Prometheus
   └── Traces  → Tracer         → OTLP → OTel Collector → Jaeger/Tempo
        ↓
   后端用 Grafana 一屏看指标 + 链路
```

**Spring AI 1.1 已内置**的核心指标（事件名按 GenAI 语义规范命名，不是你自造的）：
- `gen_ai.client.token.usage` — 按 input/output/total 切分的 Counter
- `gen_ai.client.operation.duration` — 调用耗时 Histogram
- `gen_ai.client.operation.error` — 错误数 Counter

**关键缺口：Advisor 这一层 Spring AI 没原生埋点**（Spring AI 文档明确说 Advisor 不会被自动观察，所以业界做法是写一个 `TraceAwareAdvisor` 把当前 Span 注入 context + 在 `adviseResponse` 里给 span 加自定义 attribute，前面搜到的 aliyun 文章就是这个套路）。

**扩展点（Spring AI 给了你两手）**：
- `ObservationHandler<ChatModelObservationContext>` — 在 start/stop 钩子里加自定义 attribute，比如打银行场景标签
- `ObservationConvention` — 改 span/metric 的命名和 tag，统一团队规范

**OpenTelemetry GenAI 语义规范**（v1.41，仍在 Development 但概念已稳定）已经定义了 Agent 相关 span：
- `invoke_agent` — 智能体调用（你现在 Graph 一次巡演就是一个）
- `create_agent` — Agent 创建
- `execute_tool` — 工具调用，span 名带工具名
- `invoke_workflow` — 多步骤编排

这些 span 在 Jaeger/Tempo 里能直接看出「一个 user 请求触发了几个 LLM 调用、哪个工具慢、哪个 agent 转人工」。

---

## Q4. 是不是该扔掉 Advisor 直接换主流方案？

**不要扔，要做"双轨过渡 + 渐进收编"，两周能见效果。** 我的建议分三步走：

### 阶段 1（1 周）— 接进来，不拆
- 加 actuator + micrometer-tracing-bridge-otel + otlp exporter 依赖
- 改 YAML 开启 observations
- OTel Collector 转一份 Prometheus + 一份 Jaeger（或直接 Langfuse，专门给 GenAI 用）
- **你现在的 Advisor 一行不动**，继续跑 Kafka+OceanBase，只作为业务原始数据源
- 这时你会看到：Prometheus 大屏自动有 token/latency/error，Jaeger 自动有 ChatModel span 树

**这一步对比就立刻能看出你旧方案拦到了多少、漏了多少**——通常会发现 Graph 内部用的 `ChatModel` 直接调用没被你的 Advisor 抓到，但被 Micrometer 抓到了。

### 阶段 2（1~2 周）— 把 Advisor 改造成"桥"
- 给 Advisor 加两个钩子：
  1. `adviseRequest` 时把当前 OTel `TraceId` / `SpanId` 写进你的 Kafka 消息，落库时一起存
  2. `adviseResponse` 时给当前 Span 加自定义 attribute：`bank.scene=转账`、`bank.session_id=xxx`、`bank.user_id=xxx`
- 这样：Jaeger 里能按 `bank.scene=转账` 过滤 span，你的 OceanBase 纪录里有 traceId，两边能 join 回来
- **不再在 Advisor 里塞 Counter/Histogram**——这些都让 MeterRegistry 自动做，Advisor 只负责给 span 给业务 tag

### 阶段 3（再之后）— 业务"自定义指标"独立成层
- AOP 写业务埋点（卡片点击率、结果采纳率），直接注册到 `MeterRegistry`，走 OTLP → Prometheus
- Advisor 退化为两件事：① 注入 traceId 透传 ② 给 span 打业务 tag
- OceanBase 那批历史数据可以继续做"只读归档 + 会话回放"用途（毕竟你已经存了大半年历史数据，扔了可惜），新链路用 Langfuse 做会话回放

---

## 给个一句话决策表

| 问题 | 短回答 |
|---|---|
| 业务指标怎么埋? | LLM 指标用 Spring AI 内置 observation，业务行为用 AOP 写 MeterBinder，Advisor 只打 trace tag 不写 Counter |
| 当前方案问题? | ① 拦不全(漏 ChatModel 直调) ② trace 非标准 ③ 指标和 trace 揉一库 ④ 自己写回放 UI 成本高 |
| 业界主流? | Micrometer + micrometer-tracing-bridge-otel + OTLP → Prometheus/Jaeger + 选 GenAI 专用 viewer(Langfuse/Phoenix) |
| 替换还是改造? | **不替换，渐进双轨**:先并行接入官方 stack 对照差值，再把 Advisor 改成"打 traceId + 业务 tag"的桥，最后业务指标独立 |

---

要不要我接下来帮你写一个 PoC：用一个最小 demo（一个 ChatClient + 一个 Advisor + OTel exporter）跑通端到端的 trace + 指标 + 会话回放？跑通后你直接对照现有方案看漏点，比看文档直观得多。告诉我就开干，顺手把今天这份调研记到项目 memory 里。
调研已记到项目 memory。主人四个问题的核心结论在上面已经按 Q1~Q4 给齐了，一句话总线：

- **业务指标别再塞进 Advisor**——让它专心做 traceId 透传和场景 tag 注入，LLM 指标让 Spring AI 内置 observation 自动出，业务行为用 AOP+MeterBinder 独立埋点。
- **当前 Advisor 拦截一切的问题**：① 漏 ChatModel 直调 ② trace 非标准 ③ 指标/trace 揉一库 ④ 自己写回放 UI 成本高。
- **业界主流**：Spring AI Alibaba 已经是「actuator + micrometer-tracing-bridge-otel + OTLP → OTel Collector → Prometheus/Jaeger + 选 Langfuse 做 GenAI 回放」的零代码路子。
- **不是替换，是渐进双轨**：阶段1 加官方栈并行不拆旧的（一周看漏点），阶段2 Advisor 改造成桥（一到两周），阶段3 业务指标 AOP 独立成层。

要不要我直接做那个最小 PoC 跑通端到端？跑通后你拿真链路对比 Advisor 方案，比我口述直观十倍。