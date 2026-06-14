# 附录：Spring AI Alibaba 中断机制源码级详解

> **基于版本**: Spring AI Alibaba 1.1.2.0  
> **验证方式**: graph-core + agent-framework 源码反编译(javap -p -c)  
> **目的**: 为架构决策提供源码级依据，而非依赖文档推断

---

## A.1 中断机制全景

Spring AI Alibaba 的中断机制分为**两条独立路径**，分别作用于不同层级：

```
路径A: 静态配置中断 (编译时声明)
  CompileConfig.interruptsBefore / interruptsAfter (Set<String>)
  → GraphRunnerContext.shouldInterruptBefore() / shouldInterruptAfter()
  → 按节点名匹配，在节点执行前/后暂停图

路径B: 动态InterruptableAction中断 (运行时触发)
  节点实现 InterruptableAction 接口
  → NodeExecutor.executeNode() 在执行前检查 instanceof
  → 调用 interrupt() / interruptAfter()
  → 如果返回 InterruptionMetadata → 图暂停

子图中断传播:
  子图内部 InterruptableAction 节点触发中断
  → InterruptionMetadata 通过 Flux<GraphResponse> 流嵌入父图
  → NodeExecutor.processGraphResponseFlux() 检测
  → context.setReturnFromEmbedWithValue()
  → MainGraphExecutor 下一轮检测 returnFromEmbed → 父图暂停
```

---

## A.2 路径A：静态配置中断

### A.2.1 数据结构

```java
// CompileConfig.java
public class CompileConfig {
    private Set<String> interruptsBefore;  // 在这些节点执行前暂停
    private Set<String> interruptsAfter;   // 在这些节点执行后暂停
    private boolean interruptBeforeEdge;   // 是否在边路由前中断
}
```

### A.2.2 检查逻辑

```java
// GraphRunnerContext.java — 源码还原自字节码

public boolean shouldInterrupt() {
    return shouldInterruptBefore(nextNodeId, currentNodeId) 
        || shouldInterruptAfter(currentNodeId, nextNodeId);
}

private boolean shouldInterruptBefore(String nextNode, String currentNode) {
    if (nextNode == null) return false;
    return compiledGraph.compileConfig.interruptsBefore().contains(nextNode);
}

private boolean shouldInterruptAfter(String currentNode, String nextNode) {
    if (currentNode == null || Objects.equals(currentNode, nextNode)) return false;
    
    // 如果启用了 interruptBeforeEdge，且当前节点是 __INTERRUPTED__，则中断
    if (compiledGraph.compileConfig.interruptBeforeEdge() 
        && Objects.equals(currentNode, "__INTERRUPTED__")) return true;
    
    return compiledGraph.compileConfig.interruptsAfter().contains(currentNode);
}
```

### A.2.3 使用位置

`MainGraphExecutor.execute()` 在每轮循环中检查：

```java
// MainGraphExecutor.execute() — 源码还原
public Flux<GraphResponse<NodeOutput>> execute(GraphRunnerContext context, AtomicReference<Object> resultValue) {
    // ... shouldStop / returnFromEmbed 检查 ...
    
    if (context.shouldInterrupt()) {
        InterruptionMetadata metadata = InterruptionMetadata.builder(
            context.getCurrentNodeId(), 
            context.cloneState(context.getCurrentStateData())
        ).build();
        return Flux.just(GraphResponse.done(metadata));
    }
    
    // 正常执行节点
    return nodeExecutor.execute(context, resultValue);
}
```

### A.2.4 特点

- **编译时确定**：哪些节点前后暂停，在图编译时就确定了
- **按节点名匹配**：不是按接口类型，是按字符串节点名
- **与InterruptableAction无关**：路径A完全不检查节点是否实现InterruptableAction
- **用途**：框架级别的"断点"机制，用于调试和HITL场景

---

## A.3 路径B：动态InterruptableAction中断

### A.3.1 接口定义

```java
// InterruptableAction.java — graph-core 完整源码
public interface InterruptableAction {
    /**
     * 在节点执行前调用。
     * 如果返回非空Optional，图暂停，节点不执行。
     */
    Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config);
    
    /**
     * 在节点执行后调用（默认返回empty）。
     * 如果返回非空Optional，图暂停。
     */
    default Optional<InterruptionMetadata> interruptAfter(
        String nodeId, OverAllState state, 
        Map<String, Object> actionResult, RunnableConfig config) {
        return Optional.empty();
    }
}
```

