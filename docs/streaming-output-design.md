# Streaming Output 设计方案 v6

## 1. 核心设计原则

> **L0/L1统一输出方式：`Flux<StreamChunk>`。所有差别点在GES中闭环。**

- L1和L0的代码中**不存在**关于"是否流式"的分支判断
- L2在注册时声明是否流式（这是Graph的固有属性，不是运行时决策）
- GES是**唯一**读取此声明的层，内部分流处理
- GES对外输出**始终是** `Flux<StreamChunk>`，流式/非流式的差异对上层透明
- 非流式Graph走已验证的 `stream().blockLast()` 路径，不冒险迁移

### 1.1 三个统一

| 维度 | 统一方式 |
|------|----------|
| **接口统一** | L0/L1/GES之间只有一个数据类型 `StreamChunk`，一个传输协议 `Flux` |
| **路径统一** | L1/L0始终走同一条调用链，不存在 `if(streaming)` 分支 |
| **ChatMemory统一** | L0/L1用同一个 `StreamingChatMemoryWriter` 类做addMessage，逻辑完全一致 |

### 1.2 GES闭环原则

```
L2注册声明: SubGraphRegistry.bindGraph(intent, graph, streamable)
              ↓ 只有GES读这个标记
GES内部:
  ├── streamable=false → stream().blockLast() → Flux.just(终结chunk)  ← 已验证路径
  └── streamable=true  → graphResponseStream() → Flux<CHUNK... + 终结chunk>
              ↓ 输出统一
         Flux<StreamChunk>
              ↓
L1: 不读isStreamable，不判断，只透传Flux
              ↓
L0: 不读isStreamable，不判断，只消费Flux（SSE或JSON）
```

### 1.3 SSE标准协议 — OpenAI兼容格式

采用OpenAI/DeepSeek/通义千问通用的SSE流式格式：

- **不使用** `event:` SSE字段，所有事件走 `data:` 行
- JSON内的 `type` 字段区分事件类型（CHUNK/COMPLETE/INTERRUPTED等）
- 流结束时发送 `data: [DONE]` 作为终止信号（与OpenAI一致）

```
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"根据"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"产品信息"}

data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}

data: [DONE]
```

前端消费方式（与接OpenAI/DeepSeek SSE完全一致）：
```javascript
eventSource.onmessage = (event) => {
    if (event.data === '[DONE]') { eventSource.close(); return; }
    const chunk = JSON.parse(event.data);
    switch (chunk.type) {
        case 'CHUNK': appendText(chunk.content); break;
        case 'COMPLETE': markDone(); break;
        case 'INTERRUPTED': showQuestion(chunk.question); break;
        // ...
    }
};
```

---

## 2. 现状

### 2.1 当前全链路阻塞

```
BankController.chat() → DomainHandler.handle() → GES.executeGraph()
                                                       ↓
                                               graph.stream(input, config).blockLast()
                                                       ↓
                                               checkGraphResult() → WorkflowOutput (POJO)
```

### 2.2 ChatMemory架构

```
4个ChatMemory Bean，共享同一个ChatMemoryRepository:

全局chatMemory     — L0 BankController用，记录所有对话
wealthChatMemory   — L1 WealthService用，只记录理财领域消息
transferChatMemory — L1 TransferService用，只记录转账领域消息
billChatMemory     — L1 BillService用，只记录账单领域消息

写入方式: chatMemory.add(sessionId, Message) — sessionId隔离，per session
```

### 2.3 Spring AI Alibaba 已有流式基础设施

| 类/方法 | 作用 |
|---------|------|
| `CompiledGraph.graphResponseStream(Map, RunnableConfig)` | 返回 `Flux<GraphResponse<NodeOutput>>`，流式Graph会发射 `StreamingOutput` |
| `StreamingOutput<T>` extends `NodeOutput` | `chunk()`(String), `getOutputType()`(OutputType) |
| `OutputType` 枚举 | `AGENT_MODEL_STREAMING/FINISHED`, `GRAPH_NODE_STREAMING/FINISHED` |
| `ChatModel.stream(Prompt)` | 返回 `Flux<ChatResponse>` |

---

## 3. 统一架构

### 3.1 数据流（改造后）

