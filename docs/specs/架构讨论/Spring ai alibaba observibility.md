Spring AI Alibaba 可观测性基于 Micrometer Observation API 与 OpenTelemetry 标准构建，提供对聊天、嵌入、图像生成及工具调用的全链路追踪、指标采集与日志记录能力，支持对接 Zipkin/Jaeger、阿里云 ARMS、Langfuse 等后端 。

核心机制与架构
- 抽象层：利用 Spring AI 的 `Observation` 机制自动包裹模型调用，无需业务代码侵入 。
- 桥接层：通过 `micrometer-tracing-bridge-otel` 将观测事件转换为 OpenTelemetry Span 。
- 导出层：支持 OTLP 协议上报至任意兼容后端（如 Langfuse、ARMS、Prometheus+Tempo 等）。
- 覆盖范围：涵盖 Prompt/Completion 内容（可选）、Token 用量、耗时、错误堆栈及工具调用细节 。

关键依赖配置 (Maven)
需引入 Actuator 及观测桥接依赖，具体取决于目标后端：
- 基础观测核心：`spring-boot-starter-actuator` + `spring-ai-autoconfigure-model-*-observation` (Chat/Embedding/Image)。
- OpenTelemetry 链路：`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`。
- 阿里云 ARMS 专用：`spring-ai-alibaba-autoconfigure-arms-observation`。
- Langfuse 集成：通常直接使用 OTLP 导出至 Langfuse 端点，无需额外特定 Starter，但需确保 OTel SDK 完整 。

核心配置项 (application.yml)
```yaml
spring:
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}
    observations:
      log-prompt: true          是否在日志记录提示词
      log-completion: true      是否在日志记录回复内容
      include-error-logging: true
  OpenTelemetry 采样与导出配置
management:
  tracing:
    sampling:
      probability: 1.0          开发环境建议 1.0，生产环境建议 0.1-0.5
  otlp:
    tracing:
      endpoint: http://localhost:4317  替换为实际后端地址 (如 Langfuse/ARMS)
      export:
        enabled: true
    metrics:
      export:
        enabled: true
```
注意：敏感信息（如 Prompt 内容）在生产环境需权衡开启，避免泄露；可通过自定义 `ObservationHandler` 实现脱敏或动态采样策略 。

主流集成方案对比
- 开源通用方案 (Zipkin/Jaeger)：依赖 `micrometer-tracing-bridge-brave` 或 `otel`，适合基础链路追踪，但缺乏 AI 语义（如 Token 成本分析）。
- 阿里云企业级 (ARMS)：深度集成，提供应用实时监控服务，需特定 Starter 或 Java Agent，支持全链路且符合国内合规要求 。
- AI 专项分析 (Langfuse)：通过 OTLP 协议对接，专为 LLM 设计，可可视化 Token 消耗、成本估算、Prompt 版本对比及效果评估，是目前社区推荐的高级观测方案 。

监控指标示例
框架自动暴露以下 Micrometer 指标（可通过 `/actuator/prometheus` 访问）：
- `gen_ai.client.token.usage`：按输入/输出/总计统计 Token 消耗。
- `gen_ai.client.operation.duration`：模型调用耗时分布。
- `gen_ai.client.operation.error`：错误次数统计 。

若需查看官方示例代码，可参考 GitHub 仓库中的 `spring-ai-alibaba-observability-example` 模块，包含 Chat、Embedding、Image 及 Graph 工作流的完整观测演示 。