# SxDevOps × 自有方案（V4 / V6 / V20）对比分析

> 对比对象：
> - **SxDevOps**：开源 AIOps 智能运维 Agent 平台（GitHub: aiyiyi121/sxdevops，Apache 2.0，178 commits，2026-07-01 活跃）
>   Django + Vue 3 + Element Plus + ECharts + Channels WebSocket / SQLite&MySQL + Redis
>   在线阅读：https://www.sxdevops.top
> - **我们的方案**：V4（Demo 基线）/ V6（银行生产终稿）/ V20 HTML 大屏
>   Java 17 + Spring Boot 3 + Spring AI Alibaba / Vite + React + ECharts + Ant Design
>
> 对比维度：方案设计（定位差异）→ 组件选型 → 系统架构 → 可观测性设计 → AI Agent 设计 → UI 设计 → 综合判断

---

## 一、方案定位：本质差异

**这是两份文档最根本的不同——不是"谁好谁坏"，而是"解决完全不同的问题"。**

| 维度 | SxDevOps | 我们的 V4/V6 |
|---|---|---|
| **定位** | 通用 AIOps 平台——把可观测性、事件、任务、K8s 管理、工单、SQL 审计等整合成一个"运维控制面"，AI Agent 是其大脑 | **AI 银行 Agent 的可观测系统**——只做一件事：观测 AI Agent 的运行健康（TTFT/准确率/转化/满意度），不做运维工具面 |
| **目标用户** | 运维/SRE/研发/审计员的多角色平台 | AI 团队 + 运营（看 AI 表现好不好） |
| **可观测性角色** | "事实层"——为 AI Agent 提供证据来源 | **"终端产品"**——可观测本身就是交付物 |
| **AI Agent 角色** | 核心卖点——Action Router + Skill + MCP + Preflight + 确认流 | InsightsEngine 仅做诊断建议（三层边界 L3 禁止写操作） |
| **数据采集** | 不采集数据——对接外部 Prometheus/Grafana/Loki/Tempo/SkyWalking | **自己采集**——OTel javaagent → Collector → Backend → PG/Redis |
| **规模假设** | 通用小中型（个人开源，SQLite 默认） | 银行数千万用户（PostgreSQL 分区表 / Redis Sentinel / Bitmap DAU / 银保监合规） |

> **一句话**：SxDevOps 是「让 AI 帮运维干活」的平台，我们是「看 AI 干活好不好」的观测系统。两者架构互补而非竞争。

---

## 二、组件选型对比

| 层级 | SxDevOps | 我们的 V4/V6 | 异同 |
|---|---|---|---|
| **后端语言** | Python Django + django-rest-framework | Java 17 + Spring Boot 3 | 语言不同，架构风格不同（Django 单体 vs Spring Boot 分层） |
| **前端框架** | Vue 3 + Element Plus | Vite + React + Ant Design + Tailwind | 框架不同，Element Plus 偏企业后台风格，Ant Design 同样 |
| **数据库** | SQLite（默认）/ MySQL（生产） | H2（Demo）→ PostgreSQL（生产） | SxDevOps SQLite 适合个人小规模；PG 对银行量级必须 |
| **实时通信** | Django Channels + Redis | REST / WebSocket（V6 P1） | SxDevOps 原生 WebSocket；我们 P1 才补 |
| **指标存储** | Prometheus（外部） | VictoriaMetrics（自建集群） | SxDevOps 依赖外部；我们自建 |
| **日志存储** | Loki / ELK / SLS（外部） | Loki + Vector（自建） | 同上——SxDevOps 对接，我们自建 |
| **Trace 存储** | SkyWalking / Tempo / Jaeger / Zipkin（外部） | Tempo（自建） | 同上 |
| **采集器** | 无——对接已有 | OTel Collector / Alloy（自建集群） | **核心差异**：我们不依赖外部可观测栈 |
| **看板** | 承接 Grafana（iframe 嵌入 + 跳转） | Grafana（自建）+ 自研 V20 大屏 | SxDevOps 无自研大屏 |
| **AI Agent 框架** | 自研 Action Router + Skill Registry + MCP | 自研 InsightsEngine（双层架构） | 两边都是自研 |
| **LLM** | OpenAI Compatible（可配任何） | DashScope qwen-plus / qwen-turbo | — |
| **容器管理** | 内置 K8s / Docker 管理 | 无（运维面交给其他工具） | 完全不同赛道 |