### A.3.2 执行前中断检查

```java
// NodeExecutor.executeNode() — 源码还原自字节码

private Flux<GraphResponse<NodeOutput>> executeNode(
    GraphRunnerContext context, AtomicReference<Object> resultValue) {
    
    String currentNodeId = context.getCurrentNodeId();
    AsyncNodeActionWithConfig nodeAction = context.getNodeAction(currentNodeId);
    
    if (nodeAction == null) {
        return Flux.just(GraphResponse.error(...));
    }
    
    // ===== 执行前中断检查 =====
    if (nodeAction instanceof InterruptableAction) {
        // 检查是否有 STATE_UPDATE metadata（用于恢复时注入状态）
        context.getConfig().metadata("STATE_UPDATE").ifPresent(stateUpdate -> {
            context.mergeIntoCurrentState((Map<String, Object>) stateUpdate);
        });
        
        // 调用 interrupt()
        Optional<InterruptionMetadata> interruption = 
            ((InterruptableAction) nodeAction).interrupt(
                currentNodeId, 
                context.cloneState(context.getCurrentStateData()), 
                context.getConfig()
            );
        
        if (interruption.isPresent()) {
            resultValue.set(interruption.get());
            return Flux.just(GraphResponse.done(interruption.get()));  // 图暂停！
        }
    }
    
    // ===== 正常执行节点 =====
    context.doListeners("__NODE_BEFORE__", null);
    CompletableFuture<Map<String, Object>> result = 
        nodeAction.apply(context.getOverallState(), context.getConfig());
    
    return Mono.fromFuture(result)
        .flatMapMany(deltaState -> handleActionResult(context, deltaState, resultValue))
        .onErrorResume(e -> Flux.just(GraphResponse.error(e)));
}
```

### A.3.3 执行后中断检查

```java
// NodeExecutor.handleActionResult() — 非流式结果处理分支 — 源码还原

// 在处理完节点执行结果后：
AsyncNodeActionWithConfig nodeAction = context.getNodeAction(currentNodeId);

if (nodeAction instanceof InterruptableAction) {
    Optional<InterruptionMetadata> interruption = 
        ((InterruptableAction) nodeAction).interruptAfter(
            currentNodeId, 
            context.cloneState(context.getCurrentStateData()),
            deltaState,  // 节点执行结果
            context.getConfig()
        );
    
    if (interruption.isPresent()) {
        context.mergeIntoCurrentState(deltaState);
        Command nextCommand = context.nextNodeId(currentNodeId, deltaState);
        context.setNextNodeId(nextCommand.gotoNode());
        context.buildNodeOutputAndAddCheckpoint(deltaState);
        context.doListeners("__NODE_AFTER__", null);
        
        resultValue.set(interruption.get());
        return Flux.just(GraphResponse.done(interruption.get()));  // 图暂停！
    }
}

// 没有中断 → 正常继续
context.mergeIntoCurrentState(deltaState);
// ... 确定下一个节点，继续执行
```

### A.3.4 流式输出的中断检查

```java
// NodeExecutor.interruptAfterForStreaming() — 源码还原

private Optional<InterruptionMetadata> interruptAfterForStreaming(
    GraphRunnerContext context, Map<String, Object> deltaState) {
    
    String currentNodeId = context.getCurrentNodeId();
    AsyncNodeActionWithConfig nodeAction = context.getNodeAction(currentNodeId);
    
    if (nodeAction instanceof InterruptableAction) {
        return ((InterruptableAction) nodeAction).interruptAfter(
            currentNodeId,
            context.cloneState(context.getCurrentStateData()),
            deltaState,
            context.getConfig()
        );
    }
    return Optional.empty();
}
```

### A.3.5 框架内置的InterruptableAction实现

#### InterruptionHook（执行前中断）

