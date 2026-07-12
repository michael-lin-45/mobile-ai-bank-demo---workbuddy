# 可观测设计方案差异分析

> WorkBuddy V2 (`docs/可观测优化总结-0711-WorkBuddy V2.md`) vs Codex V2 (`docs/specs/可观测优化总结-0711-Codex-V2.md`)
> 两份文档分别代表两个独立设计团队的最终产出。本文按模块对齐、逐项比对，给出优劣判断与合并建议。

---

## 0. 文档结构映射

| Codex V2 章节目录 | WorkBuddy V2 对应章节 | 可比性 |
|---|---|---|
| §1 现状诊断与优化原则 | §0 如何使用本文 + §7.3 关键坑清单 | 中 — 主题相似但编排差异大 |
| §2 优化计划总览 | §1 最新优化计划 | ⭐ 高 |
| §3 系统目标架构 + §12 Collector | §2 系统目标架构 + §3.8 Collector | ⭐ 高 |
| §4 数据流详细设计 | §3 各优化功能详细设计（3.1-3.7） | ⭐ 高 |
| §5 Core 端埋点设计 | §3.7 Core 数据埋点设计 | ⭐ 高 |
| §6 指标设计全表 | §4 指标设计全表 | ⭐ 高 |
| §7 DB 表设计全表 | §5 DB 表设计 | ⭐ 高 |
| §8 Redis 缓存设计 | §3.9 后端数据处理（含 Redis 逻辑） | ⭐ 高 |
| §9 AI 洞察智能诊断设计 | §6.1-6.2 AI 洞察设计 | ⭐ 高 |
| §10 告警系统设计 | §3.6 告警数据流 | ⭐ 高 |
| §11 UI 设计终稿说明 | §6.2-6.4 UI 设计（V20） | ⭐ 高 |
| §13 实施路径与里程碑 | §1 优化计划 | 高 |
| §14 GAP 修复清单 | §7 补充（待定项/坑清单） | 中 |
| §3.3 架构设计决策记录 D1-D7 | §2.1.3 架构变化总结 + §7 补充 | 中 |
| §6.5 维度规范与基数预算 | （无专门章节） | 独有 — Codex 特有 |
| §8.2 DAU HyperLogLog 实现 | §3.5 会话（含 recordDauUser） | 中 |
| §12.1 Collector 完整 config.yaml | §3.8 Collector（仅文字） | Codex 更详细 |

---

## 1. 逐模块差异分析

### 1.1 优化计划与分阶段策略

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **阶段划分** | P0→P1→P2→P3（4 个阶段） | Phase 0→1→2（3 个阶段，3 周/3.5 人周） |
| **P0/Phase0 重心** | **Core 发射层埋点补齐**（让 6 TAB 有真实数据源） | **基础设施补齐**（部署 Jaeger/Loki/Grafana + Collector 基础配置 + 埋点） |
| **组件引入时机** | P0 不引入新组件，纯埋点+修复；P2 才引标准栈 | Phase 0 就引入 Jaeger/Loki/Grafana/Docker Compose |
| **存储迁移** | P2：H2 → PostgreSQL | P2：未提及 PostgreSQL，仅组件选型表提 Jaeger |
| **工时估算** | "3-5 天 / 约 1 周 / 1-2 周" 示意级 | Phase 0: 1.5 人周 / Phase 1: 1 人周 / Phase 2: 1 人周，精确到半天 |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **P0 要不要引入标准栈？** | **WorkBuddy 更务实** | Codex Phase 0 同时埋点+部署标准栈，风险高且不聚焦。实际瓶颈是 Core 数据源断链（DAU/置信度/分层意图/reroute），不引入 Jaeger/Loki 也能修。先修数据链、再扩存储栈，每一步可验证，不会"埋点没修完、新组件也起不来"两头卡 | **采纳 WorkBuddy**：P0 纯埋点修复（Core 发射层），P2 再引入标准栈 + 存储迁移。Codex 的工时细化值得借鉴 |
| **是否引入 Jaeger？** | **WorkBuddy 的 Tempo 更优** | Codex 选型 Jaeger 看中"CNCF 毕业 + UI 成熟"，但 Tempo 对象存储后端 + 与 Loki 同源关联 + 查询便宜是 JAeger 不具备的；且 P0 自研 9090 后端已有 trace 列表，Jaeger 短期非刚需 | **采纳 WorkBuddy**：P2 统一用 Tempo。同期 Codex Collector config 可参考 |