```
┌─────────────────────────────────────────────────────────────────┐
│ L0: BankController                                              │
│                                                                 │
│ POST /api/bank/chat                                             │
│   Accept: text/event-stream  → SSE (逐chunk发射)               │
│   Accept: application/json   → JSON (blockLast取终结chunk)      │
│                                                                 │
│ ChatMemory写入 (StreamingChatMemoryWriter):                      │
│   UserMessage: 管道入口写入                                     │
│   CHUNK: 累积到StringBuilder，不写ChatMemory                    │
│   终结chunk: 拼接累积文本 → chatMemory.add(AssistantMessage)    │
└──────────────┬──────────────────────────────────────────────────┘
               │ Flux<StreamChunk>
┌──────────────▼──────────────────────────────────────────────────┐
│ L1: DomainHandler / AbstractDomainService                       │
│                                                                 │
│ Flux<StreamChunk> handle(sessionId, userInput, history)         │
│                                                                 │
│ ChatMemory写入 (StreamingChatMemoryWriter):                      │
│   UserMessage: handle入口写入                                   │
│   CHUNK: 累积到StringBuilder，不写ChatMemory                    │
│   终结chunk: 拼接累积文本 → chatMemory.add(AssistantMessage)    │
│                                                                 │
│ L0和L1用同一个StreamingChatMemoryWriter类，逻辑完全一致          │
│ L1不知道也不关心Graph是否流式                                   │
└──────────────┬──────────────────────────────────────────────────┘
               │ Flux<StreamChunk>
┌──────────────▼──────────────────────────────────────────────────┐
│ GES: GraphExecutionEngine  ← 唯一知道流式/非流式的地方          │
│                                                                 │
│ Flux<StreamChunk> executeGraph(graph, intent, input, tid)       │
│                                                                 │
│ 从SubGraphRegistry读取isStreamable(intent):                       │
│   false → stream().blockLast() → checkGraphResult()             │
│           → Flux.just(StreamChunk.complete(intent, content))    │
│   true  → graphResponseStream() → map/filter                   │
│           → Flux<CHUNK... + StreamChunk.streamingDone(intent)>  │
│                                                                 │
│ 流式COMPLETE不带content（前端已通过CHUNK获得所有文本）           │
│ ChatMemory的完整文本由StreamingChatMemoryWriter从累积器获取      │
└──────────────┬──────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────────┐
│ L2: Graph Nodes                                                 │
│                                                                 │
│ 非流式节点: OverAllState → Map (不变，设置_outputContent)       │
│ 流式节点:   AgentNode/ChatModel.stream()                        │
│           → 框架自动发射StreamingOutput chunk                   │
│                                                                 │
│ 注册: SubGraphRegistry.bindGraph(intent, graph, streamable)       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. 核心数据结构

### 4.1 StreamChunk — 全链路唯一载体

#### 字段可见性设计

| 字段 | 前端可见 | 说明 |
|------|---------|------|
| `type` | ✅ | JSON `type` 字段，区分事件类型（CHUNK/COMPLETE/INTERRUPTED等） |
| `intent` | ✅ | 当前意图标识 |
| `content` | ✅ | **统一文本字段**：CHUNK时=增量文本，COMPLETE时=完整文本 |
| `question` | ✅ | INTERRUPTED/DISAMBIGUATION的提问 |
| `errorMessage` | ✅ | ERROR的错误信息 |
| `candidateIntents` | ✅ | DISAMBIGUATION的候选意图 |
| `rerouteIntent` | ❌ `@JsonIgnore` | L1→L0内部路由信号，前端不可见 |
| `rerouteHint` | ❌ `@JsonIgnore` | L1→L0内部路由信号，前端不可见 |
| `getReplyContent()` | ❌ `@JsonIgnore` | 派生方法，仅内部使用（ChatMemory写入） |

**为什么增量和完整文本都用 `content` 字段**：
- 这是SSE流式输出的标准做法（OpenAI: `delta.content`，Anthropic: `delta.text`）
- 前端只需统一读 `content`，按 `type` 判断语义——CHUNK追加显示，COMPLETE标记结束
- 不需要"CHUNK读chunk字段，COMPLETE读content字段"的特殊适配
- 流式Graph的COMPLETE不带 `content`（前端已通过CHUNK获得全部文本），非流式Graph的COMPLETE带 `content`

**为什么 `getReplyContent()` 不序列化**：
- 派生方法，与 `content`/`question`/`errorMessage` 重复
- 仅供 `StreamingChatMemoryWriter.AssistantWriter` 内部使用，提取写入ChatMemory的文本

**为什么 `rerouteIntent` / `rerouteHint` 不序列化**：
- REROUTE是L1→L0的内部控制信号，BankController收到后直接进入下一轮路由循环
- 前端永远不会收到REROUTE事件（L0拦截处理）
- 即使意外序列化，前端也无法处理，反而暴露内部实现

#### 完整代码

```java
package com.mobileagent.app.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 流式传输单元 — L0/L1/GES之间的唯一数据载体
 *
 * 两类chunk:
 * - 中间态: CHUNK — 流式文本增量，前端追加显示，ChatMemory累积但不写入
 * - 终结态: COMPLETE/INTERRUPTED/DISAMBIGUATION/ERROR/REROUTE
 *   一个Flux<StreamChunk>有且仅有1个终结chunk（最后一个元素）
 *
 * ChatMemory写入（由StreamingChatMemoryWriter统一处理）:
 * - 流式Graph: 终结chunk不带content，完整文本从累积的CHUNK拼接
 * - 非流式Graph: 终结chunk带content，直接使用
 * - 判断逻辑: 累积器有内容用累积器，没有就用终结chunk.getReplyContent()
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamChunk {

    private ChunkType type;
    private String intent;

    /**
     * 统一文本字段
     * - CHUNK时: 增量文本（如 "根据"、"产品信息"、"分析..."）
     * - COMPLETE时（非流式Graph）: 完整文本（如 "转账成功！已向..."）
     * - COMPLETE时（流式Graph）: null（前端已通过CHUNK获得全部文本）
     *
     * 前端统一读 content，按 type 判断是追加(CHUNK)还是显示/结束(COMPLETE)
     */
    private String content;

    /** 提问 (INTERRUPTED/DISAMBIGUATION时) */
    private String question;

    /** 错误信息 (ERROR时) */
    private String errorMessage;

    /** 候选意图 (DISAMBIGUATION时) */
    private List<String> candidateIntents;

    /** REROUTE信息 — 仅L1→L0内部信号，不暴露给前端 */
    @JsonIgnore
    private String rerouteIntent;
    @JsonIgnore
    private String rerouteHint;

    // ==================== 工厂方法 ====================

    /** 流式文本增量 — content承载增量文本 */
    public static StreamChunk chunk(String intent, String text) {
        return StreamChunk.builder().type(ChunkType.CHUNK).intent(intent).content(text).build();
    }

    /** 非流式完成 — content承载完整文本 */
    public static StreamChunk complete(String intent, String content) {
        return StreamChunk.builder().type(ChunkType.COMPLETE).intent(intent).content(content).build();
    }

    /** 流式完成 — 只是结束信号，不带content */
    public static StreamChunk streamingDone(String intent) {
        return StreamChunk.builder().type(ChunkType.COMPLETE).intent(intent).build();
    }

    /** 中断 */
    public static StreamChunk interrupted(String intent, String question) {
        return StreamChunk.builder().type(ChunkType.INTERRUPTED).intent(intent).question(question).build();
    }

    /** 消歧 */
    public static StreamChunk disambiguation(String question, List<String> candidates) {
        return StreamChunk.builder().type(ChunkType.DISAMBIGUATION).question(question).candidateIntents(candidates).build();
    }

    /** 错误 */
    public static StreamChunk error(String errorMessage) {
        return StreamChunk.builder().type(ChunkType.ERROR).errorMessage(errorMessage).build();
    }

    /** 重新路由 */
    public static StreamChunk reroute(String rerouteIntent, String rerouteHint) {
        return StreamChunk.builder().type(ChunkType.REROUTE).rerouteIntent(rerouteIntent).rerouteHint(rerouteHint).build();
    }

    // ==================== 工具方法 ====================

    /** 是否为终结chunk */
    public boolean isTerminal() {
        return type != ChunkType.CHUNK;
    }

    /** 是否为流式Graph的COMPLETE（不带content） */
    public boolean isStreamingComplete() {
        return type == ChunkType.COMPLETE && content == null;
    }

    /** 提取回复内容 — 供ChatMemory写入（只用终结chunk），不序列化给前端 */
    @JsonIgnore
    public String getReplyContent() {
        return switch (type) {
            case COMPLETE -> content;       // 非流式时有值，流式时为null
            case INTERRUPTED, DISAMBIGUATION -> question;
            case ERROR -> errorMessage;
            case CHUNK, REROUTE -> null;
        };
    }

    /** 转换为WorkflowOutput — 仅用于Accept: application/json场景 */
    public WorkflowOutput toWorkflowOutput() {
        return switch (type) {
            case COMPLETE -> WorkflowOutput.completed(intent, content);
            case INTERRUPTED -> WorkflowOutput.interrupted(intent, question);
            case DISAMBIGUATION -> WorkflowOutput.disambiguation(question, candidateIntents);
            case ERROR -> WorkflowOutput.error(errorMessage);
            case REROUTE -> WorkflowOutput.reroute(rerouteIntent, rerouteHint);
            case CHUNK -> WorkflowOutput.completed(intent, content);
        };
    }

    /** 从WorkflowOutput转换 — 非流式路径GES内部使用 */
    public static StreamChunk fromWorkflowOutput(WorkflowOutput output) {
        return switch (output.getStatus()) {
            case COMPLETED -> complete(output.getIntent(), output.getContent());
            case INTERRUPTED -> interrupted(output.getIntent(), output.getQuestion());
            case DISAMBIGUATION -> disambiguation(output.getQuestion(), output.getCandidateIntents());
            case ERROR -> error(output.getErrorMessage());
            case REROUTE -> reroute(output.getRerouteIntent(), output.getRerouteHint());
        };
    }
}
```

### 4.2 ChunkType 枚举

```java
package com.mobileagent.app.data;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ChunkType {
    CHUNK("CHUNK"),               // 流式文本增量 — 中间态
    COMPLETE("COMPLETE"),         // 完成 — 终结态
    INTERRUPTED("INTERRUPTED"),   // 中断 — 终结态
    DISAMBIGUATION("DISAMBIGUATION"), // 消歧 — 终结态
    ERROR("ERROR"),               // 错误 — 终结态
    REROUTE("REROUTE");           // 重新路由 — 终结态（L1→L0信号，不暴露前端）

    private final String value;
    ChunkType(String value) { this.value = value; }
    @JsonValue
    public String getValue() { return value; }
}
```

### 4.3 StreamingChatMemoryWriter — L0/L1统一addMessage机制

> **职责拆分**: `writeUserMessage`（静态方法）+ `AssistantWriter`（内部类）。L0/L1各写各的ChatMemory bean。

#### ChatMemory架构回顾

```
L0写全局chatMemory，L1写领域chatMemory — 不同的bean，不存在重复写入问题。

