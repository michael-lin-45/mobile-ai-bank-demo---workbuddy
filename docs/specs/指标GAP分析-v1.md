# AI 银行智能体可观测平台 — 指标 GAP 分析文档

> 作者：许清楚（产品经理） | 日期：2025-07-21 | 版本：v1.0

---

## 一、总览表

### 1.1 DashboardPage — Zone A 系统健康

| # | 指标名 | 当前实现 | 数据来源 | 业务预期 | GAP描述 | 优先级 |
|---|--------|---------|---------|---------|---------|--------|
| A1 | activeSessions | ✅ 已实现 | Redis `obs:metrics:active_sessions` (30s TTL) | 实时活跃会话数 | 30s TTL 偏短，Redis 重启后归零无回填；无历史趋势 | P2 |
| A2 | DAU | ✅ 已实现 | Redis `obs:metrics:dau` (24h TTL) | 日活用户数 | 仅 Redis 计数，无 H2 持久化；跨日对比需前端额外处理 | P2 |
| A3 | realTimeOnline | ✅ 已实现 | Redis `obs:metrics:online_users` (30s TTL) | 实时在线人数 | 同 A1，TTL 偏短 | P2 |
| A4 | requestCount | ✅ 已实现 | Redis INCR `obs:metrics:request_count:1m` (120s TTL) | 6h 累计请求数 | 🔴 **前端标注"6h 累计"但 Redis key 为 1m 窗口 + 120s TTL**。当前 requestCount 实际仅反映最近 2 分钟的 INCR 值，与「6h 累计」语义严重不符 | P0 |
| A5 | QPS | ⚠️ 前端计算 | `requestCount / 6 / 3600` = requestCount/21600 | 每秒请求数 | 🔴 **计算公式错误**：requestCount 来自 1m 窗口 key，正确 QPS 应为 `requestCount / 60`。当前除以 21600 导致 QPS 被低估约 360 倍 | P0 |
| A6 | agentCallCount | ✅ 已实现 | Redis INCR `obs:metrics:agent_call:L0/L1/L2` (120s TTL) | L0+L1+L2 Agent 总调用次数 | 功能正确。TTL 120s 导致窗口外数据丢失 | P1 |
| A7 | l0Calls / l1Calls / l2Calls | ✅ 已实现 | 同 A6 | 分层 Agent 调用次数 | 功能正确，与 A6 共享数据源 | P1 |

### 1.2 DashboardPage — Zone B AI性能

| # | 指标名 | 当前实现 | 数据来源 | 业务预期 | GAP描述 | 优先级 |
|---|--------|---------|---------|---------|---------|--------|
| B1 | Token 调用量 (input+output) | ✅ 已实现 | Redis INCRBY delta `obs:metrics:token_input:1m` / `token_output:1m` | 近 1 分钟 Token 消耗总量 | ✅ 功能正确，OTel Counter delta 转换逻辑完备 | — |
| B2 | TTFT P50/P95/P99 | ✅ 已实现 | Redis ZSET `obs:metrics:ttft:1m` → 百分位计算 | 首 Token 时延分位数 | ✅ 功能正确，ZSET percentile 实现合理 | — |
| B3 | P95 系统时延 | ✅ 已实现 | Redis ZSET `obs:metrics:latency:1m` | 端到端延迟 P95 | ✅ 功能正确 | — |
| B4 | 错误率 | ⚠️ 前端计算 | `errorCount / requestCount` | 错误请求占比 | 🔴 **误差传导**：requestCount 语义不准确（见 A4），错误率分母偏差导致错误率失真。此外 `errorRate` 在 RealtimeMetricsVO 中为 Double 字段但未在后端计算，前端自行计算 | P0 |

### 1.3 DashboardPage — Zone C 语义质量

