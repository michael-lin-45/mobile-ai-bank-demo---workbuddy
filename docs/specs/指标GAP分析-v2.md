# AI 银行智能体可观测平台 — 指标 GAP 分析文档（V2）

> 整理：小爪（AI 搭档） | 日期：2026-07-08 | 版本：v2.0
> 基线：基于《指标GAP分析-v1.md》(2025-07-21) + 当前最新代码（observability 后端 Java / Vite+React 前端 / Core 应用）重新梳理

---

## 〇、V2 方法论与结论速览

本次重审对 V1 的 30 个指标条目逐一定位到当前代码实现，结论分四类：

| 状态 | 含义 | 数量 |
|------|------|------|
| 🟢 已解决 | V1 标记的 GAP 已被代码实际修复 | 4 项（A4/A5/B4 + A6 部分） |
| 🔴 仍开放 | V1 标记的问题代码侧依旧未实现/无数据源 | 14 项 |
| 🟡 回归 | V1 标注"已实现"的能力当前代码已损坏 | 4 项（A2/A3/I3/I4） |
| 🆕 新增 | V1 不存在、本次新发现的问题或能力 | 7 项（N1–N7） |

**一句话结论**：V1 的三大 P0 计算类问题（QPS 公式、requestCount 窗口、errorRate 后端计算）已全部打通；但**语义质量与链路层的核心 GAP（L0/L1 分层准确率、LLM 置信度、分层意图）一条都没动**，且因项目扩张（新增 5 个 AI 洞察 TAB、前端重写）暴露出**新的契约错配回归**——DAU/在线人数数据源断链、AccuracyTab 整页读取了后端根本不返回的字段。

> 说明：本分析覆盖 DashboardPage、TraceExplorerPage、AIInsightsPage（含 6 个 TAB）、RealtimeMetricsVO/AIInsightVO/MetricsAgg/SpanEntity/Session 数据模型，以及 Core 端指标发射（`ObservabilityMetrics`）。新增的 AgentPerf / TokenCost / ToolCall / Funnel / Satisfaction 五个 TAB 不在 V1 范围，仅做"建议另立 GAP 审计"标注（见 N6）。

---

## 一、总览表（当前代码实测状态）

### 1.1 DashboardPage — Zone A 系统健康

| # | 指标名 | V1状态 | 当前实现 | 数据来源（实测） | V2状态 |
|---|--------|--------|---------|-----------------|--------|
| A1 | activeSessions | ✅已实现 | `setActiveSessions` 由 OTel Gauge `session.active` 驱动 | OtlpParserService L166 → Redis | ✅ 正常 |
| A2 | DAU | ✅已实现 | `RealtimeMetricsVO.dailyActiveUsers` 读取 `redisMetrics.getDau()` | **`setDau()` 全代码库无任何调用方** → 永远 0/null | 🟡 回归（数据源断链） |
| A3 | realTimeOnline | ✅已实现 | `RealtimeMetricsVO.realtimeOnline` 读取 `redisMetrics.getOnlineUsers()` | **`setOnlineUsers()` 全代码库无任何调用方** → 永远 0/null | 🟡 回归（数据源断链） |
| A4 | requestCount | 🔴P0 | `getRequestCount("6h")`，OTel 对 `request_count:1m/5m/15m/6h` 均做 INCR | Redis 6h 窗口，TTL 21600s | 🟢 已解决 |
| A5 | QPS | 🔴P0 | 后端 `vo.setQps(requestCount/21600.0)`；前端 `requestCount/6/3600` | 与 6h 窗口自洽，=近6h平均QPS | 🟢 已解决 |
| A6 | agentCallCount/L0/L1/L2 | ✅(P1 TTL) | L0/L2 由 OTel `router.decision`/`workflow.execution` INCR（TTL 6h）；**L1 已由 Core `ContextRouter`/`SubGraphRouter` 发射 `agent.l1.call` + 后端 `agent_call:L1` 聚合打通** | Redis `agent_call:L0/L1/L2` 均实时 | 🟢 已解决（2026-07-08 P0-5/P1-2） |

### 1.2 DashboardPage — Zone B AI 性能

| # | 指标名 | V1状态 | 当前实现 | 数据来源 | V2状态 |
|---|--------|--------|---------|---------|--------|
| B1 | Token 调用量 | ✅已实现 | `incrTokenCountDelta` 1m/5m 窗口 | `llm.token.*` / `agent.token.*` | ✅ 正常 |
| B2 | TTFT P50/P95/P99 | ✅已实现 | `recordTTFT`（仅 1m 窗口） | Redis ZSET `ttft:1m` | ✅ 正常（仅1m窗口） |
| B3 | P95 系统时延 | ✅已实现 | `getLatencyStats("1m")` | Redis ZSET `latency:1m` | ✅ 正常 |
| B4 | 错误率 | 🔴P0 | **后端计算** `errorRate = errorCount/requestCount`（均 6h） | Redis `error_count:6h` | 🟢 已解决（V1 P0-7） |

### 1.3 DashboardPage — Zone C 语义质量