L0 BankController:
  - UserMessage: 写入全局chatMemory（记录所有对话）
  - AssistantMessage: 写入全局chatMemory（记录最终回复）

L1 DomainService:
  - UserMessage: 写入领域chatMemory（wealthChatMemory/transferChatMemory/billChatMemory）
  - AssistantMessage: 写入领域chatMemory

L0和L1用同一个StreamingChatMemoryWriter类，但传入不同的chatMemory实例。
```

#### 完整代码

```java
package com.mobileagent.app.execution;

import com.mobileagent.app.data.ChunkType;
import com.mobileagent.app.data.StreamChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 流式ChatMemory写入器 — L0和L1共用同一套addMessage逻辑
 *
 * 职责拆分:
 * - writeUserMessage(): 静态方法，在L0管道入口/L1 handle入口调用
 * - AssistantWriter: 内部类，累积CHUNK → 终结chunk时写AssistantMessage
 *
 * 并发安全: AssistantWriter是per-request局部变量，StringBuilder是局部变量
 * ChatMemory.add(sessionId, msg) 内部按sessionId隔离，不同请求完全独立
 *
 * L0/L1写不同的ChatMemory bean，不存在重复写入问题:
 * - L0传入全局chatMemory
 * - L1传入领域chatMemory（wealthChatMemory/transferChatMemory/billChatMemory）
 */
@Slf4j
public class StreamingChatMemoryWriter {

    /**
     * 写入UserMessage — 静态方法，在handle入口处调用
     *
     * L0: buildChatPipeline入口调用，传入全局chatMemory
     * L1: handle入口调用addUserMessage()保持现有调用点，传入领域chatMemory
     */
    public static void writeUserMessage(ChatMemory chatMemory, String sessionId, String userInput) {
        chatMemory.add(sessionId, new UserMessage(userInput));
    }

    /**
     * 流式AssistantMessage写入器 — 每次executeNewAgent/resumeActiveAgent创建独立实例
     *
     * 职责: 累积CHUNK → 终结chunk时拼接完整文本 → chatMemory.add(AssistantMessage)
     * REROUTE chunk不写AssistantMessage（对用户不可见）
     *
     * 时序示例（流式Graph）:
     *   CHUNK "A" → 累积器: "A"        → 不写ChatMemory
     *   CHUNK "B" → 累积器: "AB"       → 不写ChatMemory
     *   CHUNK "C" → 累积器: "ABC"      → 不写ChatMemory
     *   COMPLETE  → chatMemory.add("ABC") ← 一次写入完整文本
     *
     * 时序示例（非流式Graph）:
     *   COMPLETE {content:"转账成功"} → 累积器空 → chatMemory.add("转账成功")
     */
    public static class AssistantWriter {
        private final ChatMemory chatMemory;
        private final String sessionId;
        private final StringBuilder contentAccumulator = new StringBuilder();

        public AssistantWriter(ChatMemory chatMemory, String sessionId) {
            this.chatMemory = chatMemory;
            this.sessionId = sessionId;
        }

        /** 处理每个chunk — 在doOnNext中调用 */
        public void onChunk(StreamChunk chunk) {
            if (chunk.getType() == ChunkType.CHUNK) {
                contentAccumulator.append(chunk.getContent());
            }
            if (chunk.isTerminal() && chunk.getType() != ChunkType.REROUTE) {
                writeAssistantMessage(chunk);
            }
        }

        private void writeAssistantMessage(StreamChunk terminalChunk) {
            String fullReply;
            if (contentAccumulator.length() > 0) {
                // 流式Graph: 拼接所有CHUNK文本
                fullReply = contentAccumulator.toString();
            } else {
                // 非流式Graph: 从终结chunk取完整内容
                fullReply = terminalChunk.getReplyContent();
            }
            if (fullReply != null && !fullReply.isEmpty()) {
                chatMemory.add(sessionId, new AssistantMessage(fullReply));
                log.debug("[AssistantWriter] addMessage: session={}, len={}, source={}",
                        sessionId, fullReply.length(),
                        contentAccumulator.length() > 0 ? "accumulated" : "terminal");
            }
        }
    }
}
```

### 4.4 SseOutputAdapter — SSE传输适配器

> **唯一知道SSE格式的组件。L0/L1/L2完全不感知SSE。**

采用 `SseEmitter`（Spring MVC原生SSE组件），与Spring AI Alibaba官方Admin项目保持一致。

```java
package com.mobileagent.app.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.data.StreamChunk;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * SSE输出适配器 — 唯一知道SSE格式的组件
 *
 * 使用SseEmitter（Spring MVC原生），与Spring AI Alibaba官方做法一致:
 *   Flux<StreamChunk> → subscribe → emitter.send() → emitter.complete()
 *
 * 职责:
 * 1. StreamChunk → JSON字符串 → emitter.send()
 * 2. 流结束时发送 [DONE]（OpenAI格式）
 * 3. 设置SSE响应头
 *
 * 不关心业务语义（isTerminal、ChatMemory等），只做格式转换
 * L0/L1/L2通过StreamChunk的isTerminal()判断流结束，不依赖SSE
 */
@Slf4j
@Component
public class SseOutputAdapter {

    private static final long DEFAULT_TIMEOUT = 0L; // 无超时（由Flux控制生命周期）

    private final ObjectMapper objectMapper;