| # | 指标名 | 当前实现 | 数据来源 | 业务预期 | GAP描述 | 优先级 |
|---|--------|---------|---------|---------|---------|--------|
| C1 | intentAccuracy | ⚠️ 单值 | Redis `obs:metrics:accuracy:intent` (外部SET, 120s TTL) | **L0/L1 分层准确率** | 🔴 **核心GAP**：业务要求分别展示「L0预测 vs L1实际」和「L1预测 vs L2实际」两个准确率。当前仅一个全局单值，无法区分层级。Redis key 未标注层级，MetricsAgg 表 `agent.intent.accuracy` 的 tags 中也无 layer 字段 | P0 |
| C2 | rewriteAccuracy | ⚠️ 单值 | Redis `obs:metrics:accuracy:rewrite` (外部SET, 120s TTL) | 改写准确率 | 功能正确但无历史趋势 | P1 |
| C3 | rerouteRate | ⚠️ 部分实现 | Redis `obs:metrics:reroute_rate` (外部SET) + `reroute_count:1m` (INCR) | Reroute 率，关联意图识别错误，每请求最多 3 次 | 🔴 **语义偏差**：当前 rerouteRate 只是一个外部写入的单值，未体现「每次 reroute 由意图识别错误触发」的因果链路。缺少按意图分组统计、缺少 reroute 次数分布（1次/2次/3次） | P0 |
| C4 | completionRate | ⚠️ 占位 | Redis `obs:metrics:business_completion` (外部SET, 120s TTL) | L2 业务正确执行返回 complete → 完成率 | 🟡 **已知遗留问题**：L2 层未实现，前端显示"暂无数据"。Redis key 存在但无有效数据源。业务预期 L2 complete 状态驱动完成率 | P1 |

### 1.4 DashboardPage — Zone D 业务效果

| # | 指标名 | 当前实现 | 数据来源 | 业务预期 | GAP描述 | 优先级 |
|---|--------|---------|---------|---------|---------|--------|
| D1 | conversionRate | ❌ 未对接 | Redis key `obs:metrics:conversion` 存在 | 来自其他系统的业务转化率 | 🔴 **数据源缺失**：Redis key 存在但无上游系统写入。需确认对接方和接口协议 | P1 |
| D2 | violationRate | ❌ 硬编码 null | 无 | 来自安全围栏系统的违规率 | 🔴 **数据源缺失**：RealtimeMetricsVO.violationRate 返回 null，前端显示"暂无"。需对接安全围栏系统 | P1 |

### 1.5 TraceExplorerPage — 链路追踪

| # | 指标名 | 当前实现 | 数据来源 | 业务预期 | GAP描述 | 优先级 |
|---|--------|---------|---------|---------|---------|--------|
| T1 | traceId / sessionId / userId / intent / agentChain | ✅ 已实现 | SpanEntity + Session 表关联 | 链路标识 | ✅ 功能正确，跨 Span 属性提取兜底逻辑完备 | — |
| T2 | durationMs / ttftMs / tokenTotal | ✅ 已实现 | Span 属性解析 | 链路性能指标 | ✅ 功能正确 | — |
| T3 | spanTree / ioInput / ioOutput | ✅ 已实现 | Span 递归构建 + attributes 解析 | 链路详情 | ✅ 功能正确 | — |
| T4 | **LLM 置信度 (confidence)** | ❌ 未展示 | Span attributes 可存储 `llm.confidence` | 展示 LLM 返回报文中的置信度字段 | 🔴 **核心GAP**：TraceListVO 和 TraceDetailVO 均无 confidence 字段。Span attributes JSON 可存储但未提取。前端 TraceTable 列表和 TraceDetailModal 详情均无展示 | P0 |
| T5 | L0/L1/L2 分层意图 | ⚠️ 混存 | Session.intentFlow (单字符串) | 区分 L0 预测意图、L1 预测意图、L2 实际意图 | 🔴 **结构GAP**：Session.intentFlow 以单一字符串存储全链路（如 "TRANSFER→TRANSFER"），无法精确提取每层的意图值。Span attributes 中 `intent_predicted`/`intent_actual` 未标注层级 | P0 |

### 1.6 AIInsightsPage — 准确率分析 TAB

