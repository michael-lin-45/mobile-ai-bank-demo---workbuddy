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
| C4 | completionRate | 🟡P1 | 单值 `businessCompletionRate`，读 `business_completion` Redis | **`setBusinessCompletionRate()` 无调用方** → 改从 H2 `agent.business.outcome` 按 `outcome=success/fail` 聚合实时算（success/(success+fail)×100） | 🟢 已解决（2026-07-08 C4） |

### 1.4 DashboardPage — Zone D 业务效果

| # | 指标名 | V1状态 | 当前实现 | 数据来源 | V2状态 |
|---|--------|--------|---------|---------|--------|
| D1 | conversionRate | ❌P1 | 单值 `businessConversionRate`，读 `conversion` Redis | **`setConversionRate()` 无调用方** → 改从 H2 `agent.business.outcome` 实时算（业务成功率近似转化率） | 🟢 已解决（2026-07-08 D1） |
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
| C4 completionRate | 🟢 | 后端聚合已兼容 `outcome`/`result` 双源，无数据显示 null（2026-07-08 修正"假0.0"：streaming 路径漏埋 `outcome=success`，且 `AbstractDomainService` 仅发 `result=success`） |
| D1 conversionRate / D2 violationRate | 🟢(D1)/🔴(D2) | D1 同 C4 数据源已修；D2 违规率无安全围栏对接、无数据源，仍开放 |
| I2 混淆矩阵 | 🔴(运行时空) | 实时库 `traces` 为空（totalElements=0）→ 无 span 数据源；需 Core/Collector 把带 `intent_predicted`/`intent_actual` 的 span 写入该 H2 |
| I5 L2 意图识别 | 🔴 | 占位，需 L2 链路数据 |

> 结论：后端侧修复到此告一段落。下一步必须由 Core 团队补数据发射（chat 500 修复 + 意图准确率/置信度/分层意图/reroute/span 属性），后端已预留全部读取与聚合能力，Core 补字段即自动生效。

### 8.9 C4/D1 业务效果指标真值化 — 已修复（2026-07-08 第三轮）

- **根因（与 C1/C2/C3 同构）**：Dashboard Zone C `businessCompletionRate` 读 Redis `business_completion`、Zone D `businessConversionRate` 读 Redis `conversion`，二者 `setBusinessCompletionRate()`/`setConversionRate()` 全库无调用方 → 永远 null → 前端卡片显示 `-`/`暂无`。
- **数据源（Core 已接通）**：
  - `AbstractDomainService.java:161` 在 L2 子图 `COMPLETE` 时发 `agent.business.outcome{intent,result=success}`（每次 L2 完成都发，真实高频成功信号；**无 `result=fail` 分支**）。
  - `GraphExecutionEngine.java:102/104` 在 **blocking** 子图 `COMPLETED` 调 `recordBusinessSuccess()` → 发 `agent.business.outcome{outcome=success}`；否则/异常调 `recordBusinessFail()` → `{outcome=fail}`。
- **修复**（纯后端，Core 无需重编，与 C1/C2/C3 同构）：
  1. `AIInsightsService` 新增 `computeBusinessSuccessRate(from,to)`：从 H2 `agent.business.outcome` 聚合业务成功率 = success/(success+fail)×100；无数据返回 null。
  2. `MetricsQueryService` Zone C/D：Redis 有值优先，否则 `computeBusinessSuccessRate(近6h)` 兜底；最终 null 才标记 fallback。
  3. D1 转化率以"业务成功率"近似（Core 无独立转化/到达埋点），与 C4 同源同构；D2 违规率无安全围栏对接、无数据源，保持 null（前端"暂无"，不伪造）。

### 8.10 C4/D1 "假 0.0" 修正（2026-07-08 第四轮）

- **现象**：start-all 重编 backend 后，realtime `completionRate`/`conversionRate` 实测为 `0.0`（非 null），前端显示"完成率 0%"——比 null 更误导。
- **根因**：
  1. 第三轮聚合**只认 `outcome=success/fail` tag**，但 `GraphExecutionEngine` 的 **streaming 成功路径（`executeStreaming`/`resumeStreaming` 的 terminal 分支）从未调 `recordBusinessSuccess()`**（仅 blocking 与异常路径调用）→ 窗口内 `agent.business.outcome{outcome=success}` 几乎为 0。
  2. `AbstractDomainService` 仅发 `result=success`（无 `result=fail`），第三轮因"无 outcome 键"将其忽略。
  3. 聚合 `total<=0` 时返回 `0.0` → 把"无 outcome 数据"误显成"0% 完成率"。
- **修复**（`AIInsightsService.computeBusinessSuccessRate`，仍纯后端）：
  - 成功判定兼容**两套埋点**：`outcome=success` **或** `result=success` 均计入 success 桶；`outcome=fail`/`result=fail` 计入 fail 桶。
  - `total<=0` 改为返回 **null**（前端"暂无"），彻底消除假 0.0。
  - 现 success 以 `result=success`（每次 L2 COMPLETE 高频发射）为主源，可得出真实完成率。
- **待验证**：后端已改，待主人重跑 `start-all.ps1`（脚本会自动停 backend 释放 jar 锁并重编 backend）→ 用 `test/seed.sh`+`seed_new.sh` 打一轮 L2 流量 → 等 60s OTLP 步长 → 跑 `test/verify_gap_batch.py` 确认 C4/D1 显示真实非 0 值（或暂无数据时为 `-`）。