    public SseOutputAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Flux<StreamChunk> → OpenAI兼容SSE流
     *
     * @param pipeline  业务管道（Flux<StreamChunk>）
     * @param response  HttpServletResponse，用于设置SSE响应头
     * @return SseEmitter — Spring MVC自动处理SSE输出
     *
     * 输出示例:
     *   data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"根据"}
     *   data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"产品信息"}
     *   data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}
     *   data: [DONE]
     */
    public SseEmitter toSse(Flux<StreamChunk> pipeline, HttpServletResponse response) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);

        // 设置SSE响应头（防止Nginx等反向代理缓冲）
        response.addHeader("X-Accel-Buffering", "no");
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);

        pipeline
            .doOnNext(chunk -> {
                try {
                    emitter.send(serialize(chunk), MediaType.TEXT_EVENT_STREAM);
                } catch (Exception e) {
                    log.warn("[SseAdapter] Failed to send chunk: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            })
            .doOnComplete(() -> {
                try {
                    emitter.send("[DONE]", MediaType.TEXT_EVENT_STREAM);
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("[SseAdapter] Failed to send [DONE]: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            })
            .doOnError(emitter::completeWithError)
            .subscribe();

        return emitter;
    }

    /**
     * Flux<StreamChunk> → JSON响应（非SSE模式）
     *
     * 取终结chunk → WorkflowOutput → block返回
     */
    public Object toJson(Flux<StreamChunk> pipeline) {
        return pipeline
                .filter(StreamChunk::isTerminal)
                .last()
                .map(StreamChunk::toWorkflowOutput)
                .block();
    }

    private String serialize(StreamChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"ERROR\",\"errorMessage\":\"JSON serialization failed\"}";
        }
    }
}
```

### 4.5 WorkflowOutput 的角色与字段清理

```
BEFORE: 全链路唯一数据载体
AFTER:  仅作为 Accept: application/json 的视图适配器
```

**字段清理**（与StreamChunk保持一致）：

| 字段 | 处理 | 原因 |
|------|------|------|
| `rerouteIntent` | `@JsonIgnore` | L1→L0内部信号，JSON模式下也不应暴露 |
| `rerouteHint` | `@JsonIgnore` | 同上 |
| `getReplyContent()` | `@JsonIgnore` | 派生方法，COMPLETED时=content重复，INTERRUPTED时=question重复 |

前端按 `status` 判断内容语义：
- COMPLETED → 读 `content`
- INTERRUPTED/DISAMBIGUATION → 读 `question`
- ERROR → 读 `errorMessage`

```
StreamChunk.toWorkflowOutput() → WorkflowOutput
            ↑ 仅在L0层JSON输出时使用

注意: 流式Graph的COMPLETE没有content，toWorkflowOutput()会丢失文本。
解决方案: L0在JSON模式下，取终结chunk时需要考虑累积器。
但JSON模式下非流式Graph的COMPLETE有content，可以正常工作。
流式Graph + JSON模式: 需要在L0层额外处理（见5.4节）
```

---

## 5. 各层详细设计

### 5.1 L2注册 + GES闭环

#### 5.1.1 SubGraphRegistry 扩展

```java
// IntentConfig — 增加streamable标记
@Data
public static class IntentConfig {
    private final String name;
    private final String description;
    private final String paramSchema;
    private final boolean writeOp;
    private final String intentType;
    private final String scope;
    private CompiledGraph graph;
    private boolean streamable;  // ← 新增

    // ... 构造函数不变 ...
}

// 绑定时声明 — 新增重载
public void bindGraph(String intentName, CompiledGraph graph, boolean streamable) {
    IntentConfig config = registry.get(intentName);
    if (config != null) {
        config.setGraph(graph);
        config.setStreamable(streamable);
        log.info("Bound graph to intent: {} (streamable={})", intentName, streamable);
    }
}

// 保留原方法（默认非流式）
public void bindGraph(String intentName, CompiledGraph graph) {
    bindGraph(intentName, graph, false);
}

// GES读取
public boolean isStreamable(String intentName) {
    IntentConfig config = registry.get(intentName);
    return config != null && config.isStreamable();
}
```

#### 5.1.2 注册示例

```java
// GraphConfig 的 @Bean 方法中等价的初始化类
subGraphRegistry.bindGraph("TRANSFER", transferGraph);                        // 默认false
subGraphRegistry.bindGraph("BILL_QUERY", billQueryGraph);                     // 默认false
subGraphRegistry.bindGraph("WEALTH_INTERPRET", wealthInterpretGraph, true);   // 显式true
subGraphRegistry.bindGraph("WEALTH_CONSULT", wealthConsultGraph, true);       // 显式true
```

---

### 5.2 GES: GraphExecutionEngine

#### 5.2.1 核心方法

```java
@Slf4j
@Service
public class GraphExecutionEngine {

    private final SubGraphRegistry subGraphRegistry;

    public GraphExecutionEngine(SubGraphRegistry subGraphRegistry) {
        this.subGraphRegistry = subGraphRegistry;
    }

    /**
     * 执行Graph — 统一返回 Flux<StreamChunk>
     */
    public Flux<StreamChunk> executeGraph(CompiledGraph graph, String intent,
                                           Map<String, Object> input, String threadId) {
        boolean streamable = subGraphRegistry.isStreamable(intent);
        if (!streamable) {
            return executeBlocking(graph, intent, input, threadId);
        }
        return executeStreaming(graph, intent, input, threadId);
    }

    /**
     * 恢复Graph — 统一返回 Flux<StreamChunk>
     */
    public Flux<StreamChunk> resumeGraph(CompiledGraph graph, String intent,
                                          String userInput, String threadId,
                                          Map<String, Object> globalStateData) {
        boolean streamable = subGraphRegistry.isStreamable(intent);
        if (!streamable) {
            return resumeBlocking(graph, intent, userInput, threadId, globalStateData);
        }
        return resumeStreaming(graph, intent, userInput, threadId, globalStateData);
    }
```

#### 5.2.2 非流式路径（与原逻辑完全一致）

```java
    private Flux<StreamChunk> executeBlocking(CompiledGraph graph, String intent,
                                               Map<String, Object> input, String threadId) {
        return Flux.defer(() -> {
            try {
                RunnableConfig config = threadConfig(threadId);
                log.info("[GraphExec] Blocking execute: intent={}, threadId={}", intent, threadId);
                graph.stream(input, config).blockLast();
                WorkflowOutput output = checkGraphResult(graph, config, intent);
                return Flux.just(StreamChunk.fromWorkflowOutput(output));
            } catch (Exception e) {
                log.error("[GraphExec] Blocking execute failed", e);
                return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
            }
        });
    }

    private Flux<StreamChunk> resumeBlocking(CompiledGraph graph, String intent,
                                              String userInput, String threadId,
                                              Map<String, Object> globalStateData) {
        return Flux.defer(() -> {
            try {
                RunnableConfig config = threadConfig(threadId);
                log.info("[GraphExec] Blocking resume: intent={}, threadId={}", intent, threadId);
                Map<String, Object> updateData = new HashMap<>();
                if (globalStateData != null && !globalStateData.isEmpty()) {
                    updateData.put("_globalStateData", globalStateData);
                }
                updateData.put("_latestUserInput", userInput);
                RunnableConfig updatedConfig = graph.updateState(config, updateData, null);
                graph.stream(null, updatedConfig).blockLast();
                WorkflowOutput output = checkGraphResult(graph, config, intent);
                return Flux.just(StreamChunk.fromWorkflowOutput(output));
            } catch (Exception e) {
                log.error("[GraphExec] Blocking resume failed", e);
                return Flux.just(StreamChunk.error("恢复执行出错: " + e.getMessage()));
            }
        });
    }
```

#### 5.2.3 流式路径

```java
    private Flux<StreamChunk> executeStreaming(CompiledGraph graph, String intent,
                                                Map<String, Object> input, String threadId) {
        RunnableConfig config = threadConfig(threadId);
        log.info("[GraphExec] Streaming execute: intent={}, threadId={}", intent, threadId);

        return graph.graphResponseStream(input, config)
            .map(graphResponse -> mapStreamingOutput(graphResponse, intent))
            .filter(Objects::nonNull)
            .concatWith(Flux.defer(() -> {
                StreamChunk terminal = buildStreamingTerminalChunk(graph, config, intent);
                return Flux.just(terminal);
            }))
            .onErrorResume(e -> {
                log.error("[GraphExec] Streaming execute failed", e);
                return Flux.just(StreamChunk.error("执行出错: " + e.getMessage()));
            });
    }

    private Flux<StreamChunk> resumeStreaming(CompiledGraph graph, String intent,
                                               String userInput, String threadId,
                                               Map<String, Object> globalStateData) {
        RunnableConfig config = threadConfig(threadId);
        log.info("[GraphExec] Streaming resume: intent={}, threadId={}", intent, threadId);

        Map<String, Object> updateData = new HashMap<>();
        if (globalStateData != null && !globalStateData.isEmpty()) {
            updateData.put("_globalStateData", globalStateData);
        }
        updateData.put("_latestUserInput", userInput);
        RunnableConfig updatedConfig = graph.updateState(config, updateData, null);

        return graph.graphResponseStream(null, updatedConfig)
            .map(graphResponse -> mapStreamingOutput(graphResponse, intent))
            .filter(Objects::nonNull)
            .concatWith(Flux.defer(() -> {
                StreamChunk terminal = buildStreamingTerminalChunk(graph, config, intent);
                return Flux.just(terminal);
            }))
            .onErrorResume(e -> {
                log.error("[GraphExec] Streaming resume failed", e);
                return Flux.just(StreamChunk.error("恢复执行出错: " + e.getMessage()));
            });
    }
```

#### 5.2.4 流式映射和终结chunk

```java
    /**
     * 映射 StreamingOutput → StreamChunk
     * 只有 AGENT_MODEL_STREAMING / GRAPH_NODE_STREAMING 产生CHUNK
     * 其他类型一律过滤
     */
    private StreamChunk mapStreamingOutput(GraphResponse<NodeOutput> graphResponse,
                                           String intent) {
        NodeOutput output = graphResponse.output();
        if (output instanceof StreamingOutput<?> streaming) {
            OutputType outputType = streaming.getOutputType();
            if (outputType == OutputType.AGENT_MODEL_STREAMING
                    || outputType == OutputType.GRAPH_NODE_STREAMING) {
                String chunk = streaming.chunk();
                if (chunk != null && !chunk.isEmpty()) {
                    return StreamChunk.chunk(intent, chunk);
                }
            }
            return null; // FINISHED / TOOL / HOOK → 过滤
        }
        return null; // 普通NodeOutput（中间节点）→ 过滤
    }

    /**
     * 构建流式路径的终结chunk
     *
     * 与非流式路径的区别:
     * - 非流式: StreamChunk.complete(intent, content) — 携带完整content
     * - 流式:   StreamChunk.streamingDone(intent) — 不带content
     *
     * 为什么流式COMPLETE不带content:
     * 1. 前端已通过CHUNK事件获得了所有文本，COMPLETE只是"结束信号"
     * 2. 如果COMPLETE再带完整content，前端会重复显示
     * 3. ChatMemory的完整文本由StreamingChatMemoryWriter从累积器获取
     *
     * INTERRUPTED不受影响: 无论流式还是非流式，INTERRUPTED都带question
     */
    private StreamChunk buildStreamingTerminalChunk(CompiledGraph graph, RunnableConfig config,
                                                     String intent) {
        try {
            var snapshot = graph.getState(config);
            if (snapshot == null) {
                return StreamChunk.streamingDone(intent);
            }

            String nextNode = snapshot.next();
            OverAllState state = snapshot.state();
            String question = state != null ? (String) state.value("_question").orElse("") : "";

            // interruptBefore中断 — 带question
            if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
                return StreamChunk.interrupted(intent, question);
            }
            // ask→END中断 — 带question
            if (question != null && !question.isEmpty()) {
                return StreamChunk.interrupted(intent, question);
            }

            // 正常完成 — 不带content，只是结束信号
            clearCheckpoint(graph, config, intent);
            return StreamChunk.streamingDone(intent);

        } catch (Exception e) {
            log.error("[GraphExec] Failed to build streaming terminal chunk", e);
            return StreamChunk.streamingDone(intent);
        }
    }

    // ==================== 原有方法保留 ====================

    /** checkGraphResult — 非流式路径复用，逻辑不变 */
    private WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config,
                                             String intent) {
        // 与现有代码完全一致，不改动
    }

    /** clearCheckpoint — 逻辑不变 */
    private void clearCheckpoint(CompiledGraph graph, RunnableConfig config, String intent) {
        // 与现有代码完全一致，不改动
    }

    private RunnableConfig threadConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }
```

---

### 5.3 L1: DomainHandler / AbstractDomainService

#### 5.3.1 DomainHandler 接口

```java
public interface DomainHandler {

    /**
     * 处理消息 — 统一返回 Flux<StreamChunk>
     */
    Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory);

    String getDomainName();
}
```

#### 5.3.2 AbstractDomainService 核心改造

```java
protected Flux<StreamChunk> executeNewAgent(String sessionId, String intent, String rewrittenInput) {
    var graph = subGraphRegistry.getGraph(intent);
    if (graph == null) {
        return Flux.just(StreamChunk.error("Graph not found for intent: " + intent));
    }

    String threadId = generateThreadId(sessionId, intent);
    Map<String, Object> input = buildGraphInput(sessionId, rewrittenInput);
    setOwnActiveAgent(sessionId, intent, threadId);

    // per-request独立的ChatMemory写入器 — 并发安全
    StreamingChatMemoryWriter memoryWriter = new StreamingChatMemoryWriter(chatMemory, sessionId);
    memoryWriter.writeUserMessage(rewrittenInput);

    return graphExecutionEngine.executeGraph(graph, intent, input, threadId)
            .doOnNext(chunk -> {
                // ChatMemory写入（统一机制）
                memoryWriter.onChunk(chunk);
                // activeAgent状态管理（不涉及ChatMemory）
                handleActiveAgentState(sessionId, chunk);
            });
}

protected Flux<StreamChunk> resumeActiveAgent(String sessionId, String userInput, ActiveAgentInfo active) {
    log.info("[{}] FOLLOW: intent={}, threadId={}", logTag, active.getIntent(), active.getThreadId());

    var graph = subGraphRegistry.getGraph(active.getIntent());
    if (graph == null) {
        return Flux.just(StreamChunk.error("Graph not found for intent: " + active.getIntent()));
    }

    Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();

    StreamingChatMemoryWriter memoryWriter = new StreamingChatMemoryWriter(chatMemory, sessionId);
    memoryWriter.writeUserMessage(userInput);

    return graphExecutionEngine.resumeGraph(graph, active.getIntent(), userInput,
                    active.getThreadId(), globalStateData)
            .doOnNext(chunk -> {
                memoryWriter.onChunk(chunk);
                handleActiveAgentState(sessionId, chunk);
            });
}

/**
 * activeAgent状态管理 — 与ChatMemory写入解耦
 */
private void handleActiveAgentState(String sessionId, StreamChunk chunk) {
    if (!chunk.isTerminal()) return;

    ActiveAgentInfo active = getOwnActiveAgent(sessionId);
    if (active == null) return;

    if (chunk.getType() == ChunkType.INTERRUPTED && chunk.getQuestion() != null) {
        active.setLastQuestion(chunk.getQuestion());
    } else if (chunk.getType() == ChunkType.COMPLETE) {
        active.setLastQuestion(null);
        clearOwnActiveAgent(sessionId);
    }
}
```

#### 5.3.3 SingleSubAgentDomainService

```java
@Override
public Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory) {
    log.info("[{}] Handling: sessionId={}, input={}", logTag, sessionId, userInput);

    try {
        ActiveAgentInfo ownActive = getOwnActiveAgent(sessionId);

        if (ownActive != null && ownActive.getLastQuestion() != null) {
            return handleWithLastQuestion(sessionId, userInput, globalChatHistory, ownActive);
        }
        if (ownActive != null) {
            return resumeActiveAgent(sessionId, userInput, ownActive);
        }
        return handleNewIntention(sessionId, userInput, globalChatHistory);

    } catch (Exception e) {
        log.error("[{}] Error handling message", logTag, e);
        return Flux.just(StreamChunk.error("处理请求时出错: " + e.getMessage()));
    }
}
```

**内部方法改造模式**:
- 返回类型: `WorkflowOutput` → `Flux<StreamChunk>`
- REROUTE: `Flux.just(StreamChunk.reroute(...))`
- 同步结果: `Flux.just(StreamChunk.xxx(...))`
- GES调用: `return executeNewAgent(...)` / `return resumeActiveAgent(...)` ← 已返回Flux
- `addUserMessage()` 保持现有调用点不变（L1写领域chatMemory）
- `recordSystemReply()` 移除 → 由 `AssistantWriter.onChunk()` 统一处理
- `saveL2Result()` → 由 `handleActiveAgentState()` 替代（逻辑不变，只是从doOnNext调用）

#### 5.3.4 MultiSubAgentDomainService

同Single模式。注意：
- `handleResume`: 调用 `graphExecutionEngine.resumeGraph()` 返回Flux
- `handleSwitchNew`: 调用 `executeNewAgent()` 返回Flux
- `recordSystemReply` 从方法末尾移除 → 由 `AssistantWriter.onChunk()` 统一处理
- `addUserMessage` 保留在handle入口不变

**L0/L1 ChatMemory写入职责分工（无冲突）**:

| 层 | UserMessage | AssistantMessage | 写入的ChatMemory bean |
|---|---|---|---|
| **L0** BankController | `StreamingChatMemoryWriter.writeUserMessage(全局chatMemory, ...)` | `AssistantWriter.onChunk()` → 写全局chatMemory | `chatMemory`（全局） |
| **L1** DomainService | `addUserMessage()` 保持现有调用点 | `AssistantWriter.onChunk()` → 写领域chatMemory | `wealthChatMemory`/`transferChatMemory`/`billChatMemory` |

> **关键**: L0和L1写的是**不同的ChatMemory bean**，不存在重复写入问题。
> L0写全局chatMemory记录所有对话，L1写领域chatMemory记录领域内对话。
> 两者用同一个 `StreamingChatMemoryWriter` 类，只是传入不同的chatMemory实例。

L1使用：

```java
// handle入口: 写UserMessage到领域chatMemory（保持现有调用点）
addUserMessage(sessionId, userInput);

// executeNewAgent内部: AssistantWriter只处理AssistantMessage
StreamingChatMemoryWriter.AssistantWriter assistantWriter =
        new StreamingChatMemoryWriter.AssistantWriter(chatMemory, sessionId);  // ← 领域chatMemory

return graphExecutionEngine.executeGraph(graph, intent, input, threadId)
        .doOnNext(chunk -> {
            assistantWriter.onChunk(chunk);
            handleActiveAgentState(sessionId, chunk);
        });
```

L0使用：

```java
private Flux<StreamChunk> buildChatPipeline(String sessionId, String userInput) {
    // UserMessage → 全局chatMemory
    StreamingChatMemoryWriter.writeUserMessage(chatMemory, sessionId, userInput);  // ← 全局chatMemory

    // AssistantMessage → 全局chatMemory
    StreamingChatMemoryWriter.AssistantWriter assistantWriter =
            new StreamingChatMemoryWriter.AssistantWriter(chatMemory, sessionId);  // ← 全局chatMemory

    return dispatchWithReroute(...)
            .doOnNext(assistantWriter::onChunk);
}
```

---

### 5.4 L0: BankController

#### 5.4.1 统一端点

```java
private final SseOutputAdapter sseAdapter; // 注入

@PostMapping(value = "/chat", produces = {
        MediaType.TEXT_EVENT_STREAM_VALUE,
        MediaType.APPLICATION_JSON_VALUE
})
public Object chat(@RequestParam String sessionId,
                   @RequestBody Map<String, String> req,
                   @RequestHeader(value = "Accept", defaultValue = MediaType.APPLICATION_JSON_VALUE) String accept,
                   HttpServletResponse response) {

    String userInput = req.get("message");
    if (userInput == null || userInput.isBlank()) {
        StreamChunk error = StreamChunk.error("Message cannot be empty");
        if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return sseAdapter.toSse(Flux.just(error), response);
        }
        return error.toWorkflowOutput();
    }

    Flux<StreamChunk> pipeline = buildChatPipeline(sessionId, userInput);

    if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
        return sseAdapter.toSse(pipeline, response);
    }

    return sseAdapter.toJson(pipeline);
}
```

**BankController完全不接触SSE格式细节**：`toSse()` / `toJson()` 由 `SseOutputAdapter` 封装。
BankController只关心业务逻辑：构建管道、写ChatMemory、处理reroute。

#### 5.4.2 管道构建

```java
private Flux<StreamChunk> buildChatPipeline(String sessionId, String userInput) {
    Set<String> excludedDomains = new HashSet<>();
    AtomicInteger rerouteCount = new AtomicInteger(0);

    // UserMessage: 管道入口写入
    StreamingChatMemoryWriter.writeUserMessage(chatMemory, sessionId, userInput);

    // AssistantMessage: 累积CHUNK + 终结chunk时写入
    StreamingChatMemoryWriter.AssistantWriter assistantWriter =
            new StreamingChatMemoryWriter.AssistantWriter(chatMemory, sessionId);

    return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount)
            .doOnNext(assistantWriter::onChunk);
}
```

#### 5.4.3 REROUTE处理

```java
private Flux<StreamChunk> dispatchWithReroute(String sessionId, String userInput,
                                                Set<String> excludedDomains,
                                                AtomicInteger rerouteCount) {
    DomainRouter.DomainResult domainResult = domainRouter.route(sessionId, userInput, excludedDomains);
    String currentDomain = domainResult.domain();

    if (excludedDomains.contains(currentDomain)) {
        rerouteCount.incrementAndGet();
        if (rerouteCount.get() >= maxRerouteAttempts) {
            return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求，请换个方式描述。"));
        }
        return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount);
    }

    String globalChatHistory = ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, globalContextMaxPairs);
    DomainHandler handler = domainServiceRegistry.getHandler(currentDomain);
    if (handler == null) handler = domainServiceRegistry.getHandler("CHAT");
    if (handler == null) return Flux.just(StreamChunk.complete(null, "功能暂不支持"));

    if (domainResult.isUnsupported()) {
        String feature = domainResult.unsupportedFeature() != null ? domainResult.unsupportedFeature() : "该";
        return Flux.just(StreamChunk.complete(null, feature + "功能暂不支持"));
    }

    Flux<StreamChunk> resultFlux = handler.handle(sessionId, userInput, globalChatHistory);

    return resultFlux.flatMap(chunk -> {
        if (chunk.getType() != ChunkType.REROUTE) {
            return Flux.just(chunk);
        }
        excludedDomains.add(currentDomain);
        rerouteCount.incrementAndGet();
        if (rerouteCount.get() >= maxRerouteAttempts) {
            return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求，请换个方式描述。"));
        }
        return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount);
    });
}
```

**REROUTE时序与ChatMemory**：

```
L1返回REROUTE chunk
  → doOnNext(assistantWriter::onChunk)  ← REROUTE被跳过，不写AssistantMessage ✅
  → flatMap拦截REROUTE
  → 递归dispatchWithReroute，路由到新domain
  → 新domain的L1返回最终结果(COMPLETE/INTERRUPTED)
  → doOnNext(assistantWriter::onChunk)  ← 最终结果写入AssistantMessage ✅

