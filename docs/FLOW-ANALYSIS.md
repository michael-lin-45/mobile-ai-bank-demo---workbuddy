# 手机银行AI助手 — 架构与流程分析文档

> 本文档从开发者视角详细梳理代码结构、类关系、L0/L1/L2完整调用链、路由决策逻辑和SSE流式输出方案。
> 目标：看懂本文档即可迅速理解整个项目的架构和代码流程。

---

## 1. 工程目录结构

```
src/main/java/com/mobileagent/app/
├── config/                          # Spring配置类
│   ├── DomainServiceConfig.java     #   L1域服务Bean装配 + DomainStateAware注册
│   └── ModelConfig.java             #   各层ChatClient Bean定义
│
├── controller/                      # HTTP入口
│   └── BankController.java          #   L0调度层 — 领域路由 + REROUTE + SSE/JSON双模式
│
├── data/                            # 数据类(纯POJO + DTO)
│   ├── ChunkType.java               #   流式chunk类型枚举
│   ├── RoutingResult.java           #   LLM路由结果(Phase1/Phase2通用)
│   ├── RoutingResolution.java       #   SubGraphResolver决议(RESOLVED/DISAMBIGUATION/REJECTED/CANCELLED)
│   ├── StreamChunk.java             #   流式传输单元 — L0/L1/GES之间的唯一数据载体
│   ├── SubGraphProperties.java      #   子图配置属性(从yml的routing段绑定)
│   ├── WorkflowOutput.java          #   非SSE模式的JSON响应DTO
│   └── WorkflowStatus.java          #   工作流状态枚举
│
├── domain/                          # L1领域服务层
│   ├── DomainHandler.java           #   领域处理者接口(统一handle方法)
│   ├── AbstractDomainService.java   #   抽象基类(activeAgent管理/对话历史/执行方法)
│   ├── SingleSubAgentDomainService.java  #   单子图域(1-1: 转账/账单)
│   ├── MultiSubAgentDomainService.java   #   多子图域(1-N: 理财,含消歧/挂起/恢复)
│   └── ChatService.java             #   闲聊域(直接LLM对话)
│
├── execution/                       # L2执行层
│   └── GraphExecutionEngine.java    #   Graph执行引擎(流式/非流式双路径)
│
├── infrastructure/                  # 基础设施
│   └── SseOutputAdapter.java        #   SSE输出适配器(唯一知道SSE格式的组件)
│
├── memory/                          # 会话存储层
│   ├── GlobalSessionContext.java    #   全局会话上下文(OverAllState封装)
│   ├── GlobalSessionRepository.java #   存储接口(in-memory/redis切换)
│   ├── GlobalSessionRepositoryConfig.java  # 存储Bean装配(@ConditionalOnProperty)
│   ├── GlobalSessionStateStore.java #   业务门面(创建/获取/清理Session)
│   ├── DomainStateAware.java        #   域状态注册Bean(声明OverAllState的key/strategy)
│   ├── KeyStrategyFactoryConfig.java #   KeyStrategy工厂(从DomainStateAware收集策略)
│   ├── SubGraphCheckpointSaverConfig.java  # CheckpointSaver配置
│   ├── impl/
│   │   ├── InMemoryGlobalSessionRepository.java  # In-Memory实现
│   │   └── RedisGlobalSessionRepository.java     # Redis实现
│   └── model/                       # 领域状态模型
│       ├── ActiveAgentInfo.java     #   当前活跃子图信息(intent/threadId/lastQuestion)
│       ├── DisambiguationState.java #   消歧状态(存储groupId)
│       ├── DomainState.java         #   L1域状态(activeAgent/suspendedAgents/subAgents/disambiguation)
│       ├── SubAgentState.java       #   L2子图数据快照(name/interrupted/nextNode/data)
│       └── SuspendedInfo.java       #   挂起子图信息(intent/threadId/过期时间)
│
├── mock/                            # 模拟数据
│   └── MockBankingService.java      #   银行服务Mock(转账/账单/理财咨询/理财解读)
│
├── router/                          # 路由层
│   ├── domain/
│   │   └── DomainRouter.java        #   L0领域路由器(确定性+模型路由)
│   ├── subgraph/
│   │   ├── ContextRouter.java       #   L1 Phase1: FOLLOW/SWITCH/RESUME判断
│   │   ├── SubGraphRouter.java      #   L1 Phase2: 意图识别+上下文改写
│   │   └── SubGraphResolver.java    #   L1 Phase2封装: 意图决议+消歧+模糊匹配
│   └── registry/
│       ├── SubGraphRegistry.java    #   子图注册中心(元数据+Graph Bean绑定)
│       └── DomainServiceRegistry.java  # 领域服务注册表(域名→DomainHandler)
│
├── util/                            # 工具类
│   ├── JsonParseUtils.java          #   JSON提取(从LLM响应中提取JSON)
│   └── TemplateUtils.java           #   模板加载(.st文件)
│
└── workflow/                        # L2子Graph定义
    ├── AbstractGraphConfig.java     #   抽象Graph配置(提参/校验/取消检测/数据快照)
    ├── TransferGraphConfig.java     #   转账Graph
    ├── BillQueryGraphConfig.java    #   账单Graph
    ├── WealthConsultGraphConfig.java #  理财咨询Graph
    └── WealthInterpretGraphConfig.java # 理财解读Graph(流式)

src/main/resources/
├── application.yml                  # 统一配置(模型/路由/存储/会话)
└── prompts/                         # 提示词模板
    ├── l0-domain.st                 #   L0领域路由模板
    ├── l1-context.st                #   L1 Phase1上下文路由模板(Multi域,含FOLLOW/SWITCH/RESUME)
    ├── l1-context-simple.st         #   L1 Phase1简化模板(Single域,只有FOLLOW/SWITCH)
    └── l1-intention.st              #   L1 Phase2意图识别+改写模板
```

