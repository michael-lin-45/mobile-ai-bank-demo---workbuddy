# 可观测设计方案差异分析（V3 版本比较）

> WorkBuddy V3 (`docs/可观测优化总结-0711-WorkBuddy V3.md`) vs Codex V3 (`docs/specs/可观测优化总结-0711-Codex-V3.md`)
> 两份文档均为 V2→V2 差异分析后的"融合版"产物。本节对比二者在融合过程中**保留了哪些分歧**，给出优劣判断与 V4 合并建议。

---

## 0. 版本背景

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| 融合来源 | WorkBuddy V2 + Codex V2（差异分析驱动） | Codex V2 + WorkBuddy V2（自述"取两份文档之长"） |
| 组件选型 | Tempo+VM+Loki+Alloy+Grafana 五件套 | Tempo+VM+Loki+Grafana+OTel Collector 增强 |
| UI 设计稿 | V20 (`可观测DEMO-v20-WorkBuddy.html`) | V18 (`可观测DEMO-v18-Codex.html`) |
| 文档结构 | 14 章（含 §0 使用指南） | 15 章（含目录） |
| 字数 | ~20,000 字 | ~35,000 字 |

> 两份文档在绝大多数模块上高度一致（因为都已经融合了对方 V2 的优点）。以下仅列出**仍存在实质分歧**的模块。

---

## 1. 逐模块差异分析

### 1.1 AI 洞察引擎架构（核心分歧）

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **架构** | **InsightsEngineService 单一引擎**（§9.2 明确"取代分散式 6 Service"） | **AIInsightsService 族（6 Service）+ InsightsEngineService 双层架构**（§9.2 6 Service 负责指标聚合，§9.3 InsightsEngine 负责交叉分析） |
| **设计理由** | 单一引擎避免每个 TAB 各自重复查询 H2；统一缓存策略 | 6 Service 对齐 spec 已定义的 6 个 TAB；InsightsEngine 是"在 6 Service 之上的交叉引擎" |
| **代码示例** | 一个 `InsightsEngineService` 包含 `analyzePerformance()/analyzeQuality()/analyzeConversion()` | 6 个 Service 各自实现 + InsightsEngine 调用它们 |
| **API 端点** | 7 个端点（report / bottlenecks / root-cause / unsatisfied / conversion / actions / refresh） | 4 个端点（report / slow-sessions / churn-analysis / token-roi） |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **单一引擎 vs 双层** | **Codex V3 更稳健，WorkBuddy V3 更简洁** | 6 Service 族已存在且经过重构验证（AgentPerformance/TokenCost 已从空静态表改为实时聚合），强行合并为单一引擎需要改 API 契约，风险高、收益有限。但 WorkBuddy 的方向正确——最终目标应统一。 | **P0 保留双层**：6 Service 维持现有接口不变，InsightsEngine 作为交叉引擎在之上调用它们。**P2 可评估合并**，但 P0 不动 6 Service 可降低回归风险。 |
| **API 端点粒度** | **WorkBuddy 更工程化** | 7 个端点覆盖完整诊断需求；Codex 的 4 个端点缺 /insights/bottlenecks（最常用的性能诊断入口）和 /insights/actions | **采纳 WorkBuddy V3 的 7 端点设计** |

---