时序示例（REROUTE: BILL → WEALTH）:
  REROUTE chunk → onChunk跳过 → flatMap拦截 → 重新路由
  COMPLETE {content:"理财解读..."} → onChunk写AssistantMessage → 透传给SseEmitter
```

**与当前代码的对比**：
- 当前：while循环同步处理REROUTE，循环结束后统一写ChatMemory
- 改造后：flatMap异步处理REROUTE，REROUTE chunk不写ChatMemory，最终结果的AssistantMessage由AssistantWriter写
- **效果一致**：ChatMemory只记录最终结果，不记录中间REROUTE

---

### 5.5 L2: 流式节点改造（Phase 2）

#### 5.5.1 关键约束

流式节点完成时**不依赖** `_outputContent` 写入ChatMemory（ChatMemory由 `StreamingChatMemoryWriter` 从累积CHUNK获取）。但 `_outputContent` 仍建议设置，供其他用途（如日志、调试）。

#### 5.5.2 AgentNode方案（Phase 2推荐）

```java
@Bean("wealthInterpretGraph")
public CompiledGraph wealthInterpretGraph() throws GraphStateException {
    StateGraph graph = new StateGraph(createKeyStrategyFactory())
            .addNode("extractParams", node_async(this::extractParamsNode))
            .addNode("paramRouter", node_async(this::paramRouterNode))
            .addNode("askProductName", askNode("askProductName", this::askProductNameLogic))
            .addNode("executeWealthInterpret",
                AgentNode.builder()
                    .chatModel(interpretChatModel)
                    .systemMessageProvider(this::buildInterpretSystemPrompt)
                    .outputKey("_outputContent")
                    .build())
            // ... 其余不变 ...

    return graph.compile(createInterruptCompileConfig());
}
```

#### 5.5.3 降级方案（Phase 1用）

节点内部 `ChatModel.stream().block()` + 手动设置 `_outputContent`。无真正流式效果，但管道已通。

---

### 5.6 ChatService改造

`ChatService` 也实现了 `DomainHandler`，需要同步改造 `handle()` 返回 `Flux<StreamChunk>`。

**特殊处理**: ChatService**没有自己的领域chatMemory**，它用 `chatChatClient` 的 `ChatMemory.CONVERSATION_ID` advisor自动管理对话历史。因此：
- **不需要** `AssistantWriter`（advisor自动写AssistantMessage）
- **不需要** `StreamingChatMemoryWriter.writeUserMessage()`（advisor自动写UserMessage）
- 只需包装为单元素Flux

```java
@Override
public Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory) {
    log.info("[ChatService] Handling: sessionId={}, input={}", sessionId, userInput);

    try {
        var promptBuilder = chatChatClient.prompt()
                .system(SYSTEM_PROMPT)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId));

        if (globalChatHistory != null && !globalChatHistory.isBlank()) {
            promptBuilder = promptBuilder.user(
                    "===跨域对话历史(参考)===\n" + globalChatHistory + "\n===历史结束===\n\n用户当前消息: " + userInput);
        } else {
            promptBuilder = promptBuilder.user(userInput);
        }

        String reply = promptBuilder.call().content();

        // ChatService用advisor自动管理ChatMemory，不需要AssistantWriter
        return Flux.just(StreamChunk.complete("CHAT", reply));

    } catch (Exception e) {
        log.error("[ChatService] Error handling chat", e);
        return Flux.just(StreamChunk.error("闲聊服务出错: " + e.getMessage()));
    }
}
```

> **注意**: ChatService的ChatMemory由advisor自动写入（UserMessage+AssistantMessage），
> 不经过L1的 `addUserMessage()` / `AssistantWriter`。这是特例——只有ChatService走advisor，
> 其他L1 Service都走 `AssistantWriter`。

---

## 6. SSE协议格式（OpenAI兼容）

> 采用OpenAI/DeepSeek/通义千问通用的SSE流式格式：不设 `event:` 字段，`data:` 承载JSON，`[DONE]` 终止。

### 6.1 流式Graph（WealthInterpret）

```
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"根据"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"产品信息"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"分析..."}