### 关键判断

| 差异点 | SxDevOps 的优势 | 我们的优势 |
|---|---|---|
| **不自己采集数据** | 部署门槛低，开箱即用（有 Grafana 就能接） | ❌ 但「看 AI 干得好不好」的指标（TTFT/准确率/转化）外部 Grafana 没有——必须自己埋点采集 |
| **SQLite 默认** | 零配置，个人体验友好 | ❌ 银行量级不可行，独占锁 + 单文件 |
| **Element Plus 企业后台** | 表格/表单/查询/配置页交互成熟 | ❌ 但无自研数据大屏（无图表自定义能力） |
| **对接多 Trace 后端** | 灵活，甲方已有 SkyWalking 也能接 | 我们只对接 Tempo（自建栈内闭环） |

> **结论**：组件选型没有冲突——两边分别解决不同问题。SxDevOps 偏"运维平台底座"，我们偏"AI 专属可观测"。

---

## 三、系统架构对比

### 3.1 SxDevOps 架构（层次视角）

```
┌─────────────────────────────────────────────┐
│              Vue 3 前端工作台                  │
│  智能助手 │ 可观测性 │ 事件中心 │ 任务中心 │ K8s  │
└──────────────────┬──────────────────────────┘
                   │ REST / WebSocket
┌──────────────────▼──────────────────────────┐
│           Django Backend                     │
│  ┌──────────────────────────────────────┐   │
│  │        AIOps 智能体（大脑层）          │   │
│  │  Action Router → Agent Kernel →       │   │
│  │  Skill Registry → MCP Registry →      │   │
│  │  Pending Action → 审计反馈            │   │
│  └──────────────────────────────────────┘   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐    │
│  │ 可观测性  │ │ 事件中心  │ │ 任务中心  │    │
│  │ (事实层)  │ │ (记忆层)  │ │ (行动层)  │    │
│  └──────────┘ └──────────┘ └──────────┘    │
└──────────────────┬──────────────────────────┘
                   │ 对接外部系统（不采集）
     ┌─────────────┼─────────────┐
     ▼             ▼             ▼
 Prometheus    Loki/ELK    Tempo/Jaeger
 Grafana       SkyWalking  K8s/Docker
```

### 3.2 我们的架构（P2 目标）

```
┌─────────────────────────────────────────────┐
│       React 前端 + V20 自研大屏               │
│  总览 │ 会话回放 │ 链路追踪 │ AI洞察 │ 告警    │
└──────────────────┬──────────────────────────┘
                   │ REST
┌──────────────────▼──────────────────────────┐
│         Backend 9090（自研 · 业务语义权威源）  │
│  ┌──────────────────────────────────────┐   │
│  │  OtlpParser → PG/Redis →             │   │
│  │  InsightsEngine + AlertEngine        │   │
│  └──────────────────────────────────────┘   │
└──────────────────┬──────────────────────────┘
                   │ OTLP (自采集)
┌──────────────────▼──────────────────────────┐
│     OTel Collector 集群 + Kafka 缓冲         │
└──────────────────┬──────────────────────────┘
                   │ OTLP (自埋点)
┌──────────────────▼──────────────────────────┐
│      Core 8080（银行 AI Agent 业务）          │
│  BankController / Router / L0~L2 Agent      │
│  ObsChatModel 埋点 / SessionBridge          │
└─────────────────────────────────────────────┘
    ↓ 信号外溢 → Tempo + VM + Loki + Grafana
```

### 3.3 架构差异核心