| # | 指标名 | 当前实现 | 数据来源 | 业务预期 | GAP描述 | 优先级 |
|---|--------|---------|---------|---------|---------|--------|
| I1 | intentAccuracyTrend | ⚠️ 无分层 | MetricsAgg `agent.intent.accuracy` + tags → 按 intent+日期聚合 | L0准确率趋势 + L1准确率趋势，按意图对象区分 | 🔴 **核心GAP**：当前按 intent tag 分组绘5条趋势线，但未区分 L0/L1 层级。业务要求两条线（L0准确率 + L1准确率），且需要展示 L0 预测意图 vs L1 实际意图的对应关系 | P0 |
| I2 | confusionMatrix | ⚠️ 无分层 | Span attributes `intent_predicted` vs `intent_actual` → 6×6 矩阵 | L0 混淆矩阵 + L1 混淆矩阵，分别展示 | 🔴 **核心GAP**：当前仅构建一个全局混淆矩阵，不区分是 L0→L1 还是 L1→L2 的预测-实际配对。业务需要两个独立矩阵 | P0 |
| I3 | rewriteAccuracy 表 | ✅ 已实现 | MetricsAgg `agent.rewrite.accuracy` | 改写准确率明细 | ✅ 功能正确 | — |
| I4 | 改写失败根因 TOP3 | ✅ 已实现 | rule_check tag 分组 | 根因卡片 | ✅ 功能正确 | — |
| I5 | **L2 意图识别** | ❌ 未实现 | 无 | L2 意图识别正确性（暂保留占位） | 🟡 按业务原则，L2 暂不给值，保留占位 | P2 |

---

## 二、按页面分组的详细 GAP 分析

### 2.1 DashboardPage — Zone A 系统健康

#### GAP-A1: QPS 计算逻辑错误（P0）

**现状**：
```javascript
// DashboardPage.jsx Line 120
value={metrics.requestCount != null ? (metrics.requestCount / 6 / 3600).toFixed(1) : '-'}
unit="req/s"
sub={`6h 累计 ${metrics.requestCount != null ? metrics.requestCount.toLocaleString() : '-'} 次`}
```

**问题链**：
1. `requestCount` 来自 Redis key `obs:metrics:request_count:1m`，是 1 分钟窗口内的 INCR 累计值，TTL 120s
2. 前端计算 QPS = `requestCount / 6 / 3600` = `requestCount / 21600`
3. 正确计算应为 `requestCount / 60`（1分钟内的请求数 / 60秒 = 每秒请求数）
4. 同时前端标注"6h 累计"，但 Redis key 仅为 1m 窗口

**建议修复**：
- 方案一（快速修复）：前端改为 `requestCount / 60`，标注改为"近1分钟"
- 方案二（正确修复）：后端新增 `obs:metrics:request_count:6h` key（INCR + 6h TTL），或使用滑动窗口聚合
- 同时前端 sub 文本应与实际窗口一致

---

#### GAP-A2: requestCount 窗口语义不一致（P0）

**现状**：Redis key 为 `obs:metrics:request_count:1m`（120s TTL），前端显示"6h 累计"

**建议修复**：统一窗口语义，建议后端支持多窗口聚合（1m / 5m / 15m / 1h / 6h），前端切换时间窗口时调用对应数据

---

### 2.2 DashboardPage — Zone C 语义质量

#### GAP-C1: intentAccuracy 缺少 L0/L1 分层（P0）

**现状**：
- Redis: `obs:metrics:accuracy:intent` — 单值，无层级标注
- MetricsAgg: `agent.intent.accuracy` — tags 含 `intent_predicted` 但无 `layer` 字段
- RealtimeMetricsVO: `intentAccuracy` (Double) — 单值

**业务预期**：
- L0 预测意图 vs L1 实际意图 → **L0 识别准确率**
- L1 预测意图 vs L2 实际意图 → **L1 识别准确率**
- 两者需分别存储、分别展示

**建议修复**：
1. 新增 Redis keys:
   - `obs:metrics:accuracy:intent:L0` — L0→L1 准确率
   - `obs:metrics:accuracy:intent:L1` — L1→L2 准确率