```java
// InterruptionHook.java — agent-framework 完整源码（关键部分）

@HookPositions({HookPosition.BEFORE_MODEL})
public class InterruptionHook extends ModelHook 
    implements AsyncNodeActionWithConfig, InterruptableAction {
    
    public static final String INTERRUPTION_FEEDBACK_KEY = "INTERRUPTION_FEEDBACK";
    
    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        Map<String, Object> agentThreadState = this.getAgent().getThreadState(threadId);
        
        if (agentThreadState == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        
        // 原子地获取并移除反馈（ConcurrentHashMap.remove()是线程安全的）
        Object feedback = agentThreadState.remove(INTERRUPTION_FEEDBACK_KEY);
        
        if (feedback == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        
        // 处理反馈消息（List<Message> / UserMessage / String）
        List<Message> feedbackMessages = parseFeedback(feedback);
        Map<String, Object> updates = new HashMap<>();
        updates.put("messages", newMessages);
        return CompletableFuture.completedFuture(updates);
    }
    
    @Override
    public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        Map<String, Object> agentThreadState = this.getAgent().getThreadState(threadId);
        
        if (agentThreadState == null) return Optional.empty();
        
        Object feedbackValue = agentThreadState.get(INTERRUPTION_FEEDBACK_KEY);
        
        if (feedbackValue == null) return Optional.empty();
        
        // 空列表 → 需要中断（等待用户输入）
        if (feedbackValue instanceof List<?> feedbackList) {
            if (feedbackList.isEmpty()) {
                return Optional.of(InterruptionMetadata.builder(nodeId, state)
                    .addMetadata("interruption_requested", true)
                    .build());
            }
        }
        
        // 非空 → 不中断（有反馈消息待处理）
        return Optional.empty();
    }
}
```

**触发方式**：
```java
// 外部调用者触发中断
reactAgent.interrupt(config);        // 传入空消息列表 → 触发中断
reactAgent.interrupt("请确认金额", config);  // 传入用户消息 → 不中断，处理反馈
```

**InterruptionHook的interrupt()语义**：
- `INTERRUPTION_FEEDBACK_KEY` 为null → 不中断，正常推理
- `INTERRUPTION_FEEDBACK_KEY` 为空列表 → 中断，等待用户输入
- `INTERRUPTION_FEEDBACK_KEY` 为非空消息 → 不中断，处理反馈后继续推理

#### HumanInTheLoopHook（执行后中断）

```java
// HumanInTheLoopHook.java — agent-framework 关键部分

@HookPositions(HookPosition.AFTER_MODEL)
public class HumanInTheLoopHook extends ModelHook 
    implements AsyncNodeActionWithConfig, InterruptableAction {
    
    private Map<String, ToolConfig> approvalOn;  // 需要人工审批的Tool列表
    
    @Override
    public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
        AssistantMessage lastMessage = getLastAssistantMessage(state);
        
        if (lastMessage == null || !lastMessage.hasToolCalls()) {
            return Optional.empty();  // 没有Tool调用 → 不中断
        }
        
        // 检查是否已有反馈
        Optional<Object> feedback = config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY);
        if (feedback.isPresent()) {
            // 有反馈 → 验证反馈 → 不中断（让apply()处理反馈）
            if (!validateFeedback((InterruptionMetadata) feedback.get(), lastMessage.getToolCalls())) {
                return buildInterruptionMetadata(state, lastMessage);  // 反馈无效 → 仍需中断
            }
            return Optional.empty();  // 反馈有效 → 不中断
        }
        
        // 没有反馈 → 需要中断，等待人工审批
        return buildInterruptionMetadata(state, lastMessage);
    }
    
    private Optional<InterruptionMetadata> buildInterruptionMetadata(
        OverAllState state, AssistantMessage lastMessage) {
        boolean needsInterruption = false;
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder(
            Hook.getFullHookName(this), state);
        
        for (AssistantMessage.ToolCall toolCall : lastMessage.getToolCalls()) {
            if (approvalOn.containsKey(toolCall.name())) {
                // 这个Tool需要人工审批
                builder.addToolFeedback(ToolFeedback.builder()
                    .id(toolCall.id())
                    .name(toolCall.name())
                    .description("The AI is requesting to use the tool: " + toolCall.name())
                    .arguments(toolCall.arguments())
                    .build());
                needsInterruption = true;
            } else {
                // 不需要审批的Tool → 自动批准
                builder.addToolsAutomaticallyApproved(toolCall);
            }
        }
        
        return needsInterruption ? Optional.of(builder.build()) : Optional.empty();
    }
}
```

---

## A.4 子图中断传播机制（核心）

### A.4.1 关键认知

**子图节点（SubCompiledGraphNodeAction / AgentToSubCompiledGraphNodeAdapter）都不实现InterruptableAction。**

中断不是在父图node boundary发生的，而是在子图**内部**发生，通过Flux流自然传播到父图。

### A.4.2 SubCompiledGraphNodeAction.apply()

