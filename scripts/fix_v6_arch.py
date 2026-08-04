import re

path = r"d:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/架构讨论/可观测优化总结-WorkBuddy-V6-生产上线版-银行国产-0728.md"
with open(path, encoding='utf-8') as f:
    content = f.read()

# ===== Fix §2.1 Scheme A: add Backend node between Kafka and 信创 DB =====
old_21 = '''### 2.1 方案 A 架构（全栈标准版）

```mermaid
flowchart TB
    subgraph CORE["Core 应用 (8080) — 多层埋码"]
        APP["Service APP"]
        AGENT["OTel Java Agent (L1, 零代码)"]
        SAI["Spring AI Observation (L2, 自动 gen_ai.*)"]
        OBS["ObsChatModel 补TTFT (L3)"]
        BIZ["Micrometer 业务指标 (L4, deepflux.*)"]
        APP --> AGENT; APP --> SAI; APP --> OBS; APP --> BIZ
    end

    subgraph COLL["OTel Collector (4318，集群按需)"]
        RECV["otlp receiver"]
        PROC["memory_limiter → tail_sampling → filter → batch"]
        RECV --> PROC
    end

    subgraph KAFKA["Kafka 缓冲层（银行已有, 复用）"]
        K["otlp-spans / otlp-metrics / otlp-logs"]
    end

    subgraph STORE["标准可观测栈（��案A组件）"]
        TEMPO["Tempo (trace)"]
        VM["VictoriaMetrics (metrics)"]
        LOKI["Loki (log)"]
    end

    subgraph BACK["自研 Backend / 信创 DB"]
        OB["OceanBase / GaussDB — 业务真相源"]
    end

    subgraph UI["前端"]
        GRAF["Grafana (运维看板)"]
        FE["自研前端 v24 (业务洞察)"]
    end

    AGENT --> RECV; SAI --> RECV; BIZ --> RECV
    PROC --> K
    K --> TEMPO; K --> VM; K --> LOKI; K --> OB
    TEMPO --> GRAF; VM --> GRAF; LOKI --> GRAF
    OB --> FE; OB --> GRAF
```'''

new_21 = '''### 2.1 方案 A 架��（全栈标准版）

```mermaid
flowchart TB
    subgraph CORE["Core 应用 (8080) — 三层埋码 L1+L2+L3"]
        APP["BankController / Router / L0~L2 Agent"]
        AGENT["OTel Java Agent（L1 基础设施, 零代码）"]
        SAI["Spring AI Observation + ObsChatModel（L2 AI应用）"]
        BIZ["Micrometer 业务指标（L3 业务语义, deepflux.*）"]
        APP --> AGENT; APP --> SAI; APP --> BIZ
    end

    subgraph COLL["OTel Collector（4318, 集群按需）"]
        RECV["otlp receiver"]
        PROC["memory_limiter → tail_sampling → filter → batch"]
        RECV --> PROC
    end

    subgraph KAFKA["Kafka 缓冲层（银行已有, 复用）"]
        K["otlp-spans / otlp-metrics / otlp-logs"]
    end

    subgraph STORE["标准可观测栈（方案A专属）"]
        TEMPO["Tempo (trace)"]
        VM["VictoriaMetrics (metrics)"]
        LOKI["Loki (log)"]
    end

    subgraph BACK["自研 Backend（9090, 可观测后端）"]
        API["Kafka Consumer + 查询 API"]
        OB[("信创 DB — spans / metrics_agg / logs / sessions")]
        REDIS[("Redis — 实时热层")]
        API --> OB; API --> REDIS
    end

    subgraph UI["前端"]
        GRAF["Grafana（运维看板）"]
        FE["自研前端 v24（业务洞察）"]
    end

    AGENT --> RECV; SAI --> RECV; BIZ --> RECV
    PROC -->|OTLP| K
    K -->|消费| API
    K -->|消费| TEMPO; K -->|消费| VM; K -->|消费| LOKI
    TEMPO --> GRAF; VM --> GRAF; LOKI --> GRAF
    OB --> FE; OB --> GRAF; REDIS --> FE
```

> **自研 Backend = 可观测后端（observability/backend/，端口 9090）**：消费 Kafka 中的 OTLP 数据，写入信创 DB 和 Redis，对外提供 spans/sessions/metrics/logs 查询 API。它不是 OTel 官方组件，是项目自研的可观测数据消费与存储层。'''