| 维度 | SxDevOps | 我们 | 差异本质 |
|---|---|---|---|
| **数据来源** | 对接外部已有可观测栈 | 自己从 Core 埋点→Collector→Backend 全链路自采集 | SxDevOps 是「可观测数据的消费者」；我们是「可观测数据的生产者+消费者」 |
| **可观测性深度** | 通用（指标/日志/链路查询门户） | AI 专属（TTFT/准确率/转化/满意度/意图链） | 我们可以回答"这个 AI Agent 哪里不好"；SxDevOps 只能回答"这个服务哪里不好"（通用运维视角） |
| **AI Agent 安全边界** | 极严格（Preflight→确认→执行→审计闭环） | InsightsEngine L3 禁止写操作 | SxDevOps 的安全模型更完整、可执行；我们只做诊断建议 |
| **数据闭环** | AI 排障 → 事件沉淀 → 任务执行 → 审计 | Core 埋点 → 聚合 → 洞察 → 告警 | 闭环方向不同：SxDevOps 是"运维动作闭环"，我们是"数据洞察闭环" |
| **单点 vs 分布式** | Django 单体 + 外部依赖 | Collector 集群 + PG + Redis Sentinel + Tempo + VM + Loki + Kafka | 我们面向银行量级的分布式 HA 设计 |

---

## 四、可观测性设计对比

### 4.1 SxDevOps 可观测性模块

从用户文档中梳理出的可观测性功能矩阵：

| 功能 | 说明 | 数据来源 |
|---|---|---|
| **可视化与仪表盘** | 按目录维护 Grafana 看板、iframe 嵌入、跳转 | 外部 Grafana |
| **指标查询** | PromQL 查询，模板辅助 | 外部 Prometheus |
| **日志中心** | 按标签/字段/关键字查询 Loki/ELK/SLS | 外部 Loki/ELK |
| **链路追踪** | Trace ID 查询、拓扑、跳转关联日志 | 外部 Tempo/Jaeger/SkyWalking |
| **告警中心** | 汇聚告警信息，作为 AIOps 触发线索 | 外部告警源 |
| **数据源管理** | 统一管理指标/日志/链路数据源及关联配置 | — |
| **知识图谱** | 环境/服务/数据源/事件/资源之间的关系图 | 平台内配置 |

**核心特征**：SxDevOps 的可观测性 = **查询门户 + 数据源管理 + 看板承接**。它不自己采集、不自己画图、不自己建仪表盘——它是把已有 Grafana/Prometheus/Loki 的能力**整合到一个运维工作台里**，让 AI Agent 能跨系统取证。

### 4.2 我们的可观测性（V4/V6 + V20）

| 功能 | 说明 | 数据来源 |
|---|---|---|
| **V20 自研大屏（8 页）** | 总览/会话回放/链路追踪/AI洞察(6 TAB)/日志/告警/设置/智能洞察 | 自建 Backend 9090 API |
| **AI 健康概览四卡** | 性能/准确率/转化/满意度实时评分 | 自聚合 |
| **诊断驾驶舱** | TOP5 行动建议 + 三类瓶颈卡 + 慢会话根因 + 阈值散点 | InsightsEngine |
| **分层意图 + 置信度** | L0/L1/L2 预测意图 vs 实际意图 + LLM 置信度 | Core OTel span |
| **流失画像 + 不满意原因聚类** | 漏斗流失阶段定位 + 不满意会话特征画像 | Sessions 表 |
| **Agent KPI 四卡 + 工具明细** | 按 model × agent 的双维性能分析 | spans 聚合 |
| **TTFT/TPOT 阈值散点** | 1500ms 红线 + scatter 分布 | Histogram |
| **Grafana（P2 外溢）** | A 类基础指标（JVM/HTTP/DB）交 Grafana | Tempo + VM + Loki |

**核心特征**：我们是**"为 AI Agent 量身定做的专属可观测"**——TTFT/准确率/意图链/转化漏斗/满意度画像这些 AI 专属指标，外部通用可观测栈完全没有。

### 4.3 可观测性差异总结

| 维度 | SxDevOps | 我们 |
|---|---|---|
| **类型** | 通用运维可观测查询门户 | AI Agent 专属业务可观测大屏 |
| **采集** | 不采集（对接外部） | 自采集全链路（Core→Collector→Backend） |
| **图表** | 无自研仪表盘（用 Grafana） | 18+ ECharts 自研大屏 |
| **AI 专属指标** | ❌ 无（TTFT/准确率/意图链） | ✅ 核心卖点 |
| **告警** | 汇聚外部告警 | 自研 AlertEngine + P2 迁 Grafana |
| **知识图谱** | ✅ 环境/服务/数据源关系图 | ❌ 无（非目标场景） |
| **与 AI Agent 关系** | 可观测性→喂给 AI Agent 做排障 | 可观测性→看 AI Agent 干得好不好 |

