# 移动 AI 银行 Demo — AI 上层应用可观测目标架构方案

> 范围：聚焦 Agent + LLM 的**业务指标 / Trace / 会话**三类数据，并向上提炼对**运营、运维**有帮助的洞察。
> 配套图：`docs/OBSERVABILITY-TARGET-ARCH.png`（架构图见对话内联 SVG）。

---

## 1. 现状回顾（一句话）

- Core(8080) 通过 OTel SDK 埋点 → OTel Collector(4318) → 自研 9090 后端（**H2 单文件库**）。
- 会话走**独立管道**：Core `SessionBridge.reportSession(...)` 异步 HTTP POST 到 9090 的 `/api/v1/sessions`（按 sessionId upsert）。
- 业务 span 已分层命名：`L0:`(DomainRouter) / `L1-LLM1:`(ContextRouter) / `L1-LLM2:`(SubGraphRouter) / `L1:`(ChatService) / `L2:`(AbstractGraphConfig)，L1 真实意图已回写 span `intent` 属性（held-span 机制）。
- 指标发射已有，但 `agent_performance` / `token_cost` 等表**定义了却无 INSERT**；真实分位指标（TTFT）依赖 `Timer.publishPercentileHistogram(true)` 导出真正的 OTLP Histogram。
- **当前阻塞**：SenseNova LLM 端点对 Core 全部 404，路由全程兜底，业务指标/准确率洞察暂时无法在真实流量下验证。

### 1.1 重新审视结论（对齐 spec + 指标GAP分析-v2）

> 基于 `docs/specs/AI可观测-系统架构设计-WorkBuddy-V1.md` 与 `指标GAP分析-v2.md` 复核，对原方案做三处修正：

1. **存储迁移不是 P0**：原方案把"脱离 H2、落 Tempo+Prom+Loko"列 P0 偏激进。spec 明确 **H2 在 P0 保留、P2 才迁 PostgreSQL**；Tempo/Prom/Loki **仅用于信号外溢**，业务语义（session/funnel/satisfaction/reroute）必须留在关系库。真正的 P0 阻塞是 **Core 发射层数据断链**（DAU/置信度/分层意图/reroute 数据源缺失），而非存储。
2. **AI 洞察层不是独立引擎**：原称"AI Insights 引擎"偏重造。spec 已定义 **AIInsightsService 族**（Accuracy/AgentPerformance/ConversionFunnel/TokenCost/ToolStats/Satisfaction 六个 Service），前端对应 **6 个 TAB**。AI 洞察层 = 这族 Service + 6 TAB 看板；Grafana 仅作统一看板载体，不与 spec 的 6 TAB 竞争。
3. **Langfuse 属 spec 待定项 C**：其定位/接入方式/部署形态 spec §8.15 明确为待定，不抢占 P0/P1 资源，决策后再落地。

---

## 2. 设计目标（聚焦 AI 上层应用）

| 维度 | 目标数据 | 当前状态 |
|---|---|---|
| 业务指标 | 每次对话 token / 成本、TTFT、TPOT、意图路由准确率、槽位完成率、业务转化率 | 部分缺失（表空 / LLM 挂） |
| Trace | 全链路 LLM 调用树（L0/L1/L2 + prompt/response/错误） | 已有，存 H2，无 retention |
| 会话 | 多轮上下文回放、意图链、Agent 执行链 | 已有（sessions 管道），存 H2 |
| 洞察 | 性能瓶颈 / 准确率提升点 / 业务转化瓶颈 | 空白 |

---

## 3. 目标架构方案

### 3.1 数据接入层（Core）
- 复用已有 OTel SDK 埋点（span / metric / log）。
- 指标修正：**LLM token 与时延必须用 `publishPercentileHistogram(true)`** 导出 OTLP Histogram（否则被导出为 Summary，后端解析不到 → 分位恒为 0）。
- 业务属性已注入 span `intent`：L1 真实 `routeType`（SWITCH/FOLLOW/RESUME）已回写 L1-LLM1 span（held-span 机制，R26+R27 已落地）。补全：在 L2 span 注入 `intentName`、在 metrics 注入 `model`/`agent`/`intent` tag，便于按维度聚合。
- 会话管道保留：`SessionBridge` 异步上报，不依赖 trace 导出成败。

### 3.2 采集管道层（OTel Collector）
- `batch` processor：批量发送，降吞吐开销。
- `tail_sampling`：按「错误 / 慢请求（>1s）/ LLM 失败」采样，避免无脑全收撑爆存储（替代现在 H2 全量写入）。
- `attributes` / `resource`：富集 `env` / `service.version` / `deploy` 标签。
- 可选 `Grafana Alloy` 替代原生 Collector，一套进程统一采集 OTLP + Prometheus + Loki。

### 3.3 存储层（按信号分治，H2 在 P0 保留、P2 才迁 PostgreSQL）