---

### 1.2 系统目标架构

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **架构图数量** | 2 张：当前架构 + 目标架构（整改前后对照） | 2 张：P0 架构 + P2 架构（当前与未来拆分） |
| **结构变化沟通** | §2.1.3 架构变化总结（6 处结构性变化表 + 一句话总结） | §3.3 架构设计决策记录（D1-D7，7 条关键决策） |
| **自研 vs 标准栈边界** | 明确"业务语义（session/funnel/satisfaction）必须留在关系库，标准栈仅信号外溢" | 明确"A 类基础设施交 Prometheus，B+C AI 层自研"，但 Jaeger 选型让 Trace 分流到标准栈 |
| **存储迁移** | H2 → PostgreSQL（P2），Redis 热层→H2 温层每 30s 同步 | 未提 PG；Redis → DB 同步每 5 分钟（非 30s） |
| **双写分治** | 单句子总结："双写分治、标准栈兜底、告警闭环的开放可观测体系" | 无架构总结语，但决策表更结构化（7 条决策） |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **整改前 vs 当前架构图** | **WorkBuddy 更直观** | 整改前→整改后的对照图让读者一眼看懂"变了什么"；Codex 用 P0 当前/P2 未来，跳过了整改前→P0 的变化 | **采纳 WorkBuddy 的当前→目标对照方式** |
| **架构决策记录** | **Codex 更结构化** | D1-D7 每条决策带理由，可追溯、可质疑、可验证；WorkBuddy 的 6 处变化更重"现状描述"，决策可见性稍弱 | **合并**：保留 WorkBuddy 的前后对照图 + Codex 的决策记录表 |
| **Jaeger vs Tempo** | **WorkBuddy 选型更优** | 见 1.1；Codex 选 Jaeger 主要基于"CNCF 毕业"知名度，但 Tempo 对象存储 + Loki 同源关联 + Grafana 拼图优势更显著 | **采纳 WorkBuddy 的 Tempo 选型** |

---

### 1.3 组件选型

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **Trace 存储** | **Tempo**（理由：对象存储、与 Loki 同源、查询便宜） | **Jaeger**（理由：CNCF 毕业、OTel 原生、UI 成熟） |
| **Metrics** | **VictoriaMetrics**（理由：PromQL 兼容、压缩率高 10×、运维省） | **Prometheus**（理由：行业标准、Pull 模式稳定） |
| **Logs** | **Loki + Vector**（理由：标签索引、对象存储、便宜） | **Loki**（相同；未提 Vector） |
| **可视化** | **Grafana**（相同） | **Grafana**（相同） |
| **采集器** | **OTel Collector → 可选 Alloy**（理由：单二进制统一，运维更省） | **OTel Collector 增强**（理由：零新组件，只改配置） |
| **告警** | Grafana Alerting → 钉钉（P1） | 自研 AlertEngineService → 钉钉/飞书 Webhook（P0） |
| **AI 洞察** | AIInsightsService 族（6 Service，读 spans+metrics_agg） | InsightsEngineService（5 类洞察：性能/质量/业务交叉分析+根因+建议） |
| **LLM 专项** | Langfuse（P3 待定项 C） | OpenLLMetry P1 可选 / Langfuse 定位清楚（不替代、可参考） |
| **推荐组合** | Alloy+Tempo+VM+Loki+Grafana 五件套 | Collector+Jaeger+Prometheus+Loki+Grafana+Redis+自研 Insight/Alert |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **Tempo vs Jaeger** | **WorkBuddy 更优** | 对象存储成本远低于 Jaeger 的 Cassandra/ES；与 Loki 同源共享权限；Grafana 原生拼图 | **采纳 Tempo** |
| **VictoriaMetrics vs Prometheus** | **WorkBuddy 更优** | VM 压缩率高 10×、单机扛量、运维省，小团队优选 | **采纳 VM** |
| **告警：Grafana vs 自研** | **Codex 自研短期更快，但 WorkBuddy Grafana 长期更稳** | Codex 自研 AlertEngine 可立即落地（无 Docker 依赖），但需自己维护求值引擎+通知通道+抑制逻辑；Grafana Alerting 生态全且零维护 | **合并**：P0 先用自研快速打通告警闭环（Codex AlertEngine），P2 迁到 Grafana Alerting 做统一告警 |

