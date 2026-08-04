# 银行可观测落地 FAQ

> **编写日期**: 2026-07-27 / 2026-07-28
> **相关文档**:
> - `可观测架构业界调研与重构建议-GLM5.2-0726.md` v1.4（调研背景）
> - `可观测优化总结-WorkBuddy-V6-生产上线版-0727.md`（V6 生产版方案）
> - `可观测优化总结-WorkBuddy-V4-Demo上线版-0727.md`（V4 Demo 版方案）
> - `银行可观测落地细化方案-SpringAI1.1.2-Boot3.4.3-OceanBase.md`（本 FAQ §三 对应的完整配置）

---

## §一、Spring AI / Spring Boot 版本差异对可观测落地的影响

### Q: 不同银行的 Spring AI（1.0.9 / 1.1.8 / 2.0.0）和 Spring Boot（3.3.13 / 3.4.13 / 3.5.16 / 4.0.7 / 4.1.0）版本可能不同，对可观测落地影响多大？

**版本兼容矩阵：**

| Spring AI | Spring Boot | Java | 可观测能力 |
|-----------|------------|------|-----------|
| **1.0.9** | 3.3.x / 3.4.x / 3.5.x | 17+ | 基础 Micrometer 指标 + 日志，`gen_ai.*` 语义约定早期形态，**缺内置 token Counter 和 TTFT** |
| **1.1.8** | 3.4.x / 3.5.x | 17+ | 增强：Micrometer Metrics + Tracing + Token 统计（`gen_ai.client.token.usage`），ChatModel 自动 Observation |
| **2.0.0** | 4.0.x / 4.1.x | **21+** | 全面重写：原生 OTel 集成，ChatModel/Tool/Agent 全链路结构化 Span + Metrics，`gen_ai.*` 语义约定完整 |

**影响分三个层面：**

| 层面 | 影响程度 | 说明 |
|------|---------|------|
| **语义约定不统一** | 🔴 大 | 同一个 LLM 调用，1.0.x 只产生 `gen_ai.client.operation.duration`，1.1.x 多出 token usage（input/output/total），2.0.0 多出 tool call span、agent step span。Collector/Backend 按 span name 做路由/聚合/采样时，需兼容多种 schema |
| **导出路径不同** | 🟡 中 | Spring Boot 3.x 用 `micrometer-registry-otlp`（Micrometer→OTLP 桥接），4.x 用 `spring-boot-starter-opentelemetry`（原生 OTel SDK）。配置项完全不同，运维需维护多套配置模板 |
| **传输层不变** | 🟢 小 | 无论哪个版本，最终都通过 **OTLP 协议**（HTTP Protobuf `:4318`）往外发。OTel Collector 是协议级接收，不关心上游实现 |

### Q: 有些银行还使用了 Spring AI Alibaba，有什么影响？

Spring AI Alibaba（SAA）是 Spring AI 的**企业级超集**。在可观测上有三点增强：

1. **自动装配更完整**：`ObservabilityAutoConfiguration` 自动注册 ChatClient、Tool、Agent、Workflow 四层 ObservationConvention Bean，比原生 Spring AI 覆盖更广
2. **阿里云 ARMS 深度集成**：`spring.ai.alibaba.arms.enabled=true` 直通 ARMS，且支持 `capture-input/output` 控制 Prompt/Response 内容采集（银行审计刚需）
3. **DashScope Adapter 的坑**：DashScope adapter 只给 `gen_ai.client.operation.duration`，**缺少 token Counter 和 TTFT**——同原生 Spring AI，仍需 ObsChatModel 等手动埋点补 Token 指标

**一句话**：SAA 的可观测能力比同版本原生 Spring AI 更强，但底层 DashScope 适配器的 token 盲区跟原生一样，需要手工补。

---

## §二、Spring AI vs OpenTelemetry 可观测框架差异

### Q: Spring AI 和 OpenTelemetry 的可观测实现框架有什么差异？

**这不是二选一的关系，而是不同层级、互补共存。** 项目已在实际 demo 中验证了这一结论。

