# Spring AI + Micrometer AI可观测性完整指南
## 一、核心基础概念
### 1. 三者关系
- **Micrometer**：Spring生态统一观测门面，统一指标采集、链路埋点（Metrics/Trace/Log三位一体，基于`Observation API`），屏蔽Prometheus/OTLP/Zipkin差异。
- **Spring AI**：原生基于Micrometer Observation做全组件埋点，自动采集LLM、向量库、Embedding、图像模型AI专属观测数据，遵循**OpenTelemetry GenAI语义规范**（`gen_ai.*`标签）。
- **AI可观测**：区别于普通Web监控，核心关注**Token消耗、模型耗时、向量检索、RAG链路、函数调用、成本统计**三大支柱：指标(Metrics)+链路追踪(Trace)+结构化日志(Log)。

### 2. 自动埋点覆盖组件
Spring AI无需手动埋点，开箱观测：
1. `ChatClient`（同步/stream流式对话、Advisor拦截器）
2. `ChatModel`：OpenAI/阿里云/通义千问/Ollama等大模型调用
3. `EmbeddingModel` 向量嵌入
4. `VectorStore` 向量数据库检索（RAG核心链路）
5. `ImageModel` 图像生成
6. ToolCall 工具函数调用链路

### 3. 关键规则：高低基数标签
- **低基数LowCardinality**：同时写入Metrics+Trace，用于Prometheus标签（取值有限：模型名称、服务商、操作类型、状态码）
- **高基数HighCardinality**：仅写入Trace，**不进指标**（会话ID、完整Prompt文本、用户ID，避免指标爆炸）

## 二、依赖引入（Spring Boot3 + Spring AI 1.1+）
### Maven pom.xml
```xml
<!-- Spring AI 核心 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- Actuator + Micrometer 基础指标 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Prometheus 指标导出（Grafana监控） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Trace链路：桥接Micrometer → OpenTelemetry OTLP（SkyWalking/Jaeger/Loki） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<!-- OTLP导出器（上报Trace/Metrics到可观测平台） -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

## 三、application.yml 完整配置
```yaml
spring:
  application:
    name: spring-ai-demo
  ai:
    openai:
      api-key: ${OPENAI_KEY}
      base-url: https://api.openai.com/v1
    # AI日志开关：打印Prompt/Completion对话内容（调试开启，生产关闭防数据泄露）
    chat:
      client:
        log-prompt: true       # 打印用户输入、系统提示词
        log-completion: true  # 打印大模型返回内容
        include-content: true  # 工具调用内容打印

# Actuator端点暴露
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info,metrics
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
  # Trace链路配置 OTLP
  tracing:
    sampling:
      probability: 1.0 # 开发100%采样；生产改为0.1~0.3降低存储成本
  otlp:
    tracing:
      endpoint: http://127.0.0.1:4318/v1/traces # OTLP上报地址
    metrics:
      export:
        enabled: true
```

## 四、自动采集核心AI指标（Metrics）
访问端点：`/actuator/prometheus` 查看原始指标
### 1. LLM对话核心指标
1. `gen_ai.client.operation.duration`（Timer）
   - 标签：`gen_ai.system=openai`、`gen_ai.operation.name=chat`、`gen_ai.response.model=gpt-4o`、`outcome=SUCCESS/ERROR`
   - 用途：统计LLM调用耗时、P95/P99分位延迟
2. `gen_ai.client.token.usage`（Counter）
   - 标签：`gen_ai.token.type=input/output`
   - 用途：累计输入/输出Token，计算API调用成本
3. `gen_ai.client.operation.count`（Counter）
   - 对话调用总次数、失败次数统计

### 2. RAG向量库指标
- `db.vector.query.duration`：向量检索耗时Timer
- `db.vector.query.count`：检索次数、匹配文档数量标签

### 3. Embedding嵌入指标
- `gen_ai.client.operation.duration{gen_ai.operation.name=embed}`
- 向量生成Token消耗计数

## 五、Trace链路字段（遵循GenAI OTel规范）
每条LLM调用生成独立Span，链路携带完整AI上下文：
| 标签Key | 示例值 | 基数类型 |
|--------|--------|--------|
| gen_ai.system | openai/ollama/azure | 低基数（指标+链路） |
| gen_ai.response.model | gpt-4o-mini | 低基数 |
| gen_ai.operation.name | chat / embed / image | 低基数 |
| gen_ai.usage.input_tokens | 245 | 低基数 |
| gen_ai.usage.output_tokens | 120 | 低基数 |
| gen_ai.response.finish_reason | stop / length / tool_calls | 低基数 |
| conversation.id | conv-xxx-xxx | 高基数（仅Trace） |
| prompt.content | 完整用户提问 | 高基数（仅Trace） |

链路效果：HTTP入口 → ChatClient → VectorStore检索 → LLM调用 → ToolCall，完整串联一条TraceID，日志MDC自动注入`traceId/spanId`，实现日志-链路关联排查。

## 六、代码使用（零埋点+自定义观测）
### 1. 原生ChatClient自动观测（无需编码）
```java
@RestController
@RequestMapping("/ai")
public class ChatController {
    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/chat")
    public String chat(String msg) {
        // 自动生成Observation、指标、Trace
        return chatClient.prompt()
                .user(msg)
                .call()
                .content();
    }