### 8.11 总览大屏 AI 性能 + AI 洞察 AGENT性能/TOKEN成本 三类归零 — 已修复（2026-07-08 第五轮）

**现象（主人反馈）**：
1. 总览大屏 Zone B（AI 性能）：TOKEN 调用量 / 输入 / 输出**全为 0**；首 Token 时延 P50/P95/P99 **全为 0**。
2. AI 洞察 → AGENT 性能 TAB：表格 / KPI / 箱线图 / 散点图**基本全空**。
3. AI 洞察 → TOKEN 成本 TAB：趋势 / 拆解 / 明细**基本全空或 0**。
- 主人判断三类归零"有联系"，要求一起修。

**根因定位（三层，互相关联）**：

#### 根因① TTFT 永远为 0（Core 导出类型陷阱）
- `ObsChatModel` 原对 `llm.first_token.latency` / `llm.operation.duration` 用 `Timer.publishPercentiles(0.5,0.75,0.9,0.95,0.99)`。
- **Micrometer OTLP 导出器对 `publishPercentiles` 的 Timer 导出为 OTLP `Summary` 类型**；而 `OtlpParserService.parseMetrics` 只解析 `getHistogram()/getSum()/getGauge()`，**完全不解析 Summary** → TTFT P50/P95/P99 永远 0，且 Redis `ttft:1m` ZSET 永远无样本。
- **修复**：新增 `buildTimer(...)` 统一用 `publishPercentileHistogram(true)`（导出为真正的 OTLP Histogram）→ 经 OTel Collector(4318) → `OtlpParser.recordTTFT` 写入 Redis `ttft:1m`，并由 `RedisH2SyncService` 每 30s 快照 `ttft_p50/p95/p99:1m` 到 H2 `redis_metrics_snapshot` 兜底。TTFT 修复**只需改 Core**，后端管道已就绪。

#### 根因② 总览大屏 Token 调用量/输入/输出为 0（Redis 1m 窗口空闲 + H2 兜底偏弱）
- Token 类 Counter（`llm.token.input/output`）经 `incrTokenCountDelta` 写入 **1m 窗口**（TTL 60s）。当观测窗口无实时 LLM 流量时 Redis 1m 值归 0 → 大屏显示 0。
- 原兜底逻辑 `if (tokenInput == 0 && tokenOutput == 0)` **两个方向同时才回退**，且 `getLatestMetricValue` 取 H2 `llm.token.input/output` 累计值（cumulative 最后一行=当前总量）本应兜底，但联动条件使单方向缺失时不触发。
- **修复**：`MetricsQueryService.getRealtime()` 改为**输入/输出各自独立回退**（`if (tokenInput == 0) {...}` / `if (tokenOutput == 0) {...}`），回退窗口放宽到近 7d。Token 总量即可从 H2 cumulative 真实兜底，不再因 Redis 1m 空闲而恒 0。

#### 根因③ AGENT 性能 / TOKEN 成本页面全空（读从未写入的静态表）
- `AgentPerformanceService` / `TokenCostService` 原实现分别读取 `agent_performance` / `token_cost` 静态表；**全代码库无任何写入方**（schema.sql 定义了表但无 INSERT）→ 页面永远为空。
- **修复（重写两个 Service，改为实时聚合，接口契约不变）**：
  - `AgentPerformanceService`：从 **SpanEntity**（每个 LLM 调用一条业务 span，含 `model.name/agent.name/agent.level/intent` 属性）实时聚合调用次数 / 真实 operation 耗时分位 / 错误率（按 model 与 agent 双维度）；再从 H2 `metrics_agg` 补充 TTFT（`llm.first_token.latency`）、TPOT（`llm.token.per.output.time`）、Token 总量（`llm.token.input/output`，cumulative 取窗口 MAX）。返回结构保持与原前端契约一致：`{dimension, kpis, tables, boxplots, scatters, categories}`。
  - `TokenCostService`：从 H2 `metrics_agg` 按 `model`/`intent` 聚合 `llm.token.input/output`（cumulative 取窗口 MAX），从 SpanEntity 按 `(intent,model)` 交叉统计调用次数；成本 = 输入×0.002/1000 + 输出×0.006/1000（每 1K tokens，单位元）。`getDetailTable` 返回 `{content: [...]}` 适配前端 `detailRes.value?.content`。
  - 维度提取：优先从 Span/metrics 的 attributes JSON 读 `model.name/agent.name/agent.level/intent`，fallback 解析 operationName 的 `L0:qwen-plus` 格式。
- 为避免改 H2 表结构（`ddl-auto:none` + 需 ALTER 现有 2.2GB 文件的风险），**未新增列**，完全复用 SpanEntity + metrics_agg 现有表作为真实数据源。Controller 接口签名、前端端点、返回 JSON 结构均兼容，仅替换实现。
- 前端 `AgentPerfTab.jsx`：Agent 维度无 TTFT 时原渲染 `nullms`，改为显示 `—`。

**编译验证**：
- 重写 `TokenCostController` 调用 `getDetailTable()` 时因返回类型由 `List` 改为 `Map` 触发一次编译错误（`TokenCostController.java:44` 类型不兼容），已修正为直接赋值 `data = tokenCostService.getDetailTable(...)`（其已返回 `{content:[...]}`）。
- 后端 `mvnw.cmd -o package -DskipTests` **BUILD SUCCESS**（2026-07-08 17:36），`AgentPerformanceService`/`TokenCostService`/`ObsChatModel`/`MetricsQueryService` 改动全部通过编译。