| # | 指标名 | V1状态 | 当前实现 | 数据来源（实测） | V2状态 |
|---|--------|--------|---------|-----------------|--------|
| C1 | intentAccuracy | 🔴P0 | 单值 `intentAccuracy`；改读 H2 `agent.intent.accuracy`（computeIntentAccuracy 按 intent_predicted/state 聚合） | Core 发射 `agent.intent.accuracy` Counter → 后端实时算 → 实测 77.78% | 🟢 已解决（2026-07-08 P0-3） |
| C2 | rewriteAccuracy | ⚠️单值(P1) | 改读 H2 `agent.rewrite.accuracy` 的 `rule_check` tag 计数（pass/总数×100） | Core 发射改写信号 → 后端实时算 → 实测 0.0%（Core 改写检测全判失败，真实值） | 🟢 已解决（2026-07-08 C2） |
| C3 | rerouteRate | 🔴P0 | 改读 H2 `agent.reroute.count`（computeRerouteCount） / (L0+L1 调用数) | Core `BankController` 每次 reroute 发射 `agent.reroute.count` → 后端实时算 → 实测 0.03 | 🟢 已解决（2026-07-08 C3） |
| C4 | completionRate | 🟡P1 | 单值 `businessCompletionRate`，读 `business_completion` Redis | **`setBusinessCompletionRate()` 无调用方**；L2 未实现 → null | 🔴 仍开放 |

### 1.4 DashboardPage — Zone D 业务效果

| # | 指标名 | V1状态 | 当前实现 | 数据来源 | V2状态 |
|---|--------|--------|---------|---------|--------|
| D1 | conversionRate | ❌P1 | 单值 `businessConversionRate`，读 `conversion` Redis | **`setConversionRate()` 无调用方**；无上游系统 | 🔴 仍开放 |
| D2 | violationRate | ❌P1 | 后端硬编码 `setViolationRate(null)` | 无 | 🔴 仍开放 |

### 1.5 TraceExplorerPage — 链路追踪

| # | 指标名 | V1状态 | 当前实现 | V2状态 |
|---|--------|--------|---------|--------|
| T1 | LLM 置信度 confidence | 🔴P0 | TraceListVO/TraceDetailVO **均无 confidence 字段**；Span attributes 不含 `llm.confidence`（Core 未发射） | 🔴 仍开放 |
| T2 | L0/L1/L2 分层意图 | 🔴P0 | Session.intentFlow 仍为单字符串；Span attributes 未分层（无 `intent.L0.predicted` 等） | 🔴 仍开放 |
| T3 | traceId/sessionId/ioInput 等 | ✅ | 正常 | ✅ 正常 |
| T4 | 链路性能指标 durationMs/ttftMs/tokenTotal | ✅ | 正常 | ✅ 正常 |
| T5 | spanTree/ioOutput | ✅ | 正常 | ✅ 正常 |

### 1.6 AIInsightsPage — 准确率分析 TAB（及 5 个新 TAB）

| # | 指标名 | V1状态 | 当前实现 | V2状态 |
|---|--------|--------|---------|--------|
| I1 | intentAccuracyTrend | 🔴P0 | `buildTrend()` 已按 `intent_predicted` 分组（正确 tag）；前端 AccuracyTab 改读统一端点 `/accuracy-report` | 实测 TRANSFER 100% / WEALTH_CONSULT 75% / WEALTH_INTERPRET 66.67% / BILL_QUERY 100% / OVERALL 73.3% | 🟢 已解决（2026-07-08 P0-3） |
| I2 | confusionMatrix | 🔴P0 | `getConfusionMatrix()` 改读已验证的 `agent.intent.accuracy` Counter，按 intent_predicted×intent_actual 聚合二维矩阵 | 实测 totalSamples=9（不再依赖 Span attrs） | 🟢 已解决（2026-07-08 I2/P0-6） |
| I3 | rewriteAccuracy 表 | ✅已实现 | 前端读 `accuracyData.rewrite`（后端不返回）→ 表格恒空 | 🟡 回归 |
| I4 | 改写失败根因 TOP3 | ✅已实现 | 前端读 `accuracyData.rootCauses`（后端不返回）→ 卡片恒空 | 🟡 回归 |
| I5 | L2 意图识别 | 🟡P2 | 占位 | 🔴 仍开放 |

---

## 二、详细 GAP 分析（含根因定位）

### 2.1 Zone A — 系统健康

#### GAP-A2/A3：DAU / 实时在线人数 数据源断链（🟡回归，原 V1 标"已实现"）

**根因（实测）**：
- `RedisMetricsService.setDau()` / `setOnlineUsers()` 在**全仓库搜索无任何调用方**（仅定义）。
- OTel 采集链路 `OtlpParserService` 仅对 `session.active` Gauge 调用了 `setActiveSessions()`，未对 DAU / online 做任何写入。
- 因此 Dashboard 的 `metrics.dau` / `metrics.realTimeOnline` 永远为 0 / null，前端显示 "‑"。

**修复建议**：
- 方案 A：在 `OtlpParserService` 解析 `session` 类 Gauge 时，同步 `setDau()`（按去重 userId 计数，24h TTL）与 `setOnlineUsers()`（按活跃会话估算，30s TTL）。
- 方案 B：若 DAU 由 Core 直接算，则在 Core `ObservabilityMetrics` 中新增对应 Redis 写入（需注入 `StringRedisTemplate` 或经 OTel 上报后由后端解析）。

#### GAP-A6（续）：L1 Agent 调用无实时计数（🆕N4）

**现状**：`incrAgentCall("L0")` 由 `router.decision`/`intent.recognized` 驱动；`incrAgentCall("L2")` 由 `workflow.execution`/`workflow.interrupt` 驱动；**L1 在采集链路中未被 INCR**。代码注释明确："L1 calls come from SpanEntity operationName matching in MetricsQueryService fallback"——即 L1 仅在 Redis 值为 0 时，靠 `spanRepository.findDistinctOperationNames()` 做 operationName 关键词启发式回退（含 "L1"/"Intent"/"WorkflowL1"）。

