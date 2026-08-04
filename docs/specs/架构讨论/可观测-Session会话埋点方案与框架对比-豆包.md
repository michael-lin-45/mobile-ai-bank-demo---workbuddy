# AI智能体可观测体系 Session 会话埋点方案与框架对比
在AI场景中，**Session（会话）** 是跨越多轮对话、多次LLM调用、多次工具调用的完整交互周期，是比单条Trace更高一层的业务聚合单元。会话埋点的核心目标是：用一个全局唯一的会话ID，串联起整个交互周期内的所有链路、日志、指标、事件，实现「单会话全维度追溯、会话级运营统计、用户行为分析」。

下面先讲通用埋点方案，再分别拆解 **Spring AI** 和 **OpenTelemetry** 两套体系的实现方法、能力边界与选型建议。

---

## 一、Session 埋点的四类通用方案
所有框架的会话埋点，本质上都归属于以下四种实现思路，复杂度和能力逐层递增：

| 方案类型 | 实现原理 | 解决范围 | 优点 | 缺点 |
|---------|---------|---------|------|------|
| **MDC日志埋点** | 利用SLF4J MDC将sessionId注入日志上下文，日志自动携带会话标识 | 仅日志关联 | 最简单、零额外依赖、开发成本极低 | 只能关联日志，无法串联链路、指标，跨服务失效 |
| **上下文透传埋点** | 通过ThreadLocal、请求头、Baggage在调用链中传递sessionId | 单应用内全链路关联 | 灵活度高，可自定义字段 | 需自行处理异步、跨线程、跨服务透传，易丢失上下文 |
| **框架原生观测埋点** | 基于Spring Observation、OTel SDK等标准观测框架，将会话作为标准属性注入 | 指标/日志/链路三支柱统一关联 | 标准化程度高，自动上下文传播，生态完善 | 有一定学习成本，深度定制需扩展 |
| **AOP/自定义拦截器埋点** | 通过Spring AOP、自定义过滤器统一拦截会话入口，注入会话上下文 | 业务自定义强 | 完全可控，可适配复杂业务规则 | 侵入性强，维护成本高，无统一标准 |

银行AI项目的生产级落地，**优先选择框架原生观测方案**，配合少量自定义拦截器补充业务语义，兼顾标准化与业务适配。

---

## 二、Spring AI 体系的会话埋点方案
### 2.1 底层技术基础
Spring AI 的可观测体系完全构建在 **Spring Observation API + Micrometer Tracing** 之上，是Spring Boot 3+的标准可观测范式：
- Observation API：统一抽象「观测动作」，一套代码同时产出指标、链路、日志三类数据
- Micrometer Tracing：提供链路追踪抽象，可桥接 OpenTelemetry 或 Brave 两种实现
- 自动埋点：对ChatClient、ChatModel、VectorStore、工具调用等核心组件默认内置观测点，无需业务代码手动埋点

### 2.2 原生能力边界
Spring AI 默认**不直接提供Session级埋点**，它的原生观测粒度是「单次LLM调用/单次ChatClient请求」，自带的标准属性只有模型名、是否流式、操作类型等低基数字段。会话能力需要基于框架扩展实现，核心依托两个原生机制：
1. **Conversation 会话模型**：Spring AI 提供 `Conversation` 接口与 `ChatMemory` 会话记忆，自带 `conversationId` 作为会话唯一标识，负责多轮对话的上下文管理
2. **ObservationConvention 扩展机制**：允许自定义观测约定，向观测上下文注入自定义属性（如sessionId、userId），自动同步到指标、链路、日志

### 2.3 三种主流实现方式
#### 方式1：自定义 ObservationConvention 注入会话属性（推荐）
通过实现 `ChatClientObservationConvention`，在观测创建时自动将 `session.id`、`user.id` 等会话属性注入到Observation上下文，自动同步到Span、指标标签和MDC日志。

```java
// 自定义会话观测约定
public class SessionChatClientObservationConvention
        implements ChatClientObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ChatClientObservationContext context) {
        KeyValues keyValues = KeyValues.empty();
        // 从上下文获取会话ID（通常从ChatMemory/请求上下文传入）
        String sessionId = ConversationContextHolder.getSessionId();
        if (sessionId != null) {
            keyValues = keyValues.and("session.id", sessionId);
        }
        return keyValues;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ChatClientObservationContext context) {
        KeyValues keyValues = KeyValues.empty();
        String userId = ConversationContextHolder.getUserId();
        if (userId != null) {
            keyValues = keyValues.and("user.id", userId);
        }
        return keyValues;
    }
}
```
- 低基数属性（如会话ID）会同时进入指标和链路
- 高基数属性（如用户ID、对话内容）默认只进入链路，不进入指标，避免时序爆炸