### 1.2 AI 洞察 TAB 数量与 UI 布局

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **AI 洞察页 TAB 数** | **6 TAB**（准确率 / 性能 / Agent / 漏斗 / Token / 满意度） | **7 TAB**（+ 智能诊断 TAB） |
| **智能洞察独立页** | ✓ 独立末位第 8 页，含诊断驾驶舱 + 3×3 网格 | ✓ 独立末位第 8 页，含诊断驾驶舱 + 9 条洞察 3×3 网格 |
| **右栏 sidebar** | 无 | ✓ AI 洞察页右侧 320px 洞察面板（当前 TAB 关联洞察 + 严重度标签 + 下钻链接） |
| **设计稿版本** | V20（取长补短：V19 框架 + Codex 诊断深度） | V18（融合 V17 深度 + V19 健康洞察） |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **6 TAB vs 7 TAB** | **WorkBuddy V3 更合理** | 7 个 TAB 已经超过用户认知负荷上限（建议 5-7 个）。且"智能诊断"既有独立页（第 8 页）又有页内 TAB（第 7 TAB），用户会困惑："两个智能洞察入口有什么区别？" | **采纳 WorkBuddy V3**：6 TAB + 独立智能洞察页，去掉 AI 洞察页内的"智能诊断"TAB（其内容已由独立页的诊断驾驶舱完整覆盖） |
| **右栏 sidebar** | **Codex 的右栏方案有价值但非必须** | 320px 右栏展示"当前 TAB 关联洞察 + 严重度标签 + 下钻链接"可增强上下文导航，但 UI 复杂度增加。 | **P2 可选纳入**：如用户反馈 6 TAB 之间关联不足，可加右栏；P0 暂不加（简洁优先） |
| **设计稿版本** | **WorkBuddy V20 > Codex V18** | V20 是 V19→V17-Codex 取长补短产物，已在 Playwright 验证通过（8 页 + 6 TAB 全部渲染，console 零错误）；V18 未经验证 | **采纳 V20 设计稿** |

---

### 1.3 Redis 在线用户实现

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **数据结构** | ZSet (`ZADD obs:online:users timestamp userId` + 手动清理 5 分钟前记录) | SET (`SET obs:metrics:online:{userId} 1 EX 300`) |
| **优势** | 可一次性获取在线总数 (`ZCARD`) + 活跃时间段分析 | 利用 Redis TTL 自动清理，实现简单 |
| **劣势** | 需手动清理，Redis key 固定大小 | 每个在线用户独立 Key，高并发时 Key 数量大；无法直接统计总数（需全局 SCAN） |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **ZSet vs SET** | **WorkBuddy ZSet 更优** | ZSet 一条命令 (`ZCOUNT key now-5min now`) 取在线数，还可做时间段分析；SET 方案需遍历所有 `obs:metrics:online:*` key 做 SCAN（生产级灾难）。额外内存开销 ZSet 可忽略（每用户一个 member+score） | **采纳 WorkBuddy ZSet 方案** |

---

### 1.4 Redis 所有权原则

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **所有权原则** | 未单独提出 | §8.1 明确："每个 Key 唯一写入方，读方可多对一" |
| **实用价值** | — | 防止多写方冲突（如两个 Service 同时 INCR 同一窗口），是 Redis 设计的工程铁律 |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **所有权原则** | **Codex 独有价值的工程规范** | 多人协作时最易踩的坑就是"谁在写这个 Key"；明确写入方可避免"DAU 两个地方写导致不准确"等暗病 | **新增纳入 V4** |

---

### 1.5 告警求值频率与抑制

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **求值频率** | `@Scheduled(fixedRate = 30_000)` — 每 30s | `@Scheduled(fixedRate = 60000)` — 每 60s |
| **告警抑制** | **有**：5 分钟内同规则不重复通知 | **无**：未提及抑制机制 |
| **P2 演进** | P2 迁 Grafana Alerting | P1 迁 Grafana Alerting |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **求值频率** | **30s 更合理** | AI 系统 P95 时延波动可在 30s 内触发，1 分钟太慢；且 30s 对 H2/Redis 查询负担可接受 | **采纳 30s** |
| **告警抑制** | **WorkBuddy 独有价值的保护机制** | 无抑制 = 异常波动直接告警风暴（如 LLM 间歇性超时，每分钟一条钉钉） | **新增纳入 V4** |
| **P2 迁 Grafana 时机** | **WorkBuddy P2 更合理** | P1 刚引入 Grafana，要同时迁告警（配置迁移+测试+通知通道切换）风险高；P2 等 Grafana 稳定后再迁 | **采纳 P2 迁移** |

---