```java
// SubCompiledGraphNodeAction.java — graph-core 完整源码

public record SubCompiledGraphNodeAction(
    String nodeId, 
    CompileConfig parentCompileConfig,
    CompiledGraph subGraph
) implements AsyncNodeActionWithConfig, ResumableSubGraphAction {

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        final boolean resumeSubgraph = config.metadata(
            resumeSubGraphId(nodeId), new TypeRef<Boolean>() {}
        ).orElse(false);
        
        RunnableConfig subGraphRunnableConfig = RunnableConfig.builder(config)
            .checkPointId(null).nextNode(null).build();
        
        // CheckpointSaver处理（父子图共享同一saver实例）
        var parentSaver = parentCompileConfig.checkpointSaver();
        var subGraphSaver = subGraph.compileConfig.checkpointSaver();
        if (subGraphSaver.isPresent()) {
            if (parentSaver.isEmpty()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Missing CheckpointSaver in parent graph!"));
            }
            if (parentSaver.get() == subGraphSaver.get()) {
                subGraphRunnableConfig = RunnableConfig.builder(config)
                    .threadId(config.threadId()
                        .map(threadId -> format("%s_%s", threadId, subGraphId(nodeId)))
                        .orElseGet(() -> subGraphId(nodeId)))
                    .nextNode(null).checkPointId(null)
                    .build();
            }
        }
        
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        try {
            if (resumeSubgraph) {
                // 恢复模式：用父图state更新子图checkpoint
                subGraphRunnableConfig = subGraph.updateState(subGraphRunnableConfig, state.data());
            }
            // 执行子图，返回Flux流（不是同步结果！）
            var fluxStream = subGraph.graphResponseStream(state, subGraphRunnableConfig);
            future.complete(Map.of(outputKeyToParent(nodeId), fluxStream));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
```

**关键**：`apply()`返回的不是同步结果，而是`Map.of(outputKeyToParent(nodeId), fluxStream)`——一个**Flux流**嵌入到Map中。

### A.4.3 AgentToSubCompiledGraphNodeAdapter.apply()

```java
// ReactAgent.AgentToSubCompiledGraphNodeAdapter — agent-framework 完整源码（关键部分）

public class AgentToSubCompiledGraphNodeAdapter implements NodeActionWithConfig, ResumableSubGraphAction {
    
    @Override
    public Map<String, Object> apply(OverAllState parentState, RunnableConfig config) throws Exception {
        final boolean resumeSubgraph = config.metadata(
            resumeSubGraphId(nodeId), new TypeRef<Boolean>() {}
        ).orElse(false);
        
        RunnableConfig subGraphRunnableConfig = getSubGraphRunnableConfig(config);
        
        Map<String, Object> stateForChild = new HashMap<>(parentState.data());
        if (includeContents) {
            // includeContents=true: 传递父图消息历史给子Agent
            List<Object> newMessages;
            if (stateForChild.get("messages") != null) {
                newMessages = new ArrayList<>((List<Object>)stateForChild.remove("messages"));
            } else {
                newMessages = new ArrayList<>();
            }
            stateForChild.put("messages", newMessages);
            subGraphResult = childGraph.graphResponseStream(stateForChild, subGraphRunnableConfig);
        } else {
            // includeContents=false: 不传递消息历史
            parentMessages = stateForChild.remove("messages");
            subGraphResult = childGraph.graphResponseStream(stateForChild, subGraphRunnableConfig);
        }
        
        Map<String, Object> result = new HashMap<>();
        String outputKeyToParent = StringUtils.hasLength(ReactAgent.this.outputKey) 
            ? ReactAgent.this.outputKey : "messages";
        result.put(outputKeyToParent, getGraphResponseFlux(parentState, subGraphResult, null));
        return result;
    }
}
```

**关键**：与SubCompiledGraphNodeAction相同模式——返回Map中嵌入Flux流。

`getGraphResponseFlux()`只做消息过滤（buffer+处理最后一个元素），**不消费InterruptionMetadata**。

### A.4.4 父图如何处理子图的Flux流