> **对齐 spec 与 GAP v2 的关键修正**：之前把"脱离 H2、落标准栈"列 P0 偏激进。spec 明确 **H2 在 P0 保留、P2 才迁 PostgreSQL**；Tempo / Prometheus / Loki **仅用于信号外溢**（大容量、长周期 trace/metric/log 存储），**业务语义（session / funnel / satisfaction / reroute）必须留在关系库**。真正的 P0 阻塞不是存储，而是 **Core 发射层数据断链**（见 §6）。

- **H2（P0 保留，P2 迁 PostgreSQL）**：当前 9090 后端温层 + 业务语义库。P2 才改 PostgreSQL（去独占锁、加 retention、查询更快）。
- **Tempo / Jaeger（P2 引入，信号外溢）**：Trace 大容量长期存储（二选一；Jaeger 自带 UI 开箱即看，Tempo 与 Loki 同源更省）。业务语义不迁此处。
- **Prometheus / VictoriaMetrics（P2 引入，信号外溢）**：指标（TTFT/TPOT/错误率/调用量）长周期 Histogram 分位。
- **Loki + Vector（P2 引入，信号外溢）**：日志集中检索（core.log / backend.log 统一采集 enrich）。
- **Langfuse（spec 待定项 C，不列入 P0/P1 计划）**：LLM 专项——token 数、成本、首 Token 时延、Prompt 版本、回答质量评分、数据集回归评估。其定位 / 接入方式 / 部署形态 **spec §8.15 明确为待定项 C**，待决策后再落地，不抢占 P0/P1 资源。
- **自研 Sessions 服务(9090)（P0 保留）**：会话回放 / 业务语义层的**权威源**。标准栈没有「多轮上下文 + 意图链 + Agent 执行链 + 满意度」的会话级聚合能力，必须保留。

### 3.4 看板 / 告警层
- **Grafana**：统一看板，把 Tempo + Prometheus + Loki + 自研 9090(H2/PG) 拼一张图，从「错误率 spike」一键下钻到「慢 trace」再跳「那条 trace 的 log」。Langfuse 是否入图待 spec 待定项 C 决策。
- **Grafana Alerting**：配规则——LLM 错误率 > 0、TTFT P95 超阈、`/actuator/health` 非 UP、转账图槽位完成率骤降 → 推钉钉。**解决「SenseNova 挂一整天靠人工翻日志才发现」的痛点**。

### 3.5 AI 洞察层（对齐 spec 的 AIInsightsService 族，非独立引擎）

> **对齐 spec 的关键修正**：之前称为"AI Insights 引擎"偏重造。spec 已定义清晰的 **AIInsightsService 族** —— `AccuracyService / AgentPerformanceService / ConversionFunnelService / TokenCostService / ToolStatsService / SatisfactionService`，由查询服务层调用，前端对应 **6 个 TAB**（准确率 / 性能 / 漏斗 / Token / 工具 / 满意度）。AI 洞察层就是这族 Service + 6 TAB 看板，Grafana 仅作统一看板载体（把 Tempo+Prom+Loki+H2 拼一张图），**不与 spec 的 6 TAB 竞争**。

- **数据源**：以 9090 后端 H2/PG 的业务语义表（session / span / satisfaction / funnel）为主，标准栈（Tempo/Prom/Loki）为信号外溢补充。
- **三类提炼项**（性能瓶颈 / 准确率提升点 / 业务转化瓶颈）以「智能洞察建议看板」形式呈现，见 §5 与 UI《可观测DEMO-v17-WorkBuddy.html》。
- **数据健康可信度**：UI 用「待接入 / 已接待上报 / 已接通」三态角标诚实反映每个数据源，避免伪造（spec 占位原则）。

### 3.6 安全
- TLS：OTLP over gRPC + TLS、前端反代 HTTPS、PostgreSQL 连接加密、Collector 加认证（auth extension / mTLS）。原型阶段可暂缓，上生产前置。

---

## 4. 系统架构图

见对话内联 SVG「AI Agent 可观测最终目标架构」：
- 第 1 层：可观测数据底座（Core → Collector → 分信号存储 → Grafana/Alerting）。
- 第 2 层：AI 洞察层（AIInsightsService 族从各存储提炼，输出三类洞察，落到前端 6 TAB）。

---

## 5. AI 洞察提炼项（运营 / 运维）

> 三类提炼项（性能瓶颈 / 准确率提升点 / 业务转化瓶颈）是「智能洞察建议看板」的顶层卡片；其下钻落到 spec 定义的 6 个 TAB：性能 → 性能 TAB、准确率 → 准确率 TAB、业务转化 → 漏斗 TAB + 满意度 TAB，Token / 工具 TAB 作为辅助维度。详见《可观测DEMO-v17-WorkBuddy.html》。