---

## 2. 核心类关系图

### 2.1 继承关系

```
DomainHandler (interface)
├── AbstractDomainService (abstract)
│   ├── SingleSubAgentDomainService   — 单子图域(1-1)
│   └── MultiSubAgentDomainService    — 多子图域(1-N)
└── ChatService                       — 闲聊域

AbstractGraphConfig (abstract)
├── TransferGraphConfig
├── BillQueryGraphConfig
├── WealthConsultGraphConfig
└── WealthInterpretGraphConfig

GlobalSessionRepository (interface)
├── InMemoryGlobalSessionRepository
└── RedisGlobalSessionRepository
```

### 2.2 组合/依赖关系

```
BankController
├── DomainRouter              (L0路由)
├── DomainServiceRegistry     (域名→Handler映射)
├── GlobalSessionStateStore   (会话状态)
└── SseOutputAdapter          (SSE输出)

AbstractDomainService
├── ContextRouter             (Phase1: FOLLOW/SWITCH/RESUME)
├── SubGraphRegistry          (子图注册表)
├── GraphExecutionEngine      (L2执行)
└── GlobalSessionStateStore   (会话状态)

SingleSubAgentDomainService
└── SubGraphRouter            (Phase2: 意图识别+改写, 直接调用)

MultiSubAgentDomainService
└── SubGraphResolver          (Phase2: 意图决议+消歧, 封装SubGraphRouter)

SubGraphResolver
├── SubGraphRouter            (内部调用rewriteAndIdentify)
└── SubGraphRegistry          (模糊匹配/意图组查询)

ContextRouter
├── SubGraphRegistry          (获取意图列表描述)
└── ChatClient(contextChatClient)

SubGraphRouter
├── SubGraphRegistry          (获取意图列表描述)
└── ChatClient(intentChatClient)

DomainRouter
├── SubGraphProperties        (读取domains关键词配置)
├── GlobalSessionStateStore   (读取lastDomain/chatHistory)
└── ChatClient(domainChatClient)

GraphExecutionEngine
└── SubGraphRegistry          (读取isStreamable判断流式/非流式)

DomainServiceConfig (Spring @Configuration)
├── 构建 SingleSubAgentDomainService × 2 (转账/账单)
├── 构建 MultiSubAgentDomainService × 1 (理财)
├── 构建 ChatService × 1
└── 注册 DomainStateAware × 3 (声明OverAllState key/strategy)
```

### 2.3 数据类流转关系

```
用户输入
  │
  ▼
DomainRouter.DomainResult          ← L0路由结果(domain + confidence + unsupportedFeature)
  │
  ▼
RoutingResult                      ← Phase1(ContextRouter输出: routeType=FOLLOW/SWITCH/RESUME)
  │                                ← Phase2(SubGraphRouter输出: intentName + rewrittenInput + ambiguous + belongsToDomain)
  ▼
RoutingResolution                  ← SubGraphResolver决议(RESOLVED/DISAMBIGUATION/REJECTED/CANCELLED)
  │
  ▼
StreamChunk                        ← L2执行结果(流式: 多个CHUNK + 1个终结chunk)
  │                                ← 全链路唯一数据载体: L2→L1→L0→SSE
  ▼
WorkflowOutput                     ← 仅JSON模式使用(Accept: application/json)
```

---

## 3. L0/L1/L2 完整调用链

### 3.1 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│                        前端 (Vue/React)                       │
│                    Accept: text/event-stream                  │
└──────────────────────────┬───────────────────────────────────┘
                           │ POST /api/bank/chat
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  L0: BankController                                          │
│  ├── 写入UserMessage到GlobalSessionContext                    │
│  ├── DomainRouter.route() → 确定性关键词 + LLM → 领域名       │
│  ├── DomainServiceRegistry.getHandler(domain) → 分发          │
│  └── REROUTE: 排除当前域,重新路由(最多maxRerouteAttempts次)    │
└──────────────────────────┬───────────────────────────────────┘
                           │ handler.handle(sessionId, userInput)
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  L1: DomainService (Single / Multi / Chat)                   │
│  ├── Phase1: ContextRouter → FOLLOW / SWITCH / RESUME        │
│  ├── Phase2: SubGraphRouter(意图识别+改写)                    │
│  │          或 SubGraphResolver(意图决议+消歧)                │
│  ├── 路由决策: FOLLOW→恢复 / SWITCH→新建 / RESUME→恢复挂起    │
│  └── 返回 Flux<StreamChunk>                                   │
└──────────────────────────┬───────────────────────────────────┘
                           │ executeNewAgent / resumeActiveAgent
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  L2: GraphExecutionEngine                                     │
│  ├── SubGraphRegistry.isStreamable() → 流式/非流式分流        │
│  ├── 非流式: graph.stream().blockLast() → 单个StreamChunk     │
│  ├── 流式: graph.graphResponseStream() → 多个CHUNK + 终结     │
│  └── interruptBefore机制 → INTERRUPTED(需要用户补充参数)       │
└──────────────────────────┬───────────────────────────────────┘
                           │ Flux<StreamChunk>
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  SseOutputAdapter                                             │
│  ├── SSE模式: SseEmitter.send(chunk) + [DONE]                │
│  └── JSON模式: block → WorkflowOutput                         │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 L0: 领域路由 (BankController + DomainRouter)

#### 完整调用流程