| 维度 | Spring AI 可观测 | OpenTelemetry |
|------|-----------------|---------------|
| **定位** | AI 应用层 | 全栈（基础设施+应用+AI） |
| **实现方式** | Micrometer Observation API | OTel API/SDK + Java Agent 字节码增强 |
| **自动覆盖** | ChatClient、ChatModel、Tool、Agent、Workflow | HTTP、JDBC、Kafka、gRPC、Reactor、Spring MVC |
| **语义约定** | `gen_ai.*`（AI 专属） | `http.*`、`db.*`、`messaging.*`、`gen_ai.*`（全领域） |
| **指标协议** | Micrometer MeterRegistry → Prometheus/OTLP | OTel Metrics SDK → OTLP |
| **链路协议** | Micrometer Tracing（Brave/OTel bridge）→ OTLP | OTel Tracer → OTLP |
| **语言支持** | Java only | 多语言（Java/Python/Go/JS/.NET...） |
| **配置方式** | `application.yml` | 环境变量 / `-javaagent` 参数 / `config.yaml` |

**核心差异：各管一层，合起来才完整。**

```
┌─────────────────────────────────────────┐
│  业务指标层                               │
│  Reroute率/完成率/转人工率 → 必须手工写    │
├─────────────────────────────────────────┤
│  AI应用层 ← Spring AI Observation        │
│  ChatModel/Tool/Agent/Workflow Span+指标  │
│  gen_ai.client.operation.duration        │
│  gen_ai.client.token.usage               │
├─────────────────────────────────────────┤
│  基础设施层 ← OTel Java Agent             │
│  HTTP/JDBC/Kafka/Reactor Span            │
│  JVM 指标（内存/GC/线程）                  │
│  → AOP切不到，只有Agent能采               │
└─────────────────────────────────────────┘
```

Key：Spring AI observation（基于 Micrometer + OTel 桥接）和 OTel Agent **不是对立方案，是同生态不同层入口**。

---

## §三、银行插桩策略：优先用哪个？

### Q: 基于国内大多数银行的业务系统，怎么做插桩？优先用 Spring Boot/Spring AI 还是 OpenTelemetry？

**推荐：OTel Java Agent 打底 + Spring AI Observation 补 AI 层 + 手工业务指标。**

不选"优先用哪个"——选"怎么分层组合"。

#### 三层插桩策略

```
第一层：OTel Java Agent（零代码，必选）
  ↓ 自动采：HTTP请求、JDBC查询、Kafka消息、Reactor线程
  ↓ 部署：启动参数加 -javaagent:opentelemetry-javaagent.jar
  ↓ 对银行的意义：覆盖所有微服务间调用、数据库访问、消息队列

第二层：Spring AI Observation（框架自带，免费）
  ↓ 自动采：ChatModel调用、Tool执行、Agent步骤
  ↓ 配置：spring.ai.chat.observations.enabled=true
  ↓ 对银行的意义：LLM每次调用的延迟、Token量、错误类型

第三层：手工业务埋点（必须写代码，省不掉）
  ↓ 业务指标：Reroute率、完成率、转人工率、审批通过率
  ↓ LLM专属：TTFT（首Token时间）、TPOT（每输出Token时间）
  ↓ 对银行的意义：业务KPI，任何框架都不会自动给
```

#### 为什么以 OTel Agent 为主入口？

1. **覆盖面最广**：Agent 自动采 HTTP/JDBC/Kafka/Reactor/gRPC，Spring AOP 切不到这些底层调用
2. **零代码侵入**：启动参数挂载，不改一行 Java 代码。银行审批最容易过
3. **协议统一**：OTLP 是 CNCF 标准，不绑任何厂商。银行已有的 ARMS/自研平台都能接
4. **Spring AI Observation 是叠加项**：有 Spring AI 自然就有 Micrometer Observation，打开开关即可

#### 为什么不优先用 Spring Boot/Spring AI 为主？

- Spring AI 的 Observation 只覆盖 AI 层，**不覆盖** HTTP 入口、数据库查询、消息队列——而这些是银行系统故障定位的主战场
- Spring Boot 3.x 的 Micrometer Tracing 底层 Brave 正在被 OTel 取代，选 OTel 避免二次迁移

**结论：OTel Java Agent 做基座（基础设施全量覆盖），Spring AI Observation 做 AI 增强（LLM 专属维度），手工埋点做业务 KPI。三者缺一不可。**

---

## §四、国内银行信创后主流数据库

### Q: 国内大多数银行的信创后主流 DB 系统是什么？

**多强并立，没有一家独大。** 国有六大行各选 1-2 款国产数据库：