---

### 1.4 数据流设计

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **信号划分** | Trace / Metrics / Logs / Session / Alerts 5 条管线 | Metrics / Traces / Logs 3 条 OTLP 管线 + Session 独立 + DAU 修复 |
| **Session 管道** | 强调"两条独立管道，极易混淆"（spans 管道 / sessions 管道） | 在 §4.4 专述会话流程，含 `session_type`/`funnel_stage`/`handoff` 等业务字段 |
| **指标处理举例** | 以 LLM 时延为例说明踩坑（`publishPercentileHistogram` vs Summary） | 以意图准确率为例，从 Core 埋点→Collector→OtlpParser→Redis→H2 完整展开 |
| **DAU 实现** | `recordDauUser(userId)` 写入 Redis Set | **HyperLogLog + Sorted Set**（含完整 Java 代码），可压缩存储 |
| **实时同步频率** | Redis→H2 每 30s | Redis→DB 每 5 分钟（Codex 同步频率低 10 倍） |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **DAU HyperLogLog 实现** | **Codex 更优** | HyperLogLog 内存固定~12KB / 天，去重准确度 97%；WorkBuddy 用 Set 内存随用户数线性增长 | **采纳 Codex DAU 方案** |
| **信号管道组织** | **WorkBuddy 的"两条独立管道"警句更实用** | 这是新手最容易踩的坑（以为 session 依赖 trace 导出） | **保留 WorkBuddy 警句** |
| **指标举例方式** | **各有所长** | WorkBuddy 踩坑导向（`publishPercentileHistogram` truth 是致命 bug），Codex 教学导向（从埋点到存储完整展开）| **合并**：教学展开 + 踩坑警告 |

---

### 1.5 Core 端埋点设计

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **结构** | 按组件叙述（ObsChatModel / AgentSpanContext / SessionBridge / 指标约定） | 按埋点项叙述（5.2.1~5.2.6，含 Java 伪代码和文件路径） |
| **代码级细节** | 偏描述，"P0 待补"标记 + 指标契约 | 含具体 Java 代码片段（`@Override` / `recordIntentAccuracy(...)` / `SpanAttributes` builder） |
| **指标约定** | `publishPercentileHistogram(true)` 硬约束 | `llm.*` 自动拦截（ChatClientWrapper）+ `agent.*` 手动（10 项） |
| **OpenLLMetry** | 未提及 | §5.2.6 列入 P1 可选，补齐 `gen_ai.*` 语义 |
| **Reroute 埋点** | §3.7 SessionBridge 含 `rerouteTriggered` 字段 | §5.2.5 独立 reroute 事件埋点（含 `from_intent`/`to_intent`/`excluded_domain`） |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **可操作性** | **Codex 更可落地** | 代码片段 + 文件路径 + 伪代码，开发可直接抄；WorkBuddy 偏"契约约定"，需自行推断实现 | **采纳 Codex 的代码级埋点设计** |
| **OpenLLMetry** | **Codex 补充有价值** | `gen_ai.*` 语义属可让 Langfuse 等工具吃标准数据，P1 引入无侵入 | **采纳 Codex OpenLLMetry 为 P1 可选项** |
| **埋点组织方式** | **各有所长** | WorkBuddy 按组件叙述适合理解全局；Codex 按埋点项叙述适合实现 | **V3 合并两者**：概述用组件叙述 + 实现用埋点代码 |

---

