# 银行可观测落地细化方案

> **目标环境**: Spring AI 1.1.2 + Spring Boot 3.4.3 + OceanBase + Redis + Kafka
> **编写日期**: 2026-07-27
> **前置阅读**: `可观测架构业界调研与重构建议-GLM5.2-0726.md` v1.4

---

## 一、架构总览

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
                           │ OTLP :4317 (Collector → Backend)
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    OTLP Receiver / 自研 Backend                       │
│  接收 Collector 导出的 trace/metric/log → 写入 OceanBase              │
│  提供查询 API (复用 demo TraceQueryService 模式)                      │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ JDBC / MyBatis
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          OceanBase                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐           │
│  │ spans    │  │ traces   │  │ metrics_ │  │ sessions │           │
│  │          │  │          │  │ agg      │  │          │           │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘           │
│  ┌──────────┐  ┌──────────┐                                         │
│  │ session_ │  │ prompt_  │  (AI工程: 提示词管理/评估表)            │
│  │ turns    │  │ templates│                                         │
│  └──────────┘  └──────────┘                                         │
└──────────────────────────────────────────────────────────────────────┘

可选增强:
  VictoriaMetrics (仅当 OceanBase 时序查询性能不足时)
  Kafka (仅当日均 Span > 2000 万时做缓冲)
  Grafana (标准 Dashboard, 仪表盘/告警)
  银行已有大数据系统 (长期归档, trace/session 冷数据)
```

## 二、埋点层：三层互补

### 2.1 Layer 1 — OTel Java Agent (基础设施全量覆盖)

**零代码，启动参数挂载：**

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

**自动覆盖：**
- HTTP 请求入口/出口（Spring MVC, RestTemplate, WebClient）
- JDBC 数据库调用（自动生成 `db.type=OceanBase`）
- Kafka 生产/消费（自动关联 traceId）
- Reactor 线程上下文传播
- JVM 指标（内存、GC、线程、CPU）

> ⚠️ 关键：如果银行已有 ArmsAgent 或类似 APM Agent，需确认与 OTel Java Agent 的兼容性。两个 Agent 同时挂载可能冲突（都拦截同一字节码），建议二选一或将 ArmsAgent 的 tracing 关闭只保留 JVM 监控。

### 2.2 Layer 2 — Spring AI Observation (AI 应用层)

**Maven 依赖（项目已有，只需确认版本）：**

```xml
<!-- Spring AI 1.1.2 内置 Micrometer Observation -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-spring-boot-autoconfigure</artifactId>
</dependency>

<!-- Micrometer → OTLP 桥接 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-otlp</artifactId>
</dependency>

<!-- Spring Boot Actuator -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**application.yml 配置：**

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
      probability: 1.0  # 开发测试全量，生产建议 0.1~0.5

spring:
  ai:
    chat:
      observations:
        enabled: true
        include-prompt: false   # 生产关，防泄露
        include-completion: false # 生产关，防泄露
```

**Spring AI 1.1.2 自动提供：**

| 指标 | Meter 名 | 说明 |
|------|---------|------|
| 调用耗时 | `gen_ai.client.operation.duration` | ChatModel.call() 耗时 |
| Token 输入 | `gen_ai.client.token.usage` (type=input) | prompt tokens |
| Token 输出 | `gen_ai.client.token.usage` (type=output) | completion tokens |
| 错误次数 | `gen_ai.client.operation.error` | 按 error.type 区分 |

**⼿动补：ObsChatModel（TTFT + 业务 span）**

Spring AI 1.1.2 不自带 TTFT (Time To First Token)，需 ObsChatModel 装饰器补：

```java
// 伪代码，参考项目 demo 现有 ObsChatModel 实现
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

            // TTFT (call 模式即总耗时，stream 模式才有意义)
            long ttft = System.nanoTime() - startNanos;
            meterRegistry.timer("llm.first_token.latency",
                "model", getModelName()).record(ttft, TimeUnit.NANOSECONDS);

            // 业务 span 属性回填
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                span.setAttribute("gen_ai.usage.input_tokens", usage.getPromptTokens());
                span.setAttribute("gen_ai.usage.output_tokens", usage.getGenerationTokens());
            }
            span.setAttribute("gen_ai.response.model", getModelName());
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