| 数据库 | 银行使用情况 | 市场份额特征 |
|--------|------------|------------|
| **GaussDB**（华为） | **六大行业务系统数量占比最高**，邮储银行个人核心系统全量使用 | 国产数据库"系统数量"第一，全栈国产（芯片→OS→DB） |
| **OceanBase**（蚂蚁） | **近七成万亿级银行核心系统**，连续三年金融分布式数据库市场份额第一，服务 400+ 金融机构 | "核心系统"市占率最高，MySQL 兼容 |
| **TDSQL**（腾讯） | 中国银行广泛使用，微众银行全量 | 腾讯系生态，MySQL 兼容 |
| **GoldenDB**（中兴） | 中信银行核心系统 | 电信+金融双线 |
| **TiDB**（PingCAP） | 部分股份制银行非核心场景 | 开源社区活跃，MySQL 兼容 |

**关键结论：**

1. **GaussDB 和 OceanBase 是第一梯队**：GaussDB 胜在"系统数量"，OceanBase 胜在"核心系统市占率"
2. **MySQL 兼容是最大公约数**：OceanBase、TDSQL、TiDB、GoldenDB 都兼容 MySQL 协议
3. **没有 PG 什么事**：银行核心系统基本不走 PostgreSQL
4. **信创后 Oracle 仅剩少量存量业务**，不再作为新系统选型

**对可观测落地的启示：**

- OceanBase/TDSQL 等 MySQL 兼容模式 → DDL 用 MySQL 方言（`AUTO_INCREMENT`、`VARCHAR` 等）
- GaussDB → DDL 适配 PG 语法（`SERIAL`、`TEXT` 等）
- **关键原则：复用银行已有数据库，不引入新存储**

---

## §五、Langfuse 在国内银行的可行性

### Q: Langfuse 在国内银行还可以推广使用吗？

**结论：基本不行。**

Langfuse 自托管需要 **PostgreSQL + ClickHouse + Redis + S3 + Workers**（5+ 服务）。国内银行信创后的主力数据库是 OceanBase/GaussDB/TDSQL，**没有 PostgreSQL**。

| Langfuse 部署方式 | 银行可行性 | 原因 |
|-------------------|----------|------|
| **自托管（Self-Hosted）** | ❌ 不可行 | 强依赖 PostgreSQL（Prisma ORM），银行没有 PG |
| **OceanBase Langfuse Fork** | ❌ 不可行 | 未真正替换 PG，只是外围定制（Harbor 镜像、ClickHouse AMT 优化等），核心数据库依赖仍是 PG |
| **Langfuse Cloud** | ❌ 合规不过 | 数据出国，银行监管一票否决 |

### Q: 如果不用 Langfuse，影响多大？有什么替代方案？

**影响可控。** Langfuse 提供的四个核心能力，每块都有替代：

| Langfuse 能力 | 影响程度 | 推荐替代方案 | 说明 |
|-------------|---------|-------------|------|
| **LLM Trace 可视化** | 🟡 中等 | 自研前端（复用 demo TraceQueryService） 或 Grafana | OTLP 协议标准，span 里有所有需要的信息 |
| **Prompt 管理/版本化** | 🟢 较小 | OceanBase 建 `prompt_templates` + `prompt_versions` 表 | 简单 CRUD，复杂度匹配 |
| **LLM 评估框架** | 🟡 中等 | **DeepEval**（开源，Python，不绑 PG）| 离线评估 pipeline，50+ 评估指标 |
| **成本追踪** | 🟢 较小 | OceanBase `token_cost` 表 + Grafana Dashboard | 银行已有 BI 可直接对接 |

### 替代组件完整矩阵

```
┌──────────────────┬───────────────┬──────────┬──────────────┐
│ 需要的能力        │ 第一推荐       │ 第二推荐  │ 为什么不选     │
├──────────────────┼───────────────┼──────────┼──────────────┤
│ Trace 可视化      │ 自研前端       │ Grafana  │ Langfuse需PG  │
│ Prompt 管理       │ OceanBase表   │ Nacos    │ 复杂度匹配     │
│ 评估框架          │ DeepEval      │ MLflow   │ DeepEval最轻  │
│ 成本指标          │ Grafana+DB    │ 自研     │ 银行已有BI    │
│ 实验追踪          │ **MLflow**    │ -        │ MySQL兼容可用  │
└──────────────────┴───────────────┴──────────┴──────────────┘
```

> **MLflow 特别说明**：MLflow 通过 SQLAlchemy 支持 MySQL，即 **OceanBase MySQL 兼容模式可直接用**。MLflow 3.x 已有 LLM Tracing 能力（通过 OTel），支持 prompt 管理和实验对比。如果银行已有 ML 平台意愿，MLflow 是比 Langfuse 更实际的选项。