data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}

data: [DONE]
```

前端行为: `onmessage` → 解析JSON → `type=CHUNK` 追加 `content` → `type=COMPLETE` 标记结束 → `[DONE]` 关闭连接。

### 6.2 非流式Graph（Transfer）

```
data: {"type":"COMPLETE","intent":"TRANSFER","content":"转账成功：100元 → 张三"}

data: [DONE]
```

前端行为: 没收到过CHUNK → COMPLETE有 `content` → 直接显示 → `[DONE]` 关闭连接。

### 6.3 中断（INTERRUPTED）

```
data: {"type":"INTERRUPTED","intent":"TRANSFER","question":"请问您要转账给谁？"}

data: [DONE]
```

### 6.4 消歧（DISAMBIGUATION）

```
data: {"type":"DISAMBIGUATION","question":"您是指哪种转账？","candidateIntents":["TRANSFER","WEALTH_CONSULT"]}

data: [DONE]
```

### 6.5 REROUTE

不暴露给前端。L0拦截后递归重新路由。`rerouteIntent`/`rerouteHint` 字段标注 `@JsonIgnore`，即使REROUTE chunk意外到达前端也不会泄露内部路由信息。

### 6.6 ChatMemory写入时序（流式Graph）

```
CHUNK "A"  → 累积器: "A"         → 不写ChatMemory
CHUNK "B"  → 累积器: "AB"        → 不写ChatMemory
CHUNK "C"  → 累积器: "ABC"       → 不写ChatMemory
COMPLETE   → chatMemory.add("ABC") ← 一次写入完整AssistantMessage
```

### 6.7 ChatMemory写入时序（非流式Graph）

```
COMPLETE {content:"转账成功"} → 累积器空 → chatMemory.add("转账成功")
```

---

## 7. 流式/非流式Graph分类

| Graph | streamable | 原因 |
|-------|-----------|------|
| TransferGraph | false | 转账结果简短，无需流式 |
| BillQueryGraph | false | 账单数据结构化，无需流式 |
| WealthInterpretGraph | true | 解读是长文本生成，需要逐token展示 |
| WealthConsultGraph | true | 咨询推荐是长文本，需要流式 |

---

## 8. 实施路径

### Phase 1: 管道搭建 + 全量回归

1. 新增 `StreamChunk` + `ChunkType`
2. 新增 `StreamingChatMemoryWriter`（含 `AssistantWriter` 内部类）
3. `SubGraphRegistry` 增加 `streamable` 字段和 `isStreamable()`
4. GES: `executeGraph`/`resumeGraph` 返回 `Flux<StreamChunk>`
   - 非流式路径: 包装现有逻辑
   - 流式路径: 骨架（暂无Graph声明streamable=true）
5. `DomainHandler.handle()` 返回 `Flux<StreamChunk>`
6. `AbstractDomainService` 改造: `executeNewAgent`/`resumeActiveAgent` 返回Flux + `AssistantWriter`
7. `SingleSubAgentDomainService` / `MultiSubAgentDomainService` 改造
8. `ChatService` 改造
9. `BankController` 改造: Content Negotiation + `AssistantWriter`
10. **全量回归**: 所有4个Graph行为与当前完全一致

### Phase 2: WealthInterpret 流式节点

1. 引入 `interpretChatModel` Bean
2. `WealthInterpretGraphConfig` 改用 AgentNode（或降级方案）
3. 注册时声明 `streamable=true`
4. **验证**: SSE逐chunk接收理财产品解读文本

### Phase 3: WealthConsult 流式节点

1. `WealthConsultGraphConfig` 改造 + 注册声明 `streamable=true`
2. **验证**: 理财咨询推荐也能流式输出

### Phase 4: 生产级完善

1. 流式输出取消（客户端断开 → `Flux.doOnCancel()`）
2. 异常中断时 ChatMemory 兜底
3. JSON模式下流式Graph的完整文本获取
4. 前端SSE SDK对接

---

## 9. 文件变更清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `data/StreamChunk.java` | 全链路唯一数据载体 |
| `data/ChunkType.java` | chunk类型枚举 |
| `execution/StreamingChatMemoryWriter.java` | L0/L1统一addMessage机制 |
| `infrastructure/SseOutputAdapter.java` | SSE传输适配器（SseEmitter，唯一知道SSE格式的组件） |

### 修改文件

| 文件 | 变更 |
|------|------|
| `GraphExecutionEngine.java` | 返回 `Flux<StreamChunk>`，新增流式路径，依赖 `SubGraphRegistry` |
| `SubGraphRegistry.java` | `IntentConfig` 增加 `streamable`，新增 `isStreamable()` |
| `DomainHandler.java` | `handle()` 返回 `Flux<StreamChunk>` |
| `AbstractDomainService.java` | `executeNewAgent`/`resumeActiveAgent` 返回Flux + `AssistantWriter` + `handleActiveAgentState` |
| `SingleSubAgentDomainService.java` | `handle()` 及内部方法返回Flux，移除 `recordSystemReply` |
| `MultiSubAgentDomainService.java` | `handle()` 及内部方法返回Flux，移除 `recordSystemReply` |
| `BankController.java` | Content Negotiation + `SseOutputAdapter` + `AssistantWriter` + REROUTE在Flux中处理 |
| `ChatService.java` | `handle()` 返回 `Flux<StreamChunk>` |
| `WorkflowOutput.java` | `rerouteIntent`/`rerouteHint`/`getReplyContent()` 加 `@JsonIgnore` |

### 不变文件

| 文件 | 原因 |
|------|------|
| `TransferGraphConfig.java` | 非流式，零改动 |
| `BillQueryGraphConfig.java` | 非流式，零改动 |
| `WealthInterpretGraphConfig.java` | Phase 2才改 |
| `WorkflowStatus.java` | 保留 |
| `ChatHistoryUtils.java` | 保留，`recordReply` 不再被L0/L1调用（由AssistantWriter替代） |

---

## 10. 风险与对策

### 10.1 graphResponseStream() 可用性

**对策**: Phase 1 只走非流式路径。Phase 2 启用流式路径。

### 10.2 AgentNode 可用性

**对策**: 降级方案（`ChatModel.stream().block()` + 手动设置 `_outputContent`）。

### 10.3 JSON模式下流式Graph

**风险**: 流式Graph的COMPLETE没有content，`toWorkflowOutput()` 会丢失文本。

**对策**: Phase 1 仅保证非流式Graph在JSON模式下正确。流式Graph推荐走SSE。Phase 4 再处理JSON模式下的流式Graph。

### 10.4 ChatMemory并发

**风险**: 多用户并发时，不同session的ChatMemory写入是否隔离？

**对策**: `AssistantWriter` 是per-request局部变量，`StringBuilder` 是局部变量。`ChatMemory.add(sessionId, msg)` 内部按sessionId隔离。不同请求完全独立。

### 10.5 ChatMemory写入职责

L0和L1写**不同的ChatMemory bean**，不存在重复写入：
- L0写全局chatMemory（`StreamingChatMemoryWriter.writeUserMessage` + `AssistantWriter`）
- L1写领域chatMemory（`addUserMessage()` + `AssistantWriter`）
- ChatService用advisor自动管理，不经过 `AssistantWriter`

### 10.6 ChatHistoryUtils.recordReply

**现状**: L0和L1都通过 `recordSystemReply()` → `ChatHistoryUtils.recordReply()` 写ChatMemory。

**改造后**: `recordReply` 不再被调用，由 `AssistantWriter.onChunk()` 统一处理。`ChatHistoryUtils` 保留但不再用于ChatMemory写入，仅用于 `formatAndTruncate()` 读取历史。

---

## 11. Postman验证指南

### 11.1 Phase 1 验证（所有Graph非流式）

Phase 1 完成后，所有Graph仍走非流式路径，行为与改造前一致。验证目标是确认管道搭建正确、两种输出模式都工作。

#### 11.1.1 JSON模式验证（与当前行为一致）

**请求配置**：

```
POST http://localhost:8080/api/bank/chat?sessionId=test-session-1
Content-Type: application/json
Accept: application/json