### 1.6 指标设计全表

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **组织方式** | 按页面区域分组（4.1 总览 A1-D2 / 4.2 Trace T1-T5 / 4.3 AI洞察 I1-I5） | 按指标组分类（6.1 llm.* 5 项 / 6.2 agent.* 10 项 / 6.3 P0 新增 3 项） |
| **状态标注** | ✅正常 / 🟢已解决 / 🟡回归 / 🔴仍开放 / ⚪待启动 | ❌缺失 / 待实现 / →目标 |
| **大屏映射** | 区域标号（Zone A/B/C/D）+ 数据源实测 | §6.4 Zone 映射表（指标名→数据源→前端组件） |
| **维度规范** | 无 | §6.5 基数预算（Tag 允许 <200 vs 禁止 >1000）+ Span Attributes 规范表 |
| **P0 新增契约** | §4.4 6 项新增指标契约表 | §6.3 3 项新增 |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **指标状态追踪** | **WorkBuddy 更可管理** | 彩色状态标注让负责人一眼看穿哪些已修、哪些还缺、哪些待回归 | **采纳 WorkBuddy 状态体系** |
| **维度规范（基数预算）** | **Codex 独有价值的补充** | 高基数 tag（如 user_id/trace_id）会让 Prometheus TSDB 爆炸，Codex 的基数预算 + Span Attributes 规范是生产级必须的 | **新增采纳 Codex §6.5 到 V3** |
| **按区域 vs 按指标组** | **各有利弊** | 按区域适合前端看、按指标组适合后端看 | **V3 双组织并保留**：区域映射 + 指标组契约 |

---

### 1.7 DB 表设计

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **活跃表数量** | 11 张（spans / logs / sessions / session_turns / tool_calls / metrics_agg / alert_rules / alert_events / redis_metrics_snapshot / agent_performance(已弃) / token_cost(已弃)） | 12 张（3 张现有 + 8 张 Phase1 + 1 张新增 insight_reports） |
| **agent_performance / token_cost 定位** | **已弃用**：全库无 INSERT，实时聚合改读 spans+metrics_agg | **保留不变**：认为有写入方、继续维护 |
| **insight_reports 表** | 无 | **P0 新增**：`obs_insights_reports` 表，存诊断报告 JSON + 时间 + 版本号 |
| **Alert 表增强** | 无具体 SQL | Alert 表新增 `evaluation_interval`/`last_evaluated_at`/`current_value` 等求值字段 |
| **分层意图字段** | sessions 表加 `l0_intent/l1_intent/l2_intent/reroute_triggered` 列 | sessions 表加 `l0_intent/l1_intent/l2_intent/reroute_from/reroute_to/handoff` | 
| **Redis→DB 同步表** | `redis_metrics_snapshot` 单表兜底 | 多张 DB 表拆分（`obs_metrics_snapshot`/`obs_quota_daily`/`obs_ai_health_snapshot`） |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **agent_performance / token_cost 是否保留** | **WorkBuddy 判断正确** | 代码实测已确认这两张表全库无 INSERT → 永远为空。保留空表 + 继续维护误导后来开发者 | **采纳 WorkBuddy**：标记已弃用，聚合改实时 |
| **insight_reports 表** | **Codex 有价值** | 诊断报告历史归档可用于回顾+回归测试 | **采纳 Codex insight_reports 表** |
| **Alert 增强字段** | **Codex 更完整** | 求值间隔/上次求值时间/当前值便于调试告警未触发原因 | **采纳 Codex Alert 增强字段** |

---