```
BankController.chat(sessionId, message)
  │
  ├── 1. 写入UserMessage
  │     ctx.addUserMessage(userInput)
  │
  ├── 2. 构建管道
  │     buildChatPipeline(sessionId, userInput)
  │       └── dispatchWithReroute(sessionId, userInput, excludedDomains, ...)
  │
  ├── 3. L0领域路由
  │     DomainRouter.route(sessionId, userInput, excludedDomains)
  │       ├── 确定性路由: 关键词匹配 → 直接返回(0ms)
  │       └── 模型路由: chatHistory + lastDomain → LLM判断
  │
  ├── 4. 领域分发
  │     dispatchToDomain(domainResult, sessionId, userInput)
  │       ├── UNSUPPORTED → 直接返回"暂不支持"
  │       ├── 有Handler → handler.handle(sessionId, userInput)
  │       └── 无Handler → CHAT域兜底
  │
  ├── 5. REROUTE处理
  │     if chunk.type == REROUTE:
  │       excludedDomains.add(currentDomain)
  │       递归调用 dispatchWithReroute(...)
  │
  └── 6. 消息累积
        AssistantAccumulator.onChunk(chunk)
          ├── CHUNK → 累积文本
          └── 终结chunk → ctx.addAssistantMessage(fullReply)
```

#### 关键代码: BankController调度入口

```java
@PostMapping(value = "/chat", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
public Object chat(@RequestParam String sessionId, @RequestBody Map<String, String> req,
                   @RequestHeader(value = "Accept", defaultValue = MediaType.APPLICATION_JSON_VALUE) String accept,
                   HttpServletResponse response) {
    String userInput = req.get("message");
    Flux<StreamChunk> pipeline = buildChatPipeline(sessionId, userInput);

    if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
        return sseAdapter.toSse(pipeline, response);   // SSE流式
    }
    return sseAdapter.toJson(pipeline);                 // JSON一次性
}
```

#### 关键代码: REROUTE机制

```java
private Flux<StreamChunk> dispatchWithReroute(String sessionId, String userInput,
                                               Set<String> excludedDomains,
                                               AtomicInteger rerouteCount,
                                               AtomicReference<String> currentDomainRef) {
    // L0领域路由
    DomainRouter.DomainResult domainResult = domainRouter.route(sessionId, userInput, excludedDomains);
    String currentDomain = domainResult.domain();

    // 排除域检查
    if (excludedDomains.contains(currentDomain)) {
        if (rerouteCount.get() >= maxRerouteAttempts) {
            return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求"));
        }
        return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount, currentDomainRef);
    }

    Flux<StreamChunk> resultFlux = dispatchToDomain(domainResult, sessionId, userInput);

    // 检测REROUTE chunk → 排除当前域 → 重新路由
    return resultFlux.flatMap(chunk -> {
        if (chunk.getType() != ChunkType.REROUTE) return Flux.just(chunk);
        excludedDomains.add(currentDomain);
        rerouteCount.incrementAndGet();
        if (rerouteCount.get() >= maxRerouteAttempts) {
            return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求"));
        }
        return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount, currentDomainRef);
    });
}
```

#### 关键代码: DomainRouter确定性+模型路由

```java
public DomainResult route(String sessionId, String userInput, Set<String> excludedDomains) {
    // 1. 确定性路由: 关键词匹配
    DomainResult deterministic = routeDeterministic(userInput);
    if (deterministic != null && !excludedDomains.contains(deterministic.domain())) {
        updateLastDomain(sessionId, deterministic.domain());
        return deterministic;  // 0ms, 不调LLM
    }

    // 2. 模型路由: chatHistory + lastActiveDomain
    String chatHistory = formatChatHistory(sessionId);
    String lastDomainContext = formatLastDomainContext(sessionId);
    String excludedDomainsContext = formatExcludedDomainsContext(excludedDomains);
    String systemPrompt = buildDomainPrompt(userInput, chatHistory, lastDomainContext, excludedDomainsContext);

    String content = domainChatClient.prompt().system(systemPrompt).user(userInput).call().content();
    DomainResult result = parseDomainResponse(content);

    if (!"CHAT".equals(result.domain()) && !result.isUnsupported()) {
        updateLastDomain(sessionId, result.domain());
    }
    return result;
}
```

### 3.3 L1: 域内路由

#### 3.3.1 Single域路由流程 (SingleSubAgentDomainService)

适用场景: 转账(TRANSFER)、账单(BILL_QUERY) — 1个L1对应1个L2

```
SingleSubAgentDomainService.handle(sessionId, userInput)
  │
  ├── 有activeAgent且有lastQuestion?
  │   └── handleWithLastQuestion()
  │       ├── Phase1: ContextRouter → FOLLOW/SWITCH
  │       ├── FOLLOW → resumeActiveAgent()  ← 直接恢复,不用Phase2
  │       └── SWITCH → Phase2 → handleSwitchNew()
  │
  ├── 有activeAgent但无lastQuestion?
  │   └── resumeActiveAgent()  ← 直接恢复
  │
  └── 无activeAgent
      └── handleNewIntention()
          ├── Phase1: ContextRouter → SWITCH(只有SWITCH有意义)
          └── Phase2: SubGraphRouter.rewriteAndIdentify()
              ├── belongsToDomain=false → REROUTE
              ├── Auto-upgrade SWITCH→FOLLOW (如果识别的意图=activeAgent)
              └── executeNewAgent(intent, rewrittenInput)
```

##### 关键代码: Phase1 + Phase2调用

```java
private RoutingResult runSubGraphRouter(String sessionId, String userInput, RoutingResult phase1,
                                         String currentAgent, String pendingAgents) {
    String domainIntentScopeList = subGraphRegistry.getDomainIntentScopeDescription(List.of(intent));
    String chatHistory = getFormattedChatHistory(sessionId);

    return subGraphRouter.rewriteAndIdentify(sessionId, userInput, phase1,
            currentAgent, pendingAgents, sessionState, disambigContext,
            intentRoutingTemplatePath, chatHistory, domainIntentScopeList);
}
```

##### 关键代码: Auto-upgrade SWITCH→FOLLOW

