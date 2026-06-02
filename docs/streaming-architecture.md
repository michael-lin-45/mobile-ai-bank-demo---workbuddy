# 流式输出架构：原理、代码与实例详解

> 本文基于 `streaming_output` 分支 Phase 1+2 实现，完整解读从 HTTP 请求到 SSE 输出的全链路流式管道。Phase 2 已启用 WEALTH_INTERPRET 流式节点。

---

## 目录

1. [问题背景与设计目标](#1-问题背景与设计目标)
2. [核心设计：GES闭环原则](#2-核心设计ges闭环原则)
3. [数据模型：StreamChunk](#3-数据模型streamchunk)
4. [全链路架构总览](#4-全链路架构总览)
5. [L2层：图注册与流式声明](#5-l2层图注册与流式声明)
6. [GES层：分流引擎（核心）](#6-ges层分流引擎核心)
7. [L1层：领域服务透传](#7-l1层领域服务透传)
8. [L0层：控制器与输出适配](#8-l0层控制器与输出适配)
9. [ChatMemory写入机制](#9-chatmemory写入机制)
10. [实例一：非流式意图 — TRANSFER 转账](#10-实例一非流式意图--transfer-转账)
11. [实例二：流式意图 — WEALTH_INTERPRET 理财解读](#11-实例二流式意图--wealth_interpret-理财解读)
12. [实例三：模糊意图追问 — WEALTH 消歧](#12-实例三模糊意图追问--wealth-消歧)
13. [关键设计决策汇总](#13-关键设计决策汇总)

---

## 1. 问题背景与设计目标

### 1.1 原来的问题

框架是 **L0→L1→L2** 三层架构：

```
用户 ──→ L0 BankController ──→ L1 DomainService ──→ L2 子智能体Graph
              (路由)              (领域逻辑)           (图执行)
```

整个链路是**阻塞式**的：用户发消息，后端跑完整个图（可能10-30秒），一次性返回结果。对于"理财解读"需要大模型生成长文本的场景，用户看着空白页面等很久。

### 1.2 设计目标

| 目标 | 说明 |
|------|------|
| **流式输出** | 模型边生成边推送，用户看到文字"逐渐出现" |
| **框架统一** | 代码不能分裂成流式/非流式两套，L0/L1无流式分支 |
| **GES闭环** | 流式/非流式差异只在GES内部分流，对上层透明 |
| **SSE标准** | 使用OpenAI兼容SSE格式，前端通用 |
| **ChatMemory安全** | 写入机制统一，并发安全，无重复 |

---

## 2. 核心设计：GES闭环原则

这是整个架构最关键的决策。

### 2.1 痛点

"流式"和"非流式"在很多地方会产生差异：

| 关注点 | 非流式 | 流式 |
|--------|--------|------|
| 调用Graph的方式 | `graph.stream().blockLast()` | `graph.graphResponseStream()` |
| 数据形态 | 一个完整的 `WorkflowOutput` | 多个 `StreamingOutput` 片段 |
| 前端协议 | JSON响应 | SSE流 |
| ChatMemory写入 | 一次性写完整回复 | 累积片段，终结时写 |
| COMPLETE是否带内容 | 带完整内容 | 不带（前端已收到所有片段） |

如果每层都写 `if(streaming)` 分支，代码会变成意大利面条。

### 2.2 解决方案

**所有差异在GES（GraphExecutionEngine）中闭环**：

```
                        ┌──────────────────────────────────┐
                        │        GES (Graph执行引擎)        │
                        │                                  │
  IntentRegistry ──────→│  isStreamable(intent)?           │
  (L2注册时声明)         │     │                            │
                        │     ├── false → executeBlocking  │ ← 内部差异，外面看不到
                        │     │     stream().blockLast()    │
                        │     │     → 1个COMPLETE chunk     │
                        │     │                            │
                        │     └── true  → executeStreaming │
                        │           graphResponseStream()   │
                        │           → N个CHUNK + 1个COMPLETE│
                        │                                  │
                        │  ──────────────────────────────  │
                        │  输出统一: Flux<StreamChunk>      │ ← 对外只有一个类型
                        └──────────┬───────────────────────┘
                                   │
                    L1看到的: Flux<StreamChunk> — 不知道也不关心底层是流式还是非流式
                                   │
                    L0看到的: Flux<StreamChunk> — 只管消费（SSE或JSON）
```

### 2.3 三个统一

| 维度 | 统一方式 |
|------|----------|
| **接口统一** | L0/L1/GES之间只有一个数据类型 `StreamChunk`，一个传输协议 `Flux` |
| **路径统一** | L1/L0始终走同一条调用链，不存在 `if(streaming)` 分支 |
| **ChatMemory统一** | L0/L1用同一个 `StreamingChatMemoryWriter` 类做addMessage |

---

## 3. 数据模型：StreamChunk

`StreamChunk` 是整个流式管道的**唯一数据载体**。

### 3.1 两类chunk

```
一个 Flux<StreamChunk> = N个中间chunk + 1个终结chunk（一定有且仅有1个终结chunk）

中间chunk:  CHUNK — 文本增量，如 "根据"、"产品"、"信息"
终结chunk:  COMPLETE / INTERRUPTED / DISAMBIGUATION / ERROR / REROUTE
```

### 3.2 统一模型

| 场景 | 中间chunk | 终结chunk |
|------|-----------|-----------|
| 非流式Graph | 无 | COMPLETE + content="转账成功！" |
| 流式Graph | CHUNK "根据"、CHUNK "产品" | COMPLETE（无content） |
| 图中断 | 无 | INTERRUPTED + question="转账金额？" |
| 意图消歧 | 无 | DISAMBIGUATION + question + candidateIntents |
| L1跨域 | 无 | REROUTE + rerouteIntent |

**非流式 = "0个中间chunk + 1个带content的终结chunk"**。模型完全统一。

### 3.3 关键代码

```java
// StreamChunk.java — 核心字段
public class StreamChunk {
    private ChunkType type;       // CHUNK / COMPLETE / INTERRUPTED / DISAMBIGUATION / ERROR / REROUTE
    private String intent;        // 意图名
    private String content;       // CHUNK时=增量文本, COMPLETE时=完整文本或null(流式)
    private String question;      // INTERRUPTED/DISAMBIGUATION时的提问
    private String errorMessage;  // ERROR时
    private List<String> candidateIntents;  // DISAMBIGUATION时

    // 工厂方法
    public static StreamChunk chunk(String intent, String text)           // 流式文本增量
    public static StreamChunk complete(String intent, String content)     // 非流式完成
    public static StreamChunk streamingDone(String intent)               // 流式完成(无content)
    public static StreamChunk interrupted(String intent, String question) // 中断
    public static StreamChunk disambiguation(String question, List<String> candidates) // 消歧
    public static StreamChunk error(String errorMessage)                 // 错误
    public static StreamChunk reroute(String rerouteIntent, String hint) // 重新路由

    // 是否终结
    public boolean isTerminal() { return type != ChunkType.CHUNK; }
}
```

### 3.4 流式COMPLETE不带content

```java
// 非流式完成 — content承载完整文本
StreamChunk.complete("TRANSFER", "转账成功！已向张三转入1000元")
// → {type: "COMPLETE", intent: "TRANSFER", content: "转账成功！已向张三转入1000元"}

// 流式完成 — 只是结束信号，不带content
StreamChunk.streamingDone("WEALTH_INTERPRET")
// → {type: "COMPLETE", intent: "WEALTH_INTERPRET", content: null}
```

**原因**：前端已通过所有CHUNK收到了完整文本，如果COMPLETE再带content会重复显示。ChatMemory的完整文本由 `AssistantWriter` 累积器拼接。

---

## 4. 全链路架构总览

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          前端 (Browser)                                  │
│   Accept: text/event-stream              Accept: application/json        │
│   → EventSource逐个收chunk               → 一次性收JSON                   │
└───────────────┬──────────────────────────────────────┬───────────────────┘
                │                                      │
                ▼                                      ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                       L0: BankController                                 │
│                                                                          │
│   ① writeUserMessage(全局chatMemory, sessionId, userInput)               │
│   ② new AssistantWriter(全局chatMemory, sessionId)                       │
│   ③ dispatchWithReroute → DomainHandler.handle()                         │
│        └─ .doOnNext(assistantWriter::onChunk)                            │
│   ④ Content Negotiation:                                                 │
│      SSE → SseOutputAdapter.toSse(pipeline)                              │
│      JSON → SseOutputAdapter.toJson(pipeline)                            │
│                                                                          │
│   ChatMemory: 全局chatMemory, key=sessionId                              │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ Flux<StreamChunk>
                ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                       L1: DomainService                                  │
│                                                                          │
│   ① addUserMessage(领域chatMemory, domainSessionId)                      │
│   ② new AssistantWriter(领域chatMemory, domainSessionId)                 │
│   ③ graphExecutionEngine.executeGraph/resumeGraph(...)                   │
│        └─ .doOnNext(chunk → {                                            │
│               assistantWriter.onChunk(chunk);                             │
│               handleActiveAgentState(sessionId, chunk);                   │
│           })                                                             │
│                                                                          │
│   ChatMemory: 领域chatMemory, key=sessionId@logTag                       │
│   ❌ 不读 isStreamable()，不判断流式/非流式                                │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ Flux<StreamChunk>
                ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                       GES: GraphExecutionEngine                          │
│                                                                          │
│   isStreamable(intent)?                                                  │
│      │                                                                   │
│      ├── false → executeBlocking()                                       │
│      │     Flux.defer(() → {                                             │
│      │       graph.stream(input, config).blockLast();                     │
│      │       WorkflowOutput output = checkGraphResult();                  │
│      │       return Flux.just(StreamChunk.fromWorkflowOutput(output));    │
│      │     })                                                            │
│      │                                                                   │
│      └── true  → executeStreaming()                                      │
│            graph.graphResponseStream(input, config)                       │
│              .flatMap(this::mapStreamingOutput)  ← 过滤出CHUNK(允许null)  │
│              .concatWith(buildStreamingTerminalChunk) ← 追加终结信号      │
│              .onErrorResume(...)                ← 错误兜底               │
│                                                                          │
│   ✅ 唯一读取isStreamable()的层                                          │
│   输出: Flux<StreamChunk> — 流式/非流式对上层完全透明                     │
└───────────────┬──────────────────────────────────────────────────────────┘
                │
                ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                       L2: CompiledGraph (Spring AI Alibaba)               │
│                                                                          │
│   非流式: graph.stream(input, config) → 同步执行                         │
│   流式:   graph.graphResponseStream(input, config)                        │
│           → Flux<GraphResponse<NodeOutput>>                               │
│             └─ StreamingOutput {chunk, outputType}                        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 5. L2层：图注册与流式声明

### 5.1 图的注册

L2图在 `AppInitConfig` 中绑定到 `IntentRegistry`：

```java
// AppInitConfig.java
@PostConstruct
public void bindGraphs() {
    intentRegistry.bindGraph("TRANSFER", transferGraph);           // 非流式
    intentRegistry.bindGraph("BILL_QUERY", billQueryGraph);        // 非流式
    intentRegistry.bindGraph("WEALTH_CONSULT", wealthConsultGraph);// 非流式
    intentRegistry.bindGraph("WEALTH_INTERPRET", wealthInterpretGraph, true);  // ✅ 流式
}
```

### 5.2 IntentRegistry的streamable字段

```java
// IntentRegistry.IntentConfig
@Data
public static class IntentConfig {
    private final String name;
    private final String description;
    private CompiledGraph graph;
    private boolean streamable;  // ← 是否为流式Graph — 由GES读取，L0/L1不关心
}

// 绑定方法
public void bindGraph(String intentName, CompiledGraph graph, boolean streamable) {
    IntentConfig config = registry.get(intentName);
    if (config != null) {
        config.setGraph(graph);
        config.setStreamable(streamable);
    }
}

// GES读取
public boolean isStreamable(String intentName) {
    IntentConfig config = registry.get(intentName);
    return config != null && config.isStreamable();
}
```

**关键**：`streamable` 是L2图的固有属性（类似"这个图有没有中断节点"），不是运行时决策。只有GES读取这个字段。

### 5.3 意图配置（application.yml）

```yaml
routing:
  intents:
    - name: TRANSFER
      description: "转账给他人"
      intent-type: OPERATION
      scope: "资金转账操作，将钱转给他人或理财产品等"
    - name: WEALTH_INTERPRET
      description: "理财产品解读"
      intent-type: CONSULTATION
      scope: "理财产品/标的解读，分析具体理财产品的详情"
    - name: WEALTH_CONSULT
      description: "理财咨询/推荐"
      intent-type: CONSULTATION
      scope: "理财咨询与推荐，基于风险偏好推荐理财产品"

  intent-groups:
    - group-id: WEALTH
      display-name: "理财"
      intent-names: [WEALTH_CONSULT, WEALTH_INTERPRET]
      disambiguation-question: "请问您需要理财咨询还是理财产品解读？"
```

注意：`streamable` 不在yml中，而是在 `AppInitConfig.bindGraph()` 时硬编码。因为这是Graph的实现属性，不是配置属性。

---

## 6. GES层：分流引擎（核心）

GES是整个流式架构的**唯一分流点**。理解了 `executeStreaming()`，就理解了整个流式管道。

### 6.1 统一入口

```java
// GraphExecutionEngine.java
public Flux<StreamChunk> executeGraph(CompiledGraph graph, String intent,
                                       Map<String, Object> input, String threadId) {
    boolean streamable = intentRegistry.isStreamable(intent);  // ← 唯一读取点
    if (!streamable) {
        return executeBlocking(graph, intent, input, threadId);
    }
    return executeStreaming(graph, intent, input, threadId);
}
```

### 6.2 非流式路径：executeBlocking

```java
private Flux<StreamChunk> executeBlocking(CompiledGraph graph, String intent,
                                           Map<String, Object> input, String threadId) {
    return Flux.defer(() -> {
        try {
            RunnableConfig config = threadConfig(threadId);
            // 1. 同步执行Graph — 阻塞等图跑完
            graph.stream(input, config).blockLast();

            // 2. 检查Graph最终状态（正常完成 vs 中断）
            WorkflowOutput output = checkGraphResult(graph, config, intent);

            // 3. 包装成1个StreamChunk的Flux
            return Flux.just(StreamChunk.fromWorkflowOutput(output));

        } catch (Exception e) {
            return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
        }
    });
}
```

**`Flux.defer()` 的作用**：懒执行 — 只有在被subscribe时才执行内部逻辑。这样：
- SSE模式：subscribe后执行，结果变成1个chunk的流
- JSON模式：block时执行，结果被 `.last()` 取出

**非流式对上层来说和流式完全一样** — 都是 `Flux<StreamChunk>`，只是中间chunk数量为0。

### 6.3 流式路径：executeStreaming（重点）

```java
private Flux<StreamChunk> executeStreaming(CompiledGraph graph, String intent,
                                             Map<String, Object> input, String threadId) {
    try {
        RunnableConfig config = threadConfig(threadId);

        return graph.graphResponseStream(input, config)       // ① 获取原始流
            .flatMap(graphResponse -> {                        // ② 过滤+转换
                StreamChunk chunk = mapStreamingOutput(graphResponse, intent);
                return chunk != null ? Flux.just(chunk) : Flux.empty();
            })
            .concatWith(Flux.defer(() -> {                     // ③ 追加终结chunk
                StreamChunk terminal = buildStreamingTerminalChunk(...);
                return Flux.just(terminal);
            }))
            .onErrorResume(e -> {                              // ④ 错误兜底
                return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
            });
    } catch (Exception e) {
        // graphResponseStream()调用本身抛异常
        return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
    }
}
```

> **注意**：这里必须用 `.flatMap()` 而非 `.map()` + `.filter()`，因为 `mapStreamingOutput()` 对非流式节点返回 null，而 Reactor 的 `.map()` 不允许返回 null（会抛 `NullPointerException: The mapper returned a null value`）。`.flatMap()` 将 null 映射为 `Flux.empty()`，实现安全的过滤。

#### 6.3.1 步骤①：graphResponseStream()

调用 Spring AI Alibaba 的 `graphResponseStream(Map, RunnableConfig)`，返回 `Flux<GraphResponse<NodeOutput>>`。

**GraphResponse 不是最终结果，它是一个包装器**。图的每一步执行都会发出一个 GraphResponse。GraphResponse 有两种形态：

```
形态1: GraphResponse.of(data) — 节点有输出，图继续执行
  → output = CompletableFuture.completedFuture(data)
  → resultValue = null
  → isDone() = false
  → 取值方式: getOutput().join()

形态2: GraphResponse.done(value) — 节点执行完毕/图完成
  → output = null
  → resultValue = value
  → isDone() = true
  → 取值方式: resultValue().get()
```

**这是实际的时间线**（以 WealthInterpretGraph 为例）：

```
时间线   GraphResponse的类型                                  取值方式              含义
──────────────────────────────────────────────────────────────────────────────────────────────────
t1      GraphResponse.of(NodeOutput)                         getOutput().join()   extractParams节点完成
t2      GraphResponse.of(NodeOutput)                         getOutput().join()   paramRouter节点完成
t3      GraphResponse.of(StreamingOutput{chunk="稳利宝"})     getOutput().join()   模型输出"稳利宝"
t4      GraphResponse.of(StreamingOutput{chunk="是一款"})     getOutput().join()   模型输出"是一款"
t5      GraphResponse.of(StreamingOutput{chunk="低风险"})     getOutput().join()   模型输出"低风险"
t6      GraphResponse.of(StreamingOutput{type=FINISHED})      getOutput().join()   模型生成完毕
t7      GraphResponse.done(completionResult)                  resultValue().get()  流式节点终结+后续节点启动
```

**关键发现**：流式 chunk 走的是 `GraphResponse.of()` 路径，`isDone()==false` 且 `resultValue==null`。必须通过 `getOutput().join()` 才能取到 StreamingOutput。如果只检查 `resultValue()`，流式 chunk 会被全部过滤掉！

**大部分 GraphResponse 都不是我们需要的**。我们只关心模型正在生成的文本片段。

#### 6.3.2 步骤②：mapStreamingOutput() — 过滤器

```java
private StreamChunk mapStreamingOutput(GraphResponse<NodeOutput> graphResponse, String intent) {
    // 1. 从GraphResponse中提取值 — 兼容of()和done()两种形态
    Object value = extractValue(graphResponse);
    if (value == null) return null;

    // 2. 只关心StreamingOutput（流式片段）
    if (value instanceof StreamingOutput<?> streaming) {
        OutputType outputType = streaming.getOutputType();
        // 3. 只关心正在生成的文本片段
        if (outputType == OutputType.AGENT_MODEL_STREAMING
                || outputType == OutputType.GRAPH_NODE_STREAMING) {
            String chunk = streaming.chunk();
            if (chunk != null && !chunk.isEmpty()) {
                return StreamChunk.chunk(intent, chunk);  // ✅ 这是我们需要的
            }
        }
        // FINISHED / TOOL / HOOK → 过滤
    }
    return null;  // 普通NodeOutput（中间节点）→ 过滤
}

/**
 * 从GraphResponse提取值 — 兼容of()和done()两种形态
 *
 * of(data):  output != null → getOutput().join() 取值
 * done(val): output == null → resultValue() 取值
 */
private Object extractValue(GraphResponse<NodeOutput> graphResponse) {
    try {
        // 形态1: GraphResponse.of(data) — output不为空
        if (graphResponse.getOutput() != null && !graphResponse.getOutput().isCompletedExceptionally()) {
            return graphResponse.getOutput().join();
        }
        // 形态2: GraphResponse.done(value) — resultValue有值
        if (graphResponse.resultValue().isPresent()) {
            return graphResponse.resultValue().get();
        }
    } catch (Exception e) {
        log.debug("[GraphExec] extractValue failed: {}", e.getMessage());
    }
    return null;
}
```

#### 6.3.1 步骤①：graphResponseStream()

调用 Spring AI Alibaba 的 `graphResponseStream(Map, RunnableConfig)`，返回 `Flux<GraphResponse<NodeOutput>>`。

**GraphResponse 不是最终结果，它是一个包装器**。图的每一步执行都会发出一个 GraphResponse：

```
时间线   GraphResponse的内容                                    含义
─────────────────────────────────────────────────────────────────────────────
t1      {isDone=false}                                         ContextRouter节点开始
t2      {isDone=true, resultValue=NodeOutput}                  ContextRouter节点完成
t3      {isDone=false}                                         模型节点开始
t4      {isDone=true, resultValue=StreamingOutput(chunk="根据")}  模型输出"根据"
t5      {isDone=true, resultValue=StreamingOutput(chunk="产品")}  模型输出"产品"
t6      {isDone=true, resultValue=StreamingOutput(chunk="信息")}  模型输出"信息"
t7      {isDone=true, resultValue=StreamingOutput(type=FINISHED)} 模型生成完毕
t8      {isDone=true, resultValue=NodeOutput}                  结果提取节点完成
```

**大部分 GraphResponse 都不是我们需要的**。我们只关心模型正在生成的文本片段。

#### 6.3.2 步骤②：mapStreamingOutput() — 过滤器

```java
private StreamChunk mapStreamingOutput(GraphResponse<NodeOutput> graphResponse, String intent) {
    // 条件1：节点执行完毕且有结果
    if (graphResponse.isDone() && graphResponse.resultValue().isPresent()) {
        Object value = graphResponse.resultValue().get();
        // 条件2：输出是StreamingOutput（只有模型节点会产生）
        if (value instanceof StreamingOutput<?> streaming) {
            OutputType outputType = streaming.getOutputType();
            // 条件3：是正在生成的文本片段（不是FINISHED信号）
            if (outputType == OutputType.AGENT_MODEL_STREAMING
                    || outputType == OutputType.GRAPH_NODE_STREAMING) {
                String chunk = streaming.chunk();
                if (chunk != null && !chunk.isEmpty()) {
                    return StreamChunk.chunk(intent, chunk);  // ✅ 这是我们需要的
                }
            }
            return null;  // FINISHED/TOOL/HOOK → 过滤
        }
    }
    return null;  // 中间节点或未完成 → 过滤
}
```

OutputType枚举的4种值：

| 类型 | 含义 | 处理 |
|------|------|------|
| `AGENT_MODEL_STREAMING` | 模型正在生成文本 | ✅ 变成 CHUNK |
| `GRAPH_NODE_STREAMING` | 图节点流式输出 | ✅ 变成 CHUNK |
| `AGENT_MODEL_FINISHED` | 模型生成完毕 | ❌ 过滤 |
| `GRAPH_NODE_FINISHED` | 图节点完成 | ❌ 过滤 |

**为什么不用 `AGENT_MODEL_FINISHED` 作为终结信号？** 因为"模型生成完毕"≠"图执行完毕"。模型生成完后，图可能还有后续节点。终结判断在 `buildStreamingTerminalChunk` 中通过 `graph.getState()` 检查，更准确。

**过滤过程可视化**：

```
GraphResponse流:   [中间节点] [中间节点] [流式"根据"] [流式"产品"] [流式"信息"] [FINISHED] [中间节点]
                        ↓          ↓          ↓           ↓           ↓          ↓          ↓
mapStreamingOutput:   null       null     CHUNK"根据"  CHUNK"产品"  CHUNK"信息"  null       null
                        ↓          ↓          ↓           ↓           ↓          ↓          ↓
filter(nonNull):                                     [CHUNK"根据"  CHUNK"产品"  CHUNK"信息"]
```

#### 6.3.3 步骤④：buildStreamingTerminalChunk() — 终结信号

`graphResponseStream()` 只告诉我们"模型输出了什么"，不告诉我们"图是正常结束还是中断"。所以流式数据发完后，主动检查图的最终状态：

```java
private StreamChunk buildStreamingTerminalChunk(CompiledGraph graph, RunnableConfig config,
                                                 String intent) {
    try {
        var snapshot = graph.getState(config);   // 读取图的checkpoint
        if (snapshot == null) {
            return StreamChunk.streamingDone(intent);
        }

        String nextNode = snapshot.next();       // 下一个要执行的节点
        OverAllState state = snapshot.state();
        String question = state != null
                ? (String) state.value("_question").orElse("") : "";

        // interruptBefore中断 — nextNode非空且非__END__
        if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
            return StreamChunk.interrupted(intent, question);   // 带question
        }
        // ask→END中断 — 图结束但有提问
        if (question != null && !question.isEmpty()) {
            return StreamChunk.interrupted(intent, question);   // 带question
        }

        // 正常完成 — 不带content！
        clearCheckpoint(graph, config, intent);
        return StreamChunk.streamingDone(intent);  // 只是结束信号

    } catch (Exception e) {
        return StreamChunk.streamingDone(intent);   // 兜底
    }
}
```

#### 6.3.4 完整的流式管道组装时序

```
graphResponseStream    extractValue         mapStreamingOutput   flatMap            concatWith(terminal)   最终输出
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
[中间节点 of()]      → NodeOutput         → null              → Flux.empty()
[流式"根据" of()]    → StreamingOutput     → CHUNK"根据"       → Flux.just(...)   →                      CHUNK"根据"
[流式"产品" of()]    → StreamingOutput     → CHUNK"产品"       → Flux.just(...)   →                      CHUNK"产品"
[流式"信息" of()]    → StreamingOutput     → CHUNK"信息"       → Flux.just(...)   →                      CHUNK"信息"
[FINISHED of()]      → StreamingOutput     → null              → Flux.empty()
[done(completion)]   → Map(结果)           → null              → Flux.empty()
(流结束)                                                                           → streamingDone()      COMPLETE
```

### 6.4 resume路径

resume的流式/非流式分流逻辑与execute完全对称：

```java
public Flux<StreamChunk> resumeGraph(CompiledGraph graph, String intent,
                                      String userInput, String threadId,
                                      Map<String, Object> globalStateData) {
    boolean streamable = intentRegistry.isStreamable(intent);
    if (!streamable) {
        return resumeBlocking(graph, intent, userInput, threadId, globalStateData);
    }
    return resumeStreaming(graph, intent, userInput, threadId, globalStateData);
}
```

`resumeStreaming` 与 `executeStreaming` 的唯一区别是调用前先 `updateState`：

```java
private Flux<StreamChunk> resumeStreaming(...) {
    RunnableConfig config = threadConfig(threadId);

    // 注入用户输入和全局数据
    Map<String, Object> updateData = new HashMap<>();
    if (globalStateData != null && !globalStateData.isEmpty()) {
        updateData.put("_globalStateData", globalStateData);
    }
    updateData.put("_latestUserInput", userInput);
    RunnableConfig updatedConfig = graph.updateState(config, updateData, null);

    // 用更新后的config恢复流式执行
    return graph.graphResponseStream((Map<String, Object>) null, updatedConfig)
        .flatMap(graphResponse -> {
            StreamChunk chunk = mapStreamingOutput(graphResponse, intent);
            return chunk != null ? Flux.just(chunk) : Flux.empty();
        })
        .concatWith(Flux.defer(() -> {
            StreamChunk terminal = buildStreamingTerminalChunk(graph, config, intent);
            return Flux.just(terminal);
        }))
        .onErrorResume(e -> Flux.just(StreamChunk.error("恢复执行出错: " + e.getMessage())));
}
```

---

## 7. L1层：领域服务透传

L1的核心原则：**不读 isStreamable()，不判断流式/非流式，只透传 Flux<StreamChunk>**。

### 7.1 DomainHandler接口

```java
// DomainHandler.java — L1统一接口
public interface DomainHandler {
    Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory);
    String getDomainName();
}
```

### 7.2 AbstractDomainService — 共享逻辑

L1的 `executeNewAgent` 和 `resumeActiveAgent` 是两个核心方法，所有领域服务共用：

```java
// AbstractDomainService.java

protected Flux<StreamChunk> executeNewAgent(String sessionId, String intent, String rewrittenInput) {
    var graph = intentRegistry.getGraph(intent);

    // 1. 生成独立threadId
    String threadId = generateThreadId(sessionId, intent);

    // 2. 构建input（含全局OverAllState注入）
    Map<String, Object> input = buildGraphInput(sessionId, rewrittenInput);

    // 3. 设置activeAgent
    setOwnActiveAgent(sessionId, intent, threadId);

    // 4. 创建per-request的AssistantWriter — 并发安全
    StreamingChatMemoryWriter.AssistantWriter assistantWriter =
            new StreamingChatMemoryWriter.AssistantWriter(chatMemory, domainSessionId(sessionId));

    // 5. 调用GES → 透传Flux<StreamChunk>
    return graphExecutionEngine.executeGraph(graph, intent, input, threadId)
            .doOnNext(chunk -> {
                assistantWriter.onChunk(chunk);           // 累积文本 + 终结时写ChatMemory
                handleActiveAgentState(sessionId, chunk);  // 管理activeAgent状态
            });
}
```

**注意第5步**：L1只调 `graphExecutionEngine.executeGraph()`，返回 `Flux<StreamChunk>` 后用 `doOnNext` 挂载两个副作用（ChatMemory写入 + activeAgent状态管理）。L1**完全不知道**底层是 `executeBlocking` 还是 `executeStreaming`。

### 7.3 domainSessionId() — ChatMemory隔离

```java
// AbstractDomainService.java
protected String domainSessionId(String sessionId) {
    return sessionId + "@" + logTag;   // 如 "abc123@TransferService"
}
```

4个ChatMemory bean共享同一个 `InMemoryChatMemoryRepository`，通过前缀隔离：

| ChatMemory Bean | 写入key | 写入层 |
|-----------------|---------|--------|
| chatMemory（全局） | `"abc123"` | L0 |
| transferChatMemory | `"abc123@TransferService"` | L1 |
| wealthChatMemory | `"abc123@WealthService"` | L1 |
| billChatMemory | `"abc123@BillService"` | L1 |

### 7.4 handleActiveAgentState() — 终结chunk时管理状态

```java
// AbstractDomainService.java
protected void handleActiveAgentState(String sessionId, StreamChunk chunk) {
    if (!chunk.isTerminal()) return;    // 只有终结chunk才处理

    ActiveAgentInfo active = getOwnActiveAgent(sessionId);
    if (active == null) return;

    if (chunk.getType() == ChunkType.INTERRUPTED && chunk.getQuestion() != null) {
        active.setLastQuestion(chunk.getQuestion());  // 记住子智能体的提问
    } else if (chunk.getType() == ChunkType.COMPLETE) {
        active.setLastQuestion(null);                 // 清空提问
        clearOwnActiveAgent(sessionId);               // 清除activeAgent
    }
}
```

---

## 8. L0层：控制器与输出适配

### 8.1 BankController — Content Negotiation

```java
// BankController.java
@PostMapping(value = "/chat", produces = {
        MediaType.TEXT_EVENT_STREAM_VALUE,     // SSE
        MediaType.APPLICATION_JSON_VALUE       // JSON
})
public Object chat(@RequestParam String sessionId,
                   @RequestBody Map<String, String> req,
                   @RequestHeader(value = "Accept", defaultValue = MediaType.APPLICATION_JSON_VALUE) String accept,
                   HttpServletResponse response) {
    String userInput = req.get("message");

    Flux<StreamChunk> pipeline = buildChatPipeline(sessionId, userInput);

    // 根据 Accept 头选择输出方式
    if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
        return sseAdapter.toSse(pipeline, response);  // SSE模式
    }
    return sseAdapter.toJson(pipeline);               // JSON模式
}
```

### 8.2 管道构建

```java
// BankController.java
private Flux<StreamChunk> buildChatPipeline(String sessionId, String userInput) {
    // ① 写入UserMessage到全局ChatMemory
    StreamingChatMemoryWriter.writeUserMessage(chatMemory, sessionId, userInput);

    // ② 创建L0的AssistantWriter
    StreamingChatMemoryWriter.AssistantWriter assistantWriter =
            new StreamingChatMemoryWriter.AssistantWriter(chatMemory, sessionId);

    // ③ 构建分发管道 + 挂载AssistantWriter
    return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount)
            .doOnNext(assistantWriter::onChunk);
}
```

### 8.3 REROUTE机制

当L1返回REROUTE chunk时，L0自动重新路由（排除当前域）：

```java
// BankController.java — dispatchWithReroute
private Flux<StreamChunk> dispatchWithReroute(String sessionId, String userInput,
                                               Set<String> excludedDomains,
                                               AtomicInteger rerouteCount) {
    DomainRouter.DomainResult domainResult = domainRouter.route(sessionId, userInput, excludedDomains);
    Flux<StreamChunk> resultFlux = dispatchToDomain(domainResult, sessionId, userInput, ...);

    return resultFlux.flatMap(chunk -> {
        if (chunk.getType() != ChunkType.REROUTE) {
            return Flux.just(chunk);    // 正常chunk直接透传
        }
        // REROUTE: 排除当前域，重新路由
        excludedDomains.add(currentDomain);
        rerouteCount.incrementAndGet();
        if (rerouteCount.get() >= maxRerouteAttempts) {
            return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求"));
        }
        return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount);  // 递归重试
    });
}
```

### 8.4 SseOutputAdapter — SSE格式转换

`SseOutputAdapter` 是**整个系统中唯一知道 SSE 格式的组件**。它的输入是 `Flux<StreamChunk>`，输出是 `SseEmitter`（Spring MVC 原生 SSE 支持），对业务语义完全不关心。

```java
// SseOutputAdapter.java — 完整代码
@Component
public class SseOutputAdapter {

    private static final long DEFAULT_TIMEOUT = 0L; // 无超时（由Flux控制生命周期）
    private final ObjectMapper objectMapper;

    /**
     * Flux<StreamChunk> → OpenAI兼容SSE流
     */
    public SseEmitter toSse(Flux<StreamChunk> pipeline, HttpServletResponse response) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);

        // 设置SSE响应头（防止Nginx等反向代理缓冲）
        response.addHeader("X-Accel-Buffering", "no");
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);

        pipeline
            .doOnNext(chunk -> {
                // 每收到一个StreamChunk → 序列化为JSON → 立即推送给客户端
                emitter.send(serialize(chunk), MediaType.TEXT_EVENT_STREAM);
            })
            .doOnComplete(() -> {
                // 流结束后发送OpenAI格式终止符
                emitter.send("[DONE]", MediaType.TEXT_EVENT_STREAM);
                emitter.complete();  // 关闭SSE连接
            })
            .doOnError(emitter::completeWithError)
            .subscribe();  // ← 触发器！subscribe后Flux才开始消费

        return emitter;  // 返回给Spring MVC，保持HTTP连接打开
    }

    /**
     * Flux<StreamChunk> → JSON响应（非SSE模式）
     */
    public Object toJson(Flux<StreamChunk> pipeline) {
        return pipeline
                .filter(StreamChunk::isTerminal)
                .last()
                .map(StreamChunk::toWorkflowOutput)
                .block();
    }

    private String serialize(StreamChunk chunk) {
        return objectMapper.writeValueAsString(chunk);
        // StreamChunk → {"type":"CHUNK","intent":"...","content":"..."}
    }
}
```

#### 8.4.1 从 LLM 到浏览器的完整数据变换链路

```
LLM流式生成                 Spring AI Alibaba框架                我们的代码                      HTTP输出
──────────                 ──────────────────                 ──────────                    ──────────

ChatClient.stream()        
  → Flux<ChatResponse>    
        │                  
        │  NodeExecutor.getEmbedFlux() 检测到Flux
        │  transformFluxToGraphResponse() 逐个包装
        ▼                  
  Flux<GraphResponse<NodeOutput>>
  内含 StreamingOutput
        │                  
        │  ←── StreamingOutput 在这里被"翻译"掉
        ▼                  
                                    GraphExecutionEngine.executeStreaming()
                                      .flatMap(mapStreamingOutput)
        │                              │
        │                              │  StreamingOutput → StreamChunk
        │                              │  (框架类型 → 我们的类型)
        ▼                              ▼
                              Flux<StreamChunk>    ← 从这里开始，不再有 StreamingOutput
                                    │
                                    │  L1: .doOnNext(assistantWriter::onChunk)
                                    │  L0: .doOnNext(assistantWriter::onChunk)
                                    ▼
                              BankController 看到的: Flux<StreamChunk>
                                    │
                        ┌───────────┴───────────┐
                        │ Accept: text/event-stream? │
                        │                           │
                    YES │                           │ NO
                        ▼                           ▼
              SseOutputAdapter.toSse()      SseOutputAdapter.toJson()
                        │                           │
                        ▼                           ▼
                SseEmitter                  block() → WorkflowOutput
                ┌─────────────────┐        ┌─────────────────┐
                │ emitter.send()  │        │ 一次性JSON响应    │
                │ 逐个发chunk     │        │ {status,content} │
                │                 │        └─────────────────┘
                │ 每发一个chunk:  │
                │                 │
                │ data: {"type":"CHUNK",     ← StreamChunk序列化为JSON
                │   "intent":"WEALTH_INTERPRET",
                │   "content":"稳利宝"}
                │                 │
                │ data: {"type":"CHUNK",
                │   "content":"是一款"}
                │                 │
                │ ...更多...      │
                │                 │
                │ data: {"type":"COMPLETE",   ← 终结chunk
                │   "intent":"WEALTH_INTERPRET"}
                │                 │
                │ data: [DONE]              ← OpenAI格式终止符
                │                 │
                │ emitter.complete()         ← 关闭SSE连接
                └─────────────────┘
```

#### 8.4.2 SseEmitter 的核心机制

**SseEmitter 是 Spring MVC 的原生 SSE 支持**（不是 WebFlux），原理是：

1. **Controller 返回 `SseEmitter` 对象** → Spring MVC 保持 HTTP 连接打开（不立即返回响应）
2. **`emitter.send(data)`** → 立即将数据写入 HTTP 响应流，格式为 SSE 标准的 `data:` 行
3. **`emitter.complete()`** → 关闭 HTTP 连接
4. **`emitter.completeWithError(e)`** → 异常关闭

关键时序：

```
客户端 Postman/Browser                服务端
─────────────────                ─────────
  POST /chat (Accept: text/event-stream)
        │                            │
        │ ──── HTTP 请求 ────→       │
        │                            │  BankController.chat() 被调用
        │                            │  buildChatPipeline() 构建管道
        │                            │  sseAdapter.toSse(pipeline) 被调用
        │                            │    └─ new SseEmitter()
        │                            │    └─ pipeline.subscribe()  ← Flux开始消费
        │                            │    └─ return emitter
        │                            │
        │  ←── HTTP 200 OK ────      │  Spring MVC 返回emitter，保持连接
        │      Content-Type: text/event-stream
        │                            │
        │                            │  (Flux内部执行: GES → L1 → L2 → LLM开始生成)
        │                            │
        │  ←── data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"稳利宝"}
        │                            │  ↑ emitter.send() 触发
        │                            │
        │  ←── data: {"type":"CHUNK","content":"是一款"}
        │                            │  ↑ 下一个chunk到来
        │                            │
        │  ←── data: {"type":"CHUNK","content":"低风险"}
        │  ←── data: {"type":"CHUNK","content":"理财产品"}
        │  ←── ...
        │                            │
        │                            │  (所有chunk发完，Flux执行doOnComplete)
        │                            │
        │  ←── data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}
        │  ←── data: [DONE]
        │                            │  ↑ emitter.complete() 关闭连接
        │                            │
        │  连接关闭                    │
```

**`subscribe()` 是触发器**：Flux 是冷流（cold publisher），没有人消费就不执行。一旦调用 `subscribe()`，整个管道从 GES → L1 → L0 → SseEmitter 就"活"起来了——数据从 LLM 流式生成一路推到客户端浏览器。

**为什么用 SseEmitter 而不是 `Flux<ServerSentEvent>`（WebFlux）？** 因为我们的项目是 Spring MVC（同步模型），不是 WebFlux（响应式模型）。SseEmitter 是 MVC 下唯一的 SSE 方案。

#### 8.4.3 三层类型隔离

整个流式管道有三次类型转换，每次转换都是一个"隔离边界"：

| 转换点 | 输入类型 | 输出类型 | 执行者 | 意义 |
|--------|----------|----------|--------|------|
| L2节点 → 框架 | `Flux<ChatResponse>` | `Flux<GraphResponse<NodeOutput>>` 内含 `StreamingOutput` | NodeExecutor (框架) | 把原始ChatResponse包装为图执行框架的标准输出 |
| 框架 → GES | `GraphResponse<NodeOutput>` 内含 `StreamingOutput` | `StreamChunk` | GES `mapStreamingOutput()` (我们的代码) | **把框架类型翻译成业务类型，StreamingOutput不再泄漏** |
| GES → 客户端 | `StreamChunk` | `data: JSON\n\n` | SseOutputAdapter (我们的代码) | **把业务类型翻译成SSE协议格式** |

**隔离效果**：

| 层 | 知道 StreamingOutput？ | 知道 SseEmitter？ | 看到的数据类型 |
|---|---|---|---|
| L2 (Graph节点) | ❌ 只返回 `Flux<ChatResponse>` | ❌ | `Map<String, Object>` |
| GES | ✅ **唯一接触点**，立即转成 StreamChunk | ❌ | `StreamChunk` |
| L0/L1 | ❌ | ❌ | `StreamChunk` |
| SseOutputAdapter | ❌ | ✅ **唯一接触点** | `StreamChunk` → JSON → SSE |

每一层只依赖自己的类型，不依赖上下层的实现细节。如果将来把 SSE 换成 WebSocket，只需改 `SseOutputAdapter`，其他层完全不受影响。

**SSE输出示例**：

```
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"根据"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"产品信息"}

data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}

data: [DONE]
```

---

## 9. ChatMemory写入机制

### 9.1 StreamingChatMemoryWriter

```java
// StreamingChatMemoryWriter.java

// 写入UserMessage — 静态方法，在handle入口调用
public static void writeUserMessage(ChatMemory chatMemory, String sessionId, String userInput) {
    chatMemory.add(sessionId, new UserMessage(userInput));
}

// 流式AssistantMessage写入器 — per-request局部变量
public static class AssistantWriter {
    private final ChatMemory chatMemory;
    private final String sessionId;
    private final StringBuilder contentAccumulator = new StringBuilder();

    public void onChunk(StreamChunk chunk) {
        if (chunk.getType() == ChunkType.CHUNK) {
            contentAccumulator.append(chunk.getContent());  // 累积文本增量
        }
        if (chunk.isTerminal() && chunk.getType() != ChunkType.REROUTE) {
            writeAssistantMessage(chunk);  // 终结时写ChatMemory
        }
    }

    private void writeAssistantMessage(StreamChunk terminalChunk) {
        String fullReply;
        if (contentAccumulator.length() > 0) {
            fullReply = contentAccumulator.toString();  // 流式：拼接所有CHUNK
        } else {
            fullReply = terminalChunk.getReplyContent(); // 非流式：从终结chunk取
        }
        if (fullReply != null && !fullReply.isEmpty()) {
            chatMemory.add(sessionId, new AssistantMessage(fullReply));
        }
    }
}
```

### 9.2 并发安全

`AssistantWriter` 是 **per-request局部变量**，每次请求创建独立实例：

```java
// L0 — BankController
StreamingChatMemoryWriter.AssistantWriter assistantWriter =
    new StreamingChatMemoryWriter.AssistantWriter(chatMemory, sessionId);

// L1 — AbstractDomainService
StreamingChatMemoryWriter.AssistantWriter assistantWriter =
    new StreamingChatMemoryWriter.AssistantWriter(chatMemory, domainSessionId(sessionId));
```

- `StringBuilder contentAccumulator` 是局部变量，不同请求完全独立
- `ChatMemory.add(sessionId, msg)` 内部按sessionId隔离
- L0和L1写不同的ChatMemory bean + 不同的key → 无冲突

### 9.3 写入时序

**流式Graph**：

```
时间   chunk                  累积器              ChatMemory
──────────────────────────────────────────────────────────
t1     CHUNK "根据"           "根据"              (不写)
t2     CHUNK "产品"           "根据产品"          (不写)
t3     CHUNK "信息"           "根据产品信息"      (不写)
t4     COMPLETE               —                   add("根据产品信息")
```

**非流式Graph**：

```
时间   chunk                         累积器    ChatMemory
──────────────────────────────────────────────────────────
t1     COMPLETE {content:"转账成功"}  (空)      add("转账成功！")
```

---

## 10. 实例一：非流式意图 — TRANSFER 转账

### 10.1 场景

用户说 **"帮我给张三转1000块"** → 完整参数，一次执行完成。

### 10.2 L2图结构

```
START → extractParams → paramRouter → executeTransfer → END
                                      ↑ ALL_GOOD（参数齐全）
```

TransferGraph注册时：`bindGraph("TRANSFER", transferGraph)` — 默认非流式。

### 10.3 完整数据流

```
用户: "帮我给张三转1000块"
│
▼ L0: BankController.chat()
│  Accept: text/event-stream
│
├─ ① writeUserMessage(全局chatMemory, "session1", "帮我给张三转1000块")
│     → chatMemory["session1"] += [UserMessage("帮我给张三转1000块")]
│
├─ ② new AssistantWriter(全局chatMemory, "session1")
│
├─ ③ DomainRouter.route("session1", "帮我给张三转1000块", {})
│     关键词"转"命中 → TRANSFER域
│
├─ ④ dispatchToDomain → TransferService.handle()
│     │
│     ▼ L1: SingleSubAgentDomainService
│     │  无activeAgent → handleNewIntention()
│     │
│     ├─ addUserMessage(transferChatMemory, "session1@TransferService", ...)
│     │     → transferChatMemory["session1@TransferService"] += [UserMessage]
│     │
│     ├─ ContextRouter → SWITCH
│     ├─ IntentRouter → TRANSFER, belongsToDomain=true
│     │
│     ├─ new AssistantWriter(transferChatMemory, "session1@TransferService")
│     │
│     └─ executeNewAgent("session1", "TRANSFER", "帮我给张三转1000块")
│           │
│           ├─ generateThreadId → "session1-TRANSFER-a3f2b1"
│           ├─ setActiveAgent("session1", "TRANSFER", "session1-TRANSFER-a3f2b1")
│           │
│           └─ graphExecutionEngine.executeGraph(transferGraph, "TRANSFER", input, threadId)
│                 │
│                 ▼ GES: isStreamable("TRANSFER") = false
│                 │
│                 └─ executeBlocking()
│                      graph.stream(input, config).blockLast()
│                        ↓ (图内部执行)
│                        extractParams → {receiver:"张三", amount:1000}
│                        paramRouter → ALL_GOOD
│                        executeTransfer → {_outputContent:"转账成功！已向张三转入1000元"}
│                        ↓
│                      checkGraphResult() → WorkflowOutput.completed("TRANSFER", "转账成功！已向张三转入1000元")
│                      return Flux.just(StreamChunk.complete("TRANSFER", "转账成功！已向张三转入1000元"))
│                 │
│                 ▼ 返回 Flux<StreamChunk> = [COMPLETE{content="转账成功！"}]
│
│           .doOnNext(chunk → {
│               assistantWriter.onChunk(chunk);
│                 → 累积器为空，从终结chunk取 → transferChatMemory["session1@TransferService"] += [AssistantMessage("转账成功！")]
│               handleActiveAgentState("session1", chunk);
│                 → COMPLETE → clearOwnActiveAgent("session1")
│           })
│
├─ ⑤ .doOnNext(assistantWriter::onChunk)  ← L0的AssistantWriter
│     → 累积器为空，从终结chunk取 → chatMemory["session1"] += [AssistantMessage("转账成功！")]
│
└─ ⑥ SseOutputAdapter.toSse(pipeline)
       → emitter.send({"type":"COMPLETE","intent":"TRANSFER","content":"转账成功！已向张三转入1000元"})
       → emitter.send("[DONE]")
       → emitter.complete()
```

### 10.4 前端收到的SSE

```
data: {"type":"COMPLETE","intent":"TRANSFER","content":"转账成功！已向张三转入1000元"}

data: [DONE]
```

### 10.5 ChatMemory最终状态

| ChatMemory | Key | 内容 |
|------------|-----|------|
| 全局chatMemory | `"session1"` | [User:"帮我给张三转1000块", Assistant:"转账成功！已向张三转入1000元"] |
| transferChatMemory | `"session1@TransferService"` | [User:"帮我给张三转1000块", Assistant:"转账成功！已向张三转入1000元"] |

---

## 11. 实例二：流式意图 — WEALTH_INTERPRET 理财解读

### 11.1 场景

用户说 **"帮我解读一下稳利宝"** → 需要大模型生成长文本解读，Phase 2启用后走流式路径。

### 11.2 L2图结构

```
START → extractParams → paramRouter → executeWealthInterpret → END
                                      ↑ ALL_GOOD（产品名已有）
```

注册时：`bindGraph("WEALTH_INTERPRET", wealthInterpretGraph, true)` — 流式。

**`executeWealthInterpret` 是流式节点**：返回的 Map 中包含 `Flux<ChatResponse>` 值（由 `ChatClient.stream().chatResponse()` 产生），Spring AI Alibaba Graph 的 `NodeExecutor.getEmbedFlux()` 自动检测 Flux 值并包装为 `StreamingOutput`。节点还包含降级逻辑：LLM 调用初始化失败时回退到 mock 服务返回非流式结果。

```java
// WealthInterpretGraphConfig.java — 流式节点核心代码
private Map<String, Object> executeWealthInterpretNode(OverAllState state) {
    String productName = getStringValue(state, "wealthInterpret.productName");
    Map<String, Object> result = new HashMap<>();
    result.put("_outputType", "TEXT");
    result.put("_isFinal", true);

    try {
        String prompt = buildWealthInterpretPrompt(productName);
        Flux<ChatResponse> responseFlux = wealthInterpretChatClient.prompt()
                .user(prompt)
                .stream()
                .chatResponse();
        // Map中的Flux值会被NodeExecutor.getEmbedFlux()自动检测
        result.put("streaming_output", responseFlux);
    } catch (Exception e) {
        // 降级: 使用mock服务返回完整结果(非流式)
        result.put("_outputContent", mockBankingService.wealthInterpret(productName).message());
    }
    return result;
}
```

### 11.3 完整数据流

```
用户: "帮我解读一下稳利宝"
│
▼ L0: BankController.chat()
│  Accept: text/event-stream
│
├─ ① writeUserMessage(全局chatMemory, "session2", "帮我解读一下稳利宝")
│
├─ ② new AssistantWriter(全局chatMemory, "session2")
│
├─ ③ DomainRouter.route("session2", "帮我解读一下稳利宝", {})
│     关键词"解读"命中 → WEALTH域
│
├─ ④ dispatchToDomain → WealthService.handle()
│     │
│     ▼ L1: MultiSubAgentDomainService
│     │  无activeAgent，无消歧 → Phase2 IntentResolver
│     │  识别为 WEALTH_INTERPRET（具体意图，非WEALTH组）
│     │
│     ├─ addUserMessage(wealthChatMemory, "session2@WealthService", ...)
│     ├─ new AssistantWriter(wealthChatMemory, "session2@WealthService")
│     │
│     └─ executeNewAgent("session2", "WEALTH_INTERPRET", "帮我解读一下稳利宝")
│           │
│           └─ graphExecutionEngine.executeGraph(wealthInterpretGraph, "WEALTH_INTERPRET", ...)
│                 │
│                 ▼ GES: isStreamable("WEALTH_INTERPRET") = true
│                 │
│                 └─ executeStreaming()
│                      graph.graphResponseStream(input, config)
│                        ↓ (图内部执行)
│                        extractParams → {productName:"稳利宝"}
│                        paramRouter → ALL_GOOD
│                        executeWealthInterpret → 模型开始流式生成
│                          ↓
│                        GraphResponse流:
│                          [extractParams of()] → extractValue→NodeOutput → null → 过滤
│                          [paramRouter of()]   → extractValue→NodeOutput → null → 过滤
│                          [StreamingOutput "稳利宝" of()]  → extractValue→StreamingOutput → CHUNK "稳利宝"
│                          [StreamingOutput "是一款" of()]  → extractValue→StreamingOutput → CHUNK "是一款"
│                          [StreamingOutput "低风险" of()]  → extractValue→StreamingOutput → CHUNK "低风险"
│                          [StreamingOutput "理财产品" of()]→ extractValue→StreamingOutput → CHUNK "理财产品"
│                          ...更多StreamingOutput chunk...
│                          [FINISHED of()]      → extractValue→StreamingOutput → null → 过滤
│                          [done(completion)]   → extractValue→Map → null → 过滤
│                          ↓
│                        .concatWith(buildStreamingTerminalChunk)
│                          → getState() → nextNode=__END__ → streamingDone()
│
│                 ▼ Flux<StreamChunk>:
│                    CHUNK "稳利宝"
│                    CHUNK "是一款"
│                    CHUNK "低风险"
│                    CHUNK "理财产品"
│                    CHUNK "，"
│                    CHUNK "适合"
│                    CHUNK "保守型"
│                    CHUNK "投资者"
│                    ...
│                    COMPLETE (无content)
│
│           .doOnNext(chunk → {
│               assistantWriter.onChunk(chunk);
│                 → CHUNK: 累积器.append("稳利宝"), .append("是一款"), ...
│                 → COMPLETE: chatMemory.add("稳利宝是一款低风险理财产品，适合保守型投资者，历史...")
│               handleActiveAgentState("session2", chunk);
│                 → COMPLETE → clearOwnActiveAgent
│           })
│
├─ ⑤ .doOnNext(assistantWriter::onChunk)  ← L0的AssistantWriter
│     → CHUNK: 累积器.append("稳利宝"), .append("是一款"), ...
│     → COMPLETE: chatMemory["session2"] += [AssistantMessage("稳利宝是一款低风险...")]
│
└─ ⑥ SseOutputAdapter.toSse(pipeline)
       → emitter.send({"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"稳利宝"})
       → emitter.send({"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"是一款"})
       → emitter.send({"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"低风险"})
       → emitter.send({"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"理财产品"})
       → ...
       → emitter.send({"type":"COMPLETE","intent":"WEALTH_INTERPRET"})
       → emitter.send("[DONE]")
```

### 11.4 前端收到的SSE

```
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"稳利宝"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"是一款"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"低风险"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"理财产品"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"，"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"适合"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"保守型"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"投资者"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"，历史..."}

...更多CHUNK...

data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}

data: [DONE]
```

### 11.5 前端消费代码

```javascript
eventSource.onmessage = (event) => {
    if (event.data === '[DONE]') { eventSource.close(); return; }
    const chunk = JSON.parse(event.data);
    switch (chunk.type) {
        case 'CHUNK':
            appendText(chunk.content);    // 逐字追加显示
            break;
        case 'COMPLETE':
            markDone();                    // 标记完成
            break;
        case 'INTERRUPTED':
            showQuestion(chunk.question);  // 显示系统提问
            break;
    }
};
```

---

## 12. 实例三：模糊意图追问 — WEALTH 消歧

### 12.1 场景

用户说 **"我想理财"** → L0路由到WEALTH域 → L1发现模糊意图 → 消歧追问 → 用户选择。

### 12.2 完整数据流

```
用户第1轮: "我想理财"
│
▼ L0: BankController.chat()
│
├─ DomainRouter.route() → 关键词"理财"命中 → WEALTH域
│
├─ dispatchToDomain → WealthService.handle()
│     │
│     ▼ L1: MultiSubAgentDomainService
│     │  无activeAgent → Phase1 ContextRouter + Phase2 IntentResolver
│     │
│     ├─ Phase1 ContextRouter → SWITCH (新意图)
│     │
│     ├─ Phase2 IntentResolver:
│     │    用户说"理财"→ 匹配到WEALTH意图组，但无法区分是CONSULT还是INTERPRET
│     │    → RoutingResolution.DISAMBIGUATION
│     │    → question="请问您需要理财咨询还是理财产品解读？"
│     │    → candidateIntents=["WEALTH_CONSULT", "WEALTH_INTERPRET"]
│     │
│     ├─ setDisambiguationState("session3", {groupId: "WEALTH"})
│     │
│     └─ return Flux.just(StreamChunk.disambiguation(
│             "请问您需要理财咨询还是理财产品解读？",
│             ["WEALTH_CONSULT", "WEALTH_INTERPRET"]
│         ))
│
├─ L0 AssistantWriter.onChunk(chunk):
│     → 终结chunk type=DISAMBIGUATION
│     → chatMemory["session3"] += [AssistantMessage("请问您需要理财咨询还是理财产品解读？")]
│
└─ SSE输出:
     data: {"type":"DISAMBIGUATION","question":"请问您需要理财咨询还是理财产品解读？","candidateIntents":["WEALTH_CONSULT","WEALTH_INTERPRET"]}
     data: [DONE]

─────────────────────────────────────────────────────────

用户第2轮: "帮我解读一下稳利宝"
│
▼ L0: BankController.chat()
│
├─ DomainRouter.route() → WEALTH域（关键词"解读"命中）
│
├─ dispatchToDomain → WealthService.handle()
│     │
│     ▼ L1: MultiSubAgentDomainService
│     │  isInDisambiguation("session3") = true
│     │
│     ├─ Phase1 ContextRouter → FOLLOW (在回答消歧问题)
│     │  但消歧中 → 不走FOLLOW → 继续Phase2
│     │
│     ├─ Phase2 IntentResolver (消歧模式):
│     │    用户说"解读" → 明确为 WEALTH_INTERPRET
│     │    → RoutingResolution.RESOLVED, intent="WEALTH_INTERPRET"
│     │
│     ├─ clearDisambiguationState("session3")
│     ├─ addUserMessage(...)
│     │
│     └─ executeNewAgent("session3", "WEALTH_INTERPRET", "帮我解读一下稳利宝")
│           │ (后续与实例二相同，走流式或非流式路径)
```

### 12.3 消歧流程图

```
                    用户: "我想理财"
                         │
                    L0 DomainRouter
                    关键词"理财" → WEALTH域
                         │
                    L1 WealthService
                         │
               ┌─────────┴──────────┐
               │   Phase1 ContextRouter   │
               │   → SWITCH (新意图)      │
               └─────────┬──────────┘
                         │
               ┌─────────┴──────────┐
               │   Phase2 IntentResolver  │
               │   "理财" → 匹配WEALTH组  │
               │   但无法区分具体意图       │
               └─────────┬──────────┘
                         │
                  DISAMBIGUATION
                  ┌────────────────────────────────────────────┐
                  │ question: "请问您需要理财咨询还是理财产品解读？"  │
                  │ candidates: [WEALTH_CONSULT, WEALTH_INTERPRET] │
                  └────────────────────────────────────────────┘
                         │
                    前端显示追问
                         │
                    用户: "帮我解读一下稳利宝"
                         │
                    L1 WealthService (消歧中)
                         │
               ┌─────────┴──────────┐
               │   Phase2 IntentResolver  │
               │   "解读" → WEALTH_INTERPRET │
               │   明确！→ RESOLVED         │
               └─────────┬──────────┘
                         │
               executeNewAgent("WEALTH_INTERPRET", ...)
                         │
                   正常执行Graph
```

---

## 13. 关键设计决策汇总

| 决策 | 选择 | 原因 |
|------|------|------|
| L0/L1是否有流式分支 | ❌ 没有 | GES闭环，差异不上泄 |
| 流式如何注册 | `bindGraph(intent, graph, streamable=true)` | L2的固有属性，注册时声明 |
| 流式COMPLETE是否带content | ❌ 不带 | 避免前端重复显示 |
| ChatMemory如何拿完整文本 | AssistantWriter累积所有CHUNK | 终结时一次写入 |
| SSE格式 | OpenAI兼容（data: JSON + data: [DONE]） | 行业标准，前端通用 |
| SSE实现方式 | SseEmitter（Spring MVC） | 项目用MVC，不是WebFlux |
| ChatMemory隔离 | sessionId + "@" + logTag | 4个bean共享Repository，加前缀避免重复 |
| 非流式路径 | `Flux.defer(() → blockLast())` | 对外统一Flux，内部保持已验证路径 |
| GraphResponse提取NodeOutput | `extractValue()`: of()用`getOutput().join()`, done()用`resultValue()` | 流式chunk走of()路径，isDone()=false，resultValue()=null |
| `graphResponseStream(null, config)` | 必须强转 `(Map)null` | 消除方法重载歧义 |
| 终结chunk判断 | `graph.getState()` 检查next+question | 不依赖MODEL_FINISHED（后续节点可能还在执行） |

---

## 14. L0 ChatMemory 写入全流程详解

> 本节专门追踪 L0（BankController）如何记录用户的输入和输出。L0 的 ChatMemory 写入逻辑虽然集中在两行代码，但因为涉及 REROUTE、ChatService 特殊路径、以及 L0/L1 双写机制，容易产生困惑。

### 14.1 L0 写入点只有两个

L0 的 ChatMemory 写入**全部集中在 `buildChatPipeline()` 中**，只有两个写入动作：

```java
// BankController.java — buildChatPipeline()
private Flux<StreamChunk> buildChatPipeline(String sessionId, String userInput) {
    Set<String> excludedDomains = new HashSet<>();
    AtomicInteger rerouteCount = new AtomicInteger(0);

    // ✅ 写入点1：UserMessage — 管道入口，无条件写入
    StreamingChatMemoryWriter.writeUserMessage(chatMemory, sessionId, userInput);

    // ✅ 写入点2：AssistantMessage — 终结chunk时写入
    StreamingChatMemoryWriter.AssistantWriter assistantWriter =
            new StreamingChatMemoryWriter.AssistantWriter(chatMemory, sessionId);

    return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount)
            .doOnNext(assistantWriter::onChunk);   // ← 每个chunk都经过这个Writer
}
```

**写入点1**：`writeUserMessage` — 在管道构建时**立即写入**，不管后续路由到哪个域、是否 REROUTE。

**写入点2**：`AssistantWriter.onChunk` — 在 `doOnNext` 中**延迟写入**，只在收到终结chunk时才写 AssistantMessage。

### 14.2 写入点2的内部逻辑

```java
// StreamingChatMemoryWriter.AssistantWriter
public void onChunk(StreamChunk chunk) {
    if (chunk.getType() == ChunkType.CHUNK) {
        contentAccumulator.append(chunk.getContent());   // 累积文本增量
    }
    if (chunk.isTerminal() && chunk.getType() != ChunkType.REROUTE) {
        writeAssistantMessage(chunk);                     // 终结时写ChatMemory
    }
}

private void writeAssistantMessage(StreamChunk terminalChunk) {
    String fullReply;
    if (contentAccumulator.length() > 0) {
        fullReply = contentAccumulator.toString();         // 流式：用累积器
    } else {
        fullReply = terminalChunk.getReplyContent();       // 非流式：从终结chunk取
    }
    if (fullReply != null && !fullReply.isEmpty()) {
        chatMemory.add(sessionId, new AssistantMessage(fullReply));
    }
}
```

**关键逻辑**：
- **CHUNK 类型**：只累积，不写 ChatMemory
- **REROUTE 类型**：既不累积，也不写 ChatMemory（对用户不可见）
- **其他终结类型**（COMPLETE / INTERRUPTED / DISAMBIGUATION / ERROR）：写 ChatMemory

### 14.3 各终结chunk类型的写入行为

| 终结类型 | `getReplyContent()` 返回 | 是否写 ChatMemory | 说明 |
|----------|--------------------------|-------------------|------|
| `COMPLETE`（非流式） | content 字段值（如"转账成功！"） | ✅ 写 | 正常完成的完整回复 |
| `COMPLETE`（流式） | null | ✅ 写 | 从累积器取拼接文本 |
| `INTERRUPTED` | question 字段值（如"转给谁？"） | ✅ 写 | 系统提问也作为 assistant 记录 |
| `DISAMBIGUATION` | question 字段值（如"咨询还是解读？"） | ✅ 写 | 消歧追问也作为 assistant 记录 |
| `ERROR` | errorMessage 字段值 | ✅ 写 | 错误信息也记录 |
| `REROUTE` | null | ❌ 不写 | L1→L0内部信号，对用户不可见 |

### 14.4 L0 vs L1 ChatMemory 写入对照

L0 和 L1 **各自写各自的 ChatMemory bean**，互不干扰：

```
┌─────────────────────────────────────────────────────────────────┐
│ L0: BankController                                              │
│                                                                 │
│   writeUserMessage(全局chatMemory, "session1", userInput)        │
│   AssistantWriter(全局chatMemory, "session1")                    │
│                                                                 │
│   写入的ChatMemory bean: 全局 chatMemory                          │
│   写入的key: "session1"（原始sessionId）                          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ L1: DomainService (如 TransferService)                          │
│                                                                 │
│   addUserMessage(transferChatMemory, "session1@TransferService") │
│   AssistantWriter(transferChatMemory, "session1@TransferService")│
│                                                                 │
│   写入的ChatMemory bean: 领域 transferChatMemory                  │
│   写入的key: "session1@TransferService"（sessionId + @logTag）    │
└─────────────────────────────────────────────────────────────────┘

底层存储: 都写入同一个 InMemoryChatMemoryRepository
         但 key 不同 → 隔离 → 无重复
```

**同一请求的 ChatMemory 写入结果**：

| ChatMemory | Key | 内容 |
|------------|-----|------|
| 全局chatMemory | `"session1"` | [User, Assistant] |
| transferChatMemory | `"session1@TransferService"` | [User, Assistant] |

两份记录**内容相同但存储隔离**，各自服务不同用途：
- 全局chatMemory → 供 L0 DomainRouter 判断跨域上下文
- 领域chatMemory → 供 L1 ContextRouter/IntentRouter 判断域内路由

### 14.5 ChatService 特殊路径

ChatService 是 L1 中最特殊的域 — 它**没有领域 ChatMemory**，使用全局 ChatMemory 读取历史。

#### 关键代码

```java
// ModelConfig.java — chatChatClient 使用 ReadOnlyMemoryAdvisor
@Bean("chatChatClient")
public ChatClient chatChatClient(@Qualifier("chatChatModel") ChatModel chatModel,
                                  ChatMemory chatMemory) {
    return ChatClient.builder(chatModel)
            .defaultAdvisors(new ReadOnlyMemoryAdvisor(chatMemory))  // ← 只读！不写！
            .build();
}
```

```java
// ChatService.java — advisor 只读历史，不自动写入
var promptBuilder = chatChatClient.prompt()
        .system(SYSTEM_PROMPT)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId));
String content = promptBuilder.call().content();
return Flux.just(StreamChunk.complete("CHAT", content));
```

**`ReadOnlyMemoryAdvisor` 的行为**：
- `before()`：读取 chatMemory.get(sessionId) 注入历史消息到 LLM 上下文
- `before()`：**不**把当前 UserMessage 写入 ChatMemory
- `after()`：**不**把 LLM 回复写入 ChatMemory

所以 ChatService 的 ChatMemory 写入**完全由 L0 的 AssistantWriter 负责**，不存在重复写入问题。

#### ChatService 路径的 ChatMemory 时序

```
用户: "今天天气怎么样"
│
▼ L0: buildChatPipeline()
│
├─ ① writeUserMessage(全局chatMemory, "session1", "今天天气怎么样")
│     → chatMemory["session1"] += [UserMessage("今天天气怎么样")]
│
├─ ② new AssistantWriter(全局chatMemory, "session1")
│
├─ ③ DomainRouter → CHAT域
│
├─ ④ ChatService.handle()
│     │  chatChatClient的ReadOnlyMemoryAdvisor:
│     │    → 读取 chatMemory["session1"] 获取历史（包括刚写入的UserMessage）
│     │    → 不写入任何消息
│     │
│     └─ return Flux.just(StreamChunk.complete("CHAT", "今天天气晴朗，最高气温28度"))
│
├─ ⑤ L0 AssistantWriter.onChunk(COMPLETE{content="今天天气..."})
│     → 累积器为空 → 从终结chunk取 → chatMemory["session1"] += [AssistantMessage("今天天气...")]
│
└─ ⑥ SSE输出

最终 chatMemory["session1"] = [User:"今天天气怎么样", Assistant:"今天天气晴朗，最高气温28度"]
```

**结论**：ChatService 路径不会产生重复写入。`ReadOnlyMemoryAdvisor` 只读不写，L0 的两步写入机制覆盖了 UserMessage 和 AssistantMessage。

### 14.6 REROUTE 路径的 ChatMemory 时序

REROUTE 是最容易让人困惑的场景。关键点：**REROUTE chunk 不写 ChatMemory，只有最终的终结 chunk 才写**。

```
用户: "我想贷款"
│
▼ L0: buildChatPipeline()
│
├─ ① writeUserMessage(全局chatMemory, "session1", "我想贷款")
│     → chatMemory["session1"] += [UserMessage("我想贷款")]
│
├─ ② new AssistantWriter(全局chatMemory, "session1")
│
├─ ③ DomainRouter → WEALTH域
│
├─ ④ WealthService.handle()
│     ├─ addUserMessage(wealthChatMemory, "session1@WealthService", "我想贷款")
│     ├─ IntentResolver → 识别为 LOAN → belongsToDomain=false
│     └─ return Flux.just(StreamChunk.reroute("LOAN", null))
│
│  ┌─ L1 AssistantWriter.onChunk(REROUTE)
│  │  → isTerminal=true 但 type==REROUTE → 不写 ChatMemory ✅
│  └─
│
├─ ⑤ L0 AssistantWriter.onChunk(REROUTE)
│     → isTerminal=true 但 type==REROUTE → 不写 ChatMemory ✅
│
├─ ⑥ L0 flatMap检测到REROUTE → dispatchWithReroute重试
│     → DomainRouter → CHAT域（WEALTH被排除）
│
├─ ⑦ ChatService.handle()
│     → ReadOnlyMemoryAdvisor读取chatMemory["session1"]
│     → return Flux.just(StreamChunk.complete("CHAT", "贷款功能暂不支持..."))
│
├─ ⑧ L0 AssistantWriter.onChunk(COMPLETE{content="贷款功能暂不支持..."})
│     → 累积器为空 → 从终结chunk取 → chatMemory["session1"] += [AssistantMessage("贷款功能暂不支持...")]
│
└─ 最终结果:
     chatMemory["session1"] = [User:"我想贷款", Assistant:"贷款功能暂不支持..."]
     wealthChatMemory["session1@WealthService"] = [User:"我想贷款"]  ← 只有UserMessage，无AssistantMessage
```

**注意**：wealthChatMemory 中只有 UserMessage 没有 AssistantMessage，因为 L1 的 REROUTE 不写 AssistantMessage。这是**预期行为** — REROUTE 对用户不可见，最终的回复由重新路由后的域（如 ChatService）产生。

### 14.7 INTERRUPTED 路径的 ChatMemory 时序

当 L2 子图通过 `interruptBefore` 中断时（如缺少参数需要追问），终结 chunk 类型为 INTERRUPTED。

```
用户: "帮我转账"
│
▼ L0: buildChatPipeline()
│
├─ ① writeUserMessage(全局chatMemory, "session1", "帮我转账")
│     → chatMemory["session1"] += [UserMessage("帮我转账")]
│
├─ ② new AssistantWriter(全局chatMemory, "session1")
│
├─ ③ DomainRouter → TRANSFER域
│
├─ ④ TransferService.handle()
│     ├─ addUserMessage(transferChatMemory, "session1@TransferService", "帮我转账")
│     └─ executeNewAgent → GES → TransferGraph执行
│          extractParams → {无receiver}
│          paramRouter → ASK_RECEIVER → interruptBefore!
│          ↓
│        StreamChunk.interrupted("TRANSFER", "请问您要转给谁？")
│
├─ ⑤ L0 AssistantWriter.onChunk(INTERRUPTED{question="请问您要转给谁？"})
│     → 累积器为空 → getReplyContent() 返回 question
│     → chatMemory["session1"] += [AssistantMessage("请问您要转给谁？")]
│
└─ 最终结果:
     chatMemory["session1"] = [User:"帮我转账", Assistant:"请问您要转给谁？"]
     transferChatMemory["session1@TransferService"] = [User:"帮我转账", Assistant:"请问您要转给谁？"]
```

**下轮对话**（用户回答"张三"）：

```
用户: "张三"
│
▼ L0: buildChatPipeline()
│
├─ ① writeUserMessage(全局chatMemory, "session1", "张三")
│     → chatMemory["session1"] += [UserMessage("张三")]
│
├─ ② new AssistantWriter(全局chatMemory, "session1")
│
├─ ③ DomainRouter → 短回答"张三" + chatHistory有"转给谁" → TRANSFER域
│
├─ ④ TransferService.handle()
│     │  activeAgent存在 + lastQuestion="请问您要转给谁？" → FOLLOW
│     │
│     ├─ addUserMessage(transferChatMemory, "session1@TransferService", "张三")
│     └─ resumeActiveAgent → GES → resumeGraph → TransferGraph继续
│          askReceiver → {receiver:"张三"}
│          paramRouter → ASK_AMOUNT → interruptBefore!
│          ↓
│        StreamChunk.interrupted("TRANSFER", "请问您要转多少金额？")
│
├─ ⑤ L0 AssistantWriter.onChunk(INTERRUPTED{question="请问您要转多少金额？"})
│     → chatMemory["session1"] += [AssistantMessage("请问您要转多少金额？")]
│
└─ chatMemory["session1"] 累计:
     [User:"帮我转账", Assistant:"请问您要转给谁？", User:"张三", Assistant:"请问您要转多少金额？"]
```

### 14.8 L0 ChatMemory 写入全景图

```
                      buildChatPipeline()
                             │
                 ┌───────────┴───────────┐
                 │                        │
          写入点1: UserMessage       写入点2: AssistantWriter
          (立即写入，无条件)          (延迟写入，终结chunk触发)
                 │                        │
                 ▼                        ▼
        chatMemory.add(sessionId,   onChunk() 判断逻辑:
          new UserMessage(input))    ├─ CHUNK → 累积，不写
                                      ├─ REROUTE → 忽略，不写
                                      └─ 其他终结 → 写AssistantMessage
                                           │
                                           ├─ COMPLETE(非流式): 写 content
                                           ├─ COMPLETE(流式): 写累积器拼接文本
                                           ├─ INTERRUPTED: 写 question
                                           ├─ DISAMBIGUATION: 写 question
                                           └─ ERROR: 写 errorMessage
```

### 14.9 4个ChatMemory Bean的写入来源汇总

| ChatMemory Bean | 谁写入 | 写入key | 用途 |
|-----------------|--------|---------|------|
| `chatMemory`（全局） | **L0** BankController | `sessionId` | 全局对话历史，供 L0 DomainRouter 判断跨域上下文 |
| `chatMemory`（全局） | **L1** ChatService（只读） | — | ChatService 通过 ReadOnlyMemoryAdvisor 读取，不写入 |
| `transferChatMemory` | **L1** TransferService | `sessionId@TransferService` | 转账域内对话历史，供 ContextRouter/IntentRouter |
| `wealthChatMemory` | **L1** WealthService | `sessionId@WealthService` | 理财域内对话历史，供 ContextRouter/IntentRouter |
| `billChatMemory` | **L1** BillService | `sessionId@BillService` | 账单域内对话历史，供 ContextRouter/IntentRouter |

**核心规则**：
- L0 **只写**全局 chatMemory，不写领域 chatMemory
- L1 **只写**自己领域的 chatMemory，不写全局 chatMemory（ChatService 除外，它连领域 chatMemory 也不写）
- ChatService 的 ChatMemory 写入**完全由 L0 负责**（通过 ReadOnlyMemoryAdvisor 只读 + L0 AssistantWriter 写入）

---

## 附录：关键类一览

| 类 | 层 | 职责 |
|----|-----|------|
| `ChunkType` | data | chunk类型枚举（CHUNK/COMPLETE/INTERRUPTED等） |
| `StreamChunk` | data | 统一数据载体，工厂方法 + `isTerminal()` + `getReplyContent()` |
| `StreamingChatMemoryWriter` | execution | UserMessage写入 + AssistantWriter（累积+终结时写） |
| `GraphExecutionEngine` | execution | 分流引擎：executeBlocking / executeStreaming |
| `IntentRegistry` | router | 意图注册表，含streamable字段 |
| `SseOutputAdapter` | infrastructure | Flux<StreamChunk> → SseEmitter / JSON |
| `BankController` | controller (L0) | Content Negotiation + REROUTE + ChatMemory写入 |
| `DomainHandler` | domain (L1) | 统一接口：`Flux<StreamChunk> handle()` |
| `AbstractDomainService` | domain (L1) | executeNewAgent / resumeActiveAgent + domainSessionId |
| `SingleSubAgentDomainService` | domain (L1) | 1:1领域（如TRANSFER），无消歧 |
| `MultiSubAgentDomainService` | domain (L1) | 1:N领域（如WEALTH），有消歧+挂起 |
| `ChatService` | domain (L1) | 闲聊，ReadOnlyMemoryAdvisor只读全局ChatMemory，写入由L0负责 |