### 2.3 Layer 3 — 手工业务指标 (省不掉的代码)

```java
// 这层无论用什么框架都要手写

// Reroute 率
meterRegistry.counter("deepflux.reroute.count", "decision", "rerouted").increment();

// 完成率
meterRegistry.counter("deepflux.task.completed", "agent", agentName).increment();
meterRegistry.counter("deepflux.task.total", "agent", agentName).increment();
// → Grafana: rate(deepflux.task.completed) / rate(deepflux.task.total)

// 转人工率
meterRegistry.counter("deepflux.human.escalation", "reason", reason).increment();

// 审批积压
meterRegistry.gauge("deepflux.workflow.pending_approval", Tags.of("workflow", name),
    new AtomicLong(pendingCount));
```

---

## 三、传输层：OTel Collector 配置

### collecter config.yaml

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
    metrics:
      metric:
        - 'IsMatch(name, ".*health.*") == true'

  tail_sampling:
    decision_wait: 10s
    policies:
      # 错误全量保留
      - name: errors
        type: status_code
        status_code:
          status_codes: [ERROR]
      # 慢请求全量保留 (>1s)
      - name: slow
        type: latency
        latency:
          threshold_ms: 1000
      # LLM 调用失败全量保留
      - name: llm-failure
        type: string_attribute
        string_attribute:
          key: error.type
          values: [timeout, auth_error, rate_limit]
      # 正常采样 10%
      - name: normal-sampling
        type: probabilistic
        probabilistic:
          sampling_percentage: 10

  attributes:
    actions:
      - key: deployment.environment
        value: production
        action: upsert

  batch:
    send_batch_size: 512
    timeout: 5s

exporters:
  otlp/backend:
    endpoint: http://127.0.0.1:4317
    tls:
      insecure: true

  # 可选：写 Kafka 做大缓冲
  # kafka:
  #   brokers: [127.0.0.1:9092]
  #   topic: otel-traces

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, tail_sampling, filter/health, attributes, batch]
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

> ⚠️ traces pipeline 顺序铁律：`tail_sampling` 必须在 `batch` 前，否则采样无效。
> metrics/logs pipeline **不加** `tail_sampling`（采样只对 trace 有意义）。

---

## 四、存储层：OceanBase 表设计

### 4.1 spans 表（链路追踪）

```sql
CREATE TABLE spans (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id    VARCHAR(32)  NOT NULL,
    span_id     VARCHAR(16)  NOT NULL,
    parent_span_id VARCHAR(16),
    span_name   VARCHAR(255) NOT NULL,
    span_kind   VARCHAR(32),
    start_time  BIGINT NOT NULL,       -- Unix 纳秒
    end_time    BIGINT NOT NULL,
    duration_ms DOUBLE NOT NULL,
    status_code VARCHAR(16),           -- OK / ERROR
    status_msg  VARCHAR(1024),
    attributes  TEXT,                  -- JSON KV pairs
    events      TEXT,                  -- JSON array of events
    resource_attrs TEXT,               -- JSON, service.name 等
    scope_name  VARCHAR(255),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_trace_id (trace_id),
    INDEX idx_start_time (start_time),
    INDEX idx_span_name (span_name(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- OceanBase 支持分区（按时间）
-- ALTER TABLE spans PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
--     PARTITION p202607 VALUES LESS THAN (UNIX_TIMESTAMP('2026-08-01')),
--     PARTITION p202608 VALUES LESS THAN (UNIX_TIMESTAMP('2026-09-01')),
--     ...
-- );
```

### 4.2 metrics_agg 表（指标聚合）