**待验证（需主人操作）**：
- 后端已编译通过，待主人重跑 `start-all.ps1`（会停 backend 释放 jar 锁并重编 Core + backend，并完成 Core 端 `publishPercentileHistogram` 修复的部署）。
- 打一轮 L2 流量（LLM 真实调用）→ 等 60s OTLP 步长 + 30s Redis→H2 TTFT 快照：
  1. 总览大屏 Zone B：TOKEN 调用量/输入/输出应从 H2 cumulative 兜底显示真实非 0；TTFT P50/P95/P99 应出现真实延迟值（不再恒 0）。
  2. AI 洞察 → AGENT 性能：表格/KPI/箱线图/散点图应从 Span + metrics_agg 渲染真实 agent/model 维度数据。
  3. AI 洞察 → TOKEN 成本：趋势/拆解/明细应从 metrics_agg + Span 渲染真实 token 量与成本估算。

**补正（2026-07-09 验收）**：主人重启后验收，AGENT性能（532 calls + KPI + 箱线/散点图）/TOKEN成本（trend 312874 input/9261 output/cost 0.68 + detail 按 intent×model）已闭环；但**总览大屏 Token 仍 0**。根因：①Token 兜底原位于 60s 节流块内，大屏 3s 轮询多数落在节流窗口 → 显示 Redis 1m 空值（闪 0）；②`getLatestMetricValue` 取末行，而 Core 重启使 cumulative 计数器归零 → 末行仅重启后小计（25752）非真实累计总量（312874=窗口 MAX，TOKEN成本因用 MAX 故正确）。修复：`MetricsQueryService` 将 Token/Error 兜底**移出节流**（Redis 为 0 时每次实时查 H2 MAX，廉价查询并标记 fallback）、`getLatestMetricValue`→`getMaxMetricValue`（窗口 MAX）。前端 Zone B "Token 调用量"=tokenInput+tokenOutput，故修 tokenInput/output 即同时修三项。compile 通过；因运行中的 backend 持 jar 锁致 `package` repackage 失败，需重跑 `start-all.ps1` 释放锁重编部署，再用 `test/send_l2_traffic.py` 打 L2 流量验证 TTFT+Token。

### 8.12 总览大屏 8 项人工验证问题修复（2026-07-09 第十~十四轮，已闭环）

**背景**：master 重跑 `start-all.ps1` 部署后，主人对总览大屏做人工验收，发现 8 项显示问题（Zone B/C/D + 数据清理）。逐项定位根因并修复，最终 8/8 全真 PASS（第十四轮前台阻塞验收确认）。

**问题清单与根因/修复**：

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | 清 H2 老数据只留 2 天 | 无清理端点；`void deleteByX` 派生删除在 2.2GB `spans` 表 OOM→500；后改 `@Modifying` 批量删但漏 `@Transactional` 致假绿（`-1`） | 新增 `AdminController.POST /purge?days=2`，4 表 `@Modifying @Query` 批量删 + `@Transactional` 写事务；`verify_dashboard.py` 断言任一表删除=-1 即 FAIL |
| 2 | 首Token时延=0ms | `recordTTFT((long)avg)` 收秒级 `avg`→截断 0 | 按指标单位归一：llm.* Timer 记录为毫秒直接入；`http.*.request.duration` 秒×1000（新增 `isSecondsUnitMetric`） |
| 3 | P95系统时延=0ms | 同上 `avg` 单位误判 | 同 #2，`recordLatency` 用统一 `valueMs`；`isLatencyMetric` 排除 `jvm./tomcat./system./process.`（避免 GC 时长污染 P95） |
| 4 | 错误率只留 2 位小数 | Redis `incrErrorCount` 按"每个 error 指标每次导出自增"→ `errorCount(44)/requestCount(12)=366%` 失真 | `errorRate` 改基于 H2 spans 真实计算（错误数/操作数，6h 窗口；错误数=MAX(ERROR-status span 数, `llm.error.count` 窗口 MAX)），恒 0~1 分数；前端 `errorRate*100`→2 位小数 % |
| 5 | 业务转化率改"暂无" | 无数据源（`setConversionRate` 无调用方） | 后端 `businessConversionRate=null`；前端 `MetricCard emptyText="暂无"` |
| 6 | 访问用户量=0 人 | 前端用 `activeSessions`(=0) | 改读 `dau`（历史统计人数，非 0） |
| 7 | 实时在线=0 | `setOnlineUsers` TTL 30s < Session 5min 滑动窗口→过期归零 | TTL 改 300s 匹配窗口 |
| 8 | 趋势图例空 | `<TrendChart>` 未传 `data` prop（无数据早退不渲染 legend） | 新增 `GET /metrics/trend` + `TrendVO` + `fetchMetricsTrend` + `<TrendChart data={trend}/>`（按 30min 分桶：请求量=根 span 计数、Token=llm.token 累计增量） |