    // 流式响应同样自动埋点
    @GetMapping("/stream")
    public Flux<String> stream(String msg) {
        return chatClient.prompt()
                .user(msg)
                .stream()
                .content();
    }
}
```

### 2. 手动自定义AI观测（扩展业务指标）
注入`ObservationRegistry`自定义业务Span，和原生AI链路合并：
```java
@Service
public class RagService {
    private final VectorStore vectorStore;
    private final ObservationRegistry observationRegistry;

    public String ragQuery(String query) {
        // 自定义业务观测
        Observation observation = Observation.createNotStarted("rag.retrieve", observationRegistry)
                .lowCardinalityKeyValue("biz.module", "customer")
                .highCardinalityKeyValue("query.text", query)
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            // 向量检索（自动生成vector store span）
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.query(query).withTopK(3));
            return chatClient.prompt()
                    .user("基于上下文回答："+docs+query)
                    .call().content();
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
```

### 3. 非Boot环境手动开启观测
手动传入`ObservationRegistry`给模型构造器：
```java
OpenAiChatModel chatModel = OpenAiChatModel.builder()
        .apiKey("xxx")
        .observationRegistry(observationRegistry)
        .build();
```

## 七、完整可观测数据流
1. **应用层**：Spring AI内置Observation埋点，采集AI调用耗时、Token、异常
2. **Micrometer**：
   - Metrics → Prometheus端点 `/actuator/prometheus`
   - Trace → OTel桥接 → OTLP协议上报（4318端口）
3. **存储可视化**
   - 指标：Prometheus + Grafana（AI成本、QPS、延迟看板）
   - 链路：Jaeger / SkyWalking / OpenObserve
   - 日志：Loki/ELK，通过traceId关联链路详情

## 八、生产环境最佳实践
1. **日志安全**：生产关闭`log-prompt/log-completion`，防止敏感Prompt泄露；如需审计走单独脱敏存储。
2. **Trace采样**：开发100%采样，生产采样率0.1~0.3，降低存储成本。
3. **指标基数管控**：禁止将用户ID、会话ID放入Metrics标签，仅存Trace。
4. **告警配置**：基于Prometheus配置告警规则
   - LLM调用错误率 > 1%
   - P99延迟 > 3s
   - Token用量突增（成本预警）
5. **RAG专项监控**：监控向量检索命中率、TopK匹配耗时，定位检索质量瓶颈。
6. **流式兼容**：Spring AI stream响应完整支持观测，不会丢失耗时/Token统计。

## 九、常见问题
1. **看不到gen_ai指标？**
   - 缺少`spring-boot-starter-actuator`；暴露`prometheus`端点；确认Spring AI版本≥1.0。
2. **Trace没有LLM详情？**
   - 缺少`micrometer-tracing-bridge-otel`依赖；OTLP endpoint配置正确。
3. **指标标签爆炸**
   - 检查是否将高基数字段（prompt、userId）用`lowCardinalityKeyValue`，改用`highCardinalityKeyValue`仅存入链路。



问：如何使用Spring AI进行全组件埋点？

# Spring AI 全组件自动埋点 + 自定义埋点完整方案
Spring AI 底层基于 Spring 6 / Micrometer `Observation` API 实现**全组件无侵入自动埋点**，Chat、Embedding、VectorStore、Image、ToolCall 全部内置埋点，无需手动写计时、计数代码；下面分「自动埋点启用、各组件埋点说明、自定义扩展埋点、生产配置避坑、验证方式」完整说明。

## 一、前置依赖（必须齐全，否则埋点失效）
Spring Boot 3.x + Spring AI 1.0+
### Maven
```xml
<!-- Spring AI 任意模型starter（openai/ollama/aliyun等） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- Micrometer 观测核心 + Actuator指标暴露 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Trace链路导出（OTLP，Jaeger/SkyWalking/OpenTelemetry Collector） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

## 二、核心原理：Spring AI 埋点底层机制
1. Spring AI 所有客户端构造时自动注入全局 `ObservationRegistry`；
2. 所有 AI 操作统一封装 `Observation`：
   - ChatClient / ChatModel（同步+流式stream）
   - EmbeddingModel 向量化
   - VectorStore 向量检索、写入
   - ImageModel 文生图
   - ToolCall 工具函数调用
3. 自动生成两类数据：
   - Metrics（Timer/Counter）：Prometheus 指标，低基数标签
   - Trace Span：完整调用链路，携带高基数上下文（prompt、会话ID）
4. 遵循 OpenTelemetry GenAI 标准，指标统一前缀 `gen_ai.*`。

## 三、application.yml 全局开启全组件埋点（一键生效）
### 基础配置（自动埋点开关无额外代码）
```yaml
spring:
  application:
    name: spring-ai-observability-demo
  ai:
    # 调试：打印prompt/返回内容，生产关闭防止敏感数据泄露
    chat:
      client:
        log-prompt: false
        log-completion: false
        include-content: false

# Actuator 暴露指标端点
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics,trace
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      app: ${spring.application.name}
  # 链路追踪 OTLP 上报
  tracing:
    sampling:
      probability: 1.0 # 生产改为0.1~0.3降低存储
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
    metrics:
      export:
        enabled: true
```
> 配置完成后**无需任何业务代码修改**，所有 AI 组件自动埋点。

## 四、各组件内置自动埋点详情（全组件覆盖）
### 1. ChatClient / ChatModel（对话核心）
#### 自动生成指标
1. `gen_ai.client.operation.duration` Timer
   标签：`gen_ai.system`、`gen_ai.operation.name=chat`、`gen_ai.response.model`、`outcome=SUCCESS/ERROR`
   作用：LLM 调用耗时、P95/P99 延迟
2. `gen_ai.client.token.usage` Counter
   标签：`gen_ai.token.type=input/output`
   作用：累计输入输出 Token，核算调用成本
3. `gen_ai.client.operation.count` Counter
   作用：总调用次数、异常次数

#### Trace Span 内置属性
- gen_ai.usage.input_tokens / output_tokens
- gen_ai.response.finish_reason（stop/tool_calls/length）
- 流式响应 Flux 完整采集分段总 Token、总耗时

```java
// 零埋点代码，自动观测
@RestController
public class ChatCtrl {
    private final ChatClient chatClient;
    public ChatCtrl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/chat")
    public String chat(String q) {
        return chatClient.prompt().user(q).call().content();
    }
    // 流式同样自动埋点
    @GetMapping("/stream")
    public Flux<String> stream(String q) {
        return chatClient.prompt().user(q).stream().content();
    }
}
```

### 2. EmbeddingModel 向量嵌入埋点
操作名：`embed`
指标：
- `gen_ai.client.operation.duration{gen_ai.operation.name="embed"}`
- Token 消耗计数，批量嵌入批量统计

```java
@Service
public class EmbeddingSvc {
    private final EmbeddingModel embeddingModel;
    public List<Embedding> genEmbedding(String text) {
        // 自动生成观测span+指标
        return embeddingModel.embed(List.of(text));
    }
}
```

### 3. VectorStore 向量库埋点（RAG核心）
Spring AI 向量存储统一埋点前缀 `db.vector`
- `db.vector.query.duration`：相似度检索耗时
- `db.vector.query.count`：检索次数
标签：向量库类型（pgvector/milvus/chroma）、topK、匹配文档数
```java
// 自动埋点，无需修改
vectorStore.similaritySearch(SearchRequest.query(q).withTopK(5));
vectorStore.add(List.of(new Document("xxx")));
```

### 4. ToolCall 工具调用埋点
使用 `FunctionCallback` 工具调用时自动创建子Span：
- 父Span：chat 主调用
- 子Span：tool_call
携带标签：工具名称、工具入参、工具返回结果、调用耗时、异常

### 5. ImageModel 图像生成埋点
operation.name = image
指标统计图像生成耗时、生成图片数量、错误计数

## 五、手动自定义埋点（业务层扩展全链路串联）
场景：RAG 完整链路（检索→拼接上下文→LLM）、业务模块埋点、自定义业务标签
### 步骤1：注入全局 ObservationRegistry
```java
@Service
public class FullRagService {
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ObservationRegistry observationRegistry;

    // Spring自动注入全局观测注册器
    public FullRagService(VectorStore vectorStore, ChatClient.Builder cb, ObservationRegistry reg) {
        this.vectorStore = vectorStore;
        this.chatClient = cb.build();
        this.observationRegistry = reg;
    }
}
```

### 步骤2：自定义业务 Observation 埋点
区分低基数标签（进指标）、高基数标签（仅链路，防止指标爆炸）
```java
public String ragChat(String query, String userId) {
    // 创建自定义观测，绑定全局registry
    Observation obs = Observation.createNotStarted("rag.full.process", observationRegistry)
            // 低基数：可写入Prometheus指标（取值有限）
            .lowCardinalityKeyValue("biz.type", "customer_qa")
            .lowCardinalityKeyValue("env", "prod")
            // 高基数：仅存入Trace，禁止进指标（用户ID、原始查询）
            .highCardinalityKeyValue("user.id", userId)
            .highCardinalityKeyValue("raw.query", query);

    try (Observation.Scope scope = obs.start().openScope()) {
        // 内部向量检索、LLM调用会自动生成子Span，和当前业务span串联
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.query(query).withTopK(3));
        String prompt = "基于文档回答：" + docs + "\n问题：" + query;
        return chatClient.prompt().user(prompt).call().content();
    } catch (Exception e) {
        obs.error(e); // 标记链路异常，自动生成error指标
        throw e;
    } finally {
        obs.stop(); // 结束观测，上报指标、span
    }
}
```

## 六、非SpringBoot场景手动开启埋点（纯Java）
若手动构造模型，必须手动传入 `ObservationRegistry`，否则自动埋点失效：
```java
// 手动构建registry
ObservationRegistry registry = ObservationRegistry.create();
// OpenAI模型绑定观测
OpenAiChatModel chatModel = OpenAiChatModel.builder()
        .apiKey("xxx")
        .observationRegistry(registry)
        .build();

// Embedding同理
OpenAiEmbeddingModel embedModel = OpenAiEmbeddingModel.builder()
        .apiKey("xxx")
        .observationRegistry(registry)
        .build();
```

## 七、埋点生效验证方式
### 1. 查看Metrics指标
访问端点：`http://localhost:8080/actuator/prometheus`
搜索关键字 `gen_ai`，能看到 `gen_ai.client.operation_duration`、token 相关指标即代表全组件埋点正常。

### 2. 查看Trace链路
上报 OTLP 到 Jaeger / SkyWalking：
一条完整 RAG 链路结构：
HTTP入口span → 自定义rag.full.process → vector.query子span → gen_ai.chat LLM子span → tool_call（如有）

### 3. 日志关联
MDC 自动注入 `traceId` / `spanId`，日志、链路、指标三端联动排查。

## 八、生产环境埋点避坑要点
1. **禁止高基数字段用 lowCardinalityKeyValue**
   用户ID、会话ID、完整Prompt必须用 highCardinalityKeyValue，否则 Prometheus label 爆炸，服务OOM。
2. **关闭生产prompt日志**
   `log-prompt: true` 仅开发调试，线上开启会泄露敏感业务数据。
3. **流式响应埋点无丢失**
   Spring AI Flux 流式完整统计总token、总耗时，无需额外处理。
4. **异常自动采集**
   AI调用抛出超时、限流、鉴权错误时，自动标记 `outcome=ERROR`，生成错误计数指标。
5. **多模型兼容**
   Ollama、通义千问、Azure OpenAI、Claude 所有 Spring AI 适配模型统一一套 gen_ai 埋点规范，无需适配修改。

## 九、全组件埋点总结流程
1. 引入 Actuator + Micrometer Prometheus + OTLP Tracing 依赖；
2. yml 配置暴露端点、OTLP上报、采样率；
3. 直接使用 ChatClient/EmbeddingModel/VectorStore，**自动全组件埋点**；
4. 业务RAG/工具链路使用 `ObservationRegistry` 自定义业务观测串联整条链路；
5. Prometheus 看指标、Jaeger看完整AI调用链路、日志用traceId关联排查。

需要我给你一份可直接复制的 RAG 完整链路埋点完整 Service 示例（含异常处理+自定义标签）吗？