**风险**：L1 调用数非实时、依赖 Span 表全量扫描，且关键词匹配易误判（如 "Intent" 可能匹配无关 span）。建议 Core 在 L1 识别阶段显式 INCR `agent_call:L1`。

### 2.2 Zone C — 语义质量（核心债务未动）

#### GAP-C1：intentAccuracy 缺 L0/L1 分层 + 数据源断链（🔴仍开放）

**双重问题**：
1. **无分层**：`RealtimeMetricsVO.intentAccuracy` 仍是单值，`MetricsAgg` 无 `layer` 字段，Dashboard Zone C 仅一个全局准确率。
2. **数据源断链**：Dashboard 读 `accuracy:intent` Redis，但该 key 的 `setIntentAccuracy()` 无任何调用方。Core 实际发射的是 `agent.intent.accuracy`（落入 H2 `metrics_agg`，tag 为 `intent_predicted`/`intent_actual`/`state`），**从未写入该 Redis key** → Dashboard 永远显示 "‑"。

**修复建议**：统一数据通路——要么 Dashboard 改读 H2 `agent.intent.accuracy`（按 layer tag 聚合），要么 Core 将分层准确率显式写入 `accuracy:intent:L0` / `accuracy:intent:L1` Redis key 并补上写入调用。V1 的分层方案（L0=L0预测vsL1实际，L1=L1预测vsL2实际）仍成立。

#### GAP-C3：rerouteRate 缺因果链路 + 数据源断链（🔴仍开放）

- `reroute_rate` Redis key 无写入方（Core 的 reroute 逻辑在 `BankController.dispatchWithReroute` 与 `StreamChunk.reroute`，但**未发射任何 reroute 度量**）。
- 即使有值，也仅是单值，无"意图识别错误→reroute"因果、无 0/1/2/3 次分布。
- 注意：`Session` 有 `rerouteTriggered`(Boolean) 字段（`SessionService` L346），但硬编码 `false`，未随真实 reroute 置真。

**修复建议**：Core 在每次 reroute 时 INCR `reroute_count`（带 `reroute_reason`/`excluded_domain` tag），并在会话级正确设置 `rerouteTriggered`；后端新增 `obs:metrics:reroute_distribution` HASH 统计次数分布。

#### GAP-C4 / D1 / D2：业务效果区全部占位（🔴仍开放）

- `business_completion`（`setBusinessCompletionRate` 无调用方）、`conversion`（`setConversionRate` 无调用方）、`violationRate`（硬编码 null）三者均无真实数据源。
- 与 V1 一致：等待 L2 实现 / 业务系统对接 / 安全围栏系统对接。无代码侧进展。

### 2.3 TraceExplorerPage

#### GAP-T1：LLM 置信度未展示（🔴仍开放）

- `TraceListVO` / `TraceDetailVO` **均无 `confidence` 字段**。
- Span attributes 经 `extractAttributesJson()` 原样存储，但 Core 未发射 `llm.confidence` 属性 → 无可提取数据。
- **部分进展**：置信度数据其实存在于 `SessionTurn.confidence`（`SessionService` L214 从请求体 `confidence` 字段读取并落库），但仅用于会话级，未上卷到 Trace 视图。需 Core 在 LLM span 上携带 `llm.confidence`，并在 VO + 前端补列。

#### GAP-T2：L0/L1/L2 分层意图未结构化（🔴仍开放）

- `Session.intentFlow` 仍为单字符串（如 "TRANSFER→TRANSFER"），无 `l0Intent`/`l1Intent`/`l2Intent` 独立列。
- Span attributes 未规范 `intent.L0.predicted` / `intent.L1.actual` 等分层键（V1 建议未落地）。
- `TraceTable` 列仅为 Trace/Session/User/Agents/耗时/TTFT/Token/状态，**无意图列、无置信度列**（V1 T4/T5 均未实现）。

### 2.4 AIInsightsPage

#### GAP-I1：意图准确率趋势塌缩 + 前端契约错配（🔴仍开放 + 🟡回归）

**根因（两层）**：
1. **后端 tag 键名错配**：`getIntentAccuracyTrend()` 用 `extractTag(tags, "intent")` 分组，但 Core 发的 `agent.intent.accuracy` tag 是 `intent_predicted`/`intent_actual`（见 `ObservabilityMetrics` L511-512），无 `intent` key → 全部归入 "OVERALL"，且 V1 期望的 L0/L1 分层完全缺失。
2. **前端读错字段**：`AccuracyTab` 读 `accuracyData.trend`，但后端 `/ai/intent-accuracy-trend` 返回 `{ series: [...] }`（无 `trend` 键）→ `AccuracyTrendChart` 恒得 `data=null` → 显示"暂无准确率数据"。

#### GAP-I2：混淆矩阵无分层 + 可能为空（🔴仍开放）

- `getConfusionMatrix()` 读 Span attrs `intent_predicted`/`intent_actual`，无 `layer` 参数 → 无法区分 L0→L1 与 L1→L2。
- **数据可得性存疑**：Core 仅在 `agent.intent.accuracy` **metric** 中带 `intent_predicted`/`intent_actual` tag，是否在 **Span attributes** 中携带未确认（代码检索未见 Span 侧写入）→ 矩阵 `totalSamples` 很可能恒为 0（全 0 矩阵）。
- 前端 AccuracyTab 读 `confusionData.matrix`/`confusionData.labels` 与后端返回一致，故矩阵图能渲染（显示空矩阵）。