**修复链关键坑（第十二~十三轮）**：
- **532000ms 误放大**：第十轮 blanket `avg*1000` 修"0ms"，但 `avg` 单位因指标而异（`http.server/client.request.duration` 由 Micrometer OTLP 以**秒**导出；`llm.*` Timer（`buildTimer` 用 `MILLISECONDS`）`avg` 本就是**毫秒**）→ llm.* 被放大千倍。第十二轮改 metric-aware（`isSecondsUnitMetric`）。
- **errorRate 366%**：Redis 计数器按"指标导出次数"自增，口径完全失真（非按错误事件）。第十二轮改 H2 spans 真值计算，脱离 Redis 计数器。
- **purge 假绿**：第十一轮 `void deleteByX` 派生删除 OOM/500 → 第十二轮改 `@Modifying` 批量删（不加载实体）→ 但**漏 `@Transactional`**，继承 `SimpleJpaRepository` 的 `readOnly=true` 事务，在只读事务里 DELETE 抛异常 → 被 `safeDelete` catch 返回 `-1`，老数据没删却 HTTP 200 → 第十三轮补 `@Transactional` 真正生效。

**验证（2026-07-09 第十四轮，前台阻塞验收，遵守"主动监控拿到结果再汇报"）**：`verify_dashboard.py --purge --traffic --wait 120` → 8/8 全真 PASS：
- #1 `spans=17120 / metrics_agg=185916 / snapshot=6644 / logs=4390`（**真实删除行数，非 -1**，证明 @Transactional 生效，假绿消除）
- #2 TTFT P50=334 P95=514 P99=514 (ms)；#3 P95=1656ms；#4 errorRate=0.00%（2 位小数）；#5 转化率"暂无"；#6 dau=6；#7 online=6；#8 趋势 12 桶 / 387 请求 / 235318 Token

**结论**：总览大屏 8 项人工验证问题全部真值化闭环；第十二~十三轮潜伏的 532000ms 误放大 + errorRate 366% + purge 假绿三处根因均已根除并运行时验证。

## 8.13 Agent 分层调用数 L1 > L0 反向（第十五~十六轮，2026-07-09）
- **现象**：总览大屏「Agent 调用 L0/L1/L2」显示 L0=1019、L1=1114（L1 反超 L0），不符合 L0 为顶层编排、L1 为其下子 Agent 的拓扑（应 L0 ≥ L1）。
- **根因诊断**：L0/L1/L2 来自 Redis 计数器 `obs:metrics:agent_call:L0/L1/L2`，由 `OtlpParserService.incrementAgentRedis` 在**每个 `agent.*`/`llm.*` 指标 data point 摄入时 `incrAgentCall(+1)`**。指标是累积计数器、按 scrape 周期反复导出 → 每次导出都 +1，计数被放大 N 倍；且 L1 匹配名集（5 个：`intent.classif`/`agent.l1`/`l1.call`/`intent.l1`/`router.l1`）比 L0（2 个：`router.decision`/`intent.recognized`）更宽、每请求发射的 L1 类指标更多 → L1 累加速度 > L0，最终反超。**与 errorRate 366% 同源（按 OTLP 导出次数计数而非真实事件）**。
- **澄清**：**清 H2 数据（purge）不是成因**。purge 只清 H2 表，不碰 Redis；实时 `redis-cli` 实测 L0=1035/L1=1132/L2=759 在 purge+重启后毫发无损保留，证明计数器独立于 H2。
- **修复（第十六轮）**：
  1. `SpanRepository` 新增 `countByOperationNamePrefixSince(prefix, from)`（原生 SQL `operation_name LIKE :prefix AND start_time >= :from`）。
  2. `MetricsQueryService` 改为基于 H2 spans 真实计数：`getAgentCallCountsFromSpans()` 用 `L0:%`/`L1:%`/`L2:%` 前缀（'L1:' 天然排除 L1-LLM1/L1-LLM2 子调用），60s 节流缓存（沿用 errorRate 同模式），SET 进 Redis 供快照服务消费。
  3. 删除 `OtlpParserService.incrementAgentRedis` 方法及其在指标摄入循环中的两处调用，根除 per-export 失真源头；旧回退里 `findDistinctOperationNames()`「数去重 opName 种类数」的次生 bug 一并消除。
- **编译**：`mvnw -o compile` BUILD SUCCESS。待用户 `start-all.ps1` 重编部署后，L0/L1/L2 将显示 H2 真实调用数（L0 ≥ L1），不再随导出频率漂移。

## 8.14 Core 埋点根治：消除 llm: 退化 span（第十七~十八轮，2026-07-10）

### 背景与现象
- 8.13 修复后端口径（H2 真实计数）后，实测 `L0=L1=L2=20` 而 `requestCount=36`：三者全等且远小于请求数，且 span 统计发现 **16 个 LLM span 退化为 `llm:qwen3.6-35b-a3b`**（`AgentSpanContext.get()==null` 时 ObsChatModel 退化命名），不被 `L0:/L1%/L2:` 前缀命中 → 漏计。
- 退化命名体系实测（`debug/span-stats` 的 distinctOpNames）含正常 `L0:/L1:/L1-LLM1:/L1-LLM2:/L2:` 与异常 `llm:qwen3.6-35b-a3b` 并存 → 根因在 **Core 埋点不完整**，非后端查询 bug。

### 根因
1. **流式 + 裸 ThreadLocal 丢失（主因）**：`WealthInterpretGraphConfig.java:203` `AgentSpanContext.set("L2",...)` → `:206-209` `wealthInterpretChatClient.prompt().stream()` 返回惰性 Flux → `:211` `finally { clear() }` 立即清空 ThreadLocal → Flux 在 clear 之后的图执行器订阅阶段才真正执行 `ObsChatModel.stream()`，此时 `get()==null` → 退化 `llm:`。seed 中约 16 条理财解读走此流式路径。
2. `AgentSpanContext` 为裸 `ThreadLocal`，全仓库仅 `DomainRouter` 一处 `set("L0",...)` 显式设置；其余模型（`paramExtractChatModel` 等）无任何 `set` 调用，调用即退化。