#### 方式2：MVC拦截器 + ThreadLocal 会话上下文透传
在Web层通过拦截器从请求头/Token中提取sessionId，存入ThreadLocal上下文，配合Observation扩展实现全链路透传，同时自动注入MDC日志。

```java
// 会话上下文拦截器
@Component
public class SessionInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, 
                             HttpServletResponse response, 
                             Object handler) {
        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
        }
        ConversationContextHolder.setSessionId(sessionId);
        MDC.put("sessionId", sessionId); // 日志自动携带
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, 
                                HttpServletResponse response, 
                                Object handler, Exception ex) {
        ConversationContextHolder.clear();
        MDC.remove("sessionId");
    }
}
```

#### 方式3：ConversationAggregator 会话级指标聚合（社区方案）
Spring AI 社区提供 `ConversationAggregator` 组件，按 `conversationId` 聚合多轮Trace，计算会话级指标：会话总轮次、会话总Token消耗、会话总时长、最大思考深度等，适合做运营大盘统计。

### 2.4 优势与局限
✅ **优势**
1. 与Spring生态无缝融合，开发体验一致，学习成本低
2. 自动埋点覆盖所有Spring AI核心组件，业务代码零侵入
3. 一套代码同时产出指标、链路、日志，三支柱天然关联
4. 可通过Micrometer Tracing桥接OpenTelemetry，接入全局OTel体系

❌ **局限**
1. 原生无标准会话语义，session.id属于自定义扩展，不同项目实现不统一
2. 跨服务透传能力依赖OTel桥接，纯Spring体系下跨服务会话传递需要自行处理
3. 仅Java生态可用，多语言异构架构无法统一标准
4. 对话内容、工具参数等完整信息默认只打日志，不自动写入Span属性，需自定义Handler扩展

---

## 三、OpenTelemetry 体系的会话埋点方案
### 3.1 底层技术基础
OpenTelemetry（OTel）是业界统一的可观测标准，会话埋点基于三个核心机制构建：
1. **Context 上下文机制**：全局上下文传播，跨线程、跨服务自动传递
2. **Baggage 行李机制**：在全链路中透传自定义业务属性，所有Span自动继承
3. **标准语义约定**：官方定义了 `session.id`、`session.previous_id` 等通用会话属性，GenAI语义规范也正在正式引入 `session.id` 作为标准字段

### 3.2 原生能力边界
OTel 本身是通用可观测标准，不提供AI会话的业务生命周期管理，但在**会话属性的全链路透传、标准化、跨生态兼容**上是行业事实标准：
- `session.id` 是OTel官方注册的标准属性，所有合规的观测平台（Langfuse、Grafana、Datadog）都能原生识别
- 支持通过Baggage实现「一次设置、全链路生效」，跨服务、跨语言、跨线程自动传递
- 可通过SpanProcessor全局统一注入，完全无需修改业务代码

### 3.3 三种主流实现方式
#### 方式1：Baggage 全链路透传（最标准、推荐）
将 `session.id`、`user.id` 放入OTel Baggage中，整个调用链路上的所有Span、日志都会自动携带该属性，无需每层手动传递，天然支持跨服务、跨线程。

```java
// 会话入口处设置Baggage（如拦截器、网关层）
Baggage.current()
    .toBuilder()
    .put("session.id", sessionId)
    .put("user.id", userId)
    .build()
    .makeCurrent();

// 后续所有LLM调用、工具调用、数据库调用的Span
// 都会自动携带 session.id 和 user.id 属性，无需手动设置
```
- 优势：一次设置，全链路生效，跨服务跨语言无损传递
- 注意：默认Baggage属性只进Span，不进指标；如需进指标需显式配置

#### 方式2：自定义 SpanProcessor 全局注入
通过实现 `SpanProcessor`，在每个Span启动时统一注入会话属性，完全不侵入业务代码，适合全局统一规范。

```java
// 全局会话属性注入处理器
public class SessionSpanProcessor implements SpanProcessor {
    @Override
    public void onStart(Context context, ReadWriteSpan span) {
        // 从上下文/Baggage获取会话ID
        String sessionId = Baggage.fromContext(context).getEntryValue("session.id");
        if (sessionId != null) {
            span.setAttribute("session.id", sessionId);
        }
    }

    @Override
    public boolean isStartRequired() { return true; }
}
```
- 适合企业级统一规范，所有应用统一生效，业务团队无感知
- 可配合OTel Java Agent使用，零代码修改完成全应用会话埋点