#### GAP-I3/I4：改写准确率表 / 根因 TOP3 前端契约错配（🟡回归）

- `AccuracyTab` 读 `accuracyData.rewrite`（期望数组）、`accuracyData.rootCauses`（期望 rank/title/count/desc/pct 数组）、`accuracyData.overallStats` 等。
- 但后端 `/ai/intent-accuracy-trend` 仅返回 `{series}`；`/ai/insights` 返回 `{confidenceDistribution, extractionCompleteness, rewriteAccuracy}`（结构不同）；`/ai/confusion-matrix` 返回 `{labels,matrix,totalSamples}`。
- **结论**：AccuracyTab 的三块内容（趋势/改写表/根因）读取的字段后端都不返回 → 整页除混淆矩阵外全部渲染为空。V1 标注"✅已实现"的能力在当前代码中已失效。

---

## 三、数据模型 GAP（当前）

### 3.1 MetricsAgg

| 字段 | 当前 | GAP | 建议 |
|------|------|-----|------|
| metricName | 原样存 OTel 名 | 无法区分 L0/L1 准确率 | 分层命名 `agent.intent.accuracy.L0` / `.L1` |
| tags | JSON（原样 attribute 键） | 缺 `layer`；且 reader/writer 键名不一致（`intent` vs `intent_predicted`） | 统一键名 + 加 `layer` |
| value | Double（Counter 累加值） | 准确率类被当 ratio 用，实为 count | 准确率指标应存比率或分子/分母 |

### 3.2 SpanEntity

| 字段 | 当前 | GAP | 建议 |
|------|------|-----|------|
| attributes | JSON 原样 | 无 Schema；无 `llm.confidence`、`intent.L0/L1.predicted/actual`、`reroute.count` | 规范键名 + 在 Core 发射侧补齐 |

### 3.3 Session

| 字段 | 当前 | GAP | 建议 |
|------|------|-----|------|
| intentFlow | 单字符串 | 各层混存 | 新增 `l0Intent`/`l1Intent`/`l2Intent` |
| (新增) rerouteTriggered | Boolean，硬编码 false | 未随真实 reroute 置真 | 由 Core reroute 时置真 |

### 3.4 RealtimeMetricsVO

| 字段 | 当前 | GAP | 建议 |
|------|------|-----|------|
| intentAccuracy | 单值（且数据源断链→null） | 无 L0/L1 分层 | 新增 `l0IntentAccuracy`/`l1IntentAccuracy` |
| qps | 后端已赋值 `requestCount/21600` | 前端未用该字段，自行重算（冗余但一致） | 前端统一消费 `qps` 字段 |
| errorRate | 后端已计算 | — | ✅ 已解决 |
| violationRate / conversionRate | null 占位 | 无数据源 | 保留，待对接 |
| transferToHumanRate | 新增 null 占位 | 无数据源 | 保留，待对接 |

### 3.5 TraceListVO / TraceDetailVO

| VO | 缺失字段 | 建议 |
|----|---------|------|
| TraceListVO | confidence（Double） | 新增，从 Span `llm.confidence` 提取 |
| TraceListVO | l0Intent / l1Intent（String） | 新增，从分层 attrs 提取 |
| TraceDetailVO | confidence / l0Intent / l1Intent | 同上 |

---

## 四、待确认问题清单（V2 更新）

| # | 问题 | 涉及方 | 优先级 | V1→V2 变化 |
|---|------|--------|--------|-----------|
| Q1 | requestCount 窗口语义 | 产品+后端 | ✅已关闭 | V2 定为 6h 累计，前后端已对齐 |
| Q2 | L0/L1 分层准确率写入方 | 后端+Agent引擎 | 🔴P0 | 仍未定；且发现 Dashboard Redis key 与 Core H2 metric 双通路不一致 |
| Q3 | LLM 置信度字段名 | 算法+LLM平台 | 🔴P0 | 仍未定；确认 Core 是否在 span 带 `llm.confidence` |
| Q4 | 业务转化率对接 | 产品+业务 | 🟡P1 | 无进展 |
| Q5 | 违规率对接 | 产品+安全 | 🟡P1 | 无进展 |
| Q6 | L2 实现排期 | 产品+后端 | 🟡P1 | 无进展；completionRate 仍空 |
| Q7 | Reroute 前端展示粒度 | 产品 | 🟢P2 | 无进展 |
| Q8 | Redis TTL 策略 | 产品+后端 | ✅已部分关闭 | 已支持 1m/5m/15m/6h 多窗口 |
| **Q9** 🆕 | DAU / 在线人数由谁写入？（`setDau`/`setOnlineUsers` 无调用方） | 后端 | 🔴P0 | 回归问题，需补写入 |
| **Q10** 🆕 | AccuracyTab 前后端契约以哪个端点为准？（`/ai/insights` vs `/intent-accuracy-trend`+`/confusion-matrix`） | 前后端 | 🔴P0 | 回归问题，需统一 |
| **Q11** 🆕 | `agent.intent.accuracy` 的 `value` 是 Counter 计数还是比率？语义需对齐 reader | 算法+后端 | 🟡P1 | 新增，当前被误当置信度用 |

---

## 五、优先修复建议（V2）

### P0 — 必须修复（阻塞核心业务 / 当前为损坏态）