Body (raw JSON):
{
    "message": "帮我转账100元给张三"
}
```

**预期响应**：

```json
{
    "status": "COMPLETED",
    "content": "转账成功！已向张三转账100元。交易流水号：TXN...",
    "question": null,
    "intent": "TRANSFER",
    "errorMessage": null,
    "candidateIntents": null
}
```

**验证点**：
- ✅ 返回JSON对象，不是SSE流
- ✅ status/content/intent 字段正确
- ✅ rerouteIntent/rerouteHint/replyContent 不出现（@JsonIgnore）

#### 11.1.2 SSE模式验证（非流式Graph的SSE输出）

**请求配置**：

```
POST http://localhost:8080/api/bank/chat?sessionId=test-session-2
Content-Type: application/json
Accept: text/event-stream

Body (raw JSON):
{
    "message": "查询我的账单"
}
```

**Postman操作**：在Response区域选择"Stream"按钮查看SSE事件流。

**预期响应**（SSE格式）：

```
data: {"type":"COMPLETE","intent":"BILL_QUERY","content":"您最近3笔账单：..."}

data: [DONE]
```

**验证点**：
- ✅ 只有1条COMPLETE事件（非流式Graph没有CHUNK）
- ✅ 最后是 `data: [DONE]`
- ✅ 没有 `event:` 字段（OpenAI格式）
- ✅ COMPLETE携带content（非流式Graph）

#### 11.1.3 中断场景验证（INTERRUPTED）

**请求配置**：

```
POST http://localhost:8080/api/bank/chat?sessionId=test-session-3
Accept: text/event-stream