---

## §六、银行落地方案：Spring AI 1.1.2 + Boot 3.4.3 + OceanBase

> 完整配置（Maven / yml / Agent / Collector / DDL / 代码）见配套文档：
> `银行可观测落地细化方案-SpringAI1.1.2-Boot3.4.3-OceanBase.md`

### 6.1 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Bank Core Application                        │
│  Spring AI 1.1.2 + Spring Boot 3.4.3                               │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Layer 3: 手工业务埋点 (Micrometer SDK)                        │  │
│  │  deepflux.reroute.rate, deepflux.completion.rate,             │  │
│  │  deepflux.human.escalation, deepflux.workflow.pending_approval│  │
│  ├──────────────────────────────────────────────────────────────┤  │
│  │  Layer 2: Spring AI Observation (框架自带)                     │  │
│  │  gen_ai.client.operation.duration,                            │  │
│  │  gen_ai.client.token.usage (input/output/total)               │  │
│  │  + ObsChatModel 补: llm.first_token.latency (TTFT)            │  │
│  ├──────────────────────────────────────────────────────────────┤  │
│  │  Layer 1: OTel Java Agent (零代码 -javaagent 挂载)            │  │
│  │  HTTP, JDBC, Kafka, Reactor spans                             │  │
│  │  JVM metrics (内存/GC/线程)                                    │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                          │                                          │
│              micrometer-registry-otlp (指标→OTLP)                   │
│              OTel Agent (trace/log/metric→OTLP)                     │
└──────────────────────────┼──────────────────────────────────────────┘
                           │ OTLP :4318
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     OTel Collector (otelcol-contrib)                  │
│  pipelines:                                                          │
│    traces:  [memory_limiter → tail_sampling → filter → batch]        │
│    metrics: [memory_limiter → filter → batch]                        │
│    logs:    [memory_limiter → filter → batch]  ← Session用OTLP Log   │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ OTLP
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    OTLP Receiver / 自研 Backend                       │
│  接收 Collector 导出的 trace/metric/log → 写入 OceanBase              │
│  提供查询 API (复用 demo TraceQueryService 模式)                      │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ JDBC
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          OceanBase                                    │
│  spans │ metrics_agg │ sessions │ session_turns                       │
│  prompt_templates │ prompt_versions │ token_costs                    │
└──────────────────────────────────────────────────────────────────────┘

可选增强:
  VictoriaMetrics (仅当 OceanBase 时序查询性能不足时)
  Kafka (仅当日均 Span > 2000 万时做缓冲)
  Grafana (标准 Dashboard, 仪表盘/告警)
  银行已有大数据系统 (长期归档, trace/session 冷数据)
```

### 6.2 埋点：三层互补

#### Layer 1 — OTel Java Agent（基础设施全量覆盖）

零代码，启动参数挂载：

```bash
java \
  -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=bank-ai-core \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=otlp \
  -Dotel.logs.exporter=otlp \
  -Dotel.exporter.otlp.endpoint=http://127.0.0.1:4318 \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.propagators=tracecontext,baggage \
  -Dotel.resource.attributes=deployment.environment=production,host.name=$(hostname) \
  -jar bank-ai-core.jar \
  --server.port=8080
```

自动覆盖：HTTP（Spring MVC/RestTemplate/WebClient）、JDBC（自动生成 `db.type=OceanBase`）、Kafka、Reactor、JVM 指标。

> ⚠️ 如果银行已有 ArmsAgent 或类似 APM Agent，需确认兼容性。两个 Agent 同时挂载可能冲突，建议二选一或将原 Agent 的 tracing 关闭只保留 JVM 监控。

#### Layer 2 — Spring AI Observation（AI 应用层）

**Maven 依赖：**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-spring-boot-autoconfigure</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-otlp</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**application.yml：**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      otlp:
        enabled: true
        url: http://127.0.0.1:4318/v1/metrics
        step: 30s
    tags:
      application: ${spring.application.name:bank-ai-core}
  tracing:
    sampling:
      probability: 1.0  # 开发全量，生产 0.1~0.5

spring:
  ai:
    chat:
      observations:
        enabled: true
        include-prompt: false    # 生产关，防泄露
        include-completion: false # 生产关，防泄露
```

Spring AI 1.1.2 自动提供：

