内存文件超限了，我先做一次清理合并，然后回答主人的问题。
## 问题1：业务指标埋点怎么做（结论：别全塞进 Advisor）

**为什么"埋码复杂"——因为在用 Advisor 干它不擅长的事。** 指标按归属拆到三层后，代码量骤降：

**① Agent + LLM 指标 → 大部分不用自己埋**
- Spring AI 1.1.2 打开配置即得 4 个基础指标：`gen_ai.client.operation.duration`、`gen_ai.client.token.usage`、`spring.ai.chat.client`、`db.vector.client.operation`（yml 里 `management.observations` + `spring.ai.chat.observations` 开关，可选是否记录 prompt/completion 内容）。
- Graph/Node/Edge 三级观测：`spring-ai-alibaba-graph-core` 自带 GraphObservation 生命周期监听器，注册即用、零代码——**这才是 Graph 观测的正门，不是 Advisor**。
- 但注意坑：**Spring AI 1.1.x 没有 TTFT**（首 token 时延 `gen_ai.client.operation.time_to_first_chunk` 是 OTel GenAI 语义约定标准指标，但官方没实现）。流式场景要自己补：装饰 ChatModel 的 `stream()`，在第一个 chunk 到达时打 Timer——记得 `publishPercentileHistogram(true)`，否则导出的是 Summary，后端解析不出分位数（我们踩过，TTFT 恒 0）。

**② 自定义业务指标（完成率/转人工率/改派率等）→ 在业务事件发生点手写 Micrometer**
- 关键认知：**这类指标无论用 Advisor、AOP 还是 OTel SDK，代码量一样**——因为框架看不见业务语义，"转人工"只有业务代码知道。所以别纠结"基于 Advisor 埋"，直接在 Service/Graph 节点的业务分支处调 `Counter/Timer/DistributionSummary`。
- 建一个集中式 `MetricsRegistry` 门面（统一前缀如 `bank.agent.*`、统一 tag 规范），业务代码只调门面一行。**tag 必须低基数**：intent、agent_name、channel 可以；user_id、session_id 绝对不能进指标 tag（基数爆炸），它们进 trace attribute。

**③ 点击率这类前端行为指标 → 根本不该走后端埋点**
- 点击发生在手机银行前端，属于前端埋点体系（行内 App 应该已有埋点 SDK）。后端最多提供一个事件上报接口，落 Kafka → 大数据/BI 算点击率。别试图从 Advisor 里推导它——Advisor 根本看不见用户点没点。

## 问题2：当前方案的问题与改进

**核心问题：你们自建了一套"私有 trace 系统"，而不是用标准 trace。** 具体四条：

1. **没有标准 TraceContext**。Advisor 拦截数据入库后按记录展示，没有 W3C traceparent 传播——LLM 调用、工具调用、JDBC、Redis、Kafka 这些环节的耗时全都串不进来。你看到的"trace"其实是"Graph 执行日志"，出了性能问题（比如慢在 OceanBase 还是慢在大模型）定位不了。
2. **Advisor 视野盲区**。它只在 ChatClient 调用边界生效，Graph 节点内部的分支/循环/并行、Tool call、RAG 检索细节都拍不到。而 SAA Graph 原生观测这些全有，你们等于放着正门不走翻窗户。
3. **职责耦合**。长短期记忆（业务功能）和可观测（旁路系统）共用同一套拦截+入库管道——观测逻辑变更会威胁记忆功能的稳定性，反之亦然。观测数据应该走旁路异步导出，坏了也不能影响业务。
4. **不过，有一条你们做对了**：会话回放按 user_id/session_id 聚合、数据落业务库（OceanBase）——这正是业界结论"审计/回放属于业务层真相源，不属于观测层"。这部分保留，别推倒。

**改进路线（增量式，不推翻）：**
- **保留** Advisor 做记忆（它的本职），观测职责剥离出去。
- **打开** Spring AI observation + SAA Graph 原生观测，引入 `micrometer-tracing-bridge-otel` + OTLP exporter（或直接挂 OTel Java Agent，零侵入还白送 HTTP/JDBC/Kafka/Reactor 的自动 span）。
- **用 OTel Baggage 透传 session_id/user_id**：入口 Filter 把它们放进 Baggage，配一个 SpanProcessor 自动拷贝到每个 Span 的 attribute——这样标准 trace 天然带会话维度，回放页可以从业务库拿会话、点进去跳标准 trace（`session_turns` 存 traceId 弱关联即可）。
- 现有 Advisor 拦截的数据继续喂会话回放，**不再兼任 trace**。

## 问题3：业界主流机制
**业界主流机制拆解（Spring AI / SAA 生态）：**

- **埋码层**：统一基于 **Micrometer Observation API**——Spring AI 的 ChatClient、ChatModel、Advisor、Tool、VectorStore 全部内置了 ObservationConvention，指标和 span 用同一次埋点生成（这是 Spring 生态和原生 OTel SDK 最大的区别）。命名遵循 OTel **GenAI 语义约定**（`gen_ai.*`）。SAA 在此之上加了 Graph 三级观测和 ARMS 集成。
- **传输层**：一个协议走天下——**OTLP**。指标经 `micrometer-registry-otlp`，trace 经 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`（或整个由 OTel Java Agent 接管）。**会话维度靠 Baggage 传播 session.id 进 Span attribute，这是 Langfuse 官方推荐做法**——没有人为会话回放建独立上报管道。
- **处理层**：**OTel Collector** 做中枢：memory_limiter → tail_sampling（错误/慢请求/LLM 失败 100% 保留，正常流量按比例采样）→ filter（去健康检查噪声）→ batch → 多路导出。应用不直连存储，解耦 + 削峰。
- **消费层**：指标进 Prometheus/VictoriaMetrics + Grafana 大屏；trace 进 Tempo/Jaeger；LLM 工程平台（Langfuse/Arize Phoenix）直接吃 OTLP traces 端点，按 session.id 聚合就是现成的会话视图。**贵行环境特殊**：Jaeger/Langfuse 都依赖 ES/PG 类存储，如果行内只有 OceanBase，业界通用做法是 Collector 后接一个自研 OTLP Receiver 落 OceanBase + 自研查询 API——这正是我们 demo 已经验证过的路子。

**给主人的一句话总结**：Advisor 留给记忆功能；trace 换标准 OTel（SAA Graph 原生观测 + Baggage 传 session_id）；指标三层拿——框架送的白拿、TTFT 手补、业务指标在事件点手写 Micrometer、点击率还给前端。落地细节（Maven 依赖、yml、Collector 配置、OceanBase DDL、OTLP Receiver 代码）在 `docs/specs/架构讨论/` 的 0726 调研文档 v1.4 和 V4-OB 增补里都是现成的，需要的话我可以按银行环境整理成一份独立的实施方案文档。

另外汇报：本次顺手把项目记忆 MEMORY.md 做了超限清理合并（内容无丢失，四轮架构结论合并为两节）。