```java
protected Flux<StreamChunk> tryAutoUpgradeFollowUp(ActiveAgentInfo activeAgent,
                                                     RoutingResult phase2,
                                                     String sessionId, String userInput) {
    if (activeAgent != null && phase2 != null) {
        String identifiedIntent = phase2.getIntentName();
        if (identifiedIntent != null && identifiedIntent.equals(activeAgent.getIntent())) {
            // 识别的意图恰好是当前活跃意图 → 升级为FOLLOW
            return resumeActiveAgent(sessionId, userInput, activeAgent);
        }
    }
    return null;
}
```

#### 3.3.2 Multi域路由流程 (MultiSubAgentDomainService)

适用场景: 理财(WEALTH) — 1个L1对应N个L2, 需要消歧/挂起/恢复

```
MultiSubAgentDomainService.handle(sessionId, userInput)
  │
  ├── Phase1: ContextRouter.route() → FOLLOW/SWITCH/RESUME
  │
  ├── FOLLOW且有activeAgent?
  │   └── resumeActiveAgent()  ← 直接恢复,跳过Phase2
  │
  ├── RESUME但有挂起的Agent?
  │   └── 没有挂起 → 降级为SWITCH
  │
  ├── Phase2: SubGraphResolver.resolve()
  │   ├── 消歧中? → handleDisambiguationAnswer()
  │   └── 新意图? → resolveNewIntention()
  │       ├── Phase2: SubGraphRouter.rewriteAndIdentify()
  │       ├── 消歧检查(ambiguous + low confidence)
  │       ├── 补充消歧(not ambiguous + low confidence + 属于歧义组)
  │       ├── belongsToDomain=false → RoutingResolution.outOfDomain → REROUTE
  │       ├── 意图未知 → 模糊匹配(fuzzyMatchIntent)
  │       └── 返回 RoutingResolution (RESOLVED/DISAMBIGUATION/REJECTED/CANCELLED)
  │
  └── 根据 RoutingResolution.status 执行:
      ├── RESOLVED → executeRoute() → SWITCH/RESUME
      ├── DISAMBIGUATION → 挂起当前Agent → 返回追问
      ├── REJECTED → 返回拒绝消息
      └── CANCELLED → 清除消歧状态 → 返回取消消息
```

##### 关键代码: SubGraphResolver消歧逻辑

```java
// SubGraphResolver.resolveNewIntention()
RoutingResult phase2 = subGraphRouter.rewriteAndIdentify(sessionId, userInput, phase1, ...);

// 消歧检查1: LLM标ambiguous + 低置信度
if (phase2.isAmbiguous() && phase2.getCandidateIntents() != null && !phase2.getCandidateIntents().isEmpty()) {
    if (phase2.getConfidence() < highConfidenceBypass) {
        String groupId = resolveGroupId(phase2.getGroupId(), phase2.getCandidateIntents());
        if (groupId != null && subGraphRegistry.getGroup(groupId) != null) {
            return triggerDisambiguation(groupId, phase2);
        }
    }
    // 高置信度 → 信任首选意图,不消歧
}

// 消歧检查2: 补充消歧 — 未标ambiguous但低置信度+属于歧义组
if (!phase2.isAmbiguous() && effectiveIntent != null && phase2.getConfidence() < disambiguationThreshold) {
    SubGraphRegistry.IntentGroup group = subGraphRegistry.findGroupByIntent(effectiveIntent);
    if (group != null) {
        return triggerDisambiguation(group.getGroupId(), phase2);
    }
}
```

### 3.4 路由决策详解

#### 3.4.1 确定性路由 (Deterministic)

**触发条件**: 用户输入包含领域关键词(配置在yml的`routing.domains`)

**机制**: DomainRouter.routeDeterministic() 在调LLM之前先做关键词匹配

```java
private DomainResult routeDeterministic(String userInput) {
    String keywordDomain = matchDomainKeywords(input);
    if (keywordDomain != null) {
        String unsupportedFeature = "UNSUPPORTED".equals(keywordDomain)
                ? extractUnsupportedFeature(input) : null;
        return new DomainResult(keywordDomain, unsupportedFeature, 1.0, "DETERMINISTIC:keyword");
    }
    return null;  // 未命中 → 交给模型路由
}
```

**特点**: 0ms延迟, 不调LLM, confidence=1.0

**多域命中**: 多个域的关键词同时命中时,降级给LLM判断

```java
if (hitDomains.size() > 1) {
    log.info("Multi-domain keywords hit {}, delegating to LLM", hitDomains);
    return null;  // 交给模型路由
}
```

**配置示例** (application.yml):
```yaml
routing:
  domains:
    TRANSFER:
      keywords: ["转账", "转钱", "汇款", "打款", ...]
    BILL:
      keywords: ["账单", "明细", "消费", ...]
    UNSUPPORTED:
      keywords: ["贷款", "信用卡", ...]
```

#### 3.4.2 FOLLOW (路由继承)

**含义**: 用户继续当前对话,回答子智能体的提问

**触发条件**:
- 有activeAgent + activeAgent.lastQuestion非空 + Phase1判断为FOLLOW

**核心判断** (ContextRouter模板中注入lastQuestion上下文):

```
如果【子智能体的问题】非空，首要判断: 用户当前消息是否是在回答这个问题？
  - 是(语义上直接回答/补充参数) → FOLLOW
    例: 问题"风险偏好？"，用户"稳健" → FOLLOW
  - 否(用户提出了新问题/新需求) → SWITCH
    例: 问题"风险偏好？"，用户"查账单" → SWITCH
```

**执行路径**: `resumeActiveAgent()` → `graphExecutionEngine.resumeGraph(graph, intent, userInput, threadId, globalStateData)`

```java
protected Flux<StreamChunk> resumeActiveAgent(String sessionId, String userInput, ActiveAgentInfo active) {
    var graph = subGraphRegistry.getGraph(active.getIntent());
    Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();
    return graphExecutionEngine.resumeGraph(graph, active.getIntent(), userInput,
                    active.getThreadId(), globalStateData)
            .doOnNext(chunk -> handleActiveAgentState(sessionId, chunk));
}
```