### 1.8 Redis 缓存设计

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **Key 规范表** | 键空间清单（TTL：1m=120s / 5m=600s / 15m=1200s / 6h=21600s） | 14 个 Key 规范全表（含类型/写入方/读取方/说明） |
| **DAU 实现** | `recordDauUser(userId)` 写 Set | **HyperLogLog + Sorted Set**，含完整 Java 代码 |
| **实时数据 Key** | `obs:metrics:request_count:*` 等 | `obs:realtime:overview` Hash 聚合总览数据 |
| **洞察缓存** | 无单独 Key | `obs:insights:cache` JSON 5 分钟缓存 |
| **同步频率** | Redis→H2 每 30s | Redis→DB 每 5 分钟 |
| **对账机制** | 无 | 定时快照对账（5 分钟） |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **Redis Key 规范表完整性** | **Codex 更规范** | 有类型/写入方/读取方列，新人能快速定位；WorkBuddy 用文字描述，不易检索 | **采纳 Codex Key 规范表** |
| **DAU HyperLogLog** | **Codex 更优** | 内存 O(1)、准确度 97%、生产级方案 | **采纳 Codex**，替换 Set 方案 |
| **同步频率** | **WorkBuddy 30s 更实时** | 30s 快照对短窗口（1m TTL）的指标至关重要，5 分钟可能错过一整个窗口 | **保留 30s**，Codex 的 5 分钟可用于 long-term 趋势快照 |
| **实时聚合 Key 设计** | **各有所长** | WorkBuddy 细粒度 Key 逐个可查可解耦；Codex 单 Hash 聚合一次拉取更快 | **合并**：总览用 Hash 一次拉取（Codex），细分指标保留细粒度 Key（WorkBuddy） |

---

### 1.9 AI 洞察 / 智能诊断

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **名称** | AIInsightsService 族（6 个 Service） | InsightsEngineService（单一引擎） |
| **架构** | 每个 TAB 一个 Service，从 H2 聚合 | 一个引擎产生完整洞察报告（含交叉分析+根因+建议），缓存至 Redis |
| **API 设计** | REST 端点按 Service 分（6 个端点），隐式在文中 | 7 个 REST 端点显式定义（含 `insights-report/bottlenecks/root-cause/unsatisfied/conversion/actions/refresh`） |
| **根因分析** | V20 诊断驾驶舱有根因表（前端展示层面），无后端分析逻辑详述 | §9.4 完整慢会话根因分析逻辑（取 Span 独占时间 → Top3 候选 → 关联 LLM/工具/意图/上下文四维） |
| **交叉分析** | 弱 — 4 个 TAB 独立展示 | 强 — 三大诊断域 × 提炼项明细表，跨维度交叉 |
| **UI 定位** | 独立末位 TAB（智能洞察）+ AI 洞察 6 TAB | AI 洞察 7 TAB（第 7 TAB 诊断页）+ 右栏洞察 sidebar + 独立 intelligence 页 |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **引擎架构** | **Codex 更先进** | 单一引擎 + 统一报告 + API 设计清晰，避免"每个 TAB 自己算一遍"的重复查询；跨维度交叉分析是 WorkBuddy 缺失的 | **采纳 Codex InsightsEngine 架构** |
| **根因分析逻辑** | **Codex 独有价值** | §9.4 的独占时间算法 + 四维关联是可直接实现的算法设计，WorkBuddy 仅在前端展示层做了根因表 | **采纳 Codex §9.4 根因逻辑** |
| **API 显式定义** | **Codex 更工程化** | 7 个端点写明路径/方法/用途，前后端可直接对齐契约 | **采纳 Codex API 设计** |
| **UI 布局** | **WorkBuddy V20 独立末位 TAB + 诊断驾驶舱** | V20 的诊断驾驶舱（TOP5 优先行动 + 三类瓶颈卡 + 慢会话/不满意根因表 + 阈值散点）在视觉层面比 Codex 的"第 7 TAB"更具区分度 | **保留 WorkBuddy V20 布局**，后端用 Codex InsightsEngine 驱动 |

---