```java
// NodeExecutor.handleActionResult() — 源码还原

private Flux<GraphResponse<NodeOutput>> handleActionResult(
    GraphRunnerContext context, Map<String, Object> deltaState, 
    AtomicReference<Object> resultValue) {
    
    // 1. 检查是否有嵌入式Flux流（子图结果）
    Optional<Flux<GraphResponse<NodeOutput>>> embedFlux = getEmbedFlux(context, deltaState);
    
    if (embedFlux.isPresent()) {
        // 有子图流 → 进入嵌入式处理
        return handleEmbeddedFlux(mainGraphExecutor, context, embedFlux.get(), deltaState, resultValue);
    }
    
    // 2. 检查是否有GraphFlux（流式节点输出）
    Optional<GraphFlux<?>> graphFlux = getEmbedGraphFlux(deltaState, context);
    if (graphFlux.isPresent()) {
        return handleGraphFlux(context, graphFlux.get(), deltaState, resultValue);
    }
    
    // 3. 非流式结果 → 直接处理
    return handleNonStreamingResult(context, deltaState, resultValue);
}
```

### A.4.5 子图中断传播核心：processGraphResponseFlux()

```java
// NodeExecutor.processGraphResponseFlux() — 源码还原自字节码

private Flux<GraphResponse<NodeOutput>> processGraphResponseFlux(
    MainGraphExecutor mainGraphExecutor, GraphRunnerContext context,
    Flux<GraphResponse<NodeOutput>> subGraphFlux,
    Map<String, Object> deltaState, AtomicReference<Object> resultValue) {
    
    AtomicReference<GraphResponse<NodeOutput>> lastResponse = new AtomicReference<>();
    
    return subGraphFlux
        // 过滤掉InterruptionMetadata（不作为正常流元素处理）
        .filter(response -> {
            Optional<?> resultValue = response.resultValue();
            return resultValue.isEmpty() || !(resultValue.get() instanceof InterruptionMetadata);
        })
        // 收集最后一个非中断响应
        .doOnNext(response -> {
            // 处理state更新和checkpoint
            // ...
            lastResponse.set(response);
        })
        // 流结束后处理
        .concatWith(Flux.defer(() -> {
            // 子图执行完毕后，执行父图的下一个节点
            return mainGraphExecutor.execute(context, resultValue);
        }))
        // 在每个响应后检查是否有InterruptionMetadata
        .doOnNext(response -> {
            GraphResponse<NodeOutput> last = lastResponse.get();
            if (last != null && last.resultValue().isPresent() 
                && last.resultValue().get() instanceof InterruptionMetadata) {
                // ===== 子图内部产生了InterruptionMetadata =====
                context.setReturnFromEmbedWithValue(last.resultValue().get());
                return;  // 流程停止！
            }
        });
}
```

**更精确的还原**（基于字节码lambda$processGraphResponseFlux$13）：

```java
// lambda$processGraphResponseFlux$13 — 子图每个响应后的处理
void processResponse(AtomicReference<GraphResponse> lastResponse, 
                     GraphRunnerContext context, Map<String, Object> deltaState) {
    GraphResponse response = lastResponse.get();
    
    if (response == null) {
        context.setNextNodeId("__END__");
        return;
    }
    
    Optional resultValue = response.resultValue();
    
    // ===== 关键：检查InterruptionMetadata =====
    if (resultValue.isPresent() && resultValue.get() instanceof InterruptionMetadata) {
        // 子图内部产生了中断！设置returnFromEmbed
        context.setReturnFromEmbedWithValue(resultValue.get());
        return;  // 不再继续处理
    }
    
    // 正常结果 → 合并state，计算下一步
    Map<String, Object> nonFluxEntries = deltaState.entrySet().stream()
        .filter(e -> !(e.getValue() instanceof Flux))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    
    Map<String, Object> nodeResult = new HashMap<>(nonFluxEntries);
    Map<String, Object> actionResult = new HashMap<>();
    
    if (resultValue.isPresent()) {
        Object value = resultValue.get();
        if (value instanceof Map) {
            actionResult = (Map<String, Object>) value;
        }
    }
    
    Map<String, Object> merged = new HashMap<>(nonFluxEntries);
    merged.putAll(actionResult);
    
    // 检查interruptAfterForStreaming
    Optional<InterruptionMetadata> afterInterrupt = interruptAfterForStreaming(context, merged);
    
    context.mergeIntoCurrentState(nonFluxEntries);
    context.mergeIntoCurrentState(actionResult);
    
    Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
    context.setNextNodeId(nextCommand.gotoNode());
    context.buildNodeOutputAndAddCheckpoint(actionResult);
    context.doListeners("__NODE_AFTER__", null);
    
    if (afterInterrupt.isPresent()) {
        resultValue.set(afterInterrupt.get());
    }
}
```

