# 三种插桩 + SessionBridge 数据流向图

> 配套分析见 `instrumentation-analysis.md`。本文用 Mermaid 画出信号从各业务类产生、经不同插桩通道、最终汇入可观测后端的完整路径。

## 一、总体数据流向（Mermaid Flow）

```mermaid
flowchart LR
    subgraph CORE["Core 主应用 (port 8080)"]
        BC["BankController<br/>(会话入口 / L0调度)"]
        DR["DomainRouter (L0)"]
        CR["ContextRouter (L1上下文)"]
        SR["SubGraphRouter (L1改写)"]
        DS["AbstractDomainService<br/>(状态机 / L1领域)"]
        GE["GraphExecutionEngine (L2)"]
        AG["AbstractGraphConfig (ask)"]
        MC["McpToolCallback (工具)"]
        OCM["ObsChatModel<br/>(LLM装饰器)"]
        SB["SessionBridge (@Async)"]
        DELEGATE["OpenAiChatModel<br/>(原始 ChatModel)"]
    end

    subgraph INSTR["三种插桩通道"]
        OM["ObservabilityMetrics<br/>agent.* / deepflux.*"]
        MR["MetricsRegistry<br/>deepflux.*"]
        AGENT["OTel Java Agent<br/>(字节码自动插桩)"]
    end

    subgraph EXPORT["导出管线"]
        REG["MeterRegistry<br/>(Micrometer)"]
        TRACE["OTel SDK Spans<br/>(手工 + 自动)"]
        COL["OTel Collector :4318<br/>(tail_sampling / filter)"]
    end

    subgraph BACKEND["可观测后端 (port 9090)"]
        METRICS["Metrics / Prometheus"]
        TRACES["Trace / Jaeger"]
        SESSIONS["sessions / session_turns 表"]
    end

    %% ---- 业务手动埋点通道 ----
    BC --> OM
    DR --> OM
    CR --> OM
    SR --> OM
    DS --> OM
    GE --> OM
    GE --> MR
    AG --> OM
    MC --> OM
    OM --> REG
    MR --> REG

    %% ---- LLM 装饰器通道 ----
    OCM -->|"llm.* 指标直写"| REG
    OCM -->|"手工 Span + Baggage"| TRACE
    OCM -.->|delegate.call/stream| DELEGATE
    BC --> OCM

    %% ---- 自动插桩通道 ----
    AGENT -->|"JVM / HTTP server+client span"| TRACE
    AGENT -->|"JVM 指标"| REG
    CORE -.->|"bytebuddy 字节码增强"| AGENT

    %% ---- 导出到 Collector ----
    REG -->|"micrometer-registry-otlp :4318/v1/metrics"| COL
    TRACE -->|"OTEL_TRACES_EXPORTER=otlp :4318"| COL

    %% ---- SessionBridge 独立通道 ----
    BC -->|"doOnTerminate 取 traceId"| SB
    SB -->|"HTTP POST /api/v1/sessions (含 traceId)"| SESSIONS

    %% ---- Collector 分发 ----
    COL --> METRICS
    COL --> TRACES
    COL -->|"exporter -> 127.0.0.1:9090"| BACKEND
```

## 二、三类信号对比（一句话流向）

```mermaid
flowchart TD
    A["业务代码 (BankController / Router / GE / DS / MC)"] -->|"在分支里 recordXxx()"| B["ObservabilityMetrics + MetricsRegistry"]
    B -->|"agent.* / deepflux.*"| REG["MeterRegistry"]

    C["ObsChatModel (包住所有 ChatModel)"] -->|"提取 ChatResponse.usage / 流式 chunk"| D["llm.* 指标"]
    C -->|"手工 Span + 业务属性"| E["OTel Spans (L0/L1/L2)"]
    D --> REG
    E --> TRACE["OTel SDK"]

    F["OTel Java Agent (-javaagent)"] -->|"bytebuddy 增强"| G["JVM 指标 + HTTP server/client span"]
    G --> REG
    G --> TRACE

    REG -->|"OTLP :4318"| COL["Collector"]
    TRACE -->|"OTLP :4318"| COL

    H["BankController.doOnTerminate"] -->|"取 traceId 参数"| I["SessionBridge.reportSession()"]
    I -->|"HTTP POST 9090"| J["sessions 表 (会话级关联)"]
```

## 三、关键差异速查

| 维度 | OTel Java Agent | ObsChatModel | ObservabilityMetrics | SessionBridge |
|------|----------------|--------------|----------------------|---------------|
| 类型 | 自动字节码插桩 | 装饰器(半自动) | 手动埋点门面 | 数据桥(非插桩) |
| 触发方式 | 类加载期织入 | 包住 ChatModel 调用 | 业务分支主动 record | chat 结束 @Async POST |
| 捕获内容 | JVM / HTTP / 线程池 | `llm.*` + 业务 Span | `agent.*` / `deepflux.*` | 会话快照(含 traceId) |
| 是否写 MeterRegistry | JVM 指标→是 | 是(直写) | 是 | 否 |
| 是否建 Span | 是(自动) | 是(手工) | 否 | 否 |
| 传播 trace context | 是(自动) | 是(Baggage) | 否 | 否(仅携带 traceId 字段) |
| 业务语义 | 无 | LLM 层 | 领域层 | 会话层 |
| 能否替代其他 | 拿不到业务语义 | 拿不到路由/状态机 | 手写成本高、易踩线程坑 | 不产指标/span |

## 四、一句话总结

> 一次请求里：**自动 agent** 兜 JVM/HTTP 基础设施信号，**ObsChatModel** 在 LLM 调用处抽出 `llm.*` 指标并打上业务属性的 Span，**ObservabilityMetrics** 在路由/状态机/子图/工具分支里补 `agent.*/deepflux.*` 业务指标，三者都经 OTLP 汇到 Collector；而 **SessionBridge** 走另一条 HTTP 把"会话快照+traceId"送后端，让前端能把一条会话和它的 trace/指标串起来看。