Body: {"message": "帮我转账"}
```

**预期SSE响应**：

```
data: {"type":"INTERRUPTED","intent":"TRANSFER","question":"请问您要转账给谁？"}

data: [DONE]
```

**第二次请求**（回复中断问题）：

```
POST http://localhost:8080/api/bank/chat?sessionId=test-session-3
Accept: text/event-stream

Body: {"message": "张三"}
```

**预期**：返回COMPLETE。

#### 11.1.4 REROUTE场景验证

**请求配置**：

```
POST http://localhost:8080/api/bank/chat?sessionId=test-session-4
Accept: text/event-stream

Body: {"message": "我想买理财产品但不知道选哪个"}
```

**预期**：如果L1 REROUTE，前端看不到REROUTE事件——只看到最终路由结果的COMPLETE。

**验证点**：
- ✅ 前端不收到REROUTE事件
- ✅ 只收到最终结果的COMPLETE/INTERRUPTED
- ✅ 全局chatMemory只记录最终结果（不记录中间REROUTE）

#### 11.1.5 ChatMemory写入验证

**验证方法**：发送2轮对话后，通过 `/api/bank/state` 接口查看session状态，确认：
- 全局chatMemory有2条UserMessage + 2条AssistantMessage
- 领域chatMemory有2条UserMessage + 2条AssistantMessage

### 11.2 Phase 2 验证（WealthInterpret流式）

Phase 2 启用流式Graph后，验证SSE逐chunk输出。

**请求配置**：

```
POST http://localhost:8080/api/bank/chat?sessionId=test-session-5
Accept: text/event-stream

Body: {"message": "解读一下朝朝宝这款理财产品"}
```

**预期SSE响应**：

```
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"朝朝宝"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"是一款"}

data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"低风险..."}

data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}

data: [DONE]
```

**验证点**：
- ✅ 多个CHUNK事件，content逐条追加
- ✅ COMPLETE不带content（流式Graph）
- ✅ 最后是 `data: [DONE]`
- ✅ 全局chatMemory写入完整的AssistantMessage（所有CHUNK拼接后的文本）

### 11.3 Postman注意事项

1. **SSE模式必须选Stream**：Postman默认等待完整响应，SSE需要点击Response区域的"Stream"按钮
2. **超时设置**：SSE请求可能耗时较长，在Postman Settings → Request timeout 设置为60s+
3. **Accept header必须设置**：`text/event-stream` 触发SSE模式，`application/json` 触发JSON模式
4. **sessionId一致性**：中断/恢复场景必须用同一个sessionId