### 5.1 性能瓶颈（运维视角）
- **时延分位**：TTFT P95/P99、TPOT 趋势与异常波动；按 `model` / `intent` / `agent` 分组对比。
- **慢 Trace TopN**：按 L2 Agent 分组，列出最慢的 N 条业务链路。
- **慢函数**：接入 Pyroscope 后，CPU/内存火焰图随时间定位「转账图哪个方法吃资源」。
- **LLM 失败 / 重试率**：按 model/intent 统计 404/超时/限流占比（当前 SenseNova 全 404 即典型）。
- **管道健康**：Collector 丢弃率、尾采样命中率、PostgreSQL 查询延迟。

### 5.2 准确率提升点（算法 / 产品视角）
- **意图误路由率**：L0 判 `CHAT` 但 L2 实际执行了 `TRANSFER`（对比 trace 与 session 真实业务动作）→ 提示领域分类器弱。
- **FOLLOW/SWITCH/RESUME 漏判**：L1 兜底 `SWITCH` 占比高 → 上下文路由（多轮保持）能力弱，应优先优化 ContextRouter 提示词。
- **低分回答**：Langfuse 评分低的会话聚类，按 `intent` / `agent` 找共性问题。
- **槽位填充错误率**：转账 / 理财槽位校验失败次数（金额格式、账号合法性）。
- **模型对比**：同一意图下 qwen-plus vs 其他模型在准确率 / 成本 / 时延的权衡，支撑模型选型。

### 5.3 业务转化瓶颈（运营视角）
- **槽位完成率 / 放弃率**：进入转账图后走到「确认」的比例；在哪一步放弃最多。
- **会话→业务动作转化率**：进入转账意图最终完成转账的笔数 / 金额。
- **意图分布与流失**：哪些意图进来多但完成少（如「理财」咨询多但购买转化低）。
- **多轮中断点**：第几轮最常放弃（定位话术 / 槽位追问设计问题）。
- **高峰与并发**：时段分布、并发量与成功率/时延的关系，指导扩容。

---

## 6. 整改路径（分阶段，最小改动起步）

| 阶段 | 目标 | 关键动作 | 业务代码改动 | 周期 |
|---|---|---|---|---|
| **P0** | **打通 Core 发射层，让 6 TAB 有真实数据源**（对齐 GAP v2 P0-3~P0-6） | ① 补全 session 上报字段（DAU / 在线数据源关联键，根治"断链"）② L0/L1 span 注入真实意图 + 业务动作回写（L0/L1 分层准确率）③ ObsChatModel 发射 `confidence` 属性（LLM 置信度）④ 分层意图链 intentFlow 正确聚合 ⑤ reroute 标记回写 span ⑥ UI 数据健康可信度三态角标 | **大（Core 埋点为主）** | 3–5 天 |
| **P1** | 自研 9090 后端 + AIInsightsService 6 TAB 真实聚合 + Grafana 告警 | ① 落地 spec 的 6 个 Service + 6 TAB 真实聚合（H2 仍 P0 保留）② 部署 Grafana 统一看板 + Alerting（LLM 错误率>0 / TTFT P95 超阈 / 转账槽位完成率骤降 → 推钉钉，**解决"挂一整天靠人工翻日志"痛点**） | 中（后端聚合 + 前端 6 TAB 接通） | 约 1 周 |
| **P2** | 存储迁移 + 标准栈信号外溢 | ① H2 → PostgreSQL（去独占锁、retention）② 引入 Tempo / Prometheus / Loki（仅信号外溢，业务语义留 PG）③ Collector 加 tail_sampling / 标准 exporter | 小（Collector 配置 + 库迁移） | 1–2 周 |
| **P3** | 深化 + LLM 专项 + 安全 | Langfuse（spec 待定项 C，先决策后落地）/ Pyroscope（第四信号）/ Sentry（前端错误）/ Beyla（无侵入）/ TLS / 模型对比评估 | 按需 | 持续 |

**起步一跳**：P0 不靠换存储，而是先补 **Core 发射层埋点**——这是 6 TAB 真实数据的源头，比"迁 Tempo/Prom/Loki"更前置、更低成本、更高杠杆。存储迁移放到 P2（spec 也要求 H2 P0 保留）。

**依赖提醒**：P0/P1 的业务指标 / 准确率洞察都依赖 LLM 真实跑通（当前 SenseNova 404 是总阻塞），需先恢复可用模型（如 DashScope qwen 系列，需提供 base-url + key）。同时 P2/P3 的标准栈（Tempo/Prom/Loki）即便 LLM 挂掉也能先接上 trace/log 信号，可并行起步。

---

## 7. 风险与注意

- **不要一次性全上**：可观测平台最大隐性成本是运维，按 P0→P3 渐进。
- **自研 9090 sessions 保留**：会话级业务语义是标准栈（Tempo/Prom/Loki/Langfuse）都不直接具备的能力，避免重造。
- **指标导出陷阱**：`publishPercentileHistogram(true)` 必须开，否则分位指标全 0（已踩坑）。
- **LLM 阻塞**：指标 / 准确率洞察在 LLM 恢复前只能靠模板兜底数据验证，真实值待 P1 解阻塞后回填。