### 1.10 告警系统设计

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **架构** | Grafana Alerting（P1 引入，钉钉/企微推送） | 自研三组件：AlertEngineService（求值引擎）+ AlertNotifyService（Webhook 推送）+ AlertRuleRepository |
| **实现细节** | 概要级（规则管理 → 定时评估 → 推送 → 闭环） | 代码级（含 @Scheduled 30s、collectMetricValue/evaluateCondition/fireAlert/resolveAlert 伪代码、JSON webhook 模板） |
| **规则设计** | 5 条规则（LLM 错误率/P95/TTFT/Collector 接收量/满意度） | 4 条规则 + 状态机（TRIGGERED→ACKED→RESOLVED） |
| **抑制机制** | 无 | 5 分钟重复抑制（同规则 5 分钟内不重复通知） |
| **通知渠道** | 钉钉 | 钉钉 + 飞书，含 JSON webhook 模板 |
| **P2 演进** | Grafana Alerting 替代 | Alertmanager 可选（P2 规模化后） |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **谁来做告警** | **Codex 自研短期更快，WorkBuddy Grafana 长期更稳** | 自研：P0 可立即落地，但需维护引擎；Grafana：生态全、零维护，但需先搭标准栈 | **采纳 Codex P0 自研方案**：先打通告警闭环 → P2 迁 Grafana |
| **告警抑制** | **Codex 更成熟** | 5 分钟重复抑制是生产必备，否则异常波动直接告警风暴 | **采纳 Codex 抑制机制** |
| **状态机** | **Codex 更完整** | TRIGGERED→ACKED→RESOLVED 闭环让告警有完整的生命周期 | **采纳 Codex 状态机** |

---

### 1.11 UI 设计

| 维度 | WorkBuddy V2（V20） | Codex V2（V18） |
|---|---|---|
| **页面数** | 8 页 | 8 页 |
| **AI 洞察 TAB 数** | 6 TAB | 7 TAB（含诊断 TAB） |
| **智能洞察页** | 独立末位 TAB + 诊断驾驶舱 + 3×3 网格 | 独立 intelligence 页 + 洞察右栏 sidebar（在 AI 洞察页右侧） |
| **取长补短标注** | 5 处设计增强，标注借鉴来源（V17-Codex） | "融入 V19 健康洞察"，标注借鉴来源（WorkBuddy V19） |
| **图表数** | 未统计 | 18 个 |
| **数据健康** | 三态角标 + 环形卡 + 设置页对照表 | 顶栏 health-pill + 设置页 health-card |
| **总览 AI 健康** | 总览页新增 AI 健康概览四卡 | 总览页有 AI 健康概览卡片 |
| **字体** | Manrope + JetBrains Mono | 未指定 |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **智能洞察布局** | **WorkBuddy V20 更优** | 独立末位 TAB + 诊断驾驶舱给运维/运营一个"一站式问题诊断入口"，比 Codex 的"右栏 sidebar"更聚焦 | **保留 V20 独立末位 TAB 定位 + 诊断驾驶舱**，Codex 的右栏 sidebar 可作为可选布局 |
| **AI 洞察 TAB 数量** | **WorkBuddy 6 TAB+独立页 更合理 vs Codex 7 TAB** | 7 个 TAB 过多用户认知负荷高；V20 把诊断拆成独立页 + 6 TAB 保持 AI 洞察页简洁 | **保留 V20 6 TAB + 独立智能洞察页** |
| **图表清单** | **Codex 图表清单（18 个）有参考价值** | 可用于前后端对齐和实现检查 | **纳入 V3 附录** |

---

### 1.12 OTel Collector 配置

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **形式** | 文字描述（§3.8） | 完整 YAML 配置（§12.1，~120 行 config.yaml） |
| **Processors** | batch + tail_sampling + resource 富化 | batch + filter（健康检查）+ attributes（env/service）+ memory_limiter + tail_sampling |
| **Exporter 多路** | Tempo + VM + Loki exporter | prometheusremotewrite + loki exporter + 自研后端 otlphttp |
| **采样策略** | 错误/慢>1s/LLM 失败 | 错误/慢>3s/LLM 失败 + 正常 10% 概率采样 |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **可落地性** | **Codex 更可操作** | 完整 config.yaml 可直接拷贝到 `observability/otel-collector/config.yaml` | **采纳 Codex config.yaml** |
| **慢请求阈值** | ****WorkBuddy 1s 更实用** | AI 系统 3s 阈值偏大，1s 以上已可感知慢 | **保留 1s 阈值** |
| **processor 类型** | **Codex 更全** | filter(健康检查) 和 memory_limiter 是生产必备，WorkBuddy 未提 | **采纳 Codex filter+memory_limiter** |

---