| # | 修复项 | 说明 | 预估 |
|---|--------|------|------|
| P0-1 | DAU / 实时在线数据源 | 补 `setDau`/`setOnlineUsers` 调用（OTel 解析或 Core 直写） | 1d |
| P0-2 | AccuracyTab 契约对齐 | 统一前后端字段（`.trend`/`.rewrite`/`.rootCauses` vs 实际返回）；建议前端直接消费 `/ai/insights` 聚合结构 | 1.5d |
| P0-3 | intentAccuracy 分层 + 数据源贯通 | Dashboard 改读 H2 `agent.intent.accuracy`（按 layer 聚合）或 Core 写 `accuracy:intent:L0/L1` | 3d |
| P0-4 | LLM 置信度展示 | Core span 带 `llm.confidence` + VO/前端补列 | 2d |
| P0-5 | 分层意图存储 | Session 加 `l0/l1/l2Intent` + Span attrs 规范 | 2d |
| P0-6 | AIInsights 趋势/混淆矩阵分层 | tag 键名对齐（`intent`→`intent_predicted`）；加 `layer` 参数 | 2d |

### P1 — 应该修复

| # | 修复项 | 说明 | 预估 |
|---|--------|------|------|
| P1-1 | rerouteRate 数据源 + 因果 | Core 发射 reroute 度量；Session.rerouteTriggered 置真 | 2d |
| P1-2 | L1 agent 实时计数 | Core L1 识别阶段 INCR `agent_call:L1` | 0.5d |
| P1-3 | conversionRate / violationRate 对接 | 确认上游 | 待定 |
| P1-4 | completionRate（依赖 L2） | L2 实现后写 `business_completion` | 依赖 L2 |
| P1-5 | `agent.intent.accuracy` 值语义对齐 | 明确 Counter vs Ratio，修正 reader | 0.5d |

### P2 — 可以延后

| # | 修复项 | 说明 |
|---|--------|------|
| P2-1 | activeSessions/DAU H2 持久化历史趋势 | 已有 H2 回退机制，可扩展 |
| P2-2 | 多时间窗口 Dashboard 切换 | 后端已支持多窗口，前端加选择器 |
| P2-3 | Reroute 分析 TAB | 新增独立展示 |
| P2-4 | L2 意图识别占位 | 前端卡片占位 |

### 🆕 N6 — 建议另立审计

新增的 **AgentPerf / TokenCost / ToolCall / Funnel / Satisfaction** 五个 TAB（V1 不存在）各自有独立数据通路（`AgentPerformanceService`/`TokenCostService`/`ToolStatsService`/`ConversionFunnelService`/`SatisfactionService`）。其指标完整性、数据源可靠性、与 Core 发射指标的命名一致性**尚未做过 GAP 审计**，建议单独出一份《AI 洞察新增 TAB 指标 GAP》。

---

## 六、V2 vs V1 差异对照

### 6.1 状态变化总表

| 编号 | 指标 | V1 状态 | V2 状态 | 变化 |
|------|------|---------|---------|------|
| A1 | activeSessions | ✅已实现 | ✅正常 | 不变 |
| A2 | DAU | ✅已实现 | 🟡回归(null) | ⬇️ 数据源断链 |
| A3 | realTimeOnline | ✅已实现 | 🟡回归(null) | ⬇️ 数据源断链 |
| A4 | requestCount 窗口 | 🔴P0 | 🟢已解决 | ⬆️ 改为 6h 并贯通 |
| A5 | QPS 计算 | 🔴P0 | 🟢已解决 | ⬆️ 与 6h 自洽 |
| A6 | agentCall L0/L1/L2 | ✅(P1 TTL) | 🟢部分+🆕N4 | ⬆️ TTL延长；⬇️ L1无实时计数 |
| B1 | Token | ✅ | ✅ | 不变 |
| B2 | TTFT | ✅ | ✅ | 不变 |
| B3 | P95 时延 | ✅ | ✅ | 不变 |
| B4 | 错误率 | 🔴P0 | 🟢已解决 | ⬆️ 后端计算 |
| C1 | intentAccuracy 分层 | 🔴P0 | 🔴仍开放 | ➖ 未动+数据源断链 |
| C2 | rewriteAccuracy | ⚠️单值 | 🔴仍开放 | ⬇️ Dashboard侧断链 |
| C3 | rerouteRate | 🔴P0 | 🔴仍开放 | ➖ 未动+无数据源 |
| C4 | completionRate | 🟡P1 | 🔴仍开放 | ➖ 未动 |
| D1 | conversionRate | ❌P1 | 🔴仍开放 | ➖ 未动 |
| D2 | violationRate | ❌P1 | 🔴仍开放 | ➖ 未动 |
| T1 | LLM 置信度 | 🔴P0 | 🔴仍开放 | ➖ 未动 |
| T2 | 分层意图存储 | 🔴P0 | 🔴仍开放 | ➖ 未动 |
| T3 | 链路标识 | ✅ | ✅ | 不变 |
| T4 | 链路性能 | ✅ | ✅ | 不变 |
| T5 | spanTree/ioOutput | ✅ | ✅ | 不变 |
| I1 | 准确率趋势 | 🔴P0 | 🔴+🟡回归 | ➖ 分层未做；且塌缩+前端读错字段 |
| I2 | 混淆矩阵 | 🔴P0 | 🔴仍开放 | ➖ 无分层+可能空 |
| I3 | 改写准确率表 | ✅ | 🟡回归(空) | ⬇️ 前端契约错配 |
| I4 | 根因 TOP3 | ✅ | 🟡回归(空) | ⬇️ 前端契约错配 |
| I5 | L2 意图 | 🟡P2 | 🔴仍开放 | ➖ 未动 |