---

## 五、AI Agent 设计对比（核心差异）

**这是两个项目理念分歧最大的地方。**

### 5.1 SxDevOps AI Agent（运维执行型）

```
用户：「分析生产 order-center 最近异常」
  ↓
Action Router: 识别 → alert.root_cause（风险=read_only, mode=ReAct）
  ↓
Agent Kernel: Plan → 查告警 → 查日志 → 查链路 → 查变更 → 关联
  ↓
Skill: sx-alert-evidence-checklist 约束输出格式
  ↓
MCP Tools: query_observability → Prometheus/Loki/Tempo
  ↓
Pending Action: 诊断结论 + 建议操作 → 用户确认
  ↓
平台 API 执行 → 审计记录
  ↓
答案：「结论：…… 依据：…… 建议操作：……」
```

**关键设计**：
- **Action Router**：识别 10 种任务类型（告警根因/变更关联/日志查询/K8s 诊断/自愈推荐等）
- **Agent Mode**：Direct / ReAct / Plan+ReAct 三级自适应
- **Preflight**：写入/执行前强制权限校验 + 风险评估 + 缺参补齐 + 回滚就绪检查
- **Pending Action**：所有写入和执行动作必须先变成待确认动作
- **Skill/SOP**：领域能力包约束证据清单/查询规范/输出格式/安全边界
- **MCP 工具治理**：最终可用工具 = `Skill工具依赖 ∩ MCP可用性 ∩ 用户RBAC ∩ Action安全策略`
- **双阶段应答**：第一阶段 LLM 规划工具调用 → MCP 返回事实 → 第二阶段 LLM 按 Skill 模板整形 → 代码兜底防 LLM 瞎编
- **审计闭环**：会话→工具调用→预检→待确认→执行→结果全留痕

### 5.2 我们的 AI Agent（诊断建议型）

```
数据流入：
  Core OTel span → Collector → Backend → PG metrics_agg / spans / sessions
  ↓
InsightsEngine:
  1. 确定性聚合（L1）：统计 P95 / 错误率 / Token / 准确率 / 漏斗 → 代码自动
  2. 模糊判断（L2）：根因假设生成 / 行动优先级 / 降噪建议 → AI 模型推荐
  3. 写操作禁入（L3）：回滚/配置修改/扩缩容 → 必须人工 + 审批 + 审计 + 回滚

输出：
  - 6 TAB API（准确率/性能/Agent/漏斗/Token/满意度）
  - 诊断驾驶舱（TOP5 行动建议 + 瓶颈卡 + 根因表 + 散点图）
  - 告警 → 钉钉/飞书 Webhook
```

**关键设计**：
- **双层架构**：6 Service（AIInsights/AgentPerf/TokenCost/ConversionFunnel/Satisfaction/ToolStats）+ InsightsEngine 交叉引擎
- **三层边界**（web合入 P13）：L1 自动执行 / L2 AI 推荐需确认 / L3 禁止 AI 执行
- **提示词即 Runbook**（web合入 P20）：prompt 版本化/评审/测试
- **无 MCP 集成**（留 P3）：当前只从 PG/Redis 聚合，不查实时外部系统
- **无 Preflight/Pending Action**：L3 完全禁止写操作，不需要确认流

### 5.3 AI Agent 对比