### A.4.6 MainGraphExecutor处理returnFromEmbed

```java
// MainGraphExecutor.execute() — 源码还原自字节码（完整版）

public Flux<GraphResponse<NodeOutput>> execute(
    GraphRunnerContext context, AtomicReference<Object> resultValue) {
    
    // 1. 停止条件
    if (context.shouldStop() || context.isMaxIterationsReached()) {
        return handleCompletion(context, resultValue);
    }
    
    // 2. 检查子图返回的中断
    Optional<ReturnFromEmbed> embedResult = context.getReturnFromEmbedAndReset();
    if (embedResult.isPresent()) {
        Optional<InterruptionMetadata> interruption = 
            embedResult.get().value(new TypeRef<InterruptionMetadata>() {});
        if (interruption.isPresent()) {
            // 子图内部中断传播到父图！
            return Flux.just(GraphResponse.done(interruption.get()));
        }
        // 非InterruptionMetadata的嵌入返回
        return Flux.just(GraphResponse.done(
            context.buildNodeOutputAndAddCheckpoint(Map.of())));
    }
    
    // 3. 检查节点是否被标记为已中断（resume场景）
    if (context.getCurrentNodeId() != null 
        && context.getConfig().isInterrupted(context.getCurrentNodeId())) {
        context.getConfig().withNodeResumed(context.getCurrentNodeId());
        return Flux.just(GraphResponse.done(context.getCurrentStateData()));
    }
    
    // 4. Start/End节点处理
    if (context.isStartNode()) return handleStartNode(context);
    if (context.isEndNode()) return handleEndNode(context, resultValue);
    
    // 5. Resume from checkpoint处理
    Optional<String> resumeFrom = context.getResumeFromAndReset();
    if (resumeFrom.isPresent() 
        && context.getCompiledGraph().compileConfig.interruptBeforeEdge()
        && Objects.equals(context.getNextNodeId(), "__INTERRUPTED__")) {
        // 从中断恢复：计算下一个节点
        Command nextCommand = context.nextNodeId(
            resumeFrom.get(), context.getCurrentStateData());
        context.setNextNodeId(nextCommand.gotoNode());
        context.setCurrentNodeId(null);
    }
    
    // 6. 静态配置中断检查
    if (context.shouldInterrupt()) {
        InterruptionMetadata metadata = InterruptionMetadata.builder(
            context.getCurrentNodeId(),
            context.cloneState(context.getCurrentStateData())
        ).build();
        return Flux.just(GraphResponse.done(metadata));
    }
    
    // 7. 正常执行节点
    return nodeExecutor.execute(context, resultValue);
}
```

---

## A.5 Resume机制

### A.5.1 Resume触发

```java
// GraphRunnerContext.initializeFromResume() — 源码还原自字节码

private void initializeFromResume(OverAllState state, RunnableConfig config) {
    // 从checkpoint恢复：读取最后保存的checkpoint
    StateSnapshot lastCheckpoint = compiledGraph.lastStateOf(config).orElseThrow();
    
    // 恢复state
    this.overallState = cloneState(lastCheckpoint.state(), state);
    
    // 恢复执行位置
    String nextNode = lastCheckpoint.nextNode();
    this.currentNodeId = null;
    this.nextNodeId = nextNode;
    
    // ===== 关键：检查下一个节点的action是否是ResumableSubGraphAction =====
    AsyncNodeActionWithConfig nextNodeAction = compiledGraph.getNodeAction(nextNode);
    if (nextNodeAction instanceof ResumableSubGraphAction resumableAction) {
        // 设置resume metadata，子图apply()时会检测
        this.config = RunnableConfig.builder(config)
            .checkPointId(null)
            .addMetadata(resumableAction.getResumeSubGraphId(), true)
            .build();
    }
}
```

### A.5.2 子图内部Resume

当`SubCompiledGraphNodeAction.apply()`检测到`resumeSubgraphId` metadata为true时：

```java
if (resumeSubgraph) {
    // 用父图的state更新子图的checkpoint
    subGraphRunnableConfig = subGraph.updateState(subGraphRunnableConfig, state.data());
}
// 然后正常执行 graphResponseStream() — 子图会从checkpoint位置恢复
```

### A.5.3 ResumableSubGraphAction接口