**Auto-upgrade机制**: Phase2识别的意图恰好=当前activeAgent的意图时,SWITCH自动升级为FOLLOW

#### 3.4.3 SWITCH (路由切换)

**含义**: 用户想切换到同域内的另一个意图

**触发条件**: Phase1返回SWITCH

**Single域**: 无需挂起,直接executeNewAgent

**Multi域**: 先挂起当前activeAgent,再新建

```java
// MultiSubAgentDomainService
private Flux<StreamChunk> handleSwitchNew(String sessionId, String intent, String rewrittenInput) {
    ActiveAgentInfo currentActive = getOwnActiveAgent(sessionId);
    if (currentActive != null) {
        suspendOwnAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
        // 挂起: 将activeAgent信息存入DomainState.suspendedAgents
    }
    return executeNewAgent(sessionId, intent, rewrittenInput);
}
```

**新建Agent**: 生成独立threadId, 构建Graph输入, 执行Graph

```java
protected Flux<StreamChunk> executeNewAgent(String sessionId, String intent, String rewrittenInput) {
    var graph = subGraphRegistry.getGraph(intent);
    String threadId = generateThreadId(sessionId, intent);  // sessionId-intent-hexSuffix
    Map<String, Object> input = buildGraphInput(sessionId, rewrittenInput);
    setOwnActiveAgent(sessionId, intent, threadId);
    return graphExecutionEngine.executeGraph(graph, intent, input, threadId)
            .doOnNext(chunk -> handleActiveAgentState(sessionId, chunk));
}
```

#### 3.4.4 RESUME (路由恢复)

**含义**: 恢复之前挂起的子图(从suspendedAgents中恢复)

**触发条件**: Phase1返回RESUME, 且有suspendedAgents存在

**无挂起时降级**: RESUME但无suspendedAgents → 降级为SWITCH

```java
if ("RESUME".equals(phase1.getRouteType()) && !hasOwnSuspendedAgents(sessionId)) {
    phase1 = RoutingResult.builder()
            .routeType("SWITCH").confidence(0.5)
            .reasoning("RESUME但无挂起线程,降级为新意图").build();
}
```

**恢复流程**:

```java
private Flux<StreamChunk> handleResume(String sessionId, String intent, String userInput) {
    SuspendedInfo suspendedInfo = getOwnSuspendedAgent(sessionId, intent);
    if (suspendedInfo == null) {
        return handleSwitchNew(sessionId, intent, userInput);  // 无挂起 → 降级SWITCH
    }

    // 挂起当前活跃Agent(如有)
    ActiveAgentInfo currentActive = getOwnActiveAgent(sessionId);
    if (currentActive != null && !currentActive.getIntent().equals(suspendedInfo.getIntent())) {
        suspendOwnAgent(sessionId, currentActive.getIntent(), currentActive.getThreadId());
    }

    resumeOwnAgent(sessionId, intent);  // 从suspendedAgents中移除
    setOwnActiveAgent(sessionId, intent, suspendedInfo.getThreadId());

    Map<String, Object> globalStateData = globalSessionStore.getOrCreate(sessionId).data();
    return graphExecutionEngine.resumeGraph(graph, intent, userInput,
                    suspendedInfo.getThreadId(), globalStateData);
}
```

**Auto-upgrade SWITCH/RESUME**: 如果Phase2识别的意图恰好在suspendedAgents中,自动升级为RESUME

```java
if (!"RESUME".equals(resolution.getRouteType())
        && getOwnSuspendedAgent(sessionId, resolution.getIntentName()) != null) {
    log.info("Auto-upgrade {}→RESUME for suspended intent={}", resolution.getRouteType(), ...);
    return handleResume(sessionId, resolution.getIntentName(), resolution.getRewrittenInput());
}
```

#### 3.4.5 消歧 (Disambiguation)

**含义**: 多个意图共享关键词,需要追问用户明确意图

**配置** (application.yml):
```yaml
routing:
  intent-groups:
    - group-id: WEALTH
      display-name: "理财"
      intent-names: [WEALTH_CONSULT, WEALTH_INTERPRET]
      disambiguation-question: "请问您需要理财咨询还是理财产品解读？"
```

**触发条件** (SubGraphResolver中的双重检查):

| 场景 | LLM标ambiguous | confidence | 结果 |
|------|----------------|------------|------|
| LLM标ambiguous + 低置信度 | ✅ | < highConfidenceBypass(0.85) | 触发消歧 |
| LLM标ambiguous + 高置信度 | ✅ | ≥ highConfidenceBypass(0.85) | 信任首选意图,不消歧 |
| 未标ambiguous + 低置信度 + 属于歧义组 | ❌ | < disambiguationThreshold(0.7) | 补充触发消歧 |
| 未标ambiguous + 高置信度 | ❌ | ≥ threshold | 正常路由 |

**消歧流程**:
1. triggerDisambiguation → 返回`RoutingResolution.disambiguation(question, candidates)`
2. MultiSubAgentDomainService收到DISAMBIGUATION → 挂起当前activeAgent → 返回追问
3. 用户回答后再次进入handle() → 检测到inDisambiguation → handleDisambiguationAnswer()
4. 再次调SubGraphRouter识别 → 如果匹配到组内意图 → RESOLVED
5. 如果仍然模糊 → REJECTED

**消歧状态存储**: `DomainState.disambiguation` (DisambiguationState, 包含groupId)

**取消消歧**: 用户输入"取消/算了" → `isCancelExpression()` → `RoutingResolution.cancelled()`

#### 3.4.6 REROUTE (跨域重路由)

**含义**: L1发现用户意图不属于当前域,需要L0重新路由