### 修复（比"补 set 站点"更稳健）
将**层级绑定到每个 ObsChatModel 实例**（每个 model 构造时即知所属层，天然跨线程/跨流式订阅）：
- `ObsChatModel`：新增 `defaultAgentLayer`/`defaultAgentName` 字段 + 构造参数；新增 `ResolvedCtx resolve()`（ThreadLocal 有值优先，含 intent/sessionId 富属性；层级为空回退模型绑定层）；`call()/stream()/startBusinessSpan()` 改用 `resolve()`，span 名恒为 `层:模型`，**彻底消除 `llm:` 退化**。
- `ModelConfig.wrapWithObsChatModel` 增加 2 参数，6 个 bean 注入：domain→L0/DomainRouter、context→L1-LLM1/ContextRouter、intent→L1-LLM2/SubGraphRouter、paramExtract→L2/ParamExtract、wealthInterpret→L2/WealthInterpret、chat→L1/ChatService。
- 保留显式 `AgentSpanContext.set(...)`：同步路径仍提供富业务属性，仅在缺失时兜底。

### 验证（2026-07-10，清 H2 + 前台 seed 36 条）
- 操作：POST `/api/v1/admin/purge?days=-1` 全清 H2（deletedSpans=1006/metricsAgg=23622/snapshots=7588/logs=3433）→ 前台 PowerShell 跑 `test/seed_all.py` 36/36 全送达（DONE: 25/36 COMPLETED，其余 INTERRUPTED/ERROR/DISAMBIGUATION 正常分支）。
- **核心结论：llm: 退化 span 彻底消失**。`distinctOpNames` 实测 `['L0:...','L1-LLM1:...','L1-LLM2:...','L1:...','L2:...','POST']`，**零 llm: 前缀**。
- 层级关系正确：`L0=L1=L2=10`（trace 去重口径，满足 L0≥L1≥L2）。
- 语义说明：`L0=10 < 36` 属正确可观测语义——36 条中仅 10 个 trace 真正调 LLM（财富咨询/解读+闲聊），其余走确定性规则/缓存/模板直接返回（未调 LLM，不产生 L0 span）。`requestCount=89` 为 Redis 累计值（purge 不清 Redis，混入前端轮询+curl 探测），不可与 H2 的 36 条 seed 比较。

### 沙箱注意
- PowerShell 后台 seed 任务会被 sandbox 清理（约 2 分钟中断）→ 改用**前台 PowerShell（timeout 8 分钟）**跑 seed 成功。
- `purge?days=N` 中 N 为"保留最近 N 天"：N 越大保留越多；全清须用 `days=-1`（cutoff=明天，删所有历史）。

## 8.15 待定项与后续决策（2026-07-10 第二十二轮）

记录本轮主人提出、暂未拍板的两项设计决策，**仅记录、未改动代码**，待后续明确口径后实现。

### 待定项 A：L0 是否覆盖"所有请求（含不调 LLM 的规则命中）" — 🟢 已决策（方案 b）已实施（2026-07-14）
- **诉求**：主人希望 L0 能反映"所有请求接收数"（含规则命中、不调 LLM 的请求），使 L0 ≈ requestCount（请求总数），从而 **L2 ≤ L0 恒成立**（此前 L0<L1 因确定性路由跳过 L0 span）。
- **决策**：采用 **方案 (b) — 把现有 L0 span 扩展到路由入口**。理由：方案 (a) 需新增独立口径字段且要区分"请求接收数"与"LLM 调用数"，反而增加语义歧义；方案 (b) 复用既有 `L0:` span 命名与 `COUNT(DISTINCT trace_id)` 计数口径，改动最小、计数天然对齐。
- **落地**：见 **§8.18** 确定性路由补全 L0 span。改后 L0 = 全部经 DomainRouter 的请求数（含确定性命中），与 L1 持平；L2≤L0 恒成立。
- **方案 (a) 留作备选**：若未来需要严格区分"请求接收数"与"LLM 消耗数"，再引入 `request.received` 独立口径，不与本改动冲突。

### 待定项 B：reRoute（重新路由）是否包含原报文
- **现状**：路由链 `DomainRouter → ContextRouter / SubGraphRouter → 子图` 存在因意图不清 / 置信度不足 / 升级转交等触发的"重新路由"（reRoute）路径；当前 reRoute 的埋点 / 事件未明确携带触发时的原请求报文（原报文）。
- **诉求**：主人提出需确认 reRoute 是否应携带原报文（用于溯源、调试、复现）。
- **待定（待主人明确两点后决定）**：
  - (1) "reRoute" 的精确范围：哪几条路径算 reRoute（如意图升级转交、兜底重试、跨 Agent 移交等）；
  - (2) "原报文"指哪些字段：原始 query / session 上下文 / 上游响应体。
  - 本轮仅记录，未改动代码。

