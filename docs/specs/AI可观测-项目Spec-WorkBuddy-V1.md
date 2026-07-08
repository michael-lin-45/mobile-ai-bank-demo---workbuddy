# AI可观测系统 — 项目需求规格书（PRD）

> 版本：V1 | 日期：2025-07-10 | 项目：Mobile AI Bank 可观测系统
> 目标读者：开发团队、产品团队、算法团队

---

## 目录

1. [产品目标](#1-产品目标)
2. [用户故事](#2-用户故事)
3. [现有功能与目标差距分析](#3-现有功能与目标差距分析)
4. [需求池（P0/P1/P2）](#4-需求池p0p1p2)
5. [后端数据模型缺口](#5-后端数据模型缺口)
6. [技术决策与设计原则](#6-技术决策与设计原则)
    - [6.1 A/B/C/D/E 模块分类与去留决策](#61-abcde-模块分类与去留决策)
7. [待确认问题](#7-待确认问题)
8. [附录：API 现状一览](#8-附录api-现状一览)
9. [参考文档](#9-参考文档)

---

## 1. 产品目标

### 1.1 要解决什么问题

手机银行AI助手系统已具备 **L0 → L1 → L2 三级智能体架构**（Spring Boot 3.5.5 + Spring AI Alibaba Graph + DashScope qwen-plus），但当前处于"黑盒运行"状态：开发不知道链路卡在哪，算法不知道改写准确率如何，产品不知道业务转化是否达预期。本可观测系统要**把黑盒变成透明**，为四个核心角色提供统一的可观测仪表盘。

### 1.2 核心目标

| # | 目标 | 衡量标准 |
|---|------|---------|
| G1 | **全链路透明化** | 任意一次用户请求都能从 L0 → L1 → L2 贯穿查看，包含输入/输出/LLM调用/耗时 |
| G2 | **实时健康感知** | 3秒内获取系统健康快照：QPS、时延、错误率、Token消耗、在线用户数 |
| G3 | **AI质量可度量** | 意图识别准确率、改写准确率、Reroute率、业务完成率、TTFT P95 等指标可量化追踪 |
| G4 | **成本可审计** | 按模型、按意图、按Agent层级的Token消耗可视化，支撑成本优化决策 |
| G5 | **问题可定位** | 从总览KPI → 会话回放 → 链路追踪 → 日志查询，4级下钻，5分钟内定位根因 |

### 1.3 目标用户

| 角色 | 核心需求 | 使用页面 |
|------|---------|---------|
| **开发工程师** | 链路卡在哪？哪个Span慢？LLM调用了几次？ | 链路追踪、日志查询 |
| **算法工程师** | 意图命中率？改写准确率？混淆矩阵？TTFT分布？ | AI洞察（准确率分析、Agent性能） |
| **产品经理** | 用户说了什么？业务转化率？满意度？ | 会话回放、AI洞察（转化漏斗、满意度） |
| **运维/SRE** | 系统挂了没？QPS多高？告警触发了吗？ | 总览大屏、告警规则、系统设置 |

---

## 2. 用户故事

### 开发工程师

| ID | 故事 | 验收标准 |
|----|------|---------|
| US-DEV-01 | 作为开发，我想在总览大屏一眼看到系统是否健康（QPS、错误率、P95时延），以便快速判断是否需要介入 | 总览大屏4区KPI网格 + LIVE实时刷新 |
| US-DEV-02 | 作为开发，我想点击某次异常Trace查看完整的L0→L1→L2调用链和每层的输入输出，以便定位问题根因 | Trace详情Modal：输入/输出双色卡片 + Agent层级树 + Span瀑布图 |
| US-DEV-03 | 作为开发，我想按TraceID/关键词/级别检索日志，并从日志直接跳转到对应Trace，以便快速追踪 | 日志查询：多条件筛选 + TraceID可点击跳转 |
| US-DEV-04 | 作为开发，我想在Span瀑布图中看到每个Agent/LLM的耗时分布，以便识别性能瓶颈 | Span瀑布图：按Agent角色/LLM模型维度的横向条形图 + TTFT虚线标记 |

### 算法工程师

| ID | 故事 | 验收标准 |
|----|------|---------|
| US-ALGO-01 | 作为算法，我想看到意图识别准确率和混淆矩阵，以便了解模型在哪些意图上容易混淆 | AI洞察→准确率分析TAB：5条折线 + 混淆矩阵热力图 |
| US-ALGO-02 | 作为算法，我想看到改写失败根因TOP3和改写前后对比，以便优化改写Prompt | AI洞察→准确率分析TAB：改写根因TOP3卡片 + 改写准确率分析表 |
| US-ALGO-03 | 作为算法，我想按Agent和LLM维度查看TTFT/TPOT的分布（箱线图+散点图），以便评估模型选型效果 | AI洞察→Agent性能TAB：双维度子页签 + 箱线图 + 六色散点图 |

### 产品经理

| ID | 故事 | 验收标准 |
|----|------|---------|
| US-PM-01 | 作为产品，我想回放用户的完整对话历程（对话气泡形式），了解用户真实体验，以便发现产品问题 | 会话回放Modal：对话气泡 + Turn详情 + 意图流 |
| US-PM-02 | 作为产品，我想看到业务转化漏斗和分阶段放弃率，以便优化对话流程 | AI洞察→业务转化漏斗TAB：漏斗图 + 放弃率环形图 + 明细表 |
| US-PM-03 | 作为产品，我想看到用户满意度和不满意原因分布，以便推动体验改进 | AI洞察→用户满意度TAB：满意度分布 + 趋势 + 不满意原因 + 低分会话列表 |

### 运维/SRE

| ID | 故事 | 验收标准 |
|----|------|---------|
| US-OPS-01 | 作为运维，我想配置告警规则（阈值+通知方式）并查看告警处理闭环时间线 | 告警规则页：双栏（规则表 + 闭环时间线） |
| US-OPS-02 | 作为运维，我想查看OTel Collector状态、调整采样率和存储配置 | 系统设置页：双栏（采集配置 + 存储后端） |

---

## 3. 现有功能与目标差距分析

### 3.1 总览大屏（Overview）

| 维度 | 已有（DashboardPage.jsx） | 目标（dashboard-v15.html） | 差距 |
|------|--------------------------|---------------------------|------|
| 主题 | 暗色 `#0f141e` | 亮色 Ant Design 5 `#f0f2f5` | **主题切换**：需全部重写为亮色主题 |
| KPI布局 | 简单两行网格（6+3），无分区 | 4区11卡：系统健康(3)/AI性能(3)/语义质量(3)/业务效果(2) | **完全重构**：卡片数量、分区、副指标均不同 |
| KPI字段 | requestCount/errorCount/avgLatency/tokenInput/tokenOutput/activeSessions/p50/p95 | DAU/实时在线/QPS/L0L1L2分布/TTFT P95/P95时延/改写准确率/Reroute率/业务完成率/业务转化率/违规率（占位）/转人工率（占位） | **后端数据模型需大幅扩充**（详见第5节） |
| Mini Spark | 无 | 每卡32px高折线图（10点平滑+渐变面积） | **新增**：需ECharts `miniSpark()` 函数 |
| 趋势图 | HTML div模拟柱状图（随机高度） | ECharts柱状+折线双轴（请求量+Token），6小时 | **替换**：需接入`/metrics/history` API |
| Agent分布 | 水平进度条（intent分布） | ECharts环形饼图 L0/L1/L2 带数值+百分比标注 | **替换**：需按Agent层级而非意图分组 |

### 3.2 会话回放（Session Replay）

| 维度 | 已有（SessionViewerPage.jsx） | 目标（dashboard-v15.html） | 差距 |
|------|------------------------------|---------------------------|------|
| 数据源 | 硬编码 mockSessions（5条） | 后端 API | **新增API**：需 `GET /api/v1/sessions` 列表和详情接口 |
| 筛选栏 | 无 | SessionID/UserID/渠道/意图/智能体/状态 6个筛选器 | **新增**：筛选栏UI + 后端参数支持 |
| 列表列 | 会话ID/用户ID/轮次/首意图/最终意图/时长/状态/操作 | 以上 + 渠道/时间/执行智能体/Token/状态dot+中文 | **增强**：新增列 + 状态格式改为 🟢正常 |
| 详情展示 | 右侧Drawer + Timeline + Table | 全屏Modal + 对话气泡(蓝/绿) + Turn详情 + 意图流 + Token统计 | **重构**：Drawer→Modal，Table→对话气泡 |
| Token显示 | 无 | 每轮Token + 总结条Token | **新增** |

### 3.3 链路追踪（Trace Explorer）

| 维度 | 已有（TraceExplorerPage.jsx） | 目标（dashboard-v15.html） | 差距 |
|------|------------------------------|---------------------------|------|
| 布局 | 两栏内联（列表+详情面板） | 列表独占 → 点击弹出全屏Modal | **重构**：内联→Modal模式 |
| 列表列 | 时间/TraceID/用户/会话/意图/Agent链/LLM调用/耗时/状态 | 以上 + SessionID/TTFT/Token/状态dot+中文 | **增强**：新增TTFT/Token列 |
| 详情-输入输出 | 无独立IO卡片 | 蓝底蓝边(输入) → 白底箭头 → 绿底绿边(输出) 三区卡片 | **新增**：IO卡片组件 |
| 详情-Agent树 | SpanTree组件（递归，层级颜色，IO展开） | 目标类似，但需校正展示格式（tag-l0 badge + 描述文字 + LLM调用详情 + 改写对比） | **增强**：SpanTree需重构为Agent层级架构格式（含L1双LLM结构） |
| 详情-Span瀑布图 | 无 | 横向柱状图：HTTP入口→L0→L1-LLM1→L1-LLM2→L2，TTFT虚线标记，颜色编码 | **新增**：完整瀑布图组件 |
| 意图列等宽 | 无 | `min-width:82px` 统宽 | **样式优化** |

### 3.4 AI洞察（AI Insights）

| 维度 | 已有（AIInsightsPage.jsx） | 目标（dashboard-v15.html） | 差距 |
|------|---------------------------|---------------------------|------|
| TAB结构 | 无TAB，单页滚动 | 6个TAB：准确率/Agent性能/Token成本/智能体工具/转化漏斗/满意度 | **重构**：需TAB导航 + 子页签 |
| 准确率分析 | 置信度分布柱状图 + 参数提取完整率卡片 + 改写准确率卡片（部分API对接） | 5条折线(意图准确率趋势) + 改写分析表 + 根因TOP3卡片 + 混淆矩阵热力图 | **大幅增强**：后端需提供趋势数据和混淆矩阵 |
| Agent性能 | 硬编码时延表（L0/L1/L2 P50/P95） | 双维度子页签(Agent/LLM) + KPI卡片×4 + 性能明细表 + 箱线图 + 六色散点图 | **全新构建**：需后端提供TTFT/TPOT分布数据 |
| Token成本 | 无 | 堆叠柱状图(按模型) + 饼图(按意图) + 明细表 | **全新构建**：需后端提供Token统计API |
| 智能体工具 | 无 | 工具调用统计 + Skill业务效果统计（Skill P0阶段隐藏） | **全新构建**：需后端提供工具调用和Skill数据（Skill P1实现） |
| 转化漏斗 | 硬编码漏斗图（ECharts funnel） | ECharts漏斗 + 放弃率环形图 + 明细表 | **增强**：需接入真实数据 |
| 用户满意度 | 硬编码满意度横向柱状图 + 差评原因表 | 满意度分布(三栏进度条) + 7天趋势折线 + 不满意原因饼图 + 低分会话列表 | **增强**：接入真实数据（P0 实现前端 👍/👎 反馈按钮 + 后端满意度 API） |

### 3.5 日志查询（Log Viewer）

| 维度 | 已有（LogViewerPage.jsx） | 目标（dashboard-v15.html） | 差距 |
|------|--------------------------|---------------------------|------|
| 数据源 | 后端API `/api/v1/logs` ✅ | 同左 | **已完成** |
| 筛选栏 | 级别(全部/INFO/WARN/ERROR) + 关键词搜索 + TraceID搜索 | 增加 UserID/SessionID | **小增强** |
| 表格 | 时间/级别(彩色Tag)/服务/消息/TraceID(可点击) ✅ | 同左 + 彩色级别Badge | **基本完成** |
| 分页 | ✅ | ✅ | **已完成** |

### 3.6 告警规则（Alert Rules）

| 维度 | 已有 | 目标（dashboard-v15.html） | 差距 |
|------|------|---------------------------|------|
| 页面 | 无（Sidebar无入口） | 双栏：左-告警规则表 + 右-告警闭环时间线 | **全新构建**：前端页面 + 后端CRUD API |
| 后端 | 无 | 需新建 alert_rules/alert_events 表 + REST API | **全新构建** |

### 3.7 系统设置（System Settings）

| 维度 | 已有 | 目标（dashboard-v15.html） | 差距 |
|------|------|---------------------------|------|
| 页面 | 无（Sidebar无入口） | 双栏：左-采集配置 + 右-存储后端 | **全新构建**：前端页面 + 后端配置API |
| 后端 | 无 | 需配置读写的REST API | **全新构建** |

---

## 4. 需求池（P0/P1/P2）

### P0 — 第一期（必须完成，覆盖7个页面核心功能）

> P0的标准：7个页面全部可用、数据从真实API获取（非Mock）、核心KPI可展示。

> 以下三类功能 P0 阶段以占位方式呈现在页面上，数据标注"暂无"：
> - 违规率（安全合规模块未上线）
> - 转人工率（核心系统暂无转人工入口）
> - Skill 调用统计（skill 模块暂未涉及，P1 实现）

#### 4.1 总览大屏重构

| # | 需求 | 说明 |
|---|------|------|
| P0-01 | 亮色主题迁移 | 全局从暗色 `#0f141e` → 亮色 Ant Design 5 `#f0f2f5`，Sidebar保留深色 |
| P0-02 | 4区11卡KPI网格 | Zone A(系统健康)×3 + Zone B(AI性能)×3 + Zone C(语义质量)×3 + Zone D(业务效果)×2 |
| P0-03 | KPI卡片组件 | 主数值28px + 环比变化 + 副指标12px + Mini Spark折线图 |
| P0-04 | 请求&Token趋势图 | ECharts柱状+折线双轴图，接入 `/api/v1/metrics/history` |
| P0-05 | Agent分布饼图 | ECharts环形图，L0/L1/L2分布，标注格式 `{b}\n{c}次 ({d}%)` |
| P0-06 | 后端扩充 RealtimeMetricsVO | 新增字段见第5节"P0必须字段" |

#### 4.2 会话回放

| # | 需求 | 说明 |
|---|------|------|
| P0-07 | 会话列表API | 后端新增 `GET /api/v1/sessions`，支持筛选参数 |
| P0-08 | 会话详情API | 后端新增 `GET /api/v1/sessions/{sessionId}`，含timeline |
| P0-09 | 会话列表页（筛选+表格） | 6个筛选器 + 包含渠道/Token/智能体列的表格 |
| P0-10 | 会话回放Modal | 对话气泡(蓝用户/绿AI) + Turn详情 + 意图流 + Token总结条 |

#### 4.3 链路追踪

| # | 需求 | 说明 |
|---|------|------|
| P0-11 | Trace列表→Modal重构 | 内联两栏 → 列表独占 + 点击弹出全屏Modal |
| P0-12 | 输入/输出双色卡片 | 蓝底蓝边(输入)→白底箭头→绿底绿边(输出)三区卡片 |
| P0-13 | Agent层级树重构 | 按L0/L1双LLM/L2架构展示，含改写对比，tag-l0/l1/l2 badge |
| P0-14 | Span瀑布图 | 横向柱状图：HTTP→L0→L1-LLM1→L1-LLM2→L2，TTFT虚线标记，颜色编码 |
| P0-15 | 列表列增强 | 新增TTFT、Token列，状态改为dot+中文 |

#### 4.4 AI洞察

| # | 需求 | 说明 |
|---|------|------|
| P0-16 | TAB导航框架 | 6个TAB：准确率分析/Agent性能/Token成本/智能体工具/业务转化漏斗/用户满意度 |
| P0-17 | 准确率分析TAB | 5条折线(意图准确率趋势) + 改写根因TOP3 + 混淆矩阵热力图 |
| P0-18 | Agent性能TAB | 双维度子页签(Agent/LLM) + KPI卡片 + 性能明细表 + 箱线图 + 六色散点图 |
| P0-19 | Token成本TAB | 堆叠柱状图(按模型) + 饼图(按意图) + 明细表 |
| P0-20 | 智能体工具TAB | 工具调用统计（Skill业务效果 → P1，P0阶段页面隐藏） |
| P0-21 | 业务转化漏斗TAB | 漏斗图 + 放弃率环形图 + 明细表 |
| P0-22 | 用户满意度TAB | P0 实现：前端反馈按钮 + 后端满意度 API。满意度分布 + 趋势 + 不满意原因 + 低分会话列表（依赖反馈入口就位后展示真实数据） |

#### 4.5 日志查询

| # | 需求 | 说明 |
|---|------|------|
| P0-23 | 筛选栏增强 | 新增 UserID、SessionID 筛选字段 |
| P0-24 | UI对齐目标 | 彩色级别Badge + 亮色主题适配 |

#### 4.6 告警规则（全新）

| # | 需求 | 说明 |
|---|------|------|
| P0-25 | 告警规则后端 | 新建 `alert_rules` 表 + CRUD API |
| P0-26 | 告警事件后端 | 新建 `alert_events` 表 + 查询API（时间线） |
| P0-27 | 告警规则页 | 双栏：左-规则表(名称/阈值/状态/通知方式) + 右-闭环时间线 |

#### 4.7 系统设置（全新）

| # | 需求 | 说明 |
|---|------|------|
| P0-28 | 系统设置后端 | 配置读写的REST API（可用内存配置 + 预设值组合） |
| P0-29 | 系统设置页 | 双栏：左-采集配置(Collector状态/采样率/Tag策略/Prompt存储) + 右-存储后端 |

#### 4.8 全局工程

| # | 需求 | 说明 |
|---|------|------|
| P0-30 | 前端路由完善 | Sidebar增加"告警规则"和"系统设置"两个导航项 |
| P0-31 | 亮色主题全局应用 | 所有页面统一亮色Ant Design 5视觉体系（参考UI-DESIGN-V3.md第13节） |

### P1 — 第二期（增强体验和智能化）

| # | 需求 | 说明 |
|---|------|------|
| P1-01 | Mini Spark实时趋势 | 每张KPI卡接入30分钟Mini折线图数据 |
| P1-02 | 会话回放筛选增强 | 支持渠道/智能体/状态多选 + 时间范围筛选 |
| P1-03 | Trace链路对比 | 选中两条Trace并排对比Span耗时差异 |
| P1-04 | AI洞察时间范围选择器 | 各TAB支持自定义时间范围（当前默认24h） |
| P1-05 | L0→L1→L2拓扑图 | 总览大屏增加Agent调用拓扑关系图（力导向图） |
| P1-06 | 告警通知集成 | 对接邮件/钉钉/飞书通知渠道 |
| P1-07 | 数据导出 | 支持图表PNG导出 + 表格CSV导出 |
| P1-08 | 用户行为分析 | 按UserID聚合的访问频率/常用功能/异常率画像 |

### P2 — 第三期（平台化和高级功能）

| # | 需求 | 说明 |
|---|------|------|
| P2-01 | 自定义仪表盘 | 用户可拖拽KPI卡片自定义布局 |
| P2-02 | Prompt版本管理 | 对比不同Prompt版本的准确率/Token消耗A/B Test |
| P2-03 | 异常检测 | 基于历史基线的自动异常检测（时延突增/错误率飙升） |
| P2-04 | 多租户/多应用 | 支持接入多个Bank AI应用的观测数据 |
| P2-05 | Grafana数据源 | 暴露Prometheus metrics endpoint供Grafana消费 |
| P2-06 | 历史数据归档 | H2 → PostgreSQL迁移 + 自动分区归档策略 |

---

## 5. 后端数据模型缺口

### 5.1 RealtimeMetricsVO 差距

当前 `RealtimeMetricsVO` 仅9个字段，远不足以支撑总览大屏11张KPI卡片：

```
当前字段（9个）：
  requestCount, errorCount, avgLatency, tokenInput, tokenOutput,
  intentDistribution, activeSessions, p50Latency, p95Latency

目标需要字段（~20个）：
```

| 区域 | 指标 | 当前状态 | P0必需 |
|------|------|---------|--------|
| Zone A 系统健康 | 访问用户量 DAU | ❌ 缺失 | ✅ |
| Zone A 系统健康 | 实时在线用户数 | ❌ 缺失 | ✅ |
| Zone A 系统健康 | QPS (req/s) | ❌ 缺失（requestCount是累计值） | ✅ |
| Zone A 系统健康 | Agent调用 L0/L1/L2 分布 | ❌ 缺失（仅有intentDistribution） | ✅ |
| Zone A 系统健康 | 访问次数累计 | ❌ 缺失 | ✅ |
| Zone B AI性能 | Token调用总量 | ✅ (tokenInput+tokenOutput 但缺少总量字段) | ✅ |
| Zone B AI性能 | Token输入量 | ✅ tokenInput | — |
| Zone B AI性能 | Token输出量 | ✅ tokenOutput | — |
| Zone B AI性能 | TTFT P50/P95/P99 | ❌ 缺失（当前p50/p95是系统时延，非TTFT） | ✅ |
| Zone B AI性能 | P95系统时延 | ✅ p95Latency | — |
| Zone B AI性能 | 错误率 | ✅ errorCount可计算 | — |
| Zone C 语义质量 | 意图识别准确率 | ❌ 缺失 | ✅ |
| Zone C 语义质量 | 改写准确率 | ❌ 缺失 | ✅ |
| Zone C 语义质量 | Reroute率 | ❌ 缺失 | ✅ |
| Zone C 语义质量 | 业务完成率 | ❌ 缺失 | ✅ |
| Zone D 业务效果 | 业务转化率 | ❌ 缺失 | ✅ |
| Zone D 业务效果 | 违规率 | ❌ 缺失（占位，数据标注"暂无"） | ✅ |
| Zone D 业务效果 | 转人工率 | ❌ 缺失（占位，数据标注"暂无"，核心系统暂无转人工入口） | ✅ |

### 5.2 需要新建的后端 API

| API路径 | 方法 | 用途 | 优先级 |
|---------|------|------|--------|
| `/api/v1/sessions` | GET | 会话列表（支持筛选） | P0 |
| `/api/v1/sessions/{sessionId}` | GET | 会话详情（含timeline/turns） | P0 |
| `/api/v1/ai/agent-performance` | GET | Agent/LLM维度性能数据（TTFT/TPOT分布） | P0 |
| `/api/v1/ai/token-cost` | GET | Token成本拆解（按模型/意图/Agent） | P0 |
| `/api/v1/ai/tool-stats` | GET | 工具调用统计 | P0 |
| `/api/v1/ai/skill-stats` | GET | Skill业务效果统计 | P1（暂不实现，页面隐藏） |
| `/api/v1/ai/conversion-funnel` | GET | 业务转化漏斗数据 | P0 |
| `/api/v1/ai/satisfaction` | GET/POST | 用户满意度数据（GET查询 / POST提交反馈） | P0 |
| `/api/v1/ai/intent-accuracy-trend` | GET | 意图准确率趋势（5条折线） | P0 |
| `/api/v1/ai/confusion-matrix` | GET | 意图混淆矩阵 | P0 |
| `/api/v1/alerts/rules` | GET/POST/PUT/DELETE | 告警规则CRUD | P0 |
| `/api/v1/alerts/events` | GET | 告警事件时间线 | P0 |
| `/api/v1/settings/collection` | GET/PUT | 采集配置 | P0 |
| `/api/v1/settings/storage` | GET/PUT | 存储后端配置 | P0 |

### 5.3 需要延伸的现有 API

| API | 当前参数 | 需增加参数 | 优先级 |
|-----|---------|-----------|--------|
| `GET /api/v1/metrics/realtime` | 无 | 无（响应体需扩展字段） | P0 |
| `GET /api/v1/traces` | from, intent, limit | sessionId, userId, statusCode | P1 |
| `GET /api/v1/logs` | from, to, level, traceId, q, page, size | userId, sessionId | P0 |

---

## 6. 技术决策与设计原则

> 以下原则来自 UI-DESIGN-V3.md 第16节"设计决策记录"，PRD中重申以指导开发。

| # | 决策 | 原因 |
|---|------|------|
| D1 | Token不缩写 | 全文使用 `tokens`，非 `tok`。OpenAI/Anthropic/LangSmith等行业工具均用全写 |
| D2 | Agent描述不带 `/` 分隔符 | `L1领域路由` 而非 `业务总入口 / L1路由`，避免误解为两个独立概念 |
| D3 | 状态用 dot + 中文 | `🟢 正常` 而非 `OK`，降低非技术用户认知门槛 |
| D4 | 散点图多series > visualMap | 固定颜色映射实现"看色识Agent"，避免渐变色无法区分相近类别 |
| D5 | Trace详情用Modal | 替代内联两栏布局，避免overflow/滚动条冲突 |
| D6 | Agent分布不含Tool | Tool属于工具调用统计层，不与Agent层级(L0/L1/L2)混杂 |
| D7 | 执行智能体用 `→` 分隔 | `WealthConsult → TransferService` 表达调用链方向，逗号暗示并列 |
| D8 | 链路详情先逻辑后时间 | Agent层级树(谁调了谁)在上，Span瀑布图(花了多久)在下 |
| D9 | 漏斗+Skill互补 | 漏斗是横向流程视角（丢在哪一步），Skill是纵向领域视角（丢在哪个领域） |

### 6.1 A/B/C/D/E 模块分类与去留决策

> 来源：v2.5 §4.6，已确认 Q12。B+C 是自建前端主战场，A 是免费地基只借不建专用页，D 推迟到阶段二，E 是规范不是交付物。

| 模块 | 性质 | MVP决策 | 理由 |
|------|------|:---:|------|
| **A 基础指标** | 采集免费（Actuator + OTel Agent 自动采集） | ✅ 自动采集，首页借 1~2 卡，不自建页 | 时延跨层归因 + 告警兜底需要 A 数据；但自建 A 大盘与 AI 可观测目标无关 |
| **B AI模型层** | 自建展示核心 —— Token / 延迟 / 成本 | ✅ 自建展示 | AI 可观测差异化主战场 |
| **C AI业务语义** | 自建展示核心 —— 意图 / 置信度 / 状态机 / 完成率 | ✅ 自建展示（核心子集） | AI 可观测差异化主战场 |
| **D 平台自观测** | 真正的独立模块 | ❌ MVP 不做（仅存活探针） | SaaS 级可观测产品才做；内部平台 MVP 是过度工程 |
| **E 维度规范** | 不是模块，是贯穿全程的约束 | ✅ 作为 B/C 埋点规范，不单独排期 | 跳过 E 会导致高基数 Tag 打爆存储；是 B/C 落地前提 |

**覆盖映射**：

| v2.5 模块 | 对应大屏 Zone | 说明 |
|-----------|:-----------:|------|
| A 基础指标 | Zone A 系统健康 | 借 A 类数据 |
| B AI模型层 | Zone B AI性能 | B 类数据 |
| C AI业务语义 | Zone C 语义质量 | C 类数据 |
| C AI业务语义 | Zone D 业务效果 | C 类数据 |

> **指标分组规则**：大屏指标分组以 `dashboard-v15.html` / `UI-DESIGN-V3.md` 的 4 区布局（§4）和三层可观测模型（§11）为准，v2.5 中的 L1-L4 分层模型和指标命名仅作参考。

---

## 7. 待确认问题

### 数据采集层面

| # | 问题 | 背景 | 建议方向 |
|---|------|------|---------|
| Q1 | TTFT（首Token时延）数据如何采集？ | 当前p50/p95Latency是系统端到端时延，但前端需要TTFT P50/P95/P99。需要手机银行主应用在每次LLM调用时记录first_token_time并上报到OTel | 在手机银行主应用的LLM调用层增加TTFT埋点，通过Span attribute `llm.first_token_time` 传递 |
| Q2 | 意图识别准确率如何计算？ | 需要"标注正确答案"与"模型输出"的对比。标注数据从哪来？人工标注还是规则判定？ | 建议初期用业务完成状态作为弱标注（完成=准确），后期引入人工抽检标注 |
| Q3 | Reroute事件如何标记？ | 当前Span数据中不包含Reroute标记，无法计算Reroute率 | 在Agent代码中Reroute发生时，增加Span event `reroute.triggered` |
| Q4 | 业务完成率/转化率如何定义？ | "业务完成"的标准是什么？转账成功？账单已展示？不同业务的"完成"定义不同 | 需要与产品团队对齐每个意图的"完成"标准，如：转账=trade_status=SUCCESS，账单=query_result非空 |
| Q5 | 用户满意度数据来源？ | dashboard-v15.html中满意度是Mock数据。真实数据从哪来？ | ✅ 已决策：前端在对话结束时展示 👍/👎 反馈按钮 → POST /api/v1/ai/satisfaction → 写入DB → AI洞察页展示真实数据 |

### 架构层面

| # | 问题 | 背景 | 建议方向 |
|---|------|------|---------|
| Q6 | H2数据库是否适合生产？ | 当前使用H2内存/文件数据库。随数据量增长，查询性能会下降 | P0阶段H2可用，P2阶段需迁移到PostgreSQL+TimescaleDB |
| Q7 | 告警规则的通知如何实现？ | P0阶段告警规则页展示配置，但实际通知（邮件/钉钉）是否需要对接？ | P0仅做配置管理UI + 事件记录表，实际通知放在P1 |
| Q8 | 系统设置是纯前端展示还是需要后端持久化？ | dashboard-v15.html中系统设置为静态展示。实际是否需要修改并生效？ | P0阶段可只读展示当前配置（从application.yml读取），P1阶段支持在线修改 |

### 前端工程层面

| # | 问题 | 背景 | 建议方向 |
|---|------|------|---------|
| Q9 | 当前main.jsx仍引用旧App.jsx，路由体系未使用React Router。是否需要重构为React Router？ | App.jsx是早期单页原型，Sidebar虽有react-router-dom但main.jsx未接入 | **建议P0就重构**：main.jsx引入BrowserRouter+Routes，否则无法支持7页面独立渲染 |
| Q10 | ECharts已安装但未充分使用，是否需要统一图表封装？ | 当前DashboardPage用div模拟柱状图，AIInsightsPage用了echarts-for-react | 建议P0统一用 `echarts-for-react` + 封装 `useECharts` hook |
| Q11 | dashboard-v15.html中告警规则和系统设置的数据是Mock，P0阶段后端是否需要实现？ | 见Q7/Q8 | P0阶段后端实现基础CRUD/配置读取API，前端对接真实数据 |

---

## 8. 附录：API 现状一览

### 8.1 已实现 API（✅ 可用）

| 方法 | 路径 | 用途 | 状态 |
|------|------|------|------|
| GET | `/api/v1/metrics/realtime` | 实时指标（Redis, 返回 RealtimeMetricsVO） | ✅ 字段不足 |
| GET | `/api/v1/metrics/history` | 历史指标（H2, 支持from/to/step） | ✅ |
| GET | `/api/v1/traces` | Trace列表（支持from/intent/limit） | ✅ |
| GET | `/api/v1/traces/{traceId}` | Trace详情（含Span树） | ✅ |
| GET | `/api/v1/logs` | 日志查询（level/traceId/q/分页） | ✅ |
| GET | `/api/v1/ai/insights` | AI洞察聚合（confidence/extraction/rewrite） | ✅ 字段不足 |
| GET | `/api/v1/ai/intent-distribution` | 意图分布 | ✅ |
| POST | `/api/v1/otlp/v1/metrics` | OTLP Metrics接收 | ✅ |
| POST | `/api/v1/otlp/v1/traces` | OTLP Traces接收 | ✅ |
| POST | `/api/v1/otlp/v1/logs` | OTLP Logs接收 | ✅ |
| POST | `/v1/metrics` | OTLP Metrics备用接收 | ✅ |
| POST | `/v1/traces` | OTLP Traces备用接收 | ✅ |
| POST | `/v1/logs` | OTLP Logs备用接收 | ✅ |
| GET | `/health` | 健康检查 | ✅ |

### 8.2 需新增 API（❌ 缺失）

| 方法 | 路径 | 用途 | 优先级 |
|------|------|------|--------|
| GET | `/api/v1/sessions` | 会话列表 | P0 |
| GET | `/api/v1/sessions/{sessionId}` | 会话详情（Timeline） | P0 |
| GET | `/api/v1/ai/agent-performance` | Agent/LLM性能数据 | P0 |
| GET | `/api/v1/ai/token-cost` | Token成本拆解 | P0 |
| GET | `/api/v1/ai/tool-stats` | 工具调用统计 | P0 |
| GET | `/api/v1/ai/skill-stats` | Skill业务效果 | P1（暂不实现，页面隐藏） |
| GET | `/api/v1/ai/conversion-funnel` | 转化漏斗 | P0 |
| GET/POST | `/api/v1/ai/satisfaction` | 用户满意度（GET查询 / POST提交反馈） | P0 |
| GET | `/api/v1/ai/intent-accuracy-trend` | 意图准确率趋势 | P0 |
| GET | `/api/v1/ai/confusion-matrix` | 混淆矩阵 | P0 |
| CRUD | `/api/v1/alerts/rules` | 告警规则管理 | P0 |
| GET | `/api/v1/alerts/events` | 告警事件时间线 | P0 |
| GET/PUT | `/api/v1/settings/collection` | 采集配置 | P0 |
| GET/PUT | `/api/v1/settings/storage` | 存储配置 | P0 |

---

## 9. 参考文档

| 文档 | 路径 | 说明 |
|------|------|------|
| AI 可观测设计 v2.5 | `docs/superpowers/specs/2026-06-16-ai-observability-design-v2.5.md` | 老设计，A/B/C/D/E 分类和维度规范具有长期参考价值 |
| UI 设计规范 V3 | `observability/docs/UI-DESIGN-V3.md` | UI 设计规范，18 章，与 dashboard-v15.html 同步更新 |

---

## 文档版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| V1 | 2025-07-10 | 初始版本：基于完整代码分析 + dashboard-v15.html + UI-DESIGN-V3.md 产出 |
| V1.1 | 2025-07-10 | 增量更新：新增 §6.1 模块分类与去留决策（确认 Q12）、指标分组规则说明（确认 Q13）、§9 参考文档 |
| V1.2 | 2025-07-10 | 增量更新：MCP→工具调用命名统一、Skill模块P0隐藏、用户满意度P0做实、违规率/转人工率占位、§4占位说明 |

---

> **下一步**：请确认以上PRD内容（特别是第7节"待确认问题"），确认后交由架构师进行技术方案设计，再由开发团队分P0/P1/P2排期实施。