### 1.13 实施路径与里程碑

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **粒度** | 阶段目标 → 关键动作 → 改动量 → 周期 → 状态 | Phase 任务 → 组件 → 工作量(天) → 优先级 |
| **工时估算** | "3-5 天 / 约 1 周 / 1-2 周" | Phase0: 1.5 人周 / Phase1: 1 人周 / Phase2: 1 人周，共 3.5 人周 |
| **里程碑** | 无显式里程碑 | 每 Phase 有交付物 + 验收标准 |
| **风险表** | 无 | 4 项风险 + 应对（OTel 兼容、Span 写入瓶颈、Redis/DB 不一致、告警风暴） |

| 核心差异点 | 优劣判断 | 理由 | 合并建议 |
|---|---|---|---|
| **可执行性** | **Codex 更可操作** | 每个任务有工作量和优先级，可直接排期；WorkBuddy 的阶段描述偏方向性 | **采纳 Codex 的任务级执行计划** |
| **风险管控** | **Codex 独有价值** | 告警风暴等风险项是 WorkBuddy 未考虑的 | **纳入 V3** |

---

### 1.14 其他独有模块

以下模块只在其中一份文档中出现，合并时需决定是否纳入：

| 模块 | 出处 | 内容 | 是否纳入 V3 |
|---|---|---|---|
| §6.5 维度规范与基数预算 | Codex 独有 | Metric Tag 允许<200 vs 禁止>1000；Span Attributes 高基数字段降级 | ✅ **必须纳入** — 生产级指标系统必做 |
| §14.4 组件选型对照表 | Codex 独有 | 能力域→当前→目标→理由对照表 | ✅ 纳入，但替换 Jaeger→Tempo、Prom→VM |
| §1.3 优化原则 A/B/C/D/E 模块分类 | Codex 独有 | 基础指标交 Grafana、AI 模型+语义自研主战场、平台自观测 MVP 不做 | ✅ 纳入 — 清晰的模块边界划分 |
| §9.6 洞察缓存策略 | Codex 独有 | Redis / DB / 前端三层缓存 | ✅ 纳入 |
| OpenLLMetry 提议 | Codex 独有 | P1 可选补齐 gen_ai.* 语义 | ✅ 纳入为 P1 可选项 |

---

## 2. 全局差异总结

### 两份文档的根本设计哲学差异

| 维度 | WorkBuddy V2 | Codex V2 |
|---|---|---|
| **核心思路** | **自上而下**：先定"最优组件组合"（Tempo/VM/Alloy/Grafana 五件套），再往下拆实现 | **自下而上**：从现有代码出发，先在现有架构上补全 GAP（埋点/洞察/告警），再渐进引入标准栈 |
| **P0 策略** | 不推新组件，全在现有架构上修 Core 发射层（埋点补齐） | Phase 0 就引入 Jaeger/Loki/Grafana，同步埋点+搭建 |
| **存储迁移** | H2 → PostgreSQL（P2 明确） | 未提 PG（仅组件选型表列 Jaeger） |
| **AI 洞察** | 6 Service 各管一个 TAB，前端驱动 | 单一 InsightsEngine + API 统一，后端驱动 |
| **告警** | 倾向 Grafana Alerting（P1） | 自研完整告警系统（P0 落地） |
| **文档风格** | 全景架构 + 踩坑清单 + 设计阐述 | 代码片段 + 伪代码 + 表驱动 + 工时精确 |

### 合并后的最优方案原则

1. **组件选型**：WorkBuddy 的 Tempo+VM+Alloy+Grafana 五件套更优
2. **分阶段策略**：WorkBuddy 的"P0 不引新组件"更务实
3. **实现细节**：Codex 的代码级设计更可落地（埋点/Redis/告警/根因分析）
4. **AI 洞察引擎**：Codex InsightsEngine 架构优于 WorkBuddy 的 6 Service 分散式
5. **UI 布局**：WorkBuddy V20 的智能洞察独立末位 TAB + 诊断驾驶舱更聚焦
6. **DB 表**：WorkBuddy 准确识别 agent_performance/token_cost 已弃用

---

> 下一份文档《可观测优化总结-0711-WorkBuddy V3.md》将根据本差异分析，合并两份文档的优点，形成权威的可观测系统设计方案。