### 待定项 C：是否引入 Langfuse 作为可观测 / LLM 追踪平台
- **现状**：当前可观测栈 = Core（OTel javaagent）→ OTel Collector(4318) → Backend(9090) → H2（温层）+ Redis（热层）→ Vite/React 前端。LLM 调用经 `ObsChatModel` 打 OTel span（L0/L1/L2 层级）+ Micrometer `llm.*` 指标，后端 `SpanRepository` / `MetricsQueryService` 实时聚合。
- **诉求**：主人提出评估是否引入 **Langfuse**（开源 LLM 工程平台，提供 trace/observation、prompt 管理、评估、token/cost 分析）作为补充或替代的可观测层。
- **待定（待主人明确三点后决定）**：
  - (1) 定位：与现有 OTel/H2 并存（前端接 Langfuse UI 做 LLM 专项分析），还是替代部分能力（如 token/cost 直接走 Langfuse）；
  - (2) 接入方式：Langfuse 有 Spring AI 原生集成（`LangfuseObservationConvention`）与 OpenTelemetry 导出器两种，是否需改 Core 埋点；
  - (3) 部署形态：自托管（Docker）还是云端 SaaS。
  - 本轮仅记录，未改动代码。

### 同步说明
- 最近几轮修改（第十七~十八轮 / R17–R21）已同步刷新至本 V2 文档：8.12（总览大屏 8 项人工验收问题修复）、8.13（Agent 分层调用数 L1>L0 反向根因与 H2 真值化）、8.14（Core 埋点根治：消除 `llm:` 退化 span）均已落档，本轮 8.15 补充三项待定项。

## 8.16 链路追踪列表为空（前端 Trace 列表不显示，2026-07-10 第二十三~二十四轮）

### 现象
主人反馈：H2 spans 表有值（379 条）、Redis 有值，但前端「链路追踪」页 Trace 列表为空（`TraceTable` 显示"暂无 Trace 数据"）。

### 根因（先看接口数据再定位，非猜）
- 前端 `fetchTraces` → `GET /api/v1/traces` → `TraceQueryController.listTracesPaginated` → `TraceQueryService`，旧逻辑**依赖"根 span"语义**当 trace 入口：
  1. `findRootSpansByTimeRange`（parentSpanId IS NULL）→ 取根 span；
  2. `hasBusinessRoot` 判断：根 span 的 `op` 若等于 `"POST"` 则视为"无业务根" → 走 fallback `findBusinessRootSpansByTimeRange`（`kind='SERVER' AND operation_name LIKE 'POST %'`，即需 `POST /api/bank/chat` 这类带路径的 SERVER span）。
- **当前数据形态**：所有 `parentSpanId` 为空的根 span，实测（`debug/span-stats` 的 `rootSpanSamples`）全是 **Core OTel 导出器发往 Collector 的 OTLP 导出 span**（`op=POST, kind=CLIENT, status=UNSET`）——并非业务请求入口；而 `distinctOpNames` 只有 `['L0:..','L1-LLM1:..','L1-LLM2:..','L1:..','L2:..','POST']`，**根本没有** `POST /api/bank/chat` 这类 SERVER span。
- 因此：`hasBusinessRoot=false` → fallback 要求 `POST %` 带路径的 SERVER span → 当前数据**不存在** → 返回 0 条 → `rootSpans` 变空 → 列表 `totalElements=0`。"靠根 span 当 trace 入口"的模型对此数据形态彻底失效。

### 修复（第二十四轮）
重写 `listTraces` / `listTracesPaginated`，改为**按 trace_id 去重枚举**（已有 `spanRepository.findDistinctTraceIdsSince(from)`，默认 7 天窗口），对每个 trace 的全部 spans 聚合出 `TraceListVO`（新增 `collectTraceListVOs` + `buildTraceListVOFromSpans`）：
- **跳过无业务 span 的 trace**（无 L0/L1/L2/llm）→ 排除 OTLP 导出自 trace 噪声与无埋点的纯规则命中请求；
- `timestamp` = 该 trace 最早 span 的 start；`durationMs` = 业务 span 首末时间差（E2E），兜底取根 span durationMs；
- `statusCode` = 任一 span 为 ERROR → ERROR，否则首个非 UNSET 状态，否则 OK；
- `sessionId/intent/userId` 从业务 span 属性（`extractSessionIdFromUrl` / `parseAttributes`）提取，回退 Session 表；
- `agentChain / TTFT / tokenTotal` 复用既有聚合 helper（`buildAgentChainFromSpans` / `computeTTFT` / `aggregateTokenTotal`）。
- 删除脆弱的"根 span 检测 + OTLP/actuator/health 跳过"逻辑（不再需要）。

### 编译
- `./mvnw.cmd -o compile` EXIT=0。需用户 `start-all.ps1` 重启 backend（9090）生效。

### 预期结果（待重启验证）
- Trace 列表将显示**真正调用过 LLM 的 trace**（含 L0–L2 span 者）。seed 36 条中约 10 个（与 L0=10 一致）。
- 走规则命中、未调 LLM 的请求因**未产生任何 span** 仍不出现 → 属"待定项 A"范畴（是否引入"请求接收数"口径），非本次 bug。

## 8.17 大屏展示层三项优化（trace Agents 链 / 意图链 / 会话回放执行智能体，2026-07-10 第二十六轮，R26）

主人三项指令：① trace 表 Agents 列层级链展示修正；② trace 表意图列改为各层真实识别结果；③ 会话回放"执行智能体"按轮显示最终 L2 且与单 trace 的 Agents 链区分。

