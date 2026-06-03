# 业务域接入指南

> 本文档面向业务开发团队，详细说明如何在框架中新建一个 L1 Domain、挂接 L2 子 Graph、注册意图、配置消歧、适配 AskNode、声明流式输出，以及如何获取 GlobalSessionContext。

---

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [接入全景图：7 步接入清单](#2-接入全景图7-步接入清单)
3. [Step 1: 注册意图 (application.yml)](#3-step-1-注册意图-applicationyml)
4. [Step 2: 注册领域关键词 (application.yml)](#4-step-2-注册领域关键词-applicationyml)
5. [Step 3: 配置消歧组 (application.yml，可选)](#5-step-3-配置消歧组-applicationyml可选)
6. [Step 4: 注册 DomainStateAware (Java)](#6-step-4-注册-domainstateaware-java)
7. [Step 5: 创建 L2 子 Graph (Java)](#7-step-5-创建-l2-子-graph-java)
8. [Step 6: 创建 L1 DomainService 并注册 (Java)](#8-step-6-创建-l1-domainservice-并注册-java)
9. [Step 7: 绑定 Graph 到 IntentRegistry (Java)](#9-step-7-绑定-graph-到-intentregistry-java)
10. [L2 子 Graph 适配详解](#10-l2-子-graph-适配详解)
11. [获取 GlobalSessionContext 的方法](#11-获取-globalsessioncontext-的方法)
12. [流式 vs 非流式输出](#12-流式-vs-非流式输出)
13. [Single 域 vs Multi 域选择](#13-single-域-vs-multi-域选择)
14. [完整示例：新建"保险"域](#14-完整示例新建保险域)
15. [关键枚举与配置项速查](#15-关键枚举与配置项速查)
16. [常见问题](#16-常见问题)

---

## 1. 整体架构概览

```
用户请求
  │
  ▼
BankController (L0)
  │  DomainRouter.route() → 判断领域 (TRANSFER / BILL / WEALTH / 你的新域)
  │
  ▼
DomainServiceRegistry.getHandler(domain) → 找到你的 L1 DomainService
  │
  ▼
你的 L1 DomainService
  │  ContextRouter → FOLLOW / SWITCH / RESUME
  │  IntentRouter / IntentResolver → 识别具体子意图
  │
  ▼
IntentRegistry.getGraph(intentName) → 找到你的 L2 子 Graph
  │
  ▼
GraphExecutionEngine.executeGraph() / resumeGraph()
  │
  ▼
你的 L2 子 Graph (StateGraph)
  │  extractParams → paramRouter → askNode(s) → executeNode
  │
  ▼
StreamChunk → 返回给前端
```

**你要做的事：**

| 层 | 做什么 | 在哪做 |
|----|--------|--------|
| L0 | 声明你的领域关键词和意图 | `application.yml` |
| L1 | 创建 DomainService Bean，注册 DomainStateAware | `DomainServiceConfig.java` |
| L2 | 继承 `AbstractGraphConfig`，定义子 Graph | 新建 `XxxGraphConfig.java` |
| 绑定 | 在 `AppInitConfig` 中将 Graph 绑定到 IntentRegistry | `AppInitConfig.java` |

---

## 2. 接入全景图：7 步接入清单

```
Step 1  ──→  application.yml: routing.intents        ← 注册意图 (name, scope, intentType, isWriteOp)
Step 2  ──→  application.yml: routing.domains         ← 注册领域关键词
Step 3  ──→  application.yml: routing.intent-groups   ← (可选) 配置消歧组
Step 4  ──→  DomainServiceConfig.java                 ← 注册 DomainStateAware Bean
Step 5  ──→  新建 XxxGraphConfig.java                 ← 继承 AbstractGraphConfig，定义 L2 子 Graph
Step 6  ──→  DomainServiceConfig.java                 ← 创建 DomainService Bean + 注册到 DomainServiceRegistry
Step 7  ──→  AppInitConfig.java                       ← bindGraph(intentName, graph, streamable)
```

**最小改动文件：**
- `application.yml` (1 处)
- `DomainServiceConfig.java` (2 个 Bean)
- `AppInitConfig.java` (1 行 bindGraph)
- 新建 1 个 `XxxGraphConfig.java`

---

## 3. Step 1: 注册意图 (application.yml)

在 `routing.intents` 数组中添加你的意图：

```yaml
routing:
  intents:
    # ---- 已有意图 ----
    - name: TRANSFER
      description: "转账给他人"
      param-schema: "收款人名称, 转账金额, 用途(可选)"
      is-write-op: true
      intent-type: OPERATION
      scope: "资金转账操作，将钱转给他人或理财产品等"

    # ---- 你的新意图 ----
    - name: INSURANCE_CONSULT          # ← 意图名 (大写+下划线，全局唯一)
      description: "保险咨询与推荐"       # ← 给 LLM 看的意图描述
      param-schema: "保险类型(人寿/健康/财产), 保障范围(可选)"  # ← 参数描述
      is-write-op: false                # ← 是否写操作 (影响 L0 路由策略)
      intent-type: CONSULTATION         # ← 意图类型枚举
      scope: "保险产品咨询、推荐与方案设计"  # ← 供 IntentRouter 判断 belongs_to_domain
```

### 关键字段详解

| 字段 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `name` | ✅ | 意图唯一标识，大写+下划线，全局唯一 | `INSURANCE_CONSULT` |
| `description` | ✅ | 给 LLM 看的意图描述，影响识别准确率 | `"保险咨询与推荐"` |
| `param-schema` | ✅ | 参数描述，供 LLM 提取参数时参考 | `"保险类型, 保障范围"` |
| `is-write-op` | ✅ | 是否写操作。`true` = 资金变动类 (转账)，`false` = 查询/咨询类 | `false` |
| `intent-type` | ✅ | 意图类型枚举，影响路由策略 | `OPERATION` / `QUERY` / `CONSULTATION` |
| `scope` | ✅ | 意图的处理范围描述，供 IntentRouter 判断 `belongs_to_domain` | `"保险产品咨询与推荐"` |

### intent-type 枚举

| 值 | 含义 | 典型场景 | L0 路由行为 |
|----|------|---------|------------|
| `OPERATION` | 写操作，有资金变动 | 转账、支付 | 需要更高置信度才路由 |
| `QUERY` | 读操作，纯查询 | 账单查询、交易明细 | 较低置信度即可路由 |
| `CONSULTATION` | 咨询类，建议/推荐 | 理财咨询、保险推荐 | 较低置信度即可路由 |

### scope 的作用

`scope` 用于 IntentRouter 判断用户的请求是否属于**你的域**。当 IntentRouter 的 LLM 看到以下描述：

```
- INSURANCE_CONSULT [CONSULTATION]: 保险产品咨询、推荐与方案设计
```

如果用户说"我想买保险"，LLM 会判断 `belongs_to_domain=true`；如果用户说"帮我转账"，LLM 会判断 `belongs_to_domain=false` → 触发 REROUTE。

---

## 4. Step 2: 注册领域关键词 (application.yml)

在 `routing.domains` 中添加你的领域：

```yaml
routing:
  domains:
    # ---- 已有领域 ----
    TRANSFER:
      keywords: ["转账", "转钱", "汇款", "打款", "付款", "转给", "打给"]
    BILL:
      keywords: ["账单", "明细", "消费", "支出", "收入", "收支", "开销", "流水"]
    WEALTH:
      keywords: ["理财", "投资", "收益", "基金", "推荐", "咨询", "解读", "股票"]

    # ---- 你的新领域 ----
    INSURANCE:                                    # ← 领域名 (大写，与 DomainServiceConfig 注册名一致)
      keywords: ["保险", "投保", "保单", "理赔", "保费", "险种", "保障"]  # ← L0 确定性路由关键词
```

### 关键词的作用

DomainRouter 有两层路由机制：
1. **确定性路由 (0ms)**: 精确匹配关键词，如果命中则直接路由到对应领域
2. **LLM 路由 (200-500ms)**: 关键词未命中时，由 LLM 理解用户意图选择领域

**关键词选择原则：**
- 只选**无歧义**的词 — "保险"一定指保险域
- 不要选跨域通用词 — "推荐"既可能是理财也可能是保险
- 宁少勿滥 — 关键词误匹配比 LLM 路由更难修正

---

## 5. Step 3: 配置消歧组 (application.yml，可选)

只有当你的域下有**多个子意图**且用户可能无法区分时，才需要配置消歧组。

```yaml
routing:
  intent-groups:
    # ---- 已有消歧组 ----
    - group-id: WEALTH
      display-name: "理财"
      intent-names: [WEALTH_CONSULT, WEALTH_INTERPRET]
      disambiguation-question: "请问您需要理财咨询还是理财产品解读？"

    # ---- 你的消歧组 (如果域下有多个子意图) ----
    - group-id: INSURANCE
      display-name: "保险"
      intent-names: [INSURANCE_CONSULT, INSURANCE_CLAIM]   # ← 组内意图列表
      disambiguation-question: "请问您需要保险咨询还是理赔申请？"  # ← 消歧追问话术
```

### 消歧触发条件

当以下条件**同时满足**时，系统触发消歧追问：
1. LLM 无法区分组内具体意图 (`is_ambiguous=true`)
2. 置信度低于 `high-confidence-bypass` (默认 0.85)

或者：
1. LLM 给出了具体意图但置信度低于 `disambiguation-threshold` (默认 0.7)
2. 该意图属于某个消歧组

**如果域下只有 1 个子意图，不需要配置消歧组。**

---

## 6. Step 4: 注册 DomainStateAware (Java)

在 `DomainServiceConfig.java` 中添加一个轻量级 Bean：

```java
@Configuration
public class DomainServiceConfig {

    // ---- 已有注册 ----
    @Bean
    public DomainStateAware transferDomainStateAware() {
        return DomainStateAware.of("_transferState", "TRANSFER");
    }

    // ---- 你的新注册 ----
    @Bean
    public DomainStateAware insuranceDomainStateAware() {
        return DomainStateAware.of("_insuranceState", "INSURANCE");
    }
    //                 ↑ stateKey          ↑ domain名
    //  约定: "_" + domainKey小写 + "State"
    //  例如: INSURANCE → _insuranceState
```

### DomainStateAware.of() 参数

| 参数 | 约定 | 说明 |
|------|------|------|
| `stateKey` | `"_" + domainKey小写 + "State"` | OverAllState 中的 key，如 `_insuranceState` |
| `domain` | 大写，与 yml 中的领域名一致 | DomainState 内部的 domain 字段 |

### 这一步做了什么？

1. Spring 自动收集所有 `DomainStateAware` Bean
2. `KeyStrategyFactoryConfig` 将你的 `_insuranceState` 注册到全局 KeyStrategyFactory (ReplaceStrategy)
3. 新建 `GlobalSessionContext` 时，OverAllState 自动包含 `_insuranceState` 这个 key
4. 你的 L1 DomainService 就能通过 `ctx.getDomainState("_insuranceState")` 读写域状态

---

## 7. Step 5: 创建 L2 子 Graph (Java)

继承 `AbstractGraphConfig`，实现 5 个抽象方法 + 1 个可选覆盖：

```java
@Slf4j
@Configuration
public class InsuranceConsultGraphConfig extends AbstractGraphConfig {

    // ==================== 构造函数 (固定模板) ====================

    public InsuranceConsultGraphConfig(
            @Qualifier("paramExtractChatModel") ChatModel chatModel,   // ← 参数提取用的小模型
            SubGraphCheckpointSaverConfig.SubGraphCheckpointSaverFactory subGraphCheckpointSaverFactory,
            MockBankingService mockBankingService,                      // ← 替换成你的业务服务
            ObjectMapper objectMapper) {
        super(chatModel, objectMapper, subGraphCheckpointSaverFactory);
    }

    // ==================== 必须实现的 5 个抽象方法 ====================

    @Override
    protected String getGraphName() {
        return "InsuranceConsultGraph";    // ← 用于日志
    }

    @Override
    protected String getCancelDetectionContext() {
        return "正在向用户询问保险咨询条件";    // ← 取消检测的上下文描述
    }

    @Override
    protected List<String> getCancelKeywords() {
        List<String> keywords = new ArrayList<>(super.getCancelKeywords());  // ← 先加基类通用词
        keywords.addAll(List.of("不买了", "取消投保", "不用推荐了"));           // ← 加域专属词
        return keywords;
    }

    @Override
    protected void registerCustomKeys(Map<String, KeyStrategy> strategies) {
        // 注册你的业务参数 key — 全部用 ReplaceStrategy
        strategies.put("insurance.type", new ReplaceStrategy());       // 保险类型: 人寿/健康/财产
        strategies.put("insurance.coverage", new ReplaceStrategy());   // 保障范围
    }

    @Override
    protected String buildExtractPrompt(String userInput) {
        return """
            你是一个保险咨询参数提取器。从用户输入中提取保险咨询相关参数。

            用户输入: %s

            提取规则:
            - type: 保险类型,只能为"人寿"、"健康"、"财产"之一
            - coverage: 保障范围(可选),如"重疾保障"、"意外保障"
            - 只提取用户明确提到的参数,不猜测

            严格输出JSON:
            {
              "type": "保险类型或null",
              "coverage": "保障范围或null"
            }
            """.formatted(userInput);
    }

    @Override
    protected Map<String, Object> parseExtractResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            String type = node.has("type") && !node.get("type").isNull()
                    ? node.get("type").asText() : null;
            String coverage = node.has("coverage") && !node.get("coverage").isNull()
                    ? node.get("coverage").asText() : null;
            if (type != null && !type.isEmpty()) result.put("insurance.type", type);
            if (coverage != null && !coverage.isEmpty()) result.put("insurance.coverage", coverage);
        } catch (Exception e) {
            log.warn("[InsuranceConsultGraph] Failed to parse extract result: {}", content, e);
        }
        return result;
    }

    // ==================== Graph 构建 (核心) ====================

    @Bean("insuranceConsultGraph")
    public CompiledGraph insuranceConsultGraph() throws GraphStateException {
        Map<String, String> paramEdges = new HashMap<>(Map.of(
                "ASK_TYPE", "askType",              // ← 缺保险类型 → askType 节点
                "ALL_GOOD", "executeInsuranceConsult"  // ← 参数齐全 → 执行节点
        ));
        addCancelEdge(paramEdges);                  // ← 加取消路由

        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode("extractParams", node_async(this::extractParamsNode))
                .addNode("paramRouter", node_async(this::paramRouterNode))
                .addNode("askType", askNode("askType", this::askTypeLogic))   // ← askNode 自动注册 interruptBefore
                .addNode("executeInsuranceConsult", node_async(this::executeNode))
                .addEdge(START, "extractParams")
                .addEdge("extractParams", "paramRouter")
                .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
                .addEdge("executeInsuranceConsult", END);

        addCancelNode(graph);                       // ← 加取消执行节点

        // ask 节点条件路由 (有用户输入→paramRouter, 无→END)
        addAskConditionalEdges(graph, "askType");

        // 使用自动收集的 interruptNodes 编译
        CompiledGraph compiled = graph.compile(createInterruptCompileConfig());

        log.info("[InsuranceConsultGraph] Compiled successfully");
        return compiled;
    }

    // ==================== 节点实现 ====================

    private Map<String, Object> extractParamsNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareExtractParams(state);  // ← 自动拦截取消
        if (cancelResult != null) return cancelResult;

        String userInput = getLatestInput(state);
        log.info("[InsuranceConsultGraph.extractParams] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            mergeExtractedWithoutOverwrite(result, extracted, state);  // ← 自动保护已有参数
            log.info("[InsuranceConsultGraph.extractParams] extracted: {}", extracted);
        } catch (Exception e) {
            log.error("[InsuranceConsultGraph.extractParams] LLM extraction failed", e);
        }
        return result;
    }

    private Map<String, Object> paramRouterNode(OverAllState state) {
        Map<String, Object> cancelResult = cancelAwareParamRouter(state);    // ← 自动拦截取消
        if (cancelResult != null) return cancelResult;

        String type = getStringValue(state, "insurance.type");

        Map<String, Object> result = new HashMap<>();
        if (type == null || type.isEmpty()) {
            result.put("_question", "请问您想咨询哪种保险？(人寿/健康/财产)");
            result.put("_paramName", "ASK_TYPE");
            log.info("[InsuranceConsultGraph.paramRouter] Missing type → ASK_TYPE");
        } else {
            result.put("_question", null);
            result.put("_paramName", "ALL_GOOD");
            log.info("[InsuranceConsultGraph.paramRouter] All params present → ALL_GOOD");
        }
        return result;
    }

    private Map<String, Object> askTypeLogic(OverAllState state) {
        String userInput = getLatestInput(state);
        log.info("[InsuranceConsultGraph.askType] userInput={}", userInput);

        Map<String, Object> result = new HashMap<>();
        if (userInput == null || userInput.isEmpty()) {
            return result;
        }

        try {
            Map<String, Object> extracted = callExtractModel(userInput);
            String type = (String) extracted.get("insurance.type");
            if (type != null && !type.isEmpty()) {
                result.put("insurance.type", type);
            }
        } catch (Exception e) {
            log.error("[InsuranceConsultGraph.askType] Extraction failed", e);
        }
        result.put("_latestUserInput", "");    // ← 清空，防止重复读取
        return result;
    }

    private Map<String, Object> executeNode(OverAllState state) {
        String type = getStringValue(state, "insurance.type");
        // 调用你的业务服务...
        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", "根据您的需求，推荐以下" + type + "保险方案...");
        result.put("_outputType", "TEXT");
        result.put("_isFinal", true);
        return result;
    }

    // ==================== SubAgent 数据快照 (可选) ====================

    @Override
    protected Map<String, Object> extractSubAgentDataSnapshot(OverAllState state) {
        Map<String, Object> data = new HashMap<>();
        String type = getStringValue(state, "insurance.type");
        if (type != null && !type.isEmpty()) data.put("type", type);
        String coverage = getStringValue(state, "insurance.coverage");
        if (coverage != null && !coverage.isEmpty()) data.put("coverage", coverage);
        return data;
    }
}
```

---

## 8. Step 6: 创建 L1 DomainService 并注册 (Java)

在 `DomainServiceConfig.java` 中添加 Bean：

### 如果域下只有 1 个子意图 → 用 SingleSubAgentDomainService

```java
@Configuration
public class DomainServiceConfig {

    // ---- 你的新域 (Single: 1个L1对应1个L2) ----
    @Bean("insuranceDomainService")
    public SingleSubAgentDomainService insuranceDomainService(
            ContextRouter contextRouter,
            IntentRouter intentRouter,                        // ← Single 域用 IntentRouter
            GraphExecutionEngine graphExecutionEngine,
            IntentRegistry intentRegistry,
            GlobalSessionStateStore globalSessionStore,
            DomainServiceRegistry domainServiceRegistry,
            @Value("${routing.history.l1-max-pairs:6}") int l1MaxPairs) {
        SingleSubAgentDomainService service = SingleSubAgentDomainService.builder()
                .domainName("保险")                         // ← 中文显示名
                .logTag("InsuranceService")                  // ← 日志标签
                .domainKey("INSURANCE")                      // ← 与 yml 的领域名一致
                .intent("INSURANCE_CONSULT")                 // ← 唯一子意图
                .intentDescription("保险咨询")                // ← 意图描述
                .contextRouter(contextRouter)
                .intentRouter(intentRouter)
                .graphExecutionEngine(graphExecutionEngine)
                .intentRegistry(intentRegistry)
                .globalSessionStore(globalSessionStore)
                .l1DomainPairs(l1MaxPairs)
                .build();
        domainServiceRegistry.register("INSURANCE", service);  // ← 注册到全局注册表
        return service;
    }
```

### 如果域下有多个子意图 → 用 MultiSubAgentDomainService

```java
    // ---- Multi: 1个L1对应N个L2 (支持消歧、挂起、恢复) ----
    @Bean("insuranceDomainService")
    public MultiSubAgentDomainService insuranceDomainService(
            ContextRouter contextRouter,
            IntentResolver intentResolver,                   // ← Multi 域用 IntentResolver
            GraphExecutionEngine graphExecutionEngine,
            IntentRegistry intentRegistry,
            GlobalSessionStateStore globalSessionStore,
            DomainServiceRegistry domainServiceRegistry,
            @Value("${routing.history.l1-max-pairs:6}") int l1MaxPairs) {
        MultiSubAgentDomainService service = MultiSubAgentDomainService.builder()
                .domainName("保险")
                .logTag("InsuranceService")
                .domainKey("INSURANCE")
                .contextRouter(contextRouter)
                .intentResolver(intentResolver)              // ← 不是 intentRouter
                .graphExecutionEngine(graphExecutionEngine)
                .intentRegistry(intentRegistry)
                .globalSessionStore(globalSessionStore)
                .routingTemplatePath("prompts/l1-routing.st")         // ← Multi 域用完整路由模板
                .intentionTemplatePath("prompts/l1-intention.st")     // ← Phase2 意图识别模板
                .rejectedMessage("该保险功能暂不支持，目前仅支持保险咨询和理赔申请")
                .handledIntents(List.of(
                        new AbstractDomainService.IntentInfo("INSURANCE_CONSULT", "保险咨询与推荐"),
                        new AbstractDomainInfo.IntentInfo("INSURANCE_CLAIM", "理赔申请")
                ))
                .maxSuspendedDepth(3)                       // ← 最多同时挂起 3 个子图
                .suspendedExpireMinutes(20)                  // ← 挂起过期时间
                .l1DomainPairs(l1MaxPairs)
                .build();
        domainServiceRegistry.register("INSURANCE", service);
        return service;
    }
```

### Single vs Multi Builder 参数对比

| 参数 | Single | Multi | 说明 |
|------|--------|-------|------|
| `domainName` | ✅ | ✅ | 中文显示名 |
| `logTag` | ✅ | ✅ | 日志标签 |
| `domainKey` | ✅ | ✅ | 大写，与 yml 一致 |
| `intent` | ✅ | ❌ | Single 的唯一意图 |
| `intentDescription` | ✅ | ❌ | 意图描述 |
| `intentRouter` | ✅ | ❌ | Single 用 IntentRouter |
| `intentResolver` | ❌ | ✅ | Multi 用 IntentResolver (含消歧) |
| `routingTemplatePath` | 可选 | ✅ | L1 路由模板路径 |
| `intentionTemplatePath` | 可选 | ✅ | Phase2 意图识别模板路径 |
| `rejectedMessage` | ❌ | ✅ | 意图被拒绝时的回复 |
| `handledIntents` | ❌ | ✅ | 域下所有子意图列表 |
| `maxSuspendedDepth` | ❌ | ✅ | 最大挂起深度 |
| `suspendedExpireMinutes` | ❌ | ✅ | 挂起过期时间 |

---

## 9. Step 7: 绑定 Graph 到 IntentRegistry (Java)

在 `AppInitConfig.java` 中添加绑定：

```java
@Configuration
public class AppInitConfig {

    private final IntentRegistry intentRegistry;
    private final CompiledGraph transferGraph;
    private final CompiledGraph billQueryGraph;
    private final CompiledGraph wealthConsultGraph;
    private final CompiledGraph wealthInterpretGraph;
    private final CompiledGraph insuranceConsultGraph;   // ← 新增

    public AppInitConfig(IntentRegistry intentRegistry,
                          @Qualifier("transferGraph") CompiledGraph transferGraph,
                          @Qualifier("billQueryGraph") CompiledGraph billQueryGraph,
                          @Qualifier("wealthConsultGraph") CompiledGraph wealthConsultGraph,
                          @Qualifier("wealthInterpretGraph") CompiledGraph wealthInterpretGraph,
                          @Qualifier("insuranceConsultGraph") CompiledGraph insuranceConsultGraph) {  // ← 新增
        // ... 赋值
        this.insuranceConsultGraph = insuranceConsultGraph;
    }

    @PostConstruct
    public void bindGraphs() {
        // ... 已有绑定
        intentRegistry.bindGraph("INSURANCE_CONSULT", insuranceConsultGraph);       // ← 非流式
        // intentRegistry.bindGraph("INSURANCE_CONSULT", insuranceConsultGraph, true); // ← 流式
    }
}
```

### bindGraph 的 streamable 参数

```java
// 非流式 (默认) — Graph 执行完毕后一次性返回结果
intentRegistry.bindGraph("INSURANCE_CONSULT", graph);

// 流式 — Graph 执行过程中逐字返回 (如 LLM 生成的解读文本)
intentRegistry.bindGraph("INSURANCE_INTERPRET", graph, true);
```

---

## 10. L2 子 Graph 适配详解

### 10.1 AbstractGraphConfig 提供的"免费"能力

你继承 `AbstractGraphConfig` 后，以下能力**自动获得**，不需要自己实现：

| 能力 | 方法 | 你需要做什么 |
|------|------|------------|
| 取消信号拦截 | `cancelAwareExtractParams()`, `cancelAwareParamRouter()` | 在节点开头调用，检测到取消则直接返回 |
| 关键字+LLM取消检测 | `detectCancelFromInput()` | 自动被 `askNode()` 和 `cancelAwareExtractParams()` 调用 |
| 参数提取 | `callExtractModel()` | 调用 LLM 提取参数，自动用 `buildExtractPrompt()` + `parseExtractResult()` |
| 参数保护 (resume场景) | `mergeExtractedWithoutOverwrite()` | 防止 LLM 的 null 覆盖已有参数 |
| ask 节点 + interruptBefore | `askNode()` | 自动注册 interruptBefore，无需手写 |
| 取消执行节点 | `addCancelNode()` + `addCancelEdge()` | 一行添加取消流程 |
| KeyStrategy 注册 | `createKeyStrategyFactory()` | 公共 key 自动注册，你只需 `registerCustomKeys()` |
| CheckpointSaver | `createSaverConfig()` | 根据 storage.type 自动选择 InMemory/Redis |
| 全局状态注入 | `_globalStateData` | L1 自动注入，L2 通过 `state.value("_globalStateData")` 读取 |

### 10.2 AskNode 适配

**askNode 做了三件事：**
1. 自动包装 `cancelAwareAsk()` — 检测取消意图
2. 自动注册到 `interruptNodes` 列表 — 编译时自动添加 interruptBefore
3. 转换为 `AsyncNodeAction` — 无需手写 `node_async()`

**用法：**

```java
// ❌ 旧写法 (不推荐)
.addNode("askType", node_async(this::askTypeLogic))
// 还要在编译时手写: createCompileConfig("askType")

// ✅ 新写法 (推荐)
.addNode("askType", askNode("askType", this::askTypeLogic))
// 编译时用: createInterruptCompileConfig() — 自动包含所有 askNode
```

**askLogic 只需写纯业务逻辑：**

```java
private Map<String, Object> askTypeLogic(OverAllState state) {
    String userInput = getLatestInput(state);
    Map<String, Object> result = new HashMap<>();
    if (userInput == null || userInput.isEmpty()) return result;

    try {
        Map<String, Object> extracted = callExtractModel(userInput);
        String type = (String) extracted.get("insurance.type");
        if (type != null && !type.isEmpty()) {
            result.put("insurance.type", type);
        }
    } catch (Exception e) {
        log.error("Extraction failed", e);
    }
    result.put("_latestUserInput", "");  // ← 清空用户输入，防止重复读取
    return result;
}
```

### 10.3 多 AskNode 示例

```java
StateGraph graph = new StateGraph(createKeyStrategyFactory())
    .addNode("extractParams", node_async(this::extractParamsNode))
    .addNode("paramRouter", node_async(this::paramRouterNode))
    .addNode("askType", askNode("askType", this::askTypeLogic))
    .addNode("askCoverage", askNode("askCoverage", this::askCoverageLogic))  // ← 第二个 ask
    .addNode("executeNode", node_async(this::executeNode))
    .addEdge(START, "extractParams")
    .addEdge("extractParams", "paramRouter")
    .addConditionalEdges("paramRouter", createCancelAwareRouter(), paramEdges)
    .addEdge("executeNode", END);

addCancelNode(graph);
addAskConditionalEdges(graph, "askType");     // ← 每个 ask 节点都要加
addAskConditionalEdges(graph, "askCoverage");

graph.compile(createInterruptCompileConfig());  // ← 自动包含 askType + askCoverage
```

paramRouter 的 edges：

```java
Map<String, String> paramEdges = new HashMap<>(Map.of(
    "ASK_TYPE", "askType",
    "ASK_COVERAGE", "askCoverage",
    "ALL_GOOD", "executeNode"
));
addCancelEdge(paramEdges);
```

### 10.4 流式输出适配

如果你的 L2 子 Graph 需要流式输出 (如 LLM 生成的长文本解读)：

```java
private Map<String, Object> executeNode(OverAllState state) {
    String productName = getStringValue(state, "insurance.type");
    Map<String, Object> result = new HashMap<>();
    result.put("_outputType", "TEXT");
    result.put("_isFinal", true);

    try {
        // 构建 prompt
        String prompt = "你是一位保险顾问，请详细解读" + productName + "保险...";

        // 调用流式 ChatClient
        Flux<ChatResponse> responseFlux = chatClient.prompt()
                .user(prompt)
                .stream()
                .chatResponse();

        // 将 Flux 放入 result，框架自动检测并逐字推给前端
        result.put("streaming_output", responseFlux);
    } catch (Exception e) {
        // 降级: 非流式返回
        result.put("_outputContent", "保险解读内容...");
    }
    return result;
}
```

**流式关键点：**
1. `result.put("streaming_output", responseFlux)` — key 固定为 `streaming_output`
2. 框架的 `NodeExecutor.getEmbedFlux()` 自动检测 Map 中的 Flux 值
3. 每个 `ChatResponse` → `StreamingOutput` → `StreamChunk.chunk()` → 推给前端
4. 在 `AppInitConfig` 中绑定时要声明 `streamable=true`

---

## 11. 获取 GlobalSessionContext 的方法

### L1 DomainService 中 (推荐方式)

```java
// 通过 AbstractDomainService 的 globalSessionStore 字段
GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);

// 读取对话历史
String chatHistory = getFormattedChatHistory(sessionId);  // ← 封装方法

// 读取 DomainState
DomainState ds = ctx.getDomainState("_insuranceState");
ActiveAgentInfo active = ds != null ? ds.getActiveAgent() : null;

// 写入 DomainState
ctx.updateDomainState("_insuranceState", ds -> {
    ds.setActiveAgent(new ActiveAgentInfo("INSURANCE_CONSULT", threadId, now, expiresAt));
});

// 读取全局数据 (注入 L2 时用)
Map<String, Object> globalData = ctx.data();
```

### L2 子 Graph 中

L2 通过 `_globalStateData` key 读取全局状态：

```java
// 在 L2 节点中
@SuppressWarnings("unchecked")
Map<String, Object> globalData = (Map<String, Object>) state.value("_globalStateData").orElse(Map.of());

// 读取对话历史
List<String> messages = (List<String>) globalData.get("messages");

// 读取某个域的状态
Map<String, Object> insuranceState = (Map<String, Object>) globalData.get("_insuranceState");
```

**注意：** `_globalStateData` 是**只读快照**，L2 不应该直接修改它。如果需要回写数据，通过 `extractSubAgentDataSnapshot()` 机制。

### 其他组件中 (直接注入)

```java
@Component
public class YourComponent {
    private final GlobalSessionStateStore globalSessionStore;

    public YourComponent(GlobalSessionStateStore globalSessionStore) {
        this.globalSessionStore = globalSessionStore;
    }

    public void doSomething(String sessionId) {
        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        // 使用 ctx...
    }
}
```

---

## 12. 流式 vs 非流式输出

### 选择标准

| 场景 | 推荐 | 原因 |
|------|------|------|
| 查询类 (账单、余额) | 非流式 | 结果确定，无需逐字输出 |
| 操作类 (转账、支付) | 非流式 | 结果确定，一次性返回 |
| 咨询类 (理财推荐、保险方案) | 看情况 | 短回复用非流式，长解读用流式 |
| 解读类 (产品解读、报告) | 流式 | LLM 生成长文本，逐字体验更好 |

### 流式输出的完整配置

```java
// 1. Graph 节点中: result.put("streaming_output", responseFlux)
// 2. AppInitConfig 中: intentRegistry.bindGraph("YOUR_INTENT", graph, true)
// 3. 仅此两步，框架自动处理后续
```

### 流式降级

流式 Graph 应该有降级逻辑 — 如果流式初始化失败，回退到非流式：

```java
try {
    Flux<ChatResponse> responseFlux = chatClient.prompt()
            .user(prompt).stream().chatResponse();
    result.put("streaming_output", responseFlux);
} catch (Exception e) {
    log.warn("Streaming init failed, falling back to mock", e);
    result.put("_outputContent", mockService.getResult());  // ← 降级为非流式
}
```

---

## 13. Single 域 vs Multi 域选择

```
你的域下有几个子意图？
  │
  ├── 1 个 → SingleSubAgentDomainService
  │   - 无消歧
  │   - 无挂起/恢复
  │   - 用 IntentRouter (简单改写+识别)
  │   - 例: 转账(TRANSFER)、账单(BILL_QUERY)
  │
  └── 2+ 个 → MultiSubAgentDomainService
      - 需要消歧 (配置 intent-groups)
      - 支持挂起/恢复 (子图之间切换)
      - 用 IntentResolver (含消歧+模糊匹配)
      - 例: 理财(WEALTH_CONSULT + WEALTH_INTERPRET)
```

---

## 14. 完整示例：新建"保险"域

### 14.1 application.yml

```yaml
routing:
  intents:
    - name: INSURANCE_CONSULT
      description: "保险咨询与推荐"
      param-schema: "保险类型(人寿/健康/财产), 保障范围(可选)"
      is-write-op: false
      intent-type: CONSULTATION
      scope: "保险产品咨询、推荐与方案设计"

    - name: INSURANCE_CLAIM
      description: "理赔申请"
      param-schema: "保单号, 理赔类型, 理赔金额"
      is-write-op: true
      intent-type: OPERATION
      scope: "保险理赔申请与处理"

  intent-groups:
    - group-id: INSURANCE
      display-name: "保险"
      intent-names: [INSURANCE_CONSULT, INSURANCE_CLAIM]
      disambiguation-question: "请问您需要保险咨询还是理赔申请？"

  domains:
    INSURANCE:
      keywords: ["保险", "投保", "保单", "理赔", "保费", "险种", "保障"]
```

### 14.2 DomainServiceConfig.java (新增 2 个 Bean)

```java
// DomainStateAware 注册
@Bean
public DomainStateAware insuranceDomainStateAware() {
    return DomainStateAware.of("_insuranceState", "INSURANCE");
}

// Multi 域 Service 注册 (因为有两个子意图)
@Bean("insuranceDomainService")
public MultiSubAgentDomainService insuranceDomainService(
        ContextRouter contextRouter,
        IntentResolver intentResolver,
        GraphExecutionEngine graphExecutionEngine,
        IntentRegistry intentRegistry,
        GlobalSessionStateStore globalSessionStore,
        DomainServiceRegistry domainServiceRegistry,
        @Value("${routing.history.l1-max-pairs:6}") int l1MaxPairs) {
    MultiSubAgentDomainService service = MultiSubAgentDomainService.builder()
            .domainName("保险")
            .logTag("InsuranceService")
            .domainKey("INSURANCE")
            .contextRouter(contextRouter)
            .intentResolver(intentResolver)
            .graphExecutionEngine(graphExecutionEngine)
            .intentRegistry(intentRegistry)
            .globalSessionStore(globalSessionStore)
            .routingTemplatePath("prompts/l1-routing.st")
            .intentionTemplatePath("prompts/l1-intention.st")
            .rejectedMessage("该保险功能暂不支持，目前仅支持保险咨询和理赔申请")
            .handledIntents(List.of(
                    new AbstractDomainService.IntentInfo("INSURANCE_CONSULT", "保险咨询与推荐"),
                    new AbstractDomainService.IntentInfo("INSURANCE_CLAIM", "理赔申请")
            ))
            .maxSuspendedDepth(3)
            .suspendedExpireMinutes(20)
            .l1DomainPairs(l1MaxPairs)
            .build();
    domainServiceRegistry.register("INSURANCE", service);
    return service;
}
```

### 14.3 InsuranceConsultGraphConfig.java (新建)

参见 [Step 5](#7-step-5-创建-l2-子-graph-java) 的完整代码。

### 14.4 InsuranceClaimGraphConfig.java (新建第二个子 Graph)

结构类似 InsuranceConsultGraphConfig，区别在于：
- `registerCustomKeys`: `insuranceClaim.policyNo`, `insuranceClaim.claimType`, `insuranceClaim.amount`
- `buildExtractPrompt`: 提取保单号、理赔类型、理赔金额
- `paramRouter`: 按缺失参数路由到不同 askNode

### 14.5 AppInitConfig.java (新增 2 行)

```java
intentRegistry.bindGraph("INSURANCE_CONSULT", insuranceConsultGraph);
intentRegistry.bindGraph("INSURANCE_CLAIM", insuranceClaimGraph);
```

---

## 15. 关键枚举与配置项速查

### intent-type 枚举

| 值 | 含义 | 影响路由 |
|----|------|---------|
| `OPERATION` | 写操作 (资金变动) | L0 需要更高置信度 |
| `QUERY` | 读操作 (查询) | 较低置信度即可路由 |
| `CONSULTATION` | 咨询类 (推荐/方案) | 较低置信度即可路由 |

### ChunkType 枚举 (L2 返回值)

| 值 | 含义 | 何时产生 | 前端行为 |
|----|------|---------|---------|
| `CHUNK` | 流式文本增量 | 流式 Graph 执行中 | 追加显示 |
| `COMPLETE` | 完成 | Graph 正常结束 | 显示/结束 |
| `INTERRUPTED` | 中断 | interruptBefore 触发 | 显示提问，等待用户输入 |
| `DISAMBIGUATION` | 消歧 | Multi 域意图不明确 | 显示追问选项 |
| `ERROR` | 错误 | 异常 | 显示错误信息 |
| `REROUTE` | 重新路由 | L1 判断不属于本域 | 不暴露给前端，L0 重新路由 |

### _outputType 枚举 (L2 节点输出)

| 值 | 含义 | 示例 |
|----|------|------|
| `"TEXT"` | 普通文本 | "推荐以下保险方案..." |
| `"CONFIRMATION"` | 确认信息 | "转账成功！已向张三转账500元" |

### 关键 OverAllState key 约定

| key 约定 | 用途 | 策略 |
|----------|------|------|
| `messages` | 对话历史 | AppendStrategy |
| `_lastDomain` | 最近路由领域 | ReplaceStrategy |
| `_xxxState` | 各域 DomainState | ReplaceStrategy |
| `_latestUserInput` | 最新用户输入 | ReplaceStrategy |
| `_question` | 当前提问 | ReplaceStrategy |
| `_paramName` | 路由目标 (ASK_XXX / ALL_GOOD / CANCEL) | ReplaceStrategy |
| `_outputContent` | 输出内容 | ReplaceStrategy |
| `_outputType` | 输出类型 (TEXT / CONFIRMATION) | ReplaceStrategy |
| `_isFinal` | 是否终结 | ReplaceStrategy |
| `_cancelSignal` | 取消信号 | ReplaceStrategy |
| `_globalStateData` | L1→L2 全局状态注入 | ReplaceStrategy |

### 关键配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `storage.type` | `in-memory` | 存储后端: in-memory / redis |
| `routing.history.l0-max-pairs` | 10 | L0 域路由使用的对话对数 |
| `routing.history.l1-max-pairs` | 6 | L1 意图路由使用的对话对数 |
| `routing.history.chat-max-pairs` | 10 | ChatService 使用的对话对数 |
| `routing.confidence.disambiguation-threshold` | 0.7 | 低置信度补充消歧阈值 |
| `routing.confidence.high-confidence-bypass` | 0.85 | 高置信度跳过消歧阈值 |
| `session.last-domain.expire-minutes` | 5 | lastDomain 过期时间 (分钟) |

---

## 16. 常见问题

### Q: 新增意图后启动报错 "Graph not found for intent: XXX"

**原因：** 意图在 yml 中注册了，但 Graph 还没绑定到 IntentRegistry。

**解决：** 检查 `AppInitConfig.bindGraphs()` 中是否有对应的 `bindGraph("XXX", graph)` 调用。

### Q: L0 总是不路由到我的新域

**原因排查：**
1. 检查 `routing.domains.YOUR_DOMAIN.keywords` 是否有足够的关键词
2. 检查关键词是否和其他域重叠 (如"推荐"同时出现在 WEALTH 和你的域)
3. 查看 DomainRouter 日志: `[DomainRouter] L0 domain: YOUR_DOMAIN`

### Q: 用户说"保险"被路由到了 WEALTH 域

**原因：** `routing.domains.WEALTH.keywords` 中有"保险"或其他重叠词，且 WEALTH 先匹配。

**解决：** 确保 `routing.domains.INSURANCE.keywords` 中的词不与 WEALTH 重叠。DomainRouter 优先匹配关键词。

### Q: 消歧追问后用户回答仍然模糊，系统直接拒绝了

**这是预期行为。** 消歧只追问 1 次。如果用户第二次回答仍然模糊，系统返回 rejected。

**缓解方案：** 优化消歧追问的话术，让选项更明确：
```yaml
disambiguation-question: "请问您需要保险咨询(回复1)还是理赔申请(回复2)？"
```

### Q: Single 域能否改为 Multi 域？

可以，只需要在 `DomainServiceConfig` 中把 `SingleSubAgentDomainService.builder()` 改为 `MultiSubAgentDomainService.builder()`，增加 `handledIntents`、`intentResolver` 等参数，并在 yml 中配置消歧组。

### Q: L2 子 Graph 如何读取 L1 的对话历史？

通过 `_globalStateData` 读取：

```java
Map<String, Object> globalData = (Map<String, Object>) state.value("_globalStateData").orElse(Map.of());
List<String> messages = (List<String>) globalData.get("messages");
```

### Q: L2 子 Graph 执行完毕后如何回写数据到 GlobalSessionContext？

通过 `extractSubAgentDataSnapshot()` 方法。当前流程尚未集成（接口预留），L2 团队可自行集成，或在 L1 的 `handleActiveAgentState()` 回调中调用。

### Q: 流式 Graph 的 ChatClient 从哪来？

需要在配置中创建，参考 `MobileAiDemoApplication` 中 `wealthInterpretChatClient` 的写法：

```java
@Bean("insuranceInterpretChatClient")
public ChatClient insuranceInterpretChatClient(
        @Qualifier("insuranceInterpretChatModel") ChatModel chatModel) {
    return ChatClient.builder(chatModel).build();
}
```

然后在 Graph 构造函数中注入并使用。

### Q: DomainState 中的 subAgents 什么时候被写入？

`extractSubAgentDataSnapshot()` 在每个子图中已经实现，但当前 L1 DomainService 还没有调用它来写回 GlobalSessionContext。这是预留接口，L2 团队可以自行在合适的时机集成。