2. RealtimeMetricsVO 新增字段：`l0IntentAccuracy`、`l1IntentAccuracy`
3. MetricsAgg tags 增加 `layer` 字段：`{"intent":"TRANSFER","layer":"L0","value":0.942}`
4. DashboardPage Zone C 卡片改为双值展示：
   ```
   意图准确率  L0: 94.2% · L1: 91.5%
   ```

#### GAP-C2: rerouteRate 缺少因果链路追踪（P0）

**现状**：rerouteRate 仅为外部写入的单值，无因果分析能力

**业务预期**：
- 意图识别错误 → L1/L2 走 reroute → 转发 L0 重新识别
- 每请求最多 3 次
- reroute 率应与意图识别正确率关联分析

**建议修复**：
1. Span attributes 中增加 `reroute_count`（0-3）和 `reroute_reason` 字段
2. Redis 新增 `obs:metrics:reroute_distribution` (HASH)，统计 0/1/2/3 次 reroute 的请求数
3. AIInsightsPage 新增「Reroute 分析」卡片：按意图 × reroute 次数交叉统计

---

### 2.3 TraceExplorerPage

#### GAP-T1: LLM 置信度字段缺失（P0）

**现状**：
- TraceListVO：无 confidence 字段
- TraceDetailVO：无 confidence 字段
- Span attributes 可存储任意 JSON，但未规范 `llm.confidence` key
- TraceTable 前端列表无置信度列
- TraceDetailModal 详情无置信度展示

**业务预期**：链路追踪中直接展示 LLM 返回报文里的置信度字段，无需额外计算

**建议修复**：
1. OTel Span 上报时规范 attributes：
   ```json
   {
     "llm.confidence": "0.942",
     "llm.confidence_level": "HIGH"
   }
   ```
2. TraceListVO 新增 `confidence` (Double) 字段
3. TraceDetailVO 新增 `confidence` (Double) 字段
4. TraceTable 前端在「意图」列后新增「置信度」列（百分比 + 颜色编码）
5. TraceDetailModal 在请求概要卡片中展示置信度

#### GAP-T2: L0/L1/L2 分层意图存储不足（P0）

**现状**：
- `Session.intentFlow` = 单字符串（如 "TRANSFER→TRANSFER"）— 各层意图值混在一起
- Span attributes `intent_predicted` / `intent_actual` 未标注层级别

**业务预期**：精确区分每层的预测意图和实际意图

**建议修复**：
1. Session 表新增字段：
   - `l0_predicted_intent` VARCHAR(32)
   - `l1_predicted_intent` VARCHAR(32)
   - `l2_actual_intent` VARCHAR(32)
2. Span attributes 规范化：
   ```json
   {
     "intent.L0.predicted": "TRANSFER",
     "intent.L0.actual": "TRANSFER",
     "intent.L1.predicted": "TRANSFER",
     "intent.L1.actual": "TRANSFER"
   }
   ```

---

### 2.4 AIInsightsPage — 准确率分析

#### GAP-I1: 意图准确率趋势缺少 L0/L1 分层（P0）

**现状**：`getIntentAccuracyTrend()` → 按 intent tag 分组 → 5 条折线（TRANSFER/BILL_QUERY/WEALTH_CONSULT/WEALTH_INTERPRET/CHAT），每条一个准确率

**业务预期**：每个意图展示两条线（L0 准确率 + L1 准确率），按意图对象区分展示

**建议修复**：
1. `getIntentAccuracyTrend()` 返回结构改造：
   ```json
   {
     "series": [
       { "name": "TRANSFER L0准确率", "data": [...] },
       { "name": "TRANSFER L1准确率", "data": [...] }
     ]
   }
   ```
2. 前端 AccuracyTab 折线图改为分组显示（同一意图 L0/L1 用实线/虚线区分）

#### GAP-I2: 混淆矩阵缺少分层（P0）

**现状**：`getConfusionMatrix()` → 从 Span attributes 读取 `intent_predicted` vs `intent_actual` → 单个 6×6 矩阵

**业务预期**：两个独立矩阵 — L0 混淆矩阵 + L1 混淆矩阵