### 1.6 实施路径粒度

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **阶段粒度** | Phase 0/1/2（3 个 phase，按信号类型拆任务） | 四阶段 P0-P3 + P0 详细任务 12 项（精确到文件+工时） |
| **并行策略** | 无显式并行 | 明确 P2 标准栈可与 P0 并行起步，整体节省 1-2 周 |
| **任务可执行性** | 按信号类型拆，描述级 | 按文件拆，代码级（如"ChatClientWrapper.java 2h"） |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **任务粒度** | **Codex 更可排期** | 精确到文件+工时可让 PM 直接排 Sprint；WorkBuddy 按信号拆适合理解但不适合排期 | **采纳 Codex P0 详细任务表 + 并行策略** |
| **里程碑** | WorkBuddy 有 3 Phase 里程碑 | Codex 有 5 个里程碑（M0-M4） | **采纳 Codex 5 里程碑** |

---

### 1.7 UI 刷新策略

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **设计刷新频率** | 未详述 | §11.6 详细：总览 3s / AI 洞察 10s / 智能洞察 5min 缓存 / 告警 WebSocket |
| **实用价值** | — | 前后端约定的轮询频率直接影响 Redis 查询 QPS 设计 |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **刷新策略** | **Codex 独有价值的工程约定** | 前端轮询频率是 Redis/H2 查询 QPS 设计的输入参数，没有这个约定后端不知道缓存 TTL 该设多少 | **新增纳入 V4** |

---

### 1.8 告警状态机与闭环

| 维度 | WorkBuddy V3 | Codex V3 |
|---|---|---|
| **状态机** | TRIGGERED → ACKED → RESOLVED（含 SUPPRESSED） | 未提及显式状态机，仅描述触发/恢复 |
| **闭环流程** | Mermaid 流程图 | 文字描述 |
| **告警事件字段** | notified_channels / notify_result / suppression_count | 未详述 |

| 核心差异点 | 优劣判断 | 理由 | V4 合并建议 |
|---|---|---|---|
| **状态机完整度** | **WorkBuddy 更完整** | SUPPRESSED 态避免了"异常波动 = 每分钟一条钉钉"的告警风暴；ACKED 态让运维可以确认已看到告警 | **采纳 WorkBuddy 告警状态机** |

---

## 2. 全局分歧总结

### 两份 V3 文档的核心分歧仅 8 处

| # | 分歧点 | WorkBuddy V3 | Codex V3 | V4 采纳 |
|---|---|---|---|---|
| 1 | AI 洞察引擎架构 | 单一 InsightsEngine | 6 Service + InsightsEngine 双层 | **Codex 双层**（P0 保留现有 6 Service 接口，InsightsEngine 在之上） |
| 2 | AI 洞察 TAB 数 | 6 TAB | 7 TAB（多智能诊断 TAB） | **WorkBuddy 6 TAB**（消除冗余） |
| 3 | 右栏 sidebar | 无 | 320px 右栏 | P2 可选，P0 不加 |
| 4 | Redis 在线实现 | ZSet | SET per-user | **WorkBuddy ZSet** |
| 5 | Redis 所有权原则 | 无 | 明确定义 | **新增 Codex 章** |
| 6 | 告警求值频率 | 30s | 60s | **30s** |
| 7 | 告警抑制 | 有（5min） | 无 | **加入抑制** |
| 8 | 告警迁 Grafana 时机 | P2 | P1 | **P2** |

### 共识模块（以下模块两份 V3 高度一致，无需进一步合并）

- 组件选型（Tempo+VM+Loki+Grafana 已统一）
- 目标架构（前后对照 Mermaid 图已统一）
- 5 信号数据流设计（Trace/Metrics/Logs/Session/Alerts 基本统一）
- Core 埋点设计（6 处 P0 埋点已统一）
- 指标全表 + 维度规范 + 基数预算（已统一）
- DB 表设计（已校准：弃用 agent_performance/token_cost，新增 insight_reports）
- Collector 配置（完整 config.yaml 已统一）
- 关键坑清单（已统一）
- DAU HyperLogLog（已统一）

---

> V4 将基于本差异分析，取两份 V3 文档的 8 处分歧中的最优方案，输出《可观测优化总结-0711-WorkBuddy-V4-基于V3生成.md》。
