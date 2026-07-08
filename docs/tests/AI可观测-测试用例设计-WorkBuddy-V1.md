# AI可观测系统 — 测试用例设计

> 版本：V1.2 | 日期：2025-07-10 | 项目：Mobile AI Bank 可观测系统
> 作者：Edward（QA工程师）| 基于《项目Spec V1.2》+《系统架构设计 V1.3》+《项目Plan V1》
> 目标读者：QA团队、开发团队

---

## 目录

1. [测试范围与策略](#1-测试范围与策略)
2. [测试环境要求](#2-测试环境要求)
3. [测试分类矩阵](#3-测试分类矩阵)
4. [各层测试重点](#4-各层测试重点)
5. [冒烟测试清单](#5-冒烟测试清单)
6. [质量标准](#6-质量标准)
7. [附录：测试工具与框架](#7-附录测试工具与框架)

---

## 1. 测试范围与策略

### 1.1 总体测试范围

本项目 Phase 1（P0 核心）覆盖 5 大任务（T01-T05），对应 31 项 P0 需求：

| 任务 | 范围 | P0需求数 | 核心测试对象 |
|------|------|:---:|------|
| T01 | 项目基础设施 | 8项 | 8张新表DDL、前端Router(7路由)、亮色主题、useECharts hook、API client拦截器、健康检查、Redis连接 |
| T02 | 后端数据层 | 14项 | 14个新API + 3个扩展API（Session/Agent性能/Token成本/工具调用/Skill/漏斗/满意度/准确率/混淆矩阵/告警/设置 + Metrics/Traces/Logs扩展） |
| T03 | 前端核心页面 | 4项 | 总览大屏(4区11卡)、会话回放(筛选+气泡Modal)、链路追踪(列表→Modal+IO卡片+Span树+瀑布图)、日志查询(UserID/SessionID+彩色Badge) |
| T04 | AI洞察+告警设置 | 4项 | AI洞察6个TAB、告警规则页(双栏)、系统设置页(双栏)、三件套互跳 |
| T05 | OTel埋点+集成联调 | 1项 | 15项MVP指标上报（llm.* 5项 + agent.* 10项）、端到端链路、流式SSE埋点、异常路径、Reroute Span event、TTFT字段 |

### 1.2 分层测试策略

```
                    ┌──────────────┐
                    │   E2E 测试    │  ← 端到端：用户发消息→OTel上报→前端展示
                    │  (Playwright) │
                    ├──────────────┤
                    │  集成测试     │  ← API层：Controller→Service→Repository→H2
                    │  (Spring Boot │     前端：组件交互 + API mock
                    │   Test + MSW) │
                    ├──────────────┤
                    │  单元测试     │  ← Service逻辑、Repository查询、React组件
                    │  (JUnit +     │     ECharts hook、Utils函数、DTO序列化
                    │   Vitest)     │
                    └──────────────┘
```

**测试金字塔原则**：
- **单元测试**占 60%：Service 逻辑、Repository 查询、前端组件、Hooks、工具函数
- **集成测试**占 30%：Controller → Service → Repository 全链路、前端页面与 API 交互
- **E2E 测试**占 10%：核心用户旅程（总览→会话→链路→日志 4 级下钻）

### 1.3 前端测试策略

| 层次 | 工具 | 覆盖目标 | 说明 |
|------|------|---------|------|
| **组件测试** | Vitest + React Testing Library | 核心组件渲染 + 交互 | KpiCardGrid、SessionDetailModal、TraceDetailModal、各TAB组件 |
| **Hook测试** | Vitest | useECharts、usePolling | 验证响应式resize、轮询逻辑、错误重试 |
| **API Mock** | MSW (Mock Service Worker) | 前端独立测试 | 拦截axios请求，返回预设数据 |
| **E2E** | Playwright | 7个页面核心链路 | 路由跳转、Modal打开/关闭、筛选操作、三件套互跳 |

### 1.4 后端测试策略

| 层次 | 工具 | 覆盖目标 | 说明 |
|------|------|---------|------|
| **Repository测试** | @DataJpaTest + H2 | 8张新表CRUD、索引验证、分页查询 | 使用真实H2，确保DDL正确 |
| **Service测试** | @SpringBootTest + Mock Bean | 14个新Service + 3个扩展Service | Mock Redis依赖，验证业务逻辑 |
| **Controller测试** | @WebMvcTest + MockMvc | 14个新Controller + 3个扩展Controller | 验证请求参数校验、响应格式、HTTP状态码 |
| **API集成测试** | @SpringBootTest + TestRestTemplate | Controller→Service→Repository→H2全链路 | 端到端验证API响应 |

---

## 2. 测试环境要求

### 2.1 依赖服务

| 服务 | 用途 | 测试策略 | 备注 |
|------|------|:---:|------|
| **H2数据库** | 温层存储（spans/logs/metrics_agg/8新表） | ✅ 真实依赖 | 使用 `spring.datasource.url=jdbc:h2:mem:testdb` |
| **Redis** | 热层缓存（滑动窗口/实时指标） | ⚠️ Mock | 单元测试Mock RedisTemplate；集成测试可用Embedded Redis或Testcontainers |
| **手机银行主应用（:8080）** | OTel埋点数据源 | ⚠️ Mock/Stub | E2E测试需要；单元/集成测试用预设Span数据 |
| **OTel Collector（:4318）** | 采集管道 | ⚠️ Mock | 通过直接POST `/api/v1/otlp/v1/*` 端点的JSON payload模拟 |
| **LLM服务（DashScope）** | AI模型调用 | ⚠️ Mock | 所有测试使用Mock LLM响应，避免外部依赖 |

### 2.2 测试数据准备策略

| 数据类型 | 准备方式 | 说明 |
|------|------|------|
| **Seeds数据** | `data.sql` / `@Sql` 注解 | 测试用预设Span、Log、Session、Metric数据 |
| **边界数据** | 测试方法内构造 | 空数据、大量数据、特殊字符、超长时间范围 |
| **异常数据** | 测试方法内构造 | 格式错误JSON、缺失必填字段、超长字符串 |
| **性能数据** | 批量插入脚本 | 1000+ Sessions、10000+ Spans 用于分页和性能测试 |

### 2.3 Mock vs 真实依赖划分

```
✅ 真实依赖（不需要Mock）:
  - H2 数据库（内存模式）
  - Spring Bean（Service/Repository层正常注入）
  - Ant Design 5 组件渲染
  - ECharts 图表实例化
  - React Router 路由跳转

⚠️ Mock 依赖（测试中用Mock/Stub替代）:
  - Redis（RedisTemplate Mock）
  - 外部LLM API（DashScope HTTP调用Mock）
  - OTel Collector gRPC连接
  - 手机银行主应用 HTTP请求
  - 浏览器环境（E2E时用Playwright真实浏览器）
```

---

## 3. 测试分类矩阵

| 任务 | 单元测试 | 集成测试 | E2E测试 | 验收标准 |
|------|:---:|:---:|:---:|------|
| **T01 项目基础设施** | ✅ JPA Entity字段映射<br>✅ RealtimeMetricsVO序列化<br>✅ useECharts hook<br>✅ usePolling hook<br>✅ API client拦截器<br>✅ 路由配置 | ✅ H2 DDL执行<br>✅ Redis连接检查<br>✅ 健康检查端点<br>✅ CORS配置 | ✅ 7页面路由跳转<br>✅ 亮色主题渲染<br>✅ Sidebar导航 | 8张表DDL创建成功、7路由可访问、亮色主题全局生效 |
| **T02 后端数据层** | ✅ 8个Repository查询<br>✅ 14个Service业务逻辑<br>✅ VO/DTO序列化<br>✅ 分页逻辑 | ✅ 14个Controller端点<br>✅ 3个扩展API<br>✅ 筛选参数组合<br>✅ 时间范围过滤 | — | 14个新API+3个扩展API全部返回 `{code,data,message}` 格式 |
| **T03 前端核心页面** | ✅ KpiCardGrid组件<br>✅ SessionDetailModal组件<br>✅ TraceDetailModal组件<br>✅ ChatBubble组件<br>✅ IOCards组件<br>✅ SpanTree组件 | ✅ DashboardPage+API<br>✅ SessionViewerPage+API<br>✅ TraceExplorerPage+API<br>✅ LogViewerPage+API | ✅ 总览大屏→会话→链路→日志下钻 | 4个核心页面数据来自真实API、Modal交互正常 |
| **T04 AI洞察+告警** | ✅ 6个TAB组件<br>✅ AlertRuleTable<br>✅ AlertTimeline<br>✅ CollectionConfig<br>✅ StorageConfig<br>✅ 图表组件(Boxplot/Scatter/Heatmap/Funnel) | ✅ AIInsightsPage+6 API<br>✅ AlertRulesPage+CRUD API<br>✅ SystemSettingsPage+Settings API<br>✅ 三件套互跳 | ✅ AI洞察6 TAB切换<br>✅ 告警CRUD闭环<br>✅ 三件套互跳 | 6个TAB图表正确渲染、告警CRUD可用、三件套跳转正常 |
| **T05 埋点+联调** | ✅ OTel Span attribute解析<br>✅ TTFT字段验证<br>✅ Reroute event解析 | ✅ 15项MVP指标上报链路<br>✅ OtlpParserService解析<br>✅ SSE流式场景 | ✅ 端到端：发消息→上报→展示<br>✅ 异常路径：超时/错误 | 15项指标全部通过OTel Collector→后端→H2验证 |

---

## 4. 各层测试重点

### 4.1 后端 14 个新 API 测试策略

| API | 关键测试点 | 优先级 |
|------|------|:---:|
| `GET /api/v1/sessions` | 6个筛选参数组合(sessionId/userId/channel/intent/agentLevel/status)、分页、时间范围、空结果 | P0 |
| `GET /api/v1/sessions/{sessionId}` | 存在/不存在sessionId、turns嵌套结构完整、token统计正确 | P0 |
| `GET /api/v1/ai/agent-performance` | dimension=agent/llm切换、TTFT/TPOT分位数、箱线数据非空、空窗口 | P0 |
| `GET /api/v1/ai/token-cost` | groupBy=model/intent/agent、costEstimate计算、totalInput/Output汇总 | P0 |
| `GET /api/v1/ai/tool-stats` | 工具列表、successRate计算、空数据场景 | P0 |
| `GET /api/v1/ai/skill-stats` | 技能列表、completionRate/dropOffRate计算、avgTurns | P1 |
| `GET /api/v1/ai/conversion-funnel` | 5阶段漏斗数据、dropOffs计算、rate递减验证 | P0 |
| `GET /api/v1/ai/satisfaction` | GET查询 + POST反馈双端点：distribution 1-5分、trend 7天、negativeReasons TOP3、lowScoreSessions、satisfied/neutral/unsatisfied三态计数 | P0 |
| `POST /api/v1/ai/satisfaction` | 提交满意度反馈（👍/👎）、参数校验(sessionId必填/rating 1-5)、写入satisfaction表 | P0 |
| `GET /api/v1/ai/intent-accuracy-trend` | 5条series、时间序列连续、value 0-1范围 | P0 |
| `GET /api/v1/ai/confusion-matrix` | 矩阵维度匹配labels、对角线>非对角线、totalSamples正确 | P0 |
| `CRUD /api/v1/alerts/rules` | 创建/读取/更新/删除/列表、enabled切换、参数校验(threshold>0) | P0 |
| `GET /api/v1/alerts/events` | 按ruleId/status筛选、时间线排序、FIRING→RESOLVED状态流转 | P0 |
| `GET/PUT /api/v1/settings/collection` | GET返回配置、PUT更新samplingRate/retentionDays、参数范围校验 | P0 |
| `GET/PUT /api/v1/settings/storage` | GET返回配置、redisStatus、保留策略 | P0 |

### 4.2 现有 API 扩展测试策略

| API | 扩展点 | 关键测试点 |
|------|------|------|
| `GET /api/v1/metrics/realtime` | RealtimeMetricsVO 9→20字段 | 新增11个字段非null、agentDistribution含L0/L1/L2、ttftP50/P95/P99非负 |
| `GET /api/v1/traces` | 新增参数 sessionId/userId/statusCode<br>响应新增 ttftMs/tokenTotal/statusDot | 新筛选参数生效、新字段正确填充、statusDot格式 `🟢 正常`/`🔴 异常` |
| `GET /api/v1/logs` | 新增参数 userId/sessionId | 新筛选参数生效、与现有参数组合过滤 |

### 4.3 前端 7 页面测试策略

#### 总览大屏 (DashboardPage)

| 测试点 | 验证方法 | 优先级 |
|------|:---:|:---:|
| 4区11卡KPI网格布局 | 组件测试：渲染KpiCardGrid，检查11个Card元素 | P0 |
| KPI卡片：主数值28px + 副指标12px | 快照测试 / 视觉回归测试 | P0 |
| Mini Spark折线图渲染 | 组件测试：MiniSpark接收数据props，ECharts实例化 | P1 |
| 请求&Token趋势图（双轴） | 组件测试：TrendChart接收history数据，检查series配置 | P0 |
| Agent分布环形饼图 | 组件测试：PieChart接收L0/L1/L2分布数据 | P0 |
| 3s实时轮询刷新 | Hook测试：usePolling 3s间隔、cleanup、错误重试 | P0 |
| LIVE指示器状态 | 组件测试：轮询成功=绿色脉冲、失败=红色 | P1 |
| 空数据状态 | 组件测试：metrics为null时展示占位符 | P1 |
| 违规率KPI占位 | 组件测试：Zone D 违规率卡片显示指标名+图标，数值标注"暂无"（使用 Empty 组件） | P0 |

#### 会话回放 (SessionViewerPage)

| 测试点 | 验证方法 | 优先级 |
|------|:---:|:---:|
| 6个筛选器渲染 | 组件测试：SessionFilter渲染6个输入/选择框 | P0 |
| 列表列完整（渠道/Token/智能体/状态dot） | 组件测试：SessionTable列定义验证 | P0 |
| 分页功能 | 集成测试：切换页码、改变每页条数 | P1 |
| Modal打开/关闭 | 组件测试：点击查看→Modal弹出、关闭→Modal消失 | P0 |
| 对话气泡（蓝用户/绿AI） | 组件测试：ChatBubble根据role渲染不同颜色 | P0 |
| Turn详情展开 | 组件测试：点击Turn展开详情面板 | P0 |
| 意图流展示 | 组件测试：intentFlow渲染顺序箭头 | P1 |
| Token总结条 | 组件测试：totalToken展示 input+output | P0 |

#### 链路追踪 (TraceExplorerPage)

| 测试点 | 验证方法 | 优先级 |
|------|:---:|:---:|
| 列表独占布局（无内联面板） | 组件测试：验证TraceTable为独占布局 | P0 |
| 列表列增强（TTFT/Token/状态dot） | 组件测试：列定义包含新字段 | P0 |
| 点击TraceID→全屏Modal | 组件测试：点击后TraceDetailModal显示 | P0 |
| 输入/输出双色卡片 | 组件测试：IOCards蓝色输入卡片+绿色输出卡片 | P0 |
| Agent层级树（L0/L1双LLM/L2） | 组件测试：SpanTree递归渲染，tag-l0/l1/l2 badge | P0 |
| Span瀑布图（横向柱状+TTFT虚线） | 组件测试：WaterfallChart渲染bar+markLine | P0 |
| 改写对比展示 | 组件测试：改写前后文本对比卡片 | P0 |

#### 日志查询 (LogViewerPage)

| 测试点 | 验证方法 | 优先级 |
|------|:---:|:---:|
| UserID/SessionID新增筛选 | 组件测试：LogFilter包含新字段 | P0 |
| 彩色级别Badge | 组件测试：ERROR=红色、WARN=橙色、INFO=蓝色、DEBUG=灰色 | P0 |
| TraceID可点击跳转 | 组件测试：点击TraceID→导航到/traces/:traceId | P0 |
| 分页保留 | 回归测试：分页功能正常 | P1 |
| 亮色主题适配 | 视觉回归测试 | P0 |

#### AI洞察 (AIInsightsPage)

| 测试点 | 验证方法 | 优先级 |
|------|:---:|:---:|
| 6个TAB渲染+切换 | 组件测试：Tabs组件6个pane，切换时内容变化 | P0 |
| 准确率TAB：5条折线 | 组件测试：TrendChart rendering 5 series | P0 |
| 准确率TAB：改写根因TOP3 | 组件测试：根因卡片列表渲染 | P0 |
| 准确率TAB：混淆矩阵热力图 | 组件测试：HeatmapChart渲染矩阵 | P0 |
| Agent性能TAB：双维度子页签 | 组件测试：agent/llm子页签切换 | P0 |
| Agent性能TAB：箱线图 | 组件测试：BoxplotChart接收分布数据 | P0 |
| Agent性能TAB：六色散点图 | 组件测试：ScatterChart渲染多series | P0 |
| Token成本TAB：堆叠柱状 | 组件测试：stacked bar chart | P0 |
| Token成本TAB：饼图+明细表 | 组件测试：PieChart+Table并排 | P0 |
| 智能体工具TAB：工具调用+Skill | 组件测试：两个子区域渲染（Skill：P1 实现，P0 不建表） | P0 |
| 转化漏斗TAB：漏斗+环形+明细 | 组件测试：FunnelChart+环形图 | P0 |
| 满意度TAB：分布+趋势+原因+低分列表 | 组件测试：4个子区域渲染 | P0 |
| 满意度TAB：反馈提交流程 | 集成测试：会话回放Modal底部 👍/👎 按钮→POST /api/v1/ai/satisfaction→返回200→GET查询反馈聚合验证三态分布 | P0 |
| 转人工率占位 | 组件测试：AI洞察页转人工率区域显示标题，标注"暂无数据" | P0 |

#### 告警规则 + 系统设置

| 测试点 | 验证方法 | 优先级 |
|------|:---:|:---:|
| 告警规则双栏布局 | 组件测试：AlertRuleTable + AlertTimeline并排 | P0 |
| 规则CRUD表单 | 组件测试：新增/编辑Modal、删除确认 | P0 |
| 闭环时间线 | 组件测试：AlertTimeline渲染事件列表 | P0 |
| 系统设置双栏布局 | 组件测试：CollectionConfig + StorageConfig并排 | P0 |
| 采集配置字段（状态/采样率/Tag策略/Prompt存储） | 组件测试：4个配置项渲染 | P0 |
| 存储配置字段（数据库类型/Redis/保留策略） | 组件测试：3个配置项渲染 | P0 |

### 4.4 OTel 埋点 15 项 MVP 指标验证策略

> 说明：`llm.*` 5项由 **ObsChatModel**（装饰 `OpenAiChatModel`）自动拦截埋点，包装点在 ChatModel 层而非 ChatClient 层；`agent.*` 10项为手动埋点。指标命名采用双前缀体系。

**llm.* 组（5项 — ChatClientWrapper 自动拦截）**：

| # | 指标名 | 类型 | 验证方法 | 验证点 |
|---|------|:---:|:---:|------|
| 1 | `llm.token.input` | Counter | 数据库验证 | H2 metrics_agg 表存在该Counter记录，tag含model，数值非负，ObsChatModel拦截生效 |
| 2 | `llm.token.output` | Counter | 数据库验证 | 同上。流式场景：优先取末尾 chunk 的 usage → 兜底估算 → 写入 `token.estimated=true`；非流式场景：直接取 `call()` 返回的 usage |
| 3 | `llm.first_token.latency` | Histogram | 数据库验证 | bucket分布正确，TTFT计算双路径：流式取首chunk时间戳，非流式取 `call()` 返回时刻；TPOT = (总耗时-TTFT)/output_tokens；单位ms，流式WEALTH_INTERPRET场景验证 |
| 4 | `llm.operation.duration` | Histogram | 数据库验证 | 调用完成时触发，tag含model |
| 5 | `llm.error.count` | Counter | 数据库验证 | LLM超时/错误时触发，tag含error_type |

**agent.* 组（10项 — 手动埋点）**：

| # | 指标名 | 类型 | 验证方法 | 验证点 |
|---|------|:---:|:---:|------|
| 6 | `agent.router.decision.outcome` | Counter | 数据库验证 | tag layer/decision/domain齐全，各决策类型计数准确 |
| 7 | `agent.intent.accuracy` | Gauge | 数据库验证 | 四态标签{correct,fuzzy,error,disambiguated}，混淆矩阵6×6维度 |
| 8 | `agent.rewrite.accuracy` | Gauge | 数据库验证 | 规则引擎判定，实体守恒+金额归一检测，review标记新增实体 |
| 9 | `agent.workflow.execution.duration` | Histogram | 数据库验证 | tag含intent/graph，COMPLETED和INTERRUPTED都记录 |
| 10 | `agent.workflow.interrupt` | Counter | 数据库验证 | 中断节点名正确，interruptBefore和ask→END双来源 |
| 11 | `agent.slot.askback.total` | Histogram | 数据库验证 | 直方图分桶，每会话累计追问轮数 |
| 12 | `agent.tool.call.count` | Counter | 数据库验证 | tag含tool_name，调用原手机银行核心系统 API 接口（转账/账单查询等）处触发 |
| 13 | `agent.tool.call.duration` | Histogram | 数据库验证 | tag含tool_name，成功和失败都记录 |
| 14 | `agent.skill.outcome` | Counter | 数据库验证 | tag含skill_name和result，L2执行完成处触发 |
| 15 | `agent.session.completed` + `agent.session.abandoned` + `agent.business.outcome` | Counter/Counter/Counter | 数据库验证 | 三指标联动验证，域/意图tag完整 |

**Tag基数验证（Q14）**：
- 验证 `user.id`/`session.id` 不出现在 Metric Tag 中（仅出现于 Span attributes 和 Log fields）
- 验证 `llm.error.count` 不含 user.id 等禁用 Tag（高基数 Tag 仅限 Span attributes）
- 验证所有 Metric Tag 值在允许列表内（§2.4 的 15 个允许 Tag）

**混合采集验证要点（Q1 — Span + Metric 混合采集）**：

| # | 验证项 | 验证方法 | 关键验证点 |
|---|-------|:---:|------|
| Q1-1 | 同一埋点位置 Span 与 Metric 同时写入 | 数据库交叉验证 | 同一埋点代码处同时创建 Span 和 Counter/Histogram → spans 表存在对应 trace 数据 + metrics_agg 表存在对应指标记录 |
| Q1-2 | Metric Tag 基数约束 | 自动化扫描 | metrics_agg 表中所有 tags 字段不包含 `user.id`/`session.id`/`trace.id`/原始 prompt/原始金额等禁用高基数字段 |
| Q1-3 | Span attribute 高基数字段 | 数据库验证 | Span attributes 中正常包含 `user.id`/`session.id`/`trace.id`，这些高基数字段仅限 Span 层，不进入 Metric Tag |

**Q2 补充验证要点（ObsChatModel 包装方案）**：

| # | 验证项 | 验证方法 | 关键验证点 |
|---|-------|:---:|------|
| Q2-1 | 包装点正确性 | Spring Bean 注入验证 | ModelConfig 中 6 个 ChatModel 均被 `ObsChatModel` 包装，ChatClient Bean 注入的是包装后的 ChatModel（非原始 OpenAiChatModel） |
| Q2-2 | 流式末尾 usage Token | 数据库验证 | WEALTH_INTERPRET 流式调用末尾 chunk 含 usage → `llm.token.output` 为精确值 → `token.estimated=false` |
| Q2-3 | 流式估算 Token 兜底 | Mock 测试 | 模拟末尾 usage 不可用场景 → `llm.token.output` 为估算值 → `token.estimated=true` |
| Q2-4 | TTFT + TPOT 双路径计算 | 数据库验证 | 流式：首 chunk 时间戳 = TTFT；非流式：`call()` 返回时刻 = TTFT；TPOT = (总耗时 - TTFT) / output_tokens |

**AI 洞察判定逻辑验证要点（Q3）**：

意图四态标签 `{correct, fuzzy, error, disambiguated}` 的判定规则：

| 标签 | 判定规则 | 验证点 |
|------|---------|------|
| `correct` | L1-LLM2 intent = L2 intent（最终执行意图一致） | `intent.accuracy=correct` 写入 Span |
| `fuzzy` | L1-LLM2 intent ≠ L2 intent，但未触发 REROUTE 且执行成功 | `intent.accuracy=fuzzy`，说明意图虽有偏差但结果可用 |
| `error` | L1-LLM2 intent ≠ L2 intent，且触发 REROUTE（二次路由纠正） | `intent.accuracy=error`，说明第一次意图识别错误需要纠正 |
| `disambiguated` | L1-LLM2 输出意图组（多个候选），用户回答后重识别 | `intent.accuracy=disambiguated`，消歧流程完整 |

混淆矩阵 6×6 维度验证：
- 行 = L1-LLM2 识别意图（预测），列 = L2 最终执行意图（实际）
- 值域：`{TRANSFER, BILL_QUERY, WEALTH_CONSULT, WEALTH_INTERPRET, CHAT, UNSUPPORTED}`
- 对角线 = 正确分类，非对角线 = 误分类，验证 `totalSamples = Σmatrix所有元素`

改写准确率轻量信号验证：
- **实体守恒检测**：原始 query 含实体（人名/金额），改写后不得丢失 → 丢失则 `rewrite.accuracy rule_check=fail`
- **金额归一检测**：含"万""千"等中文金额单位 → 验证改写后归一为数字形式
- **review 标记**：若改写新增实体（原始不含但改写后出现），标记 `review=true` 供人工复核

改写根因分类规则（关键字匹配 + "其他"兜底）：
- `代词指代不明` → 改写后含"它/他/她/这个/那个"等指代但缺失被指代对象
- `金额格式未标准化` → 原始含"万/千/亿/块"等中文金额但改写后未归一
- `上下文关联断裂` → 多轮对话中丢失前轮关键实体/意图
- `其他` → 不匹配上述任何规则时兜底

### 4.5 关键设计决策验证

| 决策 | 测试验证点 | 关联用例 |
|------|------|:---:|
| Q2 意图准确率 | P0用业务弱标注：L2执行成功数/总意图数。验证准确率计算逻辑 | TC-T04-008 |
| Q6 H2存储 | P0阶段数据写入H2即通过，不要求PG。验证H2读写正常 | TC-T01-006 |
| Q14 Tag基数约束 | user.id/session.id 不作为 Metric Tag。代码审查+自动化检查 | TC-T05-005 |
| Q16 三件套互跳 | 每个页面验证 user.id↔session.id↔trace.id 互跳 | TC-T04-029~032 |
| A/B/C/D/E 分类 | Zone A借A类数据、B/C区用自建数据。验证数据源归属 | TC-T03-001~004 |

---

## 5. 冒烟测试清单

> 核心链路冒烟用例，覆盖端到端关键路径，用于每次部署后快速验证系统可用性。

| # | 冒烟用例 | 覆盖路径 | 预期结果 | 耗时 |
|---|---------|---------|---------|:---:|
| SMK-01 | 健康检查 | `GET /health` → 200 | 返回 `{"status":"UP"}` | <1s |
| SMK-02 | 总览大屏加载 | 访问 `/dashboard` → KPI卡片渲染 → 3s后数据刷新 | 4区11卡全部展示，无console error | <10s |
| SMK-03 | 会话列表→回放 | `/sessions` → 列表加载 → 点击查看 → Modal弹出对话气泡 | Modal展示蓝/绿气泡 + Turn详情 | <15s |
| SMK-04 | Trace列表→详情 | `/traces` → 列表加载 → 点击TraceID → Modal弹出 | IO卡片 + Agent树 + Span瀑布图 | <15s |
| SMK-05 | 日志查询→Trace跳转 | `/logs` → 输入关键词 → 查询 → 点击TraceID | 跳转到 `/traces/:traceId` Modal | <15s |
| SMK-06 | AI洞察TAB切换 | `/insights` → 依次点击6个TAB | 每个TAB图表正确渲染 | <20s |
| SMK-07 | 告警规则CRUD | `/alerts` → 新建规则 → 列表可见 → 编辑 → 删除 | CRUD闭环正常 | <15s |
| SMK-08 | 端到端数据流 | 手机银行发消息 → OTel Collector → 后端接收 → 前端展示 | 总览大屏KPI更新 + Trace列表新增记录 | <30s |

---

## 6. 质量标准

### 6.1 覆盖率目标

| 层次 | 目标 | 说明 |
|------|:---:|------|
| **后端 Service 行覆盖率** | ≥ 80% | 14个新Service + 扩展现有Service |
| **后端 Controller 行覆盖率** | ≥ 85% | 所有端点 + 参数校验 + 错误处理 |
| **后端 Repository 方法覆盖率** | ≥ 90% | 自定义查询方法全覆盖 |
| **前端组件渲染覆盖率** | ≥ 75% | 核心组件（KPI卡片、Modal、TAB、图表） |
| **前端 Hook 覆盖率** | ≥ 90% | useECharts、usePolling |
| **E2E 场景覆盖率** | 8条冒烟+5条关键路径 | 覆盖7页面核心交互 |

### 6.2 阻塞性 Bug 定义

以下情况定义为 **P0 阻塞性 Bug**，必须修复后才能发布：

1. **数据丢失/错误**：API 返回错误数据、Session/Trace 数据丢失
2. **页面崩溃**：任何页面白屏、JS 报错导致不可用
3. **核心功能不可用**：总览大屏无数据、会话回放 Modal 打不开、Trace 详情空白
4. **API 500 错误**：任何 P0 API 在正常参数下返回 500
5. **三件套互跳失效**：日志→Trace、Trace→会话、会话→Trace 任一跳转失败
6. **埋点数据断流**：手机银行上报后，可观测后端超过 30s 未收到数据
7. **安全漏洞**：SQL 注入、XSS、未授权访问

### 6.3 回归测试策略

| 阶段 | 触发条件 | 执行范围 |
|------|---------|---------|
| **每日回归** | 每日构建 | 冒烟测试 8 条 |
| **PR回归** | 每次 PR 合并 | 变更模块所有单元测试 + 受影响模块集成测试 |
| **里程碑回归** | M1-M5 里程碑 | 全量 P0 用例（~80条） |
| **发布前回归** | 发布前 | 全量用例 + 冒烟测试 + 性能抽查 |

### 6.4 测试准入/准出标准

**准入标准**（开始测试前）：
- [ ] 开发自测通过，提供自测报告
- [ ] API 可通过 Postman/curl 调通
- [ ] 前端页面可正常加载（无编译错误）
- [ ] 测试环境依赖服务就绪（H2/Redis/OTel Collector）

**准出标准**（测试通过）：
- [ ] 所有 P0 用例通过率 100%
- [ ] P1 用例通过率 ≥ 95%
- [ ] 无 P0 阻塞性 Bug 未关闭
- [ ] 后端覆盖率达标
- [ ] 冒烟测试 8 条全通过
- [ ] 15 项 MVP 指标全部验证通过
- [ ] 三件套互跳 4 条路径全部验证通过

---

## 7. 附录：测试工具与框架

| 用途 | 工具 | 版本 | 备注 |
|------|------|------|------|
| 后端单元测试 | JUnit 5 + Mockito | 5.x / 5.x | Spring Boot Test 内置 |
| 后端API测试 | MockMvc / TestRestTemplate | — | Spring Boot Test 内置 |
| 数据库测试 | @DataJpaTest + H2 | — | 内存数据库 |
| 前端单元测试 | Vitest | 1.x | Vite 原生集成 |
| 前端组件测试 | React Testing Library | 14.x | 与 Vitest 配合 |
| API Mock（前端） | MSW (Mock Service Worker) | 2.x | 拦截 axios 请求 |
| E2E测试 | Playwright | 1.x | 多浏览器支持 |
| 视觉回归 | Playwright Screenshot / Percy | — | 亮色主题 UI 一致性 |
| 代码覆盖率（后端） | JaCoCo | 0.8.x | Maven/Gradle 插件 |
| 代码覆盖率（前端） | Vitest coverage (c8/istanbul) | — | Vitest 内置 |
| API文档测试 | SpringDoc OpenAPI | 2.x | Swagger UI 自动生成 |

---

> **下一步**：基于本文档的测试策略，产出《AI可观测-测试用例集-WorkBuddy-V1.md》详细用例集，覆盖全部 31 项 P0 需求。

---

## 文档版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| V1 | 2025-07-10 | 初始版本：基于 PRD V1 + 系统架构设计 V1 + 项目Plan V1 产出 |
| V1.1 | 2025-07-10 | 增量更新：§4.4 新增 Q1混合采集验证要点、Q2 ObsChatModel包装验证要点、Q3 AI洞察判定逻辑验证要点 |
| V1.2 | 2025-07-10 | 范围调整5项：全文"MCP"→"工具调用"重命名、Skill用例P1标记("P1实现,P0不建表")、满意度双端点(POST+GET)验证策略、违规率/转人工率占位验证、agent.tool.call说明更新 |