| 维度 | SxDevOps | 我们 |
|---|---|---|
| **Agent 类型** | **运维执行型**——能理解问题→取证→生成建议→待确认→执行 | **诊断建议型**——只读分析、输出洞察和建议、禁止写操作 |
| **Agent 模式** | Direct / ReAct / Plan+ReAct 三级 | 无多模式——当前仅聚合分析 |
| **任务路由** | Action Router（10 种任务类型识别） | 无——固定流程 |
| **知识包** | Skill/SOP（证据清单/查询规范/安全边界） | 无——固定诊断逻辑 |
| **工具调用** | MCP 工具治理（多维度 ∩ 判定） | 无——纯 SQL/API 查询 |
| **安全边界** | **极严格**——Preflight→确认→执行→审计闭环 | **L3 硬禁止**——不执行就无需确认流 |
| **响应结构** | 双阶段：LLM 规划 → MCP 取证 → LLM 整形 → 结论+依据+建议 | 单阶段：SQL 聚合 → JSON → 前端渲染 |
| **审计** | 全链路：会话/工具调用/预检/确认/执行 | 仅 RBAC 访问审计（会话回放/链路详情） |
| **MCP/A2A** | ✅ 已落地（作 MCP Server + Client） | ❌ P3 扩展方向 |

### 5.4 对我们的启发

SxDevOps 的 AI Agent 设计有 **3 个直接可参考的点**（已部分在 web合入 V6 中覆盖）：

| SxDevOps 设计 | 我们可借鉴 | 当前状态 |
|---|---|---|
| **Action Router**（任务类型识别 + 上下文注入） | InsightsEngine 的诊断域可拆分为"性能诊断 Action""质量诊断 Action""业务诊断 Action"——不同 Action 加载不同 Skill（查询模板 + 输出格式） | P3 扩展方向 |
| **双阶段应答**（LLM 取证 → 整形，防 LLM 瞎编） | InsightsEngine 的 prompt 模板应拆为两阶段：① 只提取数据不回答 ② 按模板整形输出。代码兜底保证"LLM 宕了也不空" | web合入 V6 §9.1 三层边界已提及，但未实现双阶段 |
| **Preflight + Pending Action** | 若未来 InsightsEngine 的"建议操作"需要人工确认才执行（如：建议重启某个 Agent 节点），就需要 Preflight 确认流。当前全禁止写操作规避了这个问题。 | 当前 L3 禁止 AI 写操作即可，暂不需要 |

> 但 SxDevOps 的 AI Agent 是为「通用运维排障」设计的（查告警/查日志/查链路/查 K8s），我们的 InsightsEngine 专为「AI 表现好不好」设计（TTFT/准确率/转化/意图链）——两者输入数据类型完全不同，不能直接套用。

---

## 六、UI 设计对比

### 6.1 SxDevOps UI 风格（基于文档描述和截图目录推断）

| 特征 | 说明 |
|---|---|
| **框架** | Vue 3 + Element Plus（企业后台管理系统风格） |
| **布局** | 左侧菜单导航 + 右侧内容区，典型的运维管理后台 |
| **可观测性页面** | 查询型页面（指标查询=PromQL 输入框+图表 / 日志中心=条件筛选+表格 / 链路追踪=Trace ID 查询+拓扑图） |
| **智能助手** | 对话式界面（类似 ChatGPT），左侧知识图谱关联，右侧输出结论/依据/建议 |
| **图表** | ECharts（但主要是查询结果渲染，非自定义大屏） |
| **特色交互** | 结构化 Block 渲染（事件卡片/证据时间线/审批表单/待确认按钮）；知识图谱关系图 |

### 6.2 V20 大屏 UI 风格

| 特征 | 说明 |
|---|---|
| **框架** | 单文件 HTML + Ant Design 暗色侧栏 + ECharts 5.4.3 + 原生 JS |
| **布局** | 左侧暗色侧栏导航 + 8 页切换内容区 |
| **总览** | Zone A-D 指标卡片 + 趋势图 + AI 健康概览四卡（紫边渐变） |
| **AI 洞察** | 6 TAB（准确率/性能/Agent/漏斗/Token/满意度），每 TAB 2-3 个 ECharts |
| **智能洞察** | 独立末位 TAB：诊断驾驶舱（TOP5 + 瓶颈卡 + 根因表 + 阈值散点）+ 3×3 洞察网格 |
| **图表** | 18+ ECharts（boxplot/scatter/funnel/heatmap/bar/pie/line） |
| **特色交互** | 三件套跳转（链路 trace→日志/会话回放）；数据健康三态角标；TW/EN 双语切换 |