**触发条件**: SubGraphRouter判断 `belongsToDomain=false`

**数据流**:

```
L2 SubGraphRouter → belongsToDomain=false
  → SubGraphResolver → RoutingResolution.outOfDomain(intent, rewrittenInput)
    → MultiSubAgentDomainService → StreamChunk.reroute(intent, null)
      → BankController 检测 REROUTE chunk
        → excludedDomains.add(currentDomain)
          → 重新调用 DomainRouter.route() (排除当前域)
```

**L1层代码** (MultiSubAgentDomainService):
```java
if (resolution.isResolved() && !isOwnIntent(effectiveIntent)) {
    log.info("Cross-domain intent detected: intent={} not in handledIntents, → REROUTE",
            logTag, effectiveIntent);
    return Flux.just(StreamChunk.reroute(effectiveIntent, null));
}
if (resolution.isOutOfDomain()) {
    return Flux.just(StreamChunk.reroute(effectiveIntent, null));
}
```

**REROUTE不对用户可见**: `StreamChunk.rerouteIntent` 和 `rerouteHint` 标记了 `@JsonIgnore`,前端看不到

**最大重试**: `routing.reroute.max-attempts`(默认2), 超限后CHAT域兜底

#### 3.4.7 拒答 (REJECTED)

**触发条件**:
- 意图完全无法识别(UNKNOWN)
- 消歧回答仍然模糊
- 模糊匹配也找不到任何意图

**代码** (SubGraphResolver):
```java
// 意图完全无法识别
if (effectiveIntent == null || "UNKNOWN".equalsIgnoreCase(effectiveIntent)) {
    return RoutingResolution.rejected();
}

// 模糊匹配也失败
effectiveIntent = subGraphRegistry.fuzzyMatchIntent(effectiveIntent, userInput);
if (effectiveIntent == null) {
    return RoutingResolution.rejected();
}
```

**L1层处理**:
```java
case REJECTED -> Flux.just(StreamChunk.complete(null, rejectedMessage));
```

**rejectedMessage**: 在DomainServiceConfig中配置,如"该理财功能暂不支持"

#### 3.4.8 对话历史(History)如何辅助路由决策

对话历史存储在 `GlobalSessionContext.state` 的 `messages` key (AppendStrategy)。

**L0层** (DomainRouter):
- 读取最近l0-max-pairs(默认10)对消息
- 用于: LLM判断用户意图属于哪个领域
- lastDomain兜底: 对话历史也无法判断时,使用最近活跃领域

**L1层** (ContextRouter):
- 读取最近l1-max-pairs(默认6)对消息
- 用于: 判断FOLLOW/SWITCH/RESUME
- lastQuestion: 子智能体的提问是FOLLOW判断的关键依据

**L1层** (SubGraphRouter):
- 使用同一个chatHistory
- 用于: 意图识别 + 上下文改写(如"它"→指代解析)

**代码**:
```java
// AbstractDomainService
protected String getFormattedChatHistory(String sessionId) {
    return globalSessionStore.getOrCreate(sessionId).formatRecentMessages(l1DomainPairs);
}
```

**消息写入** (统一由BankController):
- UserMessage: 管道入口写入 `ctx.addUserMessage(userInput)`
- AssistantMessage: 终结chunk时由AssistantAccumulator写入

```java
public void onChunk(StreamChunk chunk) {
    if (chunk.getType() == ChunkType.CHUNK) {
        accumulator.append(chunk.getContent());  // 累积流式文本
    }
    if (chunk.isTerminal() && chunk.getType() != ChunkType.REROUTE) {
        String fullReply = accumulator.length() > 0
                ? accumulator.toString() : chunk.getReplyContent();
        if (fullReply != null && !fullReply.isEmpty()) {
            ctx.addAssistantMessage(fullReply);  // 写入完整助手消息
        }
    }
}
```

### 3.5 L2: 子Graph执行

#### 执行入口

```java
GraphExecutionEngine.executeGraph(graph, intent, input, threadId)
GraphExecutionEngine.resumeGraph(graph, intent, userInput, threadId, globalStateData)
```

#### 流式/非流式分流

```java
public Flux<StreamChunk> executeGraph(CompiledGraph graph, String intent,
                                       Map<String, Object> input, String threadId) {
    boolean streamable = subGraphRegistry.isStreamable(intent);
    if (!streamable) {
        return executeBlocking(graph, intent, input, threadId);   // 非流式
    }
    return executeStreaming(graph, intent, input, threadId);      // 流式
}
```

**isStreamable来源**: 各GraphConfig在自注册时设置

```java
// WealthInterpretGraphConfig (流式)
subGraphRegistry.bindGraph("WEALTH_INTERPRET", compiled, true);

// TransferGraphConfig (非流式)
subGraphRegistry.bindGraph("TRANSFER", compiled);  // 默认false
```

#### 非流式路径

```java
private Flux<StreamChunk> executeBlocking(CompiledGraph graph, String intent,
                                           Map<String, Object> input, String threadId) {
    return Flux.defer(() -> {
        RunnableConfig config = threadConfig(threadId);
        graph.stream(input, config).blockLast();                    // 阻塞等待执行完成
        WorkflowOutput output = checkGraphResult(graph, config, intent);  // 检查结果
        return Flux.just(StreamChunk.fromWorkflowOutput(output));   // 单个StreamChunk
    });
}
```

#### 流式路径

```java
private Flux<StreamChunk> executeStreaming(CompiledGraph graph, String intent,
                                            Map<String, Object> input, String threadId) {
    RunnableConfig config = threadConfig(threadId);
    return graph.graphResponseStream(input, config)
        .flatMap(graphResponse -> {
            StreamChunk chunk = mapStreamingOutput(graphResponse, intent);  // 只取AGENT_MODEL_STREAMING
            return chunk != null ? Flux.just(chunk) : Flux.empty();
        })
        .concatWith(Flux.defer(() -> {
            StreamChunk terminal = buildStreamingTerminalChunk(graph, config, intent);  // 终结chunk
            return Flux.just(terminal);
        }));
}
```