### 背景与问题
- 原 `agentChain` 显示 `L0 → L1-LLM1 → L1-LLM2 → L2`：业务视角下 2 个 LLM 调用在内部、对外始终还是 L1，不应把 L1-LLM1/L1-LLM2 并列暴露。
- 原 `intent` 字段从 `Session.getIntentFlow()` 整条会话意图链**回退**取值（R25 已发现串味 bug），导致单 trace 显示约 20 个意图（"WEALTH → WEALTH → …"），违背"trace 是单轮对话"语义。
- 会话回放"执行智能体"原直接复用 `intentFlow` 切分，一个会话含多个 L2 时只显示一个；多轮 `WEALTH → WEALTH → … → TRANSFER` 只显示 WEALTH。

### 修复 #1 — trace 表 Agents 链（`buildAgentChainFromSpans` 重写）
- 收集业务 span（L0/L1*/L2/llm）按 startTime 排序；**按 L0 边界切分多段**（reroute：一段 L0→L1→WEALTH 后接新 L0→L1→TRANSFER）。
- 每段输出：`L0`(有则) + `L1`(有 L1/L1-LLM1/L1-LLM2 则合并为单一 L1) + `businessNameOf(L2 span)`(L2 业务名 WEALTH/TRANSFER/BILL)。
- `businessNameOf`：优先 span 的 `intent` 属性推导（含 `GRAPH` 后缀去掉），回退 `agent.name`（WealthInterpret→WEALTH）。
- 效果：`L0 → L1 → WEALTH`；reroute：`L0 → L1 → WEALTH → L0 → L1 → TRANSFER`。

### 修复 #2 — trace 表意图链（`buildIntentChainFromSpans` 新增）
- 同样按 L0 切段；每段取四层真实识别结果：`L0 intent + " Domain"` → `L1-LLM1 intent`(switch-new 等) → `L1-LLM2 intent`(wealth-filter 等) → `L2 intent`(XXX)，用 `→` 拼接。
- L0 intent 缺失时回退：L2 业务名 → 该 session 的 `lastIntentOf(intentFlow)` → "未识别"。
- **根治 R25 的整条 intentFlow 串味 bug**：`buildTraceListVOFromSpans` 不再回退 `session.getIntentFlow()` 整条链，改为纯 span 聚合。

### 修复 #3 — 会话回放"执行智能体"（`SessionService.buildExecutingAgents` 新增）
- 按 `turnNumber` 升序遍历 `session_turns`，每轮取 `t.getIntent()`：业务域（WEALTH/TRANSFER/BILL…）→ 该轮 L2 名；否则（CHAT/UNKNOWN/UNSUPPORTED，未达 L2）→ 显示 `L1`（代表意图识别/路由异常）。
- `isBusinessDomain`：CHAT/UNKNOWN/UNSUPPORTED 视为非业务域。
- 与 `Session.intentFlow` **刻意区分**：intentFlow 用于意图统计（保留在详情页 `agentChain`），本列表仅用于每轮实际执行的智能体；连续相同的 L2 由前端 `dedupAgents`（`→` 分隔 + 折叠）处理，如 `WEALTH → WEALTH → TRANSFER → WEALTH → TRANSFER` 折叠为 `WEALTH → TRANSFER → WEALTH → TRANSFER`。
- `toSessionListVO` 的 `agents` 字段由 `buildExecutingAgents` 提供；`intent`(首意图)/`domainSwitches` 仍保留。

### 数据约束（重要，影响 #2 完整语义）
- 经静态核查 Core：`DomainRouter`/`ContextRouter`/`SubGraphRouter` 的 `AgentSpanContext.set(...)` 中 **L0 / L1-LLM1 / L1-LLM2 的 intent 参数全传 `null`**；且 L1-LLM1 的 routeType、L1-LLM2 的 intentName 在 LLM 调用**之后**才解析，而 `ObsChatModel.call()` 在 `finally` 里立即 `span.end()` → 这些识别结果**当前未写入 span 的 intent 属性**。
- 故 #2 的 `switch-new` / `wealth-filter` 等 L1 层真实意图在当前数据中**大概率取不到**（仅 `WEALTH Domain` 段可见）；`L2` 业务名（WEALTH 等）**当前可由 intent/agent.name 推导**，显示正常。
- 要让 #2 显示完整 L1 层意图，需后续 Core 改动：在 `AgentSpanContext.set` 或 `ObsChatModel` 把 ContextRouter/SubGraphRouter 的识别结果写入 span 的 intent 属性（已列为后续待定项，本轮未实施）。

### 编译与验证
- `./mvnw.cmd -o compile` EXIT=0（2026-07-10 第二轮编译验证通过）。
- 待用户用 `start-all.ps1` 重启 backend(9090)；重启后前台跑 `seed_all.py` 并拉 `/api/v1/traces`（验证 #1 `L0→L1→WEALTH`、#2 单轮真实意图链）、`/api/v1/sessions`（验证 #3 每轮 L2 + 前端 `dedupAgents` 折叠）。

## 8.18 确定性路由补全 L0 span（第二十三轮·续，2026-07-14）