```java
// ResumableSubGraphAction.java — graph-core 完整源码

public interface ResumableSubGraphAction {
    static final String OUTPUT_KEY_TO_PARENT_SUFFIX = "_compiled_graph";
    
    String getResumeSubGraphId();
    
    static String subGraphId(String nodeId) {
        return format("subgraph_%s", nodeId);
    }
    
    static String resumeSubGraphId(String nodeId) {
        return format("resume_%s", subGraphId(nodeId));
    }
    
    static String outputKeyToParent(String nodeId) {
        return format("%s_%s", subGraphId(nodeId), OUTPUT_KEY_TO_PARENT_SUFFIX);
    }
}
```

---

## A.6 ReactAgent内部的中断集成

### A.6.1 ReactAgent.initGraph()如何注入InterruptionHook

```java
// ReactAgent.initGraph() — agent-framework 源码（关键部分）

@Override
protected StateGraph initGraph() throws GraphStateException {
    if (hooks == null) hooks = new ArrayList<>();
    
    // 始终注入InstructionAgentHook
    List<Hook> effectiveHooks = new ArrayList<>();
    effectiveHooks.add(InstructionAgentHook.create());
    effectiveHooks.addAll(hooks);
    
    // 按位置分类Hook
    List<Hook> beforeModelHooks = filterHooksByPosition(effectiveHooks, HookPosition.BEFORE_MODEL);
    List<Hook> afterModelHooks = filterHooksByPosition(effectiveHooks, HookPosition.AFTER_MODEL);
    
    // ===== 关键：InterruptionHook被添加为完整节点对象（保留InterruptableAction能力）=====
    for (Hook hook : beforeModelHooks) {
        if (hook instanceof ModelHook modelHook) {
            if (hook instanceof InterruptionHook interruptionHook) {
                // 完整对象作为节点 → InterruptableAction接口保留
                graph.addNode(Hook.getFullHookName(hook) + ".beforeModel", interruptionHook);
            } else {
                // 方法引用作为节点 → InterruptableAction接口丢失
                graph.addNode(Hook.getFullHookName(hook) + ".beforeModel", modelHook::beforeModel);
            }
        }
    }
    
    // ===== HumanInTheLoopHook同样被添加为完整对象 =====
    for (Hook hook : afterModelHooks) {
        if (hook instanceof ModelHook modelHook) {
            if (hook instanceof HumanInTheLoopHook humanInTheLoopHook) {
                graph.addNode(Hook.getFullHookName(hook) + ".afterModel", humanInTheLoopHook);
            } else {
                graph.addNode(Hook.getFullHookName(hook) + ".afterModel", modelHook::afterModel);
            }
        }
    }
    
    // ... 构建图的边 ...
}
```

**框架设计选择**：`InterruptionHook`和`HumanInTheLoopHook`作为**完整对象**添加为节点，保留`InterruptableAction`能力。其他Hook作为**方法引用**添加，丢失`InterruptableAction`能力。

这意味着**自定义Hook无法直接获得InterruptableAction能力**——除非修改框架代码，或继承`InterruptionHook`/`HumanInTheLoopHook`。

### A.6.2 ReactAgent.interrupt()如何触发中断

```java
// ReactAgent.java

public void interrupt(RunnableConfig config) {
    updateAgentState(List.of(), config);  // 空列表 → InterruptionHook.interrupt()返回InterruptionMetadata
}

public void interrupt(List<Message> messages, RunnableConfig config) {
    updateAgentState(messages, config);   // 非空列表 → 不中断，处理反馈
}

public void interrupt(String userMessage, RunnableConfig config) {
    updateAgentState(List.of(UserMessage.builder().text(userMessage).build()), config);
}

public void updateAgentState(Object state, RunnableConfig config) {
    String threadId = config.threadId().orElseThrow();
    Map<String, Object> stateStatus = threadIdStateMap.computeIfAbsent(
        threadId, k -> new ConcurrentHashMap<>());
    stateStatus.put(INTERRUPTION_FEEDBACK_KEY, state);
}
```

---

## A.7 完整中断传播链路图

### 场景：L2子图执行过程中ReactAgent触发HITL中断