**mapStreamingOutput**: 只提取 `AGENT_MODEL_STREAMING` / `GRAPH_NODE_STREAMING` 类型的输出,过滤中间节点

#### 中断判断 (interruptBefore)

```java
private WorkflowOutput checkGraphResult(CompiledGraph graph, RunnableConfig config, String intent) {
    var snapshot = graph.getState(config);
    String nextNode = snapshot.next();

    // interruptBefore中断: next()非空且非__END__
    if (nextNode != null && !nextNode.isEmpty() && !nextNode.equals("__END__")) {
        return WorkflowOutput.interrupted(intent, question);
    }

    // ask→END中断: graph结束但有提问(兜底)
    if (question != null && !question.isEmpty()) {
        return WorkflowOutput.interrupted(intent, question);
    }

    // 正常完成 → 清理checkpoint
    clearCheckpoint(graph, config, intent);
    return WorkflowOutput.completed(intent, content);
}
```

#### Graph输入构建

```java
private Map<String, Object> buildGraphInput(String sessionId, String rewrittenInput) {
    Map<String, Object> input = new HashMap<>();
    input.put("messages", rewrittenInput);
    input.put("_latestUserInput", rewrittenInput);
    input.put("_question", null);
    // 注入全局状态数据
    Map<String, Object> globalData = globalSessionStore.getOrCreate(sessionId).data();
    if (globalData != null && !globalData.isEmpty()) {
        input.put("_globalStateData", globalData);
    }
    return input;
}
```

### 3.6 自注册模式

每个GraphConfig在`@Bean`方法中完成Graph编译后自注册到SubGraphRegistry:

```java
@Configuration
public class TransferGraphConfig extends AbstractGraphConfig {

    private final SubGraphRegistry subGraphRegistry;

    @Bean
    public CompiledGraph transferGraph(SubGraphRegistry subGraphRegistry, ...) {
        this.subGraphRegistry = subGraphRegistry;
        CompiledGraph compiled = graph.compile(...);
        subGraphRegistry.bindGraph("TRANSFER", compiled);  // 自注册
        return compiled;
    }
}
```

**注册时机**: `@Component`(SubGraphRegistry)在`@Configuration`(`@Bean`方法)之前初始化,所以SubGraphRegistry一定先于GraphConfig准备好

---

## 4. SSE数据返回方案

### 4.1 StreamChunk 数据结构

`StreamChunk` 是L0/L1/GES之间的**唯一数据载体**,所有层通过它传递信息。

```java
@Data @Builder
public class StreamChunk {
    private ChunkType type;           // chunk类型
    private String intent;            // 意图名
    private String content;           // 文本内容(CHUNK=增量, COMPLETE=完整)
    private String question;          // 提问(INTERRUPTED/DISAMBIGUATION)
    private String errorMessage;      // 错误信息(ERROR)
    private List<String> candidateIntents;  // 候选意图(DISAMBIGUATION)
    @JsonIgnore
    private String rerouteIntent;     // REROUTE目标意图(内部信号,不暴露前端)
    @JsonIgnore
    private String rerouteHint;       // REROUTE提示(内部信号)
}
```

#### 工厂方法与使用场景

| 工厂方法 | ChunkType | 场景 | 说明 |
|---------|-----------|------|------|
| `chunk(intent, text)` | CHUNK | 流式输出 | 增量文本,前端追加显示 |
| `complete(intent, content)` | COMPLETE | 非流式完成 | 完整文本 |
| `streamingDone(intent)` | COMPLETE | 流式完成 | 结束信号,不带content |
| `interrupted(intent, question)` | INTERRUPTED | 需要用户补充参数 | L2的interruptBefore机制 |
| `disambiguation(question, candidates)` | DISAMBIGUATION | 意图消歧 | 追问+候选列表 |
| `error(errorMessage)` | ERROR | 执行出错 | 错误信息 |
| `reroute(rerouteIntent, rerouteHint)` | REROUTE | 跨域重路由 | **内部信号,前端不可见** |

#### 中间态 vs 终结态

```
中间态: CHUNK — 可以有0~N个
终结态: COMPLETE / INTERRUPTED / DISAMBIGUATION / ERROR / REROUTE — 有且仅有1个(最后一个)

一个Flux<StreamChunk>的结构:
  [CHUNK, CHUNK, CHUNK, ..., TERMINAL]
```

```java
public boolean isTerminal() {
    return type != ChunkType.CHUNK;
}
```

### 4.2 ChunkType 枚举

```java
public enum ChunkType {
    CHUNK("CHUNK"),              // 流式文本增量 — 中间态
    COMPLETE("COMPLETE"),        // 完成 — 终结态
    INTERRUPTED("INTERRUPTED"),  // 中断 — 终结态
    DISAMBIGUATION("DISAMBIGUATION"), // 消歧 — 终结态
    ERROR("ERROR"),              // 错误 — 终结态
    REROUTE("REROUTE");          // 重路由 — 终结态(L1→L0信号)
}
```

### 4.3 SSE输出流程

```
BankController.chat()
  │
  ├── Accept: text/event-stream?
  │   └── sseAdapter.toSse(pipeline, response)
  │       ├── 创建 SseEmitter(0L) — 无超时
  │       ├── 设置响应头: X-Accel-Buffering=no (防止Nginx缓冲)
  │       ├── 订阅 Flux<StreamChunk>:
  │       │   ├── doOnNext:  emitter.send(serialize(chunk))
  │       │   ├── doOnComplete: emitter.send("[DONE]") + emitter.complete()
  │       │   └── doOnError: emitter.completeWithError(e)
  │       └── 返回 SseEmitter (Spring MVC自动处理SSE输出)
  │
  └── Accept: application/json?
      └── sseAdapter.toJson(pipeline)
          └── pipeline.filter(isTerminal).last().map(toWorkflowOutput).block()
```

