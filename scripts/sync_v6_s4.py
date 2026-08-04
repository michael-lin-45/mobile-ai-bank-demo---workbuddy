import re

path = r'd:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/架构讨论/可观测优化总结-WorkBuddy-V6-生产上线版-银行国产-0728.md'
with open(path, encoding='utf-8') as f:
    content = f.read()

s44 = content.find('### 4.4 Session')
s5 = content.find('## 5. Core')

head = content[:s44]
tail = content[s5:]

logs = """### 4.4 Logs 日志

```
Core / Backend 日志（含 logback MDC: trace_id / span_id / session_id）
        | Logback OTLP Appender -> Collector -> otlphttp exporter
Backend OtlpParserService.parseLogs()
  |- 写 LogEntity(trace_id, span_id, level, service, message, attributes)
  |- pushRecentLog -> Redis obs:logs:recent (LTRIM 1000)
        |
前端日志查询页：每行支持按 trace_id / session_id 关联跳转
        | 方案A 增强
经 OTel Collector logs pipeline -> Loki -> Grafana 统一检索
        | 方案B
直接写信创 DB logs 表（短期 7d）+ 大数据系统（长期归档）
```

### 4.5 Session 会话数据采集：7种方案对比与选型

> **0728 下午二次修订**：经业界深度调研（Langfuse 官方 OTLP 集成文档、Spring AI 1.1 官方文档、Spring AI Alibaba Graph 观测实践），对原方案做三项重大修正：
> 1. **OTLP Log 方案排除**：Langfuse 官方明确推荐 OTLP traces 端点 + Baggage，不用 OTLP Log；业界零先例。
> 2. **SessionBridge 降级**：项目自创，无业界先例，降级为"过渡期已有实现"。
> 3. **新增 Graph 原生观测**：SAA Graph 模块自带 Graph/Node/Edge 三级观测。ObsChatModel 补 TTFT 仍然必要。
> 详细对比与选型见 V4 Demo 文档 §4.5，本节仅保留生产关键结论。

#### 7 种方案总览

| | S1 SessionBridge | S2 OTLP Log | S3 ObsConv | S4 OTel Baggage | S5 Advisor | S6 Graph原生 | S7 SpanProcessor |
|---|---|---|---|---|---|---|---|
| **来源** | 项目自创 | V6曾推荐 | Spring AI | OTel官方 | Spring AI | SAA标准 | OTel标准 |
| **业界采用** | 项目独有 | 零先例 | SAI社区 | Langfuse/DD | SAI官方 | SAA官方 | trpc-agent-go |
| **代码侵入** | 高 | 中 | 低 | 极低(3行) | 低(配置) | 零(自动) | 低 |
| **推荐度** | 过渡期 | 已排除 | ⭐⭐ | ⭐⭐⭐主 | ⭐ | ⭐⭐⭐图 | ⭐⭐ |

#### 核心裁决

- **业界标准不把"100% session捕获"当独立需求**——LLM应用trace采样率本应100%
- **session是trace的聚合维度**，不是独立管道。按session.id从trace聚合即可
- **审计需求从业务层做**（信创DB记录每笔交易），非观测层
- **SessionBridge降级，OTLP Log排除**——回归业界标准Baggage+trace合理采样

#### 推荐方案：S4 OTel Baggage + tail_sampling 业务策略

```
网关/拦截器 -> Baggage.put("session.id") （3行代码）
    | 跨服务、跨线程自动传播
所有Span自动携带 session.id attribute
    | OTLP traces pipeline
后端按session_id聚合spans -> 会话回放
```

#### Graph节点观测5种方法（与V4 §4.5对齐）

| # | 方法 | 适用场景 | 侵入性 | 推荐度 |
|---|---|---|---|---|
| 1 | **SAA Graph原生观测** | SAA StateGraph/ReactAgent | 零 | ⭐⭐⭐首选 |
| 2 | **Spring AI Advisor** | ChatClient advisor链(非Graph) | 低 | ⭐⭐ |
| 3 | **OTel SDK手动Span** | 自研Agent框架 | 中 | ⭐ |
| 4 | **Observation API手动** | Micrometer方式 | 中 | ⭐ |
| 5 | **AOP切面拦截** | 统一拦截 | 高 | 兜底 |

#### TTFT采集

gen_ai.client.operation.time_to_first_chunk 是OTel标准指标（recommended），但Spring AI 1.1（含1.1.2）未实现。**ObsChatModel补TTFT仍然必要**。

### 4.6 Alerts 告警

```
P0 阶段（自研，双方案通用）：
规则管理: alert_rules 表
AlertEngineService.@Scheduled(fixedRate=30_000):
  读活跃规则 -> collectMetricValue -> evaluateCondition -> fireAlert/resolveAlert
  抑制: 5分钟内同规则不重复通知
通知: 钉钉/飞书 Webhook
状态机: TRIGGERED -> ACKED -> RESOLVED

方案A：P2迁Grafana Alerting -> 统一看板+告警
方案B：自研AlertEngine保留
```

默认告警规则：
| 规则 | 阈值 | 严重度 |
|---|---|---|
| LLM 错误率 > 0 | 任何错误 | CRITICAL |
| TTFT P95 超阈 | > 3000ms | WARNING |
| 交易完成率骤降 | < 50% | CRITICAL |
| Collector 接收量骤降 | < 基线30% | WARNING |

"""

new_content = head + logs + tail
with open(path, 'w', encoding='utf-8') as f:
    f.write(new_content)
print('V6 sync done')