| 指标 | Meter 名 | 说明 |
|------|---------|------|
| 调用耗时 | `gen_ai.client.operation.duration` | ChatModel.call() 耗时 |
| Token 输入 | `gen_ai.client.token.usage` (type=input) | prompt tokens |
| Token 输出 | `gen_ai.client.token.usage` (type=output) | completion tokens |
| 错误次数 | `gen_ai.client.operation.error` | 按 error.type 区分 |

**手动补：ObsChatModel（TTFT + 业务 span）**

Spring AI 1.1.2 不自带 TTFT (Time To First Token)，需 ObsChatModel 装饰器：

```java
@Component
public class ObsChatModel implements ChatModel {
    private final ChatModel delegate;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Override
    public ChatResponse call(Prompt prompt) {
        Span span = tracer.spanBuilder("chat.model")
            .setAttribute("agent.layer", AgentSpanContext.getLayer())
            .setAttribute("agent.intent", AgentSpanContext.getIntent())
            .setAttribute("session.id", AgentSpanContext.getSessionId())
            .startSpan();

        Timer.Sample sample = Timer.start(meterRegistry);
        long startNanos = System.nanoTime();

        try (Scope scope = span.makeCurrent()) {
            ChatResponse response = delegate.call(prompt);

            long ttft = System.nanoTime() - startNanos;
            meterRegistry.timer("llm.first_token.latency",
                "model", getModelName()).record(ttft, TimeUnit.NANOSECONDS);

            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                span.setAttribute("gen_ai.usage.input_tokens", usage.getPromptTokens());
                span.setAttribute("gen_ai.usage.output_tokens", usage.getGenerationTokens());
            }
            span.setStatus(StatusCode.OK);
            return response;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            meterRegistry.counter("llm.error.count", "error.type", e.getClass().getSimpleName()).increment();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("llm.operation.duration", "model", getModelName()));
            span.end();
        }
    }
}
```

#### Layer 3 — 手工业务指标（省不掉的代码）

```java
// Reroute 率
meterRegistry.counter("deepflux.reroute.count", "decision", "rerouted").increment();

// 完成率
meterRegistry.counter("deepflux.task.completed", "agent", agentName).increment();
meterRegistry.counter("deepflux.task.total", "agent", agentName).increment();

// 转人工率
meterRegistry.counter("deepflux.human.escalation", "reason", reason).increment();

// 审批积压
meterRegistry.gauge("deepflux.workflow.pending_approval", Tags.of("workflow", name),
    new AtomicLong(pendingCount));
```

### 6.3 传输：OTel Collector

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 512

  filter/health:
    traces:
      span:
        - 'IsMatch(name, ".*[Hh]ealth.*") == true'

  tail_sampling:
    decision_wait: 10s
    policies:
      - name: errors
        type: status_code
        status_code:
          status_codes: [ERROR]
      - name: slow
        type: latency
        latency:
          threshold_ms: 1000
      - name: llm-failure
        type: string_attribute
        string_attribute:
          key: error.type
          values: [timeout, auth_error, rate_limit]
      - name: normal-sampling
        type: probabilistic
        probabilistic:
          sampling_percentage: 10

  batch:
    send_batch_size: 512
    timeout: 5s

exporters:
  otlp/backend:
    endpoint: http://127.0.0.1:4317
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, tail_sampling, filter/health, batch]
      exporters: [otlp/backend]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, filter/health, batch]
      exporters: [otlp/backend]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, filter/health, batch]
      exporters: [otlp/backend]