```sql
CREATE TABLE metrics_agg (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_name    VARCHAR(255) NOT NULL,   -- gen_ai.client.operation.duration
    metric_type    VARCHAR(32),             -- counter / histogram / gauge
    tags           TEXT,                    -- JSON {"model":"qwen-turbo","session_id":"xxx"}
    value          DOUBLE NOT NULL,
    count          BIGINT DEFAULT 1,        -- histogram 的 _count
    sum_val        DOUBLE DEFAULT 0,        -- histogram 的 _sum
    min_val        DOUBLE,
    max_val        DOUBLE,
    window_start   BIGINT NOT NULL,         -- 聚合窗口起始 (Unix 毫秒)
    window_end     BIGINT NOT NULL,
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_metric_window (metric_name(64), window_start),
    INDEX idx_window (window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.3 sessions 表（会话回放）

```sql
CREATE TABLE sessions (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL UNIQUE,
    user_id      VARCHAR(64),
    status       VARCHAR(32) DEFAULT 'active',  -- active / completed / timeout
    agent_chain  VARCHAR(255),                  -- 路由链路: DomainRouter→AccountAgent
    total_turns  INT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    total_cost   DOUBLE DEFAULT 0,
    start_time   DATETIME NOT NULL,
    end_time     DATETIME,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE session_turns (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id     VARCHAR(64) NOT NULL,
    turn_num       INT NOT NULL,
    trace_id       VARCHAR(32),              -- 弱关联到对应 trace
    user_query     TEXT,
    agent_response TEXT,
    intent         VARCHAR(64),
    agent_layer    VARCHAR(32),
    input_tokens   INT DEFAULT 0,
    output_tokens  INT DEFAULT 0,
    ttft_ms        DOUBLE,                   -- Time To First Token
    duration_ms    DOUBLE,
    error_type     VARCHAR(64),
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_session_turn (session_id, turn_num),
    INDEX idx_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.4 prompt 管理表（Langfuse 替代）

```sql
-- 提示词模板
CREATE TABLE prompt_templates (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512),
    template     TEXT NOT NULL,               -- 提示词模板 (支持 {variable} 占位)
    variables    TEXT,                        -- JSON ["var1","var2"]
    model_name   VARCHAR(64),                -- 适用模型
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 提示词版本
CREATE TABLE prompt_versions (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_id    BIGINT NOT NULL,
    version      INT NOT NULL,
    template     TEXT NOT NULL,
    change_log   VARCHAR(512),
    status       VARCHAR(32) DEFAULT 'draft', -- draft / active / deprecated
    activated_at DATETIME,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_prompt_version (prompt_id, version),
    INDEX idx_prompt_status (prompt_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.5 token_cost 表（成本追踪）

```sql
CREATE TABLE token_costs (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(64),
    model_name    VARCHAR(64) NOT NULL,
    input_tokens  INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    total_tokens  INT DEFAULT 0,
    input_cost    DOUBLE DEFAULT 0,         -- 输入费用 (元)
    output_cost   DOUBLE DEFAULT 0,         -- 输出费用 (元)
    total_cost    DOUBLE DEFAULT 0,
    trace_id      VARCHAR(32),
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_session (session_id),
    INDEX idx_model_date (model_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 五、Session 上报：OTLP Log 替代 HTTP POST

### 方案

Session 数据（会话回放）过去通过 `SessionBridge.reportSession()` HTTP POST 到 `:9090/api/v1/sessions` 独立管道。改为：

**Core 端 → OTLP Log 发出：**

```java
// 替代 SessionBridge HTTP POST
@Async
public void reportSessionViaOtlp(SessionData session) {
    Logger logger = LoggerFactory.getLogger("session-reporter");

    // 构造 OTLP Log Record（MDC 挂 traceId 自动关联）
    MDC.put("trace_id", Span.current().getSpanContext().getTraceId());
    MDC.put("session_id", session.getSessionId());
    MDC.put("user_id", session.getUserId());

    logger.info("{}",
        new ObjectMapper().writeValueAsString(Map.of(
            "session_id", session.getSessionId(),
            "turn_num", session.getTurnNum(),
            "user_query", session.getUserQuery(),
            "agent_response", session.getAgentResponse(),
            "intent", session.getIntent(),
            "agent_layer", session.getAgentLayer(),
            "input_tokens", session.getInputTokens(),
            "output_tokens", session.getOutputTokens(),
            "ttft_ms", session.getTtftMs(),
            "duration_ms", session.getDurationMs()
        ))
    );

    MDC.clear();
}
```

**application.yml 加 Log OTLP 导出：**

```yaml
# Logback/Log4j2 OTLP Appender (需额外配置)
# 或 Spring Boot 4.x 原生支持，3.4.3 需手动加 appender
```

**Collector logs pipeline → OTLP Receiver → 解析 JSON → 写入 session_turns 表。**

> 优势：OTLP Log 不受 trace 采样影响（logs pipeline 无 tail_sampling），**sessions 100% 捕获**，且和 trace 管穿同一个协议。

---

## 六、Redis 角色

| 用途 | 说明 |
|------|------|
| **实时指标缓存** | OTEL Collector → Backend 写入 OceanBase 的同时，双写 Redis（TTL 5分钟）供前端实时大盘读取，避免频繁查 DB |
| **Session 热数据** | 活跃会话的上下文缓存，加速 Agent 多轮对话 |
| **Kafka 偏移量** | 如果用 Kafka 做缓冲，offset 存在 Redis |

---

## 七、Kafka 角色（可选，量级驱动）

**什么时候需要 Kafka？** 日均 Span > 2000 万时，Collector 直写 OceanBase 可能成为瓶颈。

```
Collector → Kafka (otel-traces topic) → OTLP Receiver → OceanBase
                ↑
                削峰填谷，解耦采集与存储
```

- 日均 < 500 万 span：Collector → OTLP Receiver → OceanBase 直写，不需要 Kafka
- 日均 500-2000 万：加 Kafka 缓冲，Receiver 批量消费写 OceanBase
- 日均 > 2000 万：Kafka + OceanBase 分区表 + 定期归档大数据系统

---

## 八、改造检查清单

- [ ] **Maven**: 添加 `micrometer-registry-otlp`，确认版本与 Spring Boot 3.4.3 兼容
- [ ] **yml**: 配 `management.otlp.metrics.export.url` / `management.tracing.sampling.probability`
- [ ] **yml**: `spring.ai.chat.observations.enabled=true`, `include-prompt=false`
- [ ] **Agent**: 下载 `opentelemetry-javaagent.jar` 最新版，挂 `-javaagent` 启动参数
- [ ] **Agent**: 确认与银行已有 APM Agent 不冲突
- [ ] **Collector**: 部署 `otelcol-contrib`，配 `config.yaml`，验证 `:4318` 端口
- [ ] **OceanBase DDL**: 建 `spans / metrics_agg / sessions / session_turns / prompt_templates / prompt_versions / token_costs`
- [ ] **OTLP Receiver**: 实现 Collector → OceanBase 写入（参考 demo `TraceQueryService` 模式）
- [ ] **Session 迁移**: `SessionBridge.reportSession()` 从 HTTP POST 改为 OTLP Log
- [ ] **ObsChatModel**: 确认 TTFT 采集正常，`llm.first_token.latency` 有值
- [ ] **Grafana**: 建 Dashboard（QPS/TTFT/Token成本/Reroute率/转人工率）
- [ ] **归档策略**: Traces > 30 天 → 大数据系统，Metrics 保留 90 天

---

## 九、验证标准

| 验证项 | 预期结果 |
|--------|---------|
| HTTP 请求 → traceId 生成 | 每个 HTTP 请求独立 traceId，无跨请求泄漏 |
| OTel Agent 自动 span | 能看到 `HTTP GET /api/bank/chat`、`JDBC query`、`Kafka produce` 等 span |
| Spring AI 指标 | `/actuator/metrics` 有 `gen_ai.client.operation.duration` |
| ObsChatModel TTFT | `llm.first_token.latency` 有值，非 0 |
| Session 100% 捕获 | 100 个请求 → 100 条 session_turns 记录（trace 采样 10% 时 session 仍全量） |
| OceanBase 写入 | spans/metrics_agg/session_turns 三表有数据 |
| Grafana Dashboard | 四个面板可正常渲染 |