```
父图 MainGraphExecutor.execute()
  → NodeExecutor.executeNode("transfer_agent")
    → AgentToSubCompiledGraphNodeAdapter.apply()
      → childGraph.graphResponseStream()  // ReactAgent内部图开始执行
        
        ReactAgent内部图 MainGraphExecutor.execute()
          → NodeExecutor.executeNode("HITL.afterModel")
            → 检查 nodeAction instanceof InterruptableAction ✅ (HumanInTheLoopHook)
            → HITL.interrupt("HITL.afterModel", state, config)
            → 检测到转账Tool需要审批，无反馈
            → return Optional.of(InterruptionMetadata)  ← 内部图暂停
            
          → MainGraphExecutor检测到InterruptionMetadata
          → return Flux.just(GraphResponse.done(interruptionMetadata))
        
      ← childGraph.graphResponseStream() 发出 GraphResponse.done(InterruptionMetadata)
    
    → NodeExecutor.processGraphResponseFlux() 处理子图Flux
      → 检测到 resultValue.get() instanceof InterruptionMetadata ✅
      → context.setReturnFromEmbedWithValue(interruptionMetadata)
      → return  // 流程停止
    
  → 父图 MainGraphExecutor.execute() 下一轮循环
    → context.getReturnFromEmbedAndReset() → Optional.of(ReturnFromEmbed(InterruptionMetadata))
    → 检测到 InterruptionMetadata.isPresent() ✅
    → return Flux.just(GraphResponse.done(interruptionMetadata))  ← 父图暂停！
```

### 场景：Resume恢复

```
父图 MainGraphExecutor.execute() 带有resume config
  → GraphRunnerContext.initializeFromResume()
    → 读取checkpoint → 确定下一个节点 "transfer_agent"
    → compiledGraph.getNodeAction("transfer_agent") 
      → AgentToSubCompiledGraphNodeAdapter (implements ResumableSubGraphAction)
    → 设置 metadata: resumeSubGraphId("transfer_agent") = true
    
  → NodeExecutor.executeNode("transfer_agent")
    → AgentToSubCompiledGraphNodeAdapter.apply()
      → config.metadata(resumeSubGraphId("transfer_agent")) = true ✅ (resume模式)
      → childGraph.updateState(subGraphConfig, state.data())  // 恢复子图checkpoint
      → childGraph.graphResponseStream()  // 从中断位置继续执行
        
        ReactAgent内部图恢复执行
          → InterruptionHook.apply() 检查 INTERRUPTION_FEEDBACK_KEY
          → 如果有反馈 → 处理反馈，继续推理
          → HumanInTheLoopHook.apply() 处理人工审批结果
          → 继续正常执行...
```

---

## A.8 关键结论汇总

### A.8.1 中断传播等价性

| 对比项 | SubCompiledGraphNodeAction (Plan B) | AgentToSubCompiledGraphNodeAdapter (Plan B+) |
|--------|-------------------------------------|----------------------------------------------|
| 实现InterruptableAction？ | ❌ | ❌ |
| 实现ResumableSubGraphAction？ | ✅ | ✅ |
| 子图执行 | `subGraph.graphResponseStream()` | `childGraph.graphResponseStream()` |
| 中断传播路径 | Flux流→processGraphResponseFlux→returnFromEmbed | **完全相同** |
| Resume路径 | resumeSubGraphId metadata→updateState() | **完全相同** |
| 中断触发源 | L2内部InterruptableAction节点 | ReactAgent内部InterruptionHook/HITLHook |

### A.8.2 自定义Hook的限制

只有`InterruptionHook`和`HumanInTheLoopHook`被`initGraph()`作为完整对象添加为节点，保留`InterruptableAction`能力。其他Hook（包括自定义Hook）被作为方法引用添加，**丢失InterruptableAction能力**。

如果需要自定义中断行为，有两个选择：
1. 继承`InterruptionHook`或`HumanInTheLoopHook`
2. 修改框架`initGraph()`代码，对自定义Hook也作为完整对象添加

### A.8.3 中断的两种语义

| 中断类型 | 触发方式 | InterruptionHook语义 | HITLHook语义 |
|---------|---------|---------------------|-------------|
| 等待用户输入 | `reactAgent.interrupt(config)` | INTERRUPTION_FEEDBACK_KEY=空列表 → 中断 | — |
| 提供用户反馈 | `reactAgent.interrupt(message, config)` | INTERRUPTION_FEEDBACK_KEY=非空 → 不中断，处理 | — |
| Tool审批 | 模型产生Tool调用 | — | 有ToolCall+无反馈 → 中断 |
| Tool批准 | `config.addMetadata(HUMAN_FEEDBACK_METADATA_KEY, feedback)` | — | 有有效反馈 → 不中断 |