```

> ⚠️ traces pipeline 顺序铁律：`tail_sampling` 必须在 `batch` 前。metrics/logs **不加** tail_sampling。

### 6.4 存储：OceanBase 表

**核心表（7 张）：**

| 表名 | 用途 | 数据来源 |
|------|------|---------|
| `spans` | 链路追踪（每条 span 一行） | OTel Agent + Spring AI Observation |
| `metrics_agg` | 指标聚合 | Micrometer → OTLP → Collector |
| `sessions` | 会话元数据 | OTLP Log |
| `session_turns` | 会话每轮详情 | OTLP Log |
| `prompt_templates` | 提示词模板管理 | 自研（Langfuse 替代） |
| `prompt_versions` | 提示词版本管理 | 自研（Langfuse 替代） |
| `token_costs` | 成本追踪 | 自研 |

**完整 DDL 见配套文档。**

### 6.5 Session 上报：OTLP Log 替代 HTTP POST

Session 数据（会话回放）从 `SessionBridge HTTP POST :9090` 独立管道改为 OTLP Log：

```java
@Async
public void reportSessionViaOtlp(SessionData session) {
    Logger logger = LoggerFactory.getLogger("session-reporter");
    MDC.put("trace_id", Span.current().getSpanContext().getTraceId());
    // ... logger.info(JSON)
    MDC.clear();
}
```

**优势：OTLP Log 不受 trace 采样影响（logs pipeline 无 tail_sampling），sessions 100% 捕获。**

### 6.6 Redis / Kafka 角色

| 组件 | 何时需要 | 用途 |
|------|---------|------|
| **Redis** | 始终 | 实时指标缓存（TTL 5min）、Session 热数据缓存 |
| **Kafka** | 日均 Span > 500 万 | Collector → Kafka → Receiver 削峰填谷 |

### 6.7 改造检查清单

- [ ] **Maven**: 添加 `micrometer-registry-otlp`，确认版本与 Spring Boot 3.4.3 兼容
- [ ] **yml**: 配 `management.otlp.metrics.export.url` / `management.tracing.sampling.probability`
- [ ] **yml**: `spring.ai.chat.observations.enabled=true`, `include-prompt=false`
- [ ] **Agent**: 下载 `opentelemetry-javaagent.jar`，挂 `-javaagent` 启动参数
- [ ] **Agent**: 确认与银行已有 APM Agent 不冲突
- [ ] **Collector**: 部署 `otelcol-contrib`，配 `config.yaml`，验证 `:4318` 端口
- [ ] **OceanBase DDL**: 建 7 张表
- [ ] **OTLP Receiver**: 实现 Collector → OceanBase 写入
- [ ] **Session 迁移**: `SessionBridge` 从 HTTP POST 改为 OTLP Log
- [ ] **ObsChatModel**: 确认 TTFT 采集正常
- [ ] **Grafana**: 建 Dashboard
- [ ] **归档策略**: Traces > 30 天 → 大数据系统，Metrics 保留 90 天

### 6.8 验证标准

| 验证项 | 预期结果 |
|--------|---------|
| HTTP 请求 → traceId 生成 | 每个 HTTP 请求独立 traceId，无跨请求泄漏 |
| OTel Agent 自动 span | 能看到 `HTTP GET /api/bank/chat`、`JDBC query`、`Kafka produce` 等 span |
| Spring AI 指标 | `/actuator/metrics` 有 `gen_ai.client.operation.duration` |
| ObsChatModel TTFT | `llm.first_token.latency` 有值，非 0 |
| Session 100% 捕获 | 100 个请求 → 100 条 session_turns 记录（trace 采样 10% 时 session 仍全量） |
| OceanBase 写入 | spans/metrics_agg/session_turns 三表有数据 |
| Grafana Dashboard | 面板可正常渲染 |

---

## 附录：关键决策速查表

```
┌────────────────────┬──────────────────────────────┬───────────────────────┐
│  决策维度           │     推荐方案                   │     避坑要点           │
├────────────────────┼──────────────────────────────┼───────────────────────┤
│ 框架版本           │ 1.1.x + Boot 3.4/3.5          │ 2.0需Java21+Boot4    │
│                    │ (现阶段银行主流)               │ 银行升级周期长         │
├────────────────────┼──────────────────────────────┼───────────────────────┤
│ 插桩方式           │ Agent打底 + SAI补AI层 + 手工  │ 不要二选一，要分层组合  │
├────────────────────┼──────────────────────────────┼───────────────────────┤
│ 协议导出           │ 统一 OTLP，不管版本           │ 传输层不变是最大安慰    │
├────────────────────┼──────────────────────────────┼───────────────────────┤
│ 数据库             │ OceanBase 或 GaussDB          │ 复用已有，不引入 PG    │
│                    │ 都能胜任                      │ (Langfuse因此被排除)   │
├────────────────────┼──────────────────────────────┼───────────────────────┤
│ Langfuse           │ 排除（需PG）                  │ MLflow可替（MySQL兼容）│
├────────────────────┼──────────────────────────────┼───────────────────────┤
│ 新增组件（最小）    │ OTel Agent + Collector        │ 仅2个CNCF毕业组件      │
│                    │ + micrometer-registry-otlp    │ 存储全部复用银行已有    │
├────────────────────┼──────────────────────────────┼───────────────────────┤
│ 审批一句话          │ 仅新增2个CNCF组件，           │ 零代码侵入，            │
│                    │ 存储全部复用银行已有，          │ 符合国产化方向           │
│                    │ 不引入任何新存储                │                        │
└────────────────────┴──────────────────────────────┴───────────────────────┘
```