#### 关键代码: SseOutputAdapter

```java
@Component
public class SseOutputAdapter {

    public SseEmitter toSse(Flux<StreamChunk> pipeline, HttpServletResponse response) {
        SseEmitter emitter = new SseEmitter(0L);  // 无超时(由Flux控制生命周期)
        response.addHeader("X-Accel-Buffering", "no");     // 防止反向代理缓冲
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);

        pipeline
            .doOnNext(chunk -> {
                emitter.send(serialize(chunk), MediaType.TEXT_EVENT_STREAM);
            })
            .doOnComplete(() -> {
                emitter.send("[DONE]", MediaType.TEXT_EVENT_STREAM);  // OpenAI格式
                emitter.complete();
            })
            .doOnError(emitter::completeWithError)
            .subscribe();

        return emitter;
    }

    public Object toJson(Flux<StreamChunk> pipeline) {
        return pipeline
                .filter(StreamChunk::isTerminal)
                .last()
                .map(StreamChunk::toWorkflowOutput)
                .block();
    }
}
```

### 4.4 SSE输出示例

#### 流式响应 (WEALTH_INTERPRET)

```
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"根据"}
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"您选择的"}
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"稳健型投资策略"}
data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"，为您推荐..."}
data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}
data: [DONE]
```

注意: 流式COMPLETE不带content,前端已通过CHUNK获得全部文本

#### 非流式响应 (TRANSFER)

```
data: {"type":"COMPLETE","intent":"TRANSFER","content":"转账成功！已向张三转账500.00元。交易流水号：TXN123456"}
data: [DONE]
```

注意: 非流式COMPLETE带完整content

#### 中断(需要参数)

```
data: {"type":"INTERRUPTED","intent":"TRANSFER","question":"请问您要转给谁？"}
data: [DONE]
```

#### 消歧追问

```
data: {"type":"DISAMBIGUATION","question":"请问您需要理财咨询还是理财产品解读？","candidateIntents":["WEALTH_CONSULT","WEALTH_INTERPRET"]}
data: [DONE]
```

#### 错误

```
data: {"type":"ERROR","errorMessage":"执行出错: Connection timeout"}
data: [DONE]
```

### 4.5 消息累积机制

BankController.AssistantAccumulator负责将流式输出累积为完整的助手消息:

```
CHUNK("根据") → accumulator = "根据"
CHUNK("产品信息") → accumulator = "根据产品信息"
CHUNK("分析...") → accumulator = "根据产品信息分析..."
COMPLETE(终结) → ctx.addAssistantMessage("根据产品信息分析...")
```

- 流式Graph: 终结chunk不带content, 从累积器获取完整文本
- 非流式Graph: 终结chunk带content, 直接使用

### 4.6 配置切换: In-Memory vs Redis

通过 `application.yml` 的 `storage.type` 一键切换:

```yaml
storage:
  type: in-memory    # in-memory | redis
  redis:
    host: localhost
    port: 6379
```

**切换机制**: `@ConditionalOnProperty(name = "storage.type", havingValue = "xxx")`

```java
@Configuration
public class GlobalSessionRepositoryConfig {
    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "in-memory", matchIfMissing = true)
    public GlobalSessionRepository inMemoryRepository() { ... }

    @Bean
    @ConditionalOnProperty(name = "storage.type", havingValue = "redis")
    public GlobalSessionRepository redisRepository() { ... }
}
```

**影响的组件**:
- GlobalSessionRepository (接口) → InMemory / Redis 实现自动选择
- SubGraphCheckpointSaver → InMemory / Redis 实现自动选择

**切换方式**: 改yml → 重启 → 生效,无需改Java代码

---

## 5. 关键配置项速查

| 配置项 | 作用 | 默认值 |
|-------|------|-------|
| `storage.type` | In-Memory/Redis切换 | `in-memory` |
| `storage.redis.host` | Redis主机地址 | `localhost` |
| `storage.redis.port` | Redis端口 | `6379` |
| `models.domain.model` | L0领域路由模型 | `qwen-plus` |
| `models.context.model` | L1上下文路由模型 | `qwen-plus` |
| `models.intent.model` | L1意图路由模型 | `qwen-plus` |
| `models.chat.model` | 闲聊模型 | `qwen-turbo` |
| `models.wealth-interpret.model` | 理财解读模型(流式) | `qwen-plus` |
| `routing.domains.*` | 领域关键词(DomainRouter确定性路由) | — |
| `routing.intents.*` | 子图元数据(SubGraphRegistry启动读取) | — |
| `routing.intent-groups.*` | 消歧组配置 | — |
| `routing.confidence.disambiguation-threshold` | 消歧触发阈值 | `0.7` |
| `routing.confidence.high-confidence-bypass` | 高置信度豁免 | `0.85` |
| `routing.history.l0-max-pairs` | L0历史对数 | `10` |
| `routing.history.l1-max-pairs` | L1历史对数 | `10` |
| `routing.history.chat-max-pairs` | 闲聊历史对数 | `10` |
| `routing.deterministic.cancel-keywords` | 取消关键词 | `["算了","取消",...]` |
| `routing.verification.max-retries` | L2提参校验重试次数 | `1` |
| `routing.reroute.max-attempts` | REROUTE最大重试次数 | `2` |
| `session.pending-agents.max-depth` | 单域最大挂起Agent数 | `5` |
| `session.pending-agents.expire-minutes` | 挂起Agent过期时间(分钟) | `5` |
| `session.last-domain.expire-minutes` | 最近活跃领域过期时间(分钟) | `5` |
| `logging.level.com.mobileagent` | 项目日志级别 | `DEBUG` |