#### 方式3：标准语义事件标记会话生命周期
遵循OTel官方Session语义约定，在会话开始/结束时发送标准事件，便于审计和生命周期统计：
- `session.start`：会话开始事件，携带session.id、开始时间
- `session.end`：会话结束事件，携带结束原因、总轮次、总Token

### 3.4 优势与局限
✅ **优势**
1. **行业标准**：`session.id` 是官方标准语义，所有主流可观测平台原生兼容，包括Langfuse、Grafana LGTM
2. **全链路透传**：Baggage机制天然支持跨服务、跨语言、跨线程，分布式架构下无额外开发成本
3. **零侵入能力**：配合OTel Java Agent + SpanProcessor，业务代码零修改即可完成全量会话埋点
4. **三支柱统一**：会话ID自动同步到链路、日志、指标，无需分别处理
5. **生态中立**：不绑定任何业务框架，Spring AI、自研Agent、多语言服务均可统一标准

❌ **局限**
1. 只负责观测层面的属性透传，不提供会话业务生命周期管理（如ChatMemory、会话状态），需业务层自行实现
2. 有一定学习曲线，Baggage、Context、Processor等概念需要理解
3. 高基数属性进入指标需要谨慎配置，避免时序爆炸

---

## 四、Spring AI vs OpenTelemetry 综合对比
| 对比维度 | Spring AI Observation 方案 | OpenTelemetry 方案 |
|---------|---------------------------|-------------------|
| **底层依赖** | Spring Observation + Micrometer Tracing，可桥接OTel | OTel SDK + 标准语义约定，框架无关 |
| **核心定位** | Java/Spring生态内的AI应用观测 | 全语言、全架构的通用可观测标准 |
| **会话原生支持** | 无原生会话语义，需基于conversationId自定义扩展 | 原生支持session.id标准语义，GenAI规范持续完善 |
| **侵入性** | 业务代码零侵入，Spring AI自动埋点；扩展需少量配置 | 配合Agent可零侵入；纯SDK需少量业务代码 |
| **跨服务透传** | 弱，需桥接OTel实现；纯Spring体系需自行处理 | 强，Baggage机制原生跨服务跨语言传递 |
| **生态兼容性** | Spring生态完美适配；非Java体系无法使用 | 全语言全框架兼容，Langfuse/LGTM等平台原生支持 |
| **AI业务适配** | 深度适配Spring AI组件，对话记忆、工具调用天然联动 | 通用标准，AI语义需按GenAI规范自行扩展 |
| **运维复杂度** | 低，Spring Boot自动配置，开箱即用 | 中，需部署OTel Collector，配置采集策略 |
| **适用场景** | 单Java应用、Spring技术栈、快速落地 | 分布式架构、多语言混合、企业级统一可观测体系 |

---

## 五、银行项目落地建议
结合你当前的「Spring AI + 华为昇腾 + 行内合规 + OceanBase存储 + LGTM/Langfuse观测」的技术栈，推荐**分层组合方案**，兼顾开发效率与标准统一：

### 1. 分层职责划分
- **会话业务层**：用Spring AI的ChatMemory/Conversation管理会话生命周期、会话上下文、多轮对话记忆，`conversationId` 作为业务会话主键
- **观测透传层**：桥接OpenTelemetry，通过Baggage将 `session.id`、`user.id`、`agent.id` 等属性全链路透传，统一遵循OTel标准语义
- **数据存储层**：
  - 会话元数据（创建时间、用户ID、业务场景、状态）存入OceanBase，支持事务与合规审计
  - 会话级事件、Token消耗明细存入ClickHouse/OceanBase，支撑运营统计
  - 链路数据存入Tempo/行内APM，日志存入行内统一日志平台，全部通过session.id关联

### 2. 实施步骤
1. **基础层**：接入OTel Java Agent + Spring Boot OTel Starter，打通基础链路与指标
2. **会话层**：在网关/拦截器层统一生成/解析sessionId，通过Baggage全链路透传
3. **业务层**：Spring AI侧通过ObservationConvention补充AI业务属性，对齐GenAI语义规范
4. **应用层**：Grafana大盘按session维度统计会话量、平均轮次、平均成本；Langfuse按session聚合多轮对话，实现运营优化闭环

### 3. 合规注意事项
- `session.id` 仅用随机UUID，禁止携带用户敏感信息
- 对话内容、用户输入等高敏感数据，默认不写入Span属性，仅存审计级存储并做脱敏
- 严格控制高基数标签进入指标，避免存储膨胀与性能问题
- 会话超时机制符合行内安全规范，超时自动失效并记录结束事件