### 6.3 "可观测性模块" UI 直接对比

| 对比维度 | SxDevOps 可观测性 | 我们的 V20 |
|---|---|---|
| **页面类型** | 查询 + 配置型（指标输入框/日志筛选/链路搜索） | 大屏展示型（指标卡片 + 趋势图 + 诊断驾驶舱） |
| **使用场景** | 排障时"主动查"——运维人员输入 PromQL / 日志条件 / Trace ID | 日常"被动看"——打开大屏即见全貌 + 下钻分析 |
| **AI 健康概览** | ❌ 无（无 AI 专属指标） | ✅ 性能/准确率/转化/满意度四卡 |
| **AI 洞察 6 TAB** | ❌ 无（通用运维指标） | ✅ 准确率/性能/Agent/漏斗/Token/满意度 |
| **诊断驾驶舱** | ❌ 无 | ✅ TOP5 建议 + 瓶颈卡 + 根因表 + 散点 |
| **意图链可视化** | ❌ 无 | ✅ L0→L1→L2 真实意图链 + 置信度 |
| **知识图谱** | ✅ 环境/服务/数据源关系图 | ❌ 无 |
| **Grafana 集成** | ✅ iframe 嵌入 + 跳转 | ✅ P2 Grafana 独立部署（A 类指标外溢） |
| **告警展示** | ✅ 告警中心汇聚 + 关联 AIOps | ✅ alert-card 卡片化 + timeline 事件流 |
| **会话回放** | ❌ 无 | ✅ turn-by-turn 回放 + 三件套跳转 |
| **设计语言** | Element Plus 企业后台（白底/表格/表单） | Ant Design 暗色侧栏 + 自定义卡片（深色专业大屏感） |

### 6.4 UI 差异本质

```
SxDevOps 可观测性 UI = 「运维工作台」里的一个模块
  用户行为：打开 → 输入查询条件 → 看结果 → 操作 → 关掉
  
我们的 V20 大屏 = 「AI 运行的实时仪表盘」
  用户行为：打开 → 挂在大屏上 → 异常时告警 → 下钻根因
```

| SxDevOps UI 优势 | 我们 V20 UI 优势 |
|---|---|
| ✅ 查询功能强大（PromQL / 日志筛选 / Trace ID 精准查询） | ✅ 一屏展示全貌（8 页大屏，挂墙即可看懂） |
| ✅ 知识图谱关系可视化 | ✅ AI 专属指标深度（意图链/流失画像/置信度） |
| ✅ 结构化 Block（事件卡/确认表/审批流） | ✅ 诊断驾驶舱（从"看数据"升级为"给行动"） |
| ✅ 与任务/事件/K8s 的联动闭环 | ✅ 三件套跳转（trace→log→session 无缝） |
| ❌ 无可观测大屏（只有查询页） | ❌ 无查询输入框（不适合临时查一条 PromQL） |
| ❌ 无 AI 专属指标 | ❌ 无知识图谱 |
| ❌ 依赖外部 Grafana（没自建图表能力） | ❌ 无事件/任务/工单等运维工具面 |

---

## 七、综合判断

### 7.1 这是两个"方向不同但可互补"的项目

```
SxDevOps:  ┌────────────────────────────────────┐
           │  运维控制面（让 AI 帮你做运维）       │
           │  AI Agent ← 可观测性事实层 ← 外部系统 │
           └────────────────────────────────────┘
           
我们的:     ┌────────────────────────────────────┐
           │  AI 专属可观测（看 AI 干得好不好）    │
           │  Core 埋点 → 采集 → 聚合 → 大屏      │
           └────────────────────────────────────┘
```

SxDevOps 的"可观测性"做得浅（查询门户），因为它的强项是 **AI Agent 编排和安全执行**。  
我们的"可观测性"做得深（18+ ECharts + 诊断驾驶舱），因为可观测本身就是我们的交付物。

### 7.2 各自最强的地方