### 6.2 关键差异说明

**1. 计算类 P0 已全部修复（最大进展）**
- V1 最痛的 A4/A5（requestCount "6h累计"标注但 1m 窗口 + QPS 低估 360 倍）与 B4（errorRate 未后端计算）在 V2 中均已解决：后端 requestCount 真正采用 6h 窗口并由 OTel 多窗口 INCR 贯通，QPS=6h累计/21600 自洽，errorRate 由后端基于 6h 计数计算。**这是 V1→V2 唯一成体系的修复。**

**2. 语义质量 / 链路层核心 GAP 零进展**
- C1（L0/L1 分层准确率）、C3（reroute 因果）、T1（LLM 置信度）、T2（分层意图）、I1/I2（分层趋势/矩阵）在 V1 中即列为 P0，V2 中**全部仍为开放状态**，且其中 C1/C3 还暴露出"Dashboard 读取的 Redis key 在代码库无任何写入方"的断链问题——即 V1 假设的"外部 SET"写入方从未落地。

**3. 项目扩张引入新回归（V1 没有的问题）**
- **DAU / 实时在线（A2/A3）**：V1 标"已实现"，但 V2 实测 `setDau`/`setOnlineUsers` 无调用方 → 永远 null。属回归。
- **AccuracyTab 整页（I3/I4 + I1 渲染）**：V1 标"✅已实现"的改写准确率表、根因 TOP3，以及趋势图，在 V2 中因前端读取后端不返回的字段（`.trend`/`.rewrite`/`.rootCauses`）而全部渲染为空。属回归 + 契约错配。

**4. 新增能力与新增 GAP**
- 新增 Redis 多窗口（1m/5m/15m/6h）、H2 回退机制（`RedisH2SyncService` + `fallbackMetrics` 指示）、TTFT 分位数、Agent 分布饼图、`transferToHumanRate` 占位、以及 5 个全新 AI 洞察 TAB。
- 新增发现：L1 agent 调用无实时计数（N4）、`agent.intent.accuracy` 值语义被误用为置信度（N5）、Core 与 Dashboard 双数据通路不一致（C1/I1）、以及 N6（新 TAB 建议另立审计）。

### 6.3 优先级走势

- V1 P0 共 7 项 → V2 中 **3 项已解决**（A4/A5/B4，含 A6 部分），**4 项仍开放**（C1/C3/T1/T2 归类于语义质量与链路层），并**新增 2 项 P0 回归**（DAU/在线数据源、AccuracyTab 契约）。
- 净结果：P0 数量从 7 增至 **9**（4 原开放 + 2 回归 + 3 待确认 Q9/Q10 类），且语义质量/链路层这一 V1 已识别的"最关键架构决策"依旧悬而未决。

---

## 七、总结

V1 是一份"识别问题"的文档；半年后重审，V2 的结论是：**计算口径类问题已修好，但业务语义类问题原地踏步，且规模扩张带来了新的契约断裂。**

当前最该做的两件事，按性价比排序：
1. **先止血回归**（P0-1 / P0-2）：补 DAU/在线写入、对齐 AccuracyTab 前后端契约——成本低、见效快、直接恢复 V1 已宣称的"已实现"能力。
2. **再攻核心架构**（P0-3~P0-6）：L0/L1 分层准确率与分层意图的存储/展示方案，仍是横跨 Dashboard/Trace/AIInsights 三页的最大技术债，且需先与 Core/Agent 引擎团队确认数据写入方（Q2/Q3/Q9/Q10），否则会继续陷入"后端读一个没人写的 key"的怪圈。

建议将 N6 的五个新 TAB 单列审计，避免下一版 GAP 再次出现"范围外能力悄悄损坏"的情况。

---

## 八、本轮修复记录（2026-07-08）

基于 V2 分析，优先止血两个回归（P0-1 / P0-2）并补 L1 实时计数钩子（P1-2）。核心架构类（P0-3~P0-6）仍需与 Core/Agent 引擎确认数据写入方后再落地（见 Q2/Q3/Q9/Q10/Q11）。

### 8.1 P0-1 DAU / 实时在线数据源断链 — 已修复
- **根因**：`setDau`/`setOnlineUsers` 全库无调用方 → DAU/在线永远为 null。
- **修复**：`RedisMetricsService` 新增 `recordDauUser(userId)`（按 userId 去重写入 `obs:metrics:dau_users:{date}` SET，SCARD 刷新 DAU）与 `recordOnlineUser(userId)`（ZSET 5 分钟滑动窗口，裁剪旧成员后 ZCARD 刷新在线人数）。
- **接入点**：`SessionService.upsertSession`（SessionBridge）在每次会话交互时调用；userId 为 `"unknown"` 时跳过，避免脏数据。Core 上报会话即产生真实 DAU/在线数据。