# ===== Fix §2.2 Scheme B: align with 3-tier naming =====
old_22 = '''### 2.2 方案 B 架构（国产化务实版）

```mermaid
flowchart TB
    subgraph CORE["Core 应用 (8080) — 多层埋码"]
        APP["BankController / Router / L0~L2 Agent"]
        AGENT["OTel Java Agent (L1, 零代码)"]
        SAI["Spring AI Observation (L2, 自动 gen_ai.*)"]
        OBS["ObsChatModel 补TTFT (L3)"]
        BIZ["Micrometer 业务指标 (L4, deepflux.*)"]
        APP --> AGENT; APP --> SAI; APP --> OBS; APP --> BIZ
    end

    subgraph COLL["OTel Collector (单实例 → 集群按需)"]
        RECV["otlp receiver"]
        PROC["memory_limiter → tail_sampling → filter → batch"]
        RECV --> PROC
    end

    subgraph KAFKA["Kafka 缓冲层（银行已有, 复用）"]
        K["otlp-spans / otlp-metrics / otlp-logs"]
    end

    subgraph BACK["自研 Backend"]
        CONSUMER["Kafka Consumer + 查询 API"]
        OB[("信创 DB — spans / metrics_agg / logs / sessions")]
        REDIS[("Redis — 实时热层")]
        CONSUMER --> OB; CONSUMER --> REDIS
    end

    subgraph UI["前端"]
        FE["自研前端 v24 — 7页"]
        GRAF["Grafana (可选, SRE 排障)"]
    end

    AGENT --> RECV; SAI --> RECV; BIZ --> RECV
    PROC -->|OTLP → Kafka| K
    K -->|消费| CONSUMER
    OB --> FE; REDIS --> FE
    GRAF -.可选.-> FE
```'''

new_22 = '''### 2.2 方案 B 架构（国产化务实版）

```mermaid
flowchart TB
    subgraph CORE["Core 应用 (8080) — 三层埋码 L1+L2+L3"]
        APP["BankController / Router / L0~L2 Agent"]
        AGENT["OTel Java Agent（L1 基础设施, 零代码）"]
        SAI["Spring AI Observation + ObsChatModel（L2 AI应用）"]
        BIZ["Micrometer 业务指标（L3 业务语义, deepflux.*）"]
        APP --> AGENT; APP --> SAI; APP --> BIZ
    end

    subgraph COLL["OTel Collector（单实例 → 集群按需）"]
        RECV["otlp receiver"]
        PROC["memory_limiter → tail_sampling → filter → batch"]
        RECV --> PROC
    end

    subgraph KAFKA["Kafka 缓冲层（银行已有, 复用）"]
        K["otlp-spans / otlp-metrics / otlp-logs"]
    end

    subgraph BACK["自研 Backend（9090, 可观测后端）"]
        CONSUMER["Kafka Consumer + 查询 API"]
        OB[("信创 DB — spans / metrics_agg / logs / sessions")]
        REDIS[("Redis — 实时热层")]
        CONSUMER --> OB; CONSUMER --> REDIS
    end

    subgraph UI["前端"]
        FE["自研前端 v24 — 7页: 总览/会话回放/链路追踪/AI洞察/日志/告警/设置"]
        GRAF["Grafana（可选, SRE 排障）"]
    end

    AGENT --> RECV; SAI --> RECV; BIZ --> RECV
    PROC -->|OTLP| K
    K -->|消费| CONSUMER
    OB --> FE; REDIS --> FE
    GRAF -.可选.-> FE
```

> **自研 Backend = 可观测后端**：同上，消费 Kafka 中的 OTLP 数据，写入信创 DB + Redis，提供查询 API。方案 B 不含 Tempo/VM/Loki/Grafana，全部由自研 Backend + 信创 DB 承载。'''

# Apply changes
content = content.replace(old_21, new_21)
content = content.replace(old_22, new_22)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print("V6 §2.1 and §2.2 architecture diagrams updated")