| SxDevOps 最强 | 我们最强 |
|---|---|
| 🔥 **AI Agent 编排引擎**——Action Router + Skill + MCP + Preflight + Pending Action + 审计闭环，是业界少见的"能安全执行"的运维 Agent | 🔥 **AI Agent 专属大屏**——TTFT/准确率/意图链/流失画像/满意度诊断，通用可观测栈完全没有 |
| 🔥 **"运维控制面"整合度**——一个平台管告警/日志/Trace/K8s/工单/SQL审计/任务，横向覆盖广 | 🔥 **银行量级合规**——Bitmap DAU / 银保监 3-7 年保留 / Kafka 缓冲 / PII 网关级 BLOCK / RBAC 五角色 |
| 🔥 **MCP 生态**——已落地对外 MCP Server + 外部 MCP Client 接入 | 🔥 **全链路自采集**——不依赖外部 Grafana/Prometheus 就绪，Core→Collector→Backend 自主闭环 |

### 7.3 对我们有直接借鉴价值的设计（按优先级）

| 优先级 | SxDevOps 设计 | 借鉴方向 | 已覆盖？ |
|---|---|---|---|
| 🟡 P1 | **双阶段应答**（LLM 取证 → 整形 + 代码兜底） | InsightsEngine 的 prompt 模板拆成取证阶段 + 整形阶段，代码兜底防 LLM 空输出 | 部分（web合入 V6 三层边界提及） |
| 🟡 P2 | **Action + Skill 分离** | InsightsEngine 当前的三大诊断域可拆为 Action（性能诊断/质量诊断/业务诊断），每个 Action 加载 Skill（查询模板 + 输出格式） | 未覆盖 |
| 🟡 P3 | **知识图谱环境** | 把 Core Agent 的 L0/L1/L2 调用关系可视化为知识图谱（比散点更能表达拓扑） | 未覆盖 |
| ⚪ 参考 | **MCP 工具治理** | 若 InsightsEngine 未来需要查实时系统（K8s/Prometheus/Git），借鉴其"多维 ∩ 判定"工具治理模型 | P3 扩展 |
| ⚪ 参考 | **事件中心** | 把 InsightsEngine 的诊断报告生成时间/prompt 版本/数据窗口作为"事件"沉淀，做历史复盘 | P3 扩展 |

### 7.4 SxDevOps 可以向我们借鉴的设计（推测）

| 优先级 | 我们的设计 | SxDevOps 可借鉴 |
|---|---|---|
| 🟡 | **AI Agent 专属仪表盘** | 若 SxDevOps 未来也做 AI Agent 可观测（而非通用运维），需要 TTFT/准确率/意图链/转化漏斗这些维度的自研大屏 |
| 🟡 | **自采集管道**（OTel Collector + Backend + PG/Redis） | SxDevOps 当前完全依赖外部可观测栈——若甲方没有 Grafana/Prometheus，它就无法取证。自采集能力可让它在"零基础"环境也跑起来 |
| 🟡 | **银行合规设计**（审计保留/PII BLOCK/RBAC/银保监分层） | 个人开源项目暂无银行场景需求，但合规设计可作为行业深度参考 |

### 7.5 一句话总结

> **SxDevOps 和我们的项目不是竞争对手——它们分别解决"让 AI 帮运维干活"和"看 AI 干活好不好"两个正交问题。SxDevOps 的 AI Agent 编排引擎（Action Router / Skill/SOP / MCP / Preflight / Pending Action）值得我们借鉴用于 InsightsEngine 的下一步演进；而我们 V20 大屏 + AI 专属指标 + 银行量级合规在"AI Agent 可观测"领域有 SxDevOps 完全不具备的深度。两者可互补：如果银行要同时建设"AI Agent 可观测大屏"和"运维 AI 助手"，SxDevOps 的编排引擎 + 我们的专属大屏是一个合理的组合方向。**

---

> 文档版本：0712 · SxDevOps 对比分析
> 对比来源：SxDevOps GitHub（README + 用户使用文档 + AIOps 2.0 设计 + MCP+Skill 设计）+ 我们的 V4 / V6-web文章合入 / V20 HTML
> 截图参考：SxDevOps docs/screenshots 目录（智能助手/链路追踪/日志中心/事件中心/任务中心/知识图谱/运转逻辑总览）