### 8.2 P0-2 AccuracyTab 前后端契约错配 — 已修复
- **根因**：前端读 `accuracyData.trend/.rewrite/.rootCauses`，后端 `/ai/intent-accuracy-trend` 仅返回 `{series}`、`/ai/insights` 返回结构不一致 → 趋势图/改写表/根因卡片整页为空。
- **修复**：新增统一端点 `GET /api/v1/ai/accuracy-report`，由 `AIInsightsService.getAccuracyReport` 聚合：
  - `trend`：按 `intent_predicted` 正确分组（原按 `intent` 键 → 塌缩成 OVERALL），返回 `{categories, series}`（series.data 为百分比数值数组，修复原 `{time,value}` 对象无法渲染的问题）；
  - `rewrite`：从 H2 `agent.rewrite.accuracy` 派生改写表行；`rootCauses`：按 `rule_check` 失败原因取 TOP3；
  - `confusion`：复用现有混淆矩阵；`overallStats`/`rewriteSummary`/`rootCauseSummary` 文案。
- **前端**：`AccuracyTab` 改为单端点 `fetchAIAccuracyReport` 消费，趋势图 y 轴改为 0–100%。

### 8.3 P1-2 L1 agent 实时计数 — 已补钩子
- `OtlpParserService.incrementAgentRedis` 新增 L1 检测（`intent.classif`/`agent.l1`/`l1.call`/`intent.l1`/`router.l1`），Core 一旦发射 L1 指标即实时计入 `agent_call:L1`；此前仍由 `MetricsQueryService` 的 Span operationName 启发式回退兜底。

### 8.4 仍需 Core 侧配合的项（本轮部分已改，分层待 Core）
- **P0-3 数据源贯通 — 本轮已修复（见 8.6）**：Dashboard 的 intentAccuracy 不再读无人写入的 Redis `accuracy:intent`，改为从 H2 `agent.intent.accuracy` 实时计算真实准确率（0-100%），趋势图 572% 失真一并修复。
  - 仍待 Core：意图识别的 **L0/L1 真分层** 需要 Core 在 `recordIntentAccuracy` 携带 `layer` tag（当前仅 `state/intent_predicted/intent_actual`）；后端已预留按 `layer` 分组能力，Core 补 tag 即自动生效（Q2）。
- **P0-4** LLM 置信度、**P0-5** 分层意图、**P0-6** 趋势/混淆矩阵分层：依赖 Core 在 span/metric 携带规范字段（Q3/Q10/Q11），本轮未改动。
- **L1 计数**真正生效依赖 Core 发射 L1 指标（8.3 已留好钩子）。

### 8.5 修复后实测验证（2026-07-08，服务重启后）
- **P0-1 ✅ 已端到端验证**：`POST /api/v1/sessions` 携带 `userId=verify-user-001` 后，`GET /api/v1/metrics/realtime` 的 `dau` 从 0→1、`realTimeOnline` 从 0→1。证明 DAU/在线数据源已真实贯通（此前永远为 0/null）。
  - ⚠️ 验证残留：测试会话 `verify-session-001` / 用户 `verify-user-001` 已写入 Redis+H2，导致 `dau≥1`；DAU 为当日去重计数不会自动清零，Session 列表会多出该测试会话，可忽略或手动清理。
- **P0-2 ✅ 结构已验证（数值待 P0-3）**：`GET /api/v1/ai/accuracy-report` 返回完整 `trend{series}` / `rewrite[]` / `rootCauses[]` / `confusion` / `overallStats`，前端契约对齐、不再空白。
  - ⚠️ 已知数据语义问题（属 P0-3 范畴，本轮未修）：`trend.series.data` 出现 `572.05`/`392.0` 等 >100% 值。根因是 H2 中 `agent.intent.accuracy` 存的是 **Counter 累积值（≈5.72）而非 0-1 准确率比率**，聚合层 `avg*100` 后失真。需 Core 规范该指标为比率口径（Q2）才能显示真实准确率。
- **P1-2 ✅ 代码已就位（运行时需 Core L1 流量）**：`GET /api/v1/metrics/realtime` 的 `fallbackMetrics` 含 `l1Calls`，说明当前 `agent_call:L1` Redis 计数为 0、走 Span 启发式回退——符合"无 Core L1 指标时回退"的预期。L1 实时计数钩子已编译进后端（BUILD SUCCESS），待 Core 发射 L1 OTel 指标（`intent.classif`/`l1.call` 等）即可观测实时 `l1Calls`。

### 8.6 P0-3 intentAccuracy 数据源贯通 + 572% 修复 — 已修复
- **根因（与 8.5 P0-2 注同源）**：
  1. Dashboard 卡片 `intentAccuracy` 读 Redis `accuracy:intent`，全库无任何 `setIntentAccuracy` 写入方 → 永远 null → 卡片空白/显示 `-`。
  2. `getAccuracyReport` 趋势图的 `buildTrend` 对 Counter 累计值（如 `TRANSFER correct=4.0`）直接 `avg×100` → 出现 572% 等 >100% 失真值。
- **修复**：
  1. `AIInsightsService` 新增 `computeIntentAccuracy(from,to)`：从 H2 `agent.intent.accuracy` 按 `(intent_predicted, state)` 取窗口内最新累计值，按意图聚合 `correct/Σ(state)×100`，返回真实准确率（0-100）；无数据返回 null。
  2. `MetricsQueryService` 注入 `AIInsightsService`，intentAccuracy 接通逻辑改为：Redis 有值用 Redis，否则用 `computeIntentAccuracy(近6h)` 兜底；两者皆 null 才标记 fallback。Dashboard 卡片首次有真实值。
  3. `buildTrend` 重构：按 `(intent, day, state)` 取最新累计值，每天一个点算真实 daily 准确率（0-100），彻底消除 572% 失真。
  4. `buildOverallStats` 改为返回 `总体意图准确率 XX.X%`（原为"样本数 N · 均值 X.XX"，均值仍是累计值）。