**建议修复**：
1. Span attributes 增加层级标注（见 T2 建议）
2. `getConfusionMatrix()` 增加 `layer` 参数：
   - `GET /ai/confusion-matrix?layer=L0` → L0 预测 vs L1 实际
   - `GET /ai/confusion-matrix?layer=L1` → L1 预测 vs L2 实际
3. 前端 AccuracyTab 增加 L0/L1 切换开关

---

## 三、数据模型 GAP

### 3.1 MetricsAgg 表

| 字段 | 当前设计 | GAP | 建议 |
|------|---------|-----|------|
| metricName | `agent.intent.accuracy` | 无法区分 L0 vs L1 | 使用分层 metricName: `agent.intent.accuracy.L0` / `agent.intent.accuracy.L1` |
| tags | JSON: `{"intent":"TRANSFER"}` | 无 layer 字段 | 增加 `"layer":"L0"` 或 `"layer":"L1"` |
| value | Double | 仅存数值，无分子分母 | 对准确率类指标建议增加 `sample_count` 字段 |

### 3.2 SpanEntity 表

| 字段 | 当前设计 | GAP | 建议 |
|------|---------|-----|------|
| attributes | JSON string (4096) | ✅ 灵活但无 Schema 约束 | 规范化 key 命名：`intent.L0.predicted`, `intent.L0.actual`, `intent.L1.predicted`, `intent.L1.actual`, `llm.confidence`, `reroute.count`, `reroute.reason` |

### 3.3 Session 表

| 字段 | 当前设计 | GAP | 建议 |
|------|---------|-----|------|
| intentFlow | VARCHAR(1024) 单字符串 | 各层意图混存，无法查询 | 新增 `l0Intent`、`l1Intent`、`l2Intent` 三个独立字段 |

### 3.4 RealtimeMetricsVO

| 字段 | 当前设计 | GAP | 建议 |
|------|---------|-----|------|
| intentAccuracy | Double 单值 | 无 L0/L1 分层 | 新增 `l0IntentAccuracy`、`l1IntentAccuracy` |
| qps | Double | 后端未赋值 | 后端在 MetricsQueryService 中计算并赋值 |
| errorRate | Double | 后端未赋值 | 后端计算：`errorCount / requestCount` |
| violationRate | Double (null) | 未对接 | 保留字段，待安全系统对接后填值 |
| conversionRate | Double | 未对接 | 保留字段，待业务系统对接后填值 |

### 3.5 TraceListVO / TraceDetailVO

| VO | 缺失字段 | 建议 |
|----|---------|------|
| TraceListVO | confidence (Double) | 新增，从 Span attributes `llm.confidence` 提取 |
| TraceDetailVO | confidence (Double) | 新增，同上 |
| TraceDetailVO | l0Intent / l1Intent (String) | 新增，从 Span attributes `intent.L0.predicted` / `intent.L1.predicted` 提取 |

---

## 四、待确认问题清单

| # | 问题 | 涉及方 | 优先级 |
|---|------|--------|--------|
| Q1 | requestCount 的实际业务窗口是 1 分钟还是 6 小时？当前标注"6h 累计"但数据源为 1m 窗口 | 产品 + 后端 | 🔴 P0 |
| Q2 | L0/L1 分层准确率的数据写入方是谁？是 Agent 引擎直接写 Redis 还是通过 OTel Metric 上报？需确认对接方案 | 后端 + Agent 引擎 | 🔴 P0 |
| Q3 | LLM 置信度字段在 LLM 返回报文中的确切字段名是什么（`confidence`? `score`? `logprobs`?）？不同模型是否一致？ | 算法 + LLM 平台 | 🔴 P0 |
| Q4 | 业务转化率的数据来源系统是哪个？对接接口协议和字段定义是否已确定？ | 产品 + 业务系统 | 🟡 P1 |
| Q5 | 安全围栏系统的违规率数据对接方式和字段定义？ | 产品 + 安全团队 | 🟡 P1 |
| Q6 | L2 层的实现排期？完成后 completionRate 的数据格式（complete/partial/failed 三态还是布尔）？ | 产品 + 后端 | 🟡 P1 |
| Q7 | Reroute 是否需要在前端展示每次 reroute 的详细链路（类似 Trace 详情）还是仅展示统计数字？ | 产品 | 🟢 P2 |
| Q8 | Redis TTL 策略：当前多数 key 为 120s，是否需要更长的历史窗口（如 15m/1h/6h）以支持趋势图？ | 产品 + 后端 | 🟢 P2 |