### 背景与根因（本轮回填 8.15 待定项 A — 方案 b 选定）
- 上轮（8.14）实测 seed 36 条中仅 **10 个 trace 产生 L0 span（L0=10）**，L0 < 36（请求数）；后续实测 L0=23 / L1=36 → **L0 < L1**，与"L0 为顶层编排、L1 为其下子 Agent"的拓扑（应 L0 ≥ L1）矛盾，且导致 L2≤L0 不恒成立。
- **根因（代码层，确定性）**：`DomainRouter.route()` 第 75-140 行有两条分支：
  1. **确定性路由（关键词命中，第 77-84 行）**：`routeDeterministic()` 命中关键词（如"转账"→TRANSFER、"账单"→BILL、"理财/基金"→WEALTH）即 `return`，**不调用 LLM、不创建任何 OTel span**。该分支是 `route()` 方法体内、在 `try-finally`（仅覆盖 LLM 分支）**之外**的提前返回。
  2. **LLM 兜底路由（第 88-139 行）**：无关键词或多域命中时才调 `domainChatClient`，由 `AgentSpanContext.setWithHeldSpan("L0",...)` 经 `ObsChatModel` 创建 L0 span。
- 关键词来自 `application.yml` 的 `routing.domains.*.keywords`（TRANSFER/BILL/WEALTH/UNSUPPORTED）。seed 36 条中约 14 条中文输入含关键词 → 走确定性分支 → 无 L0 span，正是 L0 缺口来源。

### 修复（方案 b：确定性分支也显式创建 L0 span）
仅改 `DomainRouter.route()` 确定性分支（约 +15 行），**不触碰 LLM 分支、不触碰 ObsChatModel**：
```java
// 1. 确定性路由: 关键词匹配
DomainResult deterministic = routeDeterministic(userInput);
if (deterministic != null && !excludedDomains.contains(deterministic.domain())) {
    // 可观测补全（方案A）：确定性路由虽不调 LLM，仍显式创建 L0 span，
    // 保证 L0 计数 = 请求数（与 L1 持平 → L2≤L0 恒成立），trace 瀑布层级完整。
    // 直接经 OTel Tracer 创建（不经 LLM 路径的 ObsChatModel），parent 自动取当前
    // server span（与 LLM 路径一致）。绝不 makeCurrent——避免线程 OTel 上下文栈残留
    // → traceId 跨请求泄漏（历史已修复的 98968ms 异常本源）。span 仅覆盖路由决策，
    // 立即 end()，无悬挂风险。
    Span l0Span = GlobalOpenTelemetry.getTracer("obs-chat-model")
            .spanBuilder("L0:DomainRouter")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
    try {
        l0Span.setAttribute("agent.name", "DomainRouter");
        l0Span.setAttribute("agent.layer", "L0");
        l0Span.setAttribute("intent", deterministic.domain());
        l0Span.setAttribute("routing.mode", "deterministic");   // 区分确定性 vs LLM 路由
        l0Span.setAttribute("session_id", sessionId != null ? sessionId.trim() : "");
    } finally {
        l0Span.end();
    }
    updateLastDomain(...); ... return deterministic;
}
```
- **设计要点**：
  - 确定性 L0 span 的 `operationName = "L0:DomainRouter"`（LLM 路径为 `"L0:qwen-plus"`），均被后端 `COUNT(DISTINCT trace_id) WHERE op LIKE 'L0%'` 计数命中 → L0 计数覆盖全部请求。
  - **不 `makeCurrent`**：沿用历史教训（traceId 跨请求泄漏根因 = `Scope` 同线程不配对 close）。确定性路径无 LLM 调用，直接创建+结束 span，parent 自动取当前 server span，与 LLM 路径 OTel 树结构一致（均为 server span 的「业务子 span」），前端按 `layer` 缩进渲染正常。
  - **不依赖 `AgentSpanContext`**：确定性分支无下游 ObsChatModel 消费，故不走 `setWithHeldSpan`/`commitIntent` 托管模式，避免 `try-finally` 外 return 导致 `clear()` 不执行而 span 泄漏。
  - 新增 `routing.mode=deterministic` 属性，便于后端/前端区分"关键词命中（0ms）"与"LLM 路由"两种 L0 来源。

### 编译
- `./mvnw.cmd compile -DskipTests -o` EXIT=0（2026-07-14）。待 `start-all.ps1` 重启 Core(8080) 部署后验证。

### 预期验证（清库 + seed_all.py 36 条后）
- `GET /api/v1/metrics/realtime` 的 `l0Calls ≈ 36`（= 请求数，确定性 14 + LLM 22），`l1Calls = 36`（每请求 1 次 L1-*），`l2Calls ≤ 36` → **L0(36) ≥ L1(36) ≥ L2**，L2≤L0 恒成立。
- 含 `routing.mode=deterministic` 的 L0 span 出现在 trace 瀑布 L0 层（与 LLM 路径 L0 并列），trace 列表全部 36 条均带 L0 段。
- 注意：若某 session 多轮均走确定性（如 np1 三轮机"基金/货币基金/买货币基金"全命中 WEALTH 关键词），该 session 三轮均产生 L0 span，Trace 列表按轮显示，与 8.17 的「单轮真实意图链」一致。

### 同步说明
- 8.15 待定项 A 已从「待定」改为「🟢 已决策（方案 b）已实施」，本 §8.18 为其落地记录。
- 8.14 中"L0=10 < 36 属正确可观测语义"的结论需修正：那是基于"L0 仅统计 LLM 路由"的旧口径；现方案 b 下 L0 覆盖全部请求，L0=36 才是目标态，L0<请求数反而说明有请求未进 DomainRouter（如 `/state`、`/session` 非业务端点，本就不该有 L0）。