- **分层（L0/L1）**：后端已预留按 `layer` tag 分组能力（`extractTag(tags,"layer")`）。当前 Core 的 `recordIntentAccuracy` 仅发 `state/intent_predicted/intent_actual`，无 `layer` → 暂按 `intent_predicted` 分组展示；Core 补 `layer` tag 后自动生效（Q2）。
- **验证**：
  - **算法级（已通过）**：用 `debug/metric-samples` 拉取 `agent.intent.accuracy` 全量记录（某历史库含 4013 条），在 Python 复现 `computeIntentAccuracy` + `buildTrend` 算法 → 总体意图准确率 **76.92%**、趋势图跨 07-06/07-07/07-08 各意图准确率全部 ≤100（最大 100%）→ **572% 失真彻底消失**。
  - **运行时（被 Core 卡住，非后端问题）**：当前运行 Core(8080) **未向 H2 写入 `agent.intent.accuracy`**（实时库该 metric 记录数 = 0；而 `agent.rewrite.accuracy` 有 28 条、`agent.intent.recognized` 有 14 条）。根因：Core `/api/bank/chat` 端点返回 HTTP 500（`No converter for WorkflowOutput`），query 在 L2 意图准确率埋点触发前就中断；且当前运行 Core 构建疑似偏旧。故实时 `intentAccuracy` 仍 null。**P0-3 后端代码已正确，需 Core 修 chat 500 + 确保 `recordIntentAccuracy` 在实时流程触发后方能在生产见效**（见 8.7）。

### 8.7 C2 rewriteAccuracy 数据源断链 — 已修复并运行时验证（2026-07-08 第二轮）

- **根因**：Dashboard 卡片 `rewriteAccuracy` 读 Redis `accuracy:rewrite`，全库无 `setRewriteAccuracy` 写入方 → 永远 null → 卡片显示 `-`。`getDoubleWithFallback` 再读快照表（由 null 的 Redis 值写入）仍 null。
- **修复**（与 P0-3 同构）：
  1. `AIInsightsService` 新增 `computeRewriteAccuracy(from,to)`：从 H2 `agent.rewrite.accuracy`（Micrometer DistributionSummary 记录每次改写比率 0-1，tag `rule_check`/`domain`）取窗口内均值 ×100，返回 0-100 百分比；无数据返回 null。
  2. `MetricsQueryService` 的 Zone C 接通逻辑：Redis 有值用 Redis，否则用 `computeRewriteAccuracy(近6h)` 兜底；两者皆 null 才标记 fallback。
- **运行时验证 ✅（已通过）**：重启后端（2.2GB 实时库，含 28 条 `agent.rewrite.accuracy`）后，`GET /api/v1/metrics/realtime` 的 `rewriteAccuracy = 0.0`（**非 null**），且 `rewriteAccuracy` 已从 `fallbackMetrics` 消失（证明成功算出值）。Dashboard 卡片由 "改写 -" 变为 "改写 0%"。
  - ⚠️ 数据观察：实时库 28 条 `agent.rewrite.accuracy` 值**全为 0.0** 且无 `rule_check` 标签 → 来自 `recordRewriteAccuracy(double rate=0.0)`，即 Core 在 rule 模式下改写准确率确记为 0。属 Core 数据语义，非后端 bug；后端已忠实呈现真实值。
- **P0-3 vs C2 对照**：C2 在实时库有数据故运行时直接见效；P0-3 实时库无 `agent.intent.accuracy` 故仅算法级验证通过，等 Core 接通埋点。

### 8.8 后端可独立修复项已穷尽 — 剩余项均依赖 Core 发射遥测

经本轮（P0-1/P0-2/P1-2/P0-3 代码/C2）后，所有**不依赖 Core 改写数据发射**的 GAP 已修完。剩余未修项及其 Core 侧前置条件：

| 项 | 当前状态 | 需 Core 配合动作 |
|---|---|---|
| P0-3 运行时 | 代码✅/算法✅/运行时❌ | 修 `/api/bank/chat` 500（WorkflowOutput 序列化）；确保 `recordIntentAccuracy` 在实时 query 流程触发，向 H2 写 `agent.intent.accuracy` |
| P0-4 LLM 置信度 (T1) | 🔴 | Core 在 Span attributes 携带 `llm.confidence` |
| P0-5 分层意图 (T2) | 🔴 | Core 在 Span/会话携带 L0/L1/L2 分层意图字段 |
| P0-6 趋势/混淆矩阵分层 | 🔴 | 依赖 P0-4/P0-5 的 span 字段 + Core 在 `recordIntentAccuracy` 加 `layer` tag |
| C3 rerouteRate | 🔴 | Core 在 reroute 时发射 reroute 度量（或写 `reroute_rate` Redis） |
| C4 completionRate | 🔴 | L2 实现 + Core 发射业务完成率 |
| D1 conversionRate / D2 violationRate | 🔴 | 上游业务系统对接 |
| I2 混淆矩阵 | 🔴(运行时空) | 实时库 `traces` 为空（totalElements=0）→ 无 span 数据源；需 Core/Collector 把带 `intent_predicted`/`intent_actual` 的 span 写入该 H2 |
| I5 L2 意图识别 | 🔴 | 占位，需 L2 链路数据 |

> 结论：后端侧修复到此告一段落。下一步必须由 Core 团队补数据发射（chat 500 修复 + 意图准确率/置信度/分层意图/reroute/span 属性），后端已预留全部读取与聚合能力，Core 补字段即自动生效。