---

## 五、优先修复建议

### P0 — 必须修复（阻塞核心业务）

| # | GAP | 修复项 | 预估工作量 |
|---|-----|--------|-----------|
| P0-1 | QPS 计算错误 | 前端 `requestCount/3600` → `requestCount/60`；标注改为"近1分钟" | 0.5d |
| P0-2 | requestCount 窗口语义 | 后端支持多窗口聚合（1m/5m/15m/1h/6h），前端切换 | 2d |
| P0-3 | intentAccuracy L0/L1 分层 | 新增 Redis keys + VO 字段 + MetricsAgg layer tag + 前端双值展示 | 3d |
| P0-4 | LLM 置信度展示 | TraceListVO/TraceDetailVO 新增 confidence + Span attributes 规范化 + 前端 TraceTable 列 | 2d |
| P0-5 | L0/L1 分层意图存储 | Session 表新增 l0Intent/l1Intent/l2Intent + Span attributes 规范化 | 2d |
| P0-6 | AIInsights L0/L1 趋势 + 混淆矩阵 | getIntentAccuracyTrend / getConfusionMatrix 增加 layer 参数 + 前端双矩阵 | 3d |
| P0-7 | errorRate 后端计算 | MetricsQueryService 中计算 errorRate + VO 赋值 | 0.5d |

### P1 — 应该修复（影响功能完整性）

| # | GAP | 修复项 | 预估工作量 |
|---|-----|--------|-----------|
| P1-1 | conversionRate 数据对接 | 确认上游系统 + 对接接口 + 写入 Redis `obs:metrics:conversion` | 待定 |
| P1-2 | violationRate 数据对接 | 确认安全围栏系统 + 对接接口 | 待定 |
| P1-3 | Reroute 因果链路 | Span attributes `reroute_count`/`reroute_reason` + Redis 分布统计 | 2d |
| P1-4 | Agent 调用 Redis TTL | 从 120s 延长至更合理窗口 + 增加历史存储 | 1d |
| P1-5 | completionRate L2 对接 | 待 L2 实现后写入 `obs:metrics:business_completion` | 依赖 L2 |

### P2 — 可以延后（改善体验）

| # | GAP | 修复项 | 预估工作量 |
|---|-----|--------|-----------|
| P2-1 | activeSessions / DAU 历史持久化 | Redis → H2 定期刷入 | 2d |
| P2-2 | 多时间窗口 Dashboard 切换 | 前端时间选择器 + 后端多窗口聚合 | 3d |
| P2-3 | AIInsights Reroute 分析 TAB | 新增独立 TAB，展示 reroute 统计 | 2d |
| P2-4 | L2 意图识别占位 | 前端 AIInsights 中增加 L2 占位卡片 | 0.5d |

---

## 六、总结

本次 GAP 分析覆盖 DashboardPage（4区11卡）、TraceExplorerPage（列表+详情）和 AIInsightsPage（准确率TAB）三个核心页面的所有指标。共识别出：

- **P0 级 GAP：7 项** — 主要集中在 QPS 计算错误、L0/L1 分层准确率缺失、LLM 置信度未展示、分层意图存储不足
- **P1 级 GAP：5 项** — 主要集中在外部系统数据对接（转化率、违规率、Reroute 因果链路）
- **P2 级 GAP：4 项** — 主要为体验优化和历史数据持久化

最关键的架构决策是 **L0/L1/L2 分层意图的存储和展示方案**，这影响 DashboardPage Zone C、TraceExplorerPage 和 AIInsightsPage 三个页面的准确率相关指标。建议优先与 Agent 引擎团队确认数据写入方案后，统一推进。
