# AI可观测系统 — 测试用例集

> 版本：V1.2 | 日期：2025-07-10 | 项目：Mobile AI Bank 可观测系统
> 作者：Edward（QA工程师）| 基于《测试用例设计 V1.2》
> 用例总数：134 条 | P0：115 条 | P1：19 条 | P2：0 条

---

## 目录

1. [T01 — 项目基础设施（10条）](#t01--项目基础设施)
2. [T02 — 后端数据层（30条）](#t02--后端数据层)
3. [T03 — 前端核心页面（18条）](#t03--前端核心页面)
4. [T04 — AI洞察+告警设置（23条）](#t04--ai洞察告警设置)
5. [T05 — OTel埋点+集成联调（23条）](#t05--otel埋点集成联调)
6. [边缘情况与异常路径（12条）](#边缘情况与异常路径)
7. [用例统计与覆盖矩阵](#用例统计与覆盖矩阵)

---

## T01 — 项目基础设施

### 1.1 数据库与后端基础设施

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T01-001 | T01 | 8张新表DDL创建验证 | H2数据库就绪，schema.sql已更新 | 1. 启动可观测后端应用<br>2. 检查H2控制台，验证8张新表存在<br>3. 验证每张表的列定义与架构设计§2.2一致 | sessions/session_turns/agent_performance/token_cost/tool_calls/skill_stats/alert_rules/alert_events 8张表全部创建成功，字段类型和索引正确 | P0 | P0-07,P0-25 |
| TC-T01-002 | T01 | RealtimeMetricsVO扩展至20字段 | VO类已修改 | 1. 检查RealtimeMetricsVO.java编译<br>2. 序列化为JSON<br>3. 验证新增11个字段存在：dau/realtimeOnline/qps/agentDistribution/totalVisits/tokenTotal/ttftP50/ttftP95/ttftP99/intentAccuracyRate/rewriteAccuracyRate/rerouteRate/businessCompletionRate/conversionRate/violationRate | 20个字段全部存在，JSON序列化字段名正确（camelCase） | P0 | P0-06 |
| TC-T01-003 | T01 | 健康检查端点 `/health` | 可观测后端启动 | 1. 发送 `GET /health`<br>2. 检查响应状态码<br>3. 检查响应体 | 返回 200，响应体包含 `{"status":"UP"}` | P0 | SMK-01 |
| TC-T01-004 | T01 | Redis连接可用性检查 | Redis服务启动在6379端口 | 1. 启动可观测后端<br>2. 检查应用启动日志无Redis连接错误<br>3. 调用 `GET /api/v1/settings/storage` 检查redisStatus | 启动日志无Redis错误，redisStatus返回"connected" | P0 | P0-28 |
| TC-T01-005 | T01 | 8张新表索引验证 | 8张表已创建 | 1. 向sessions表插入100条测试数据(含不同user_id/status/created_at)<br>2. 执行带WHERE条件的查询<br>3. 通过EXPLAIN验证索引命中 | 索引生效，查询计划显示使用对应索引(idx_sessions_user/idx_sessions_status/idx_sessions_created) | P1 | P0-07 |
| TC-T01-006 | T01 | H2数据读写正常性（Q6验证） | H2数据库就绪 | 1. 向sessions表INSERT一条记录<br>2. SELECT查询验证写入成功<br>3. UPDATE修改status字段<br>4. DELETE删除记录 | 数据CRUD全部正常，确认P0阶段H2可用 | P0 | Q6 |

### 1.2 前端工程基础设施

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T01-007 | T01 | React Router 7路由正确性 | 前端工程就绪，main.jsx重构完成 | 1. 访问 `/` → 验证重定向到 `/dashboard`<br>2. 访问 `/dashboard` → DashboardPage渲染<br>3. 访问 `/sessions` → SessionViewerPage渲染<br>4. 访问 `/traces` → TraceExplorerPage渲染<br>5. 访问 `/insights` → AIInsightsPage渲染<br>6. 访问 `/logs` → LogViewerPage渲染<br>7. 访问 `/alerts` → AlertRulesPage渲染<br>8. 访问 `/settings` → SystemSettingsPage渲染 | 7个路由全部正确渲染对应页面，`/` 自动重定向到 `/dashboard` | P0 | P0-30 |
| TC-T01-008 | T01 | Sidebar 8项导航显示 | AppLayout组件就绪 | 1. 检查Sidebar渲染的MenuItem数量<br>2. 验证每个导航项的图标+文字+路由<br>3. 点击各导航项验证路由跳转 | 8个导航项：总览大屏/会话回放/链路追踪/AI洞察/日志查询/告警规则/系统设置 + 首页，点击跳转正确，当前路由高亮 | P0 | P0-30 |
| TC-T01-009 | T01 | Ant Design 5亮色主题全局应用 | antdTheme.js配置完成 | 1. 访问 `/dashboard` 页面<br>2. 检查页面背景色为 `#f0f2f5`<br>3. 检查Card组件背景色为 `#ffffff`<br>4. 检查文字颜色为 `#1e293b`<br>5. 检查Sidebar背景色保持暗色 `#0f172a` | 主内容区亮色主题生效，Sidebar保持暗色，与架构设计§4.6一致 | P0 | P0-01,P0-31 |
| TC-T01-010 | T01 | useECharts hook功能验证 | useECharts.js已实现 | 1. 在测试组件中使用useECharts hook<br>2. 传入options并渲染图表<br>3. 触发window resize事件<br>4. 验证chart实例调用resize | 图表正确渲染，窗口resize时图表自适应调整尺寸，unmount时正确清理 | P0 | Q10 |

---

## T02 — 后端数据层

### 2.1 Session API（会话列表/详情）

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T02-001 | T02 | 会话列表-无筛选条件默认分页 | sessions表有50条测试数据 | 1. `GET /api/v1/sessions` 无参数<br>2. 验证响应结构<br>3. 验证默认分页 | 返回 `{code:0, data:{content:[...], totalElements:50, totalPages:3, number:0}}`，每页20条 | P0 | P0-07 |
| TC-T02-002 | T02 | 会话列表-按sessionId模糊搜索 | sessions表有数据 | 1. `GET /api/v1/sessions?sessionId=sess_a1b2` | 返回sessionId包含"sess_a1b2"的会话，大小写不敏感 | P0 | P0-07 |
| TC-T02-003 | T02 | 会话列表-按userId筛选 | sessions表有多个userId数据 | 1. `GET /api/v1/sessions?userId=user_zhang` | 仅返回userId=user_zhang的会话 | P0 | P0-07 |
| TC-T02-004 | T02 | 会话列表-按channel筛选 | sessions表有mobile/web/miniprogram数据 | 1. `GET /api/v1/sessions?channel=mobile` | 仅返回channel=mobile的会话 | P1 | P0-07 |
| TC-T02-005 | T02 | 会话列表-多条件组合筛选 | sessions表有数据 | 1. `GET /api/v1/sessions?status=completed&intent=TRANSFER&agentLevel=L2&from=2025-07-01T00:00:00Z&to=2025-07-10T23:59:59Z` | 返回同时满足所有条件的会话，AND逻辑 | P0 | P0-07 |
| TC-T02-006 | T02 | 会话列表-空结果处理 | sessions表为空或无匹配数据 | 1. `GET /api/v1/sessions?userId=nonexistent` | 返回 `{code:0, data:{content:[], totalElements:0, totalPages:0}}`，不报错 | P1 | P0-07 |
| TC-T02-007 | T02 | 会话详情-正常获取 | sessions和session_turns表有数据 | 1. `GET /api/v1/sessions/{sessionId}`（存在的sessionId）<br>2. 验证响应结构<br>3. 验证turns数组 | 返回完整会话信息，turns数组包含每个turn的turnNumber/traceId/userQuery/aiResponse/intent/agentLevel/agentName/durationMs/tokenInput/tokenOutput/ttftMs/rerouteTriggered/businessOutcome | P0 | P0-08 |
| TC-T02-008 | T02 | 会话详情-sessionId不存在 | sessions表无该sessionId | 1. `GET /api/v1/sessions/nonexistent-id` | 返回 `{code:404, message:"会话不存在"}`，HTTP 200（业务码404） | P0 | P0-08 |
| TC-T02-009 | T02 | 会话详情-Token统计正确性 | sessions和session_turns表有数据 | 1. `GET /api/v1/sessions/{sessionId}`<br>2. 手动累加各turn的tokenInput和tokenOutput<br>3. 与响应中totalTokenInput/totalTokenOutput对比 | totalTokenInput = Σ(turn.tokenInput)，totalTokenOutput = Σ(turn.tokenOutput) | P0 | P0-08 |

### 2.2 Agent性能API

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T02-010 | T02 | Agent性能-dimension=agent数据 | agent_performance表有数据 | 1. `GET /api/v1/ai/agent-performance?dimension=agent`<br>2. 验证byAgent数组 | byAgent按agentLevel分组，每组含callCount/ttftP50/ttftP95/ttftP99/tpotP50/tpotP95/durationP50/durationP95/tokenAvgInput/tokenAvgOutput/errorCount/completionRate | P0 | P0-18 |
| TC-T02-011 | T02 | Agent性能-dimension=llm数据 | agent_performance表有数据 | 1. `GET /api/v1/ai/agent-performance?dimension=llm` | byModel按llmModel分组，每组含callCount/ttftP50/ttftP95/tpotP50/tpotP95/tokenAvgInput/tokenAvgOutput/errorCount | P0 | P0-18 |
| TC-T02-012 | T02 | Agent性能-箱线数据非空 | agent_performance表有数据 | 1. `GET /api/v1/ai/agent-performance?dimension=agent`<br>2. 验证ttftDistribution字段 | ttftDistribution包含各Agent层级的TTFT值数组（如L0:[380,420,...]），数组非空 | P0 | P0-18 |
| TC-T02-013 | T02 | Agent性能-时间范围过滤 | agent_performance表有跨多日数据 | 1. `GET /api/v1/ai/agent-performance?from=2025-07-09T00:00:00Z&to=2025-07-09T23:59:59Z` | 仅返回时间范围内的性能数据 | P1 | P0-18 |

### 2.3 Token成本API

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T02-014 | T02 | Token成本-按模型分组 | token_cost表有数据 | 1. `GET /api/v1/ai/token-cost?groupBy=model`<br>2. 验证breakdown数组 | breakdown按model_name分组，每组含inputTokens/outputTokens/costEstimate，totalInput/totalOutput/totalCost汇总正确 | P0 | P0-19 |
| TC-T02-015 | T02 | Token成本-按意图分组 | token_cost表有数据 | 1. `GET /api/v1/ai/token-cost?groupBy=intent` | breakdown按intent分组 | P0 | P0-19 |
| TC-T02-016 | T02 | Token成本-按Agent层级分组 | token_cost表有数据 | 1. `GET /api/v1/ai/token-cost?groupBy=agent` | breakdown按agent_level分组 | P0 | P0-19 |

### 2.4 工具调用/Skill/漏斗/满意度API

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T02-017 | T02 | 工具调用统计 | tool_calls表有数据 | 1. `GET /api/v1/ai/tool-stats`<br>2. 验证tools数组 | tools数组每项含toolName/toolProvider/totalCalls/successCalls/errorCalls/avgDurationMs/successRate，successRate=successCalls/totalCalls×100 | P0 | P0-20 |
| TC-T02-018 | T02 | 【P1移入】Skill业务效果统计 | skill_stats表有数据 | 1. `GET /api/v1/ai/skill-stats`<br>2. 验证skills数组 | skills数组每项含skillName/intent/totalCalls/successCalls/completionRate/avgDurationMs/avgTurns/dropOffRate | P1 | P0-20 |
| TC-T02-019 | T02 | 转化漏斗-5阶段数据 | 有完整的会话流转数据 | 1. `GET /api/v1/ai/conversion-funnel`<br>2. 验证stages数组 | stages含5阶段：进入会话→意图识别→L1路由→L2执行→业务完成，每阶段count递减，rate=count/第一阶段count×100 | P0 | P0-21 |
| TC-T02-020 | T02 | 转化漏斗-dropOffs数据 | 有中途放弃的会话 | 1. `GET /api/v1/ai/conversion-funnel`<br>2. 验证dropOffs数组 | dropOffs标识阶段间流失，stage格式"L2执行→业务完成"，count和rate正确 | P0 | P0-21 |
| TC-T02-021 | T02 | 满意度-完整数据结构 | 有满意度评分数据 | 1. `GET /api/v1/ai/satisfaction`<br>2. 验证distribution/trend/negativeReasons/lowScoreSessions | distribution含1-5分计数，trend含7天数据，negativeReasons按percentage降序，lowScoreSessions含sessionId/score/query/reason | P0 | P0-22 |

### 2.5 准确率与混淆矩阵API

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T02-022 | T02 | 意图准确率趋势-5条折线 | 有意图识别历史数据 | 1. `GET /api/v1/ai/intent-accuracy-trend`<br>2. 验证series数组 | series含5条：总体准确率/TRANSFER/BILL_QUERY/WEALTH_CONSULT/CHAT，每条data数组含time+value，value范围[0,1] | P0 | P0-17 |
| TC-T02-023 | T02 | 混淆矩阵-数据完整性 | 有意图识别结果和标注数据 | 1. `GET /api/v1/ai/confusion-matrix`<br>2. 验证labels和matrix | labels长度=matrix行数=matrix列数，matrix[i][j]表示真实labels[i]被预测为labels[j]的数量，totalSamples=Σmatrix所有元素 | P0 | P0-17 |

### 2.6 告警与设置API

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T02-024 | T02 | 告警规则CRUD完整流程 | alert_rules表为空 | 1. POST创建规则（name/description/metricName/conditionOperator/threshold/durationMinutes/severity/enabled/notifyChannels）<br>2. GET列表验证包含新规则<br>3. PUT更新规则severity和threshold<br>4. GET验证更新生效<br>5. DELETE删除规则<br>6. GET验证规则已删除 | 创建返回201+规则对象，更新返回200+更新后对象，删除返回204，列表反映增删改变化 | P0 | P0-25,P0-27 |
| TC-T02-025 | T02 | 告警事件时间线 | alert_events表有触发/确认/解决记录 | 1. `GET /api/v1/alerts/events`<br>2. 验证事件列表<br>3. `GET /api/v1/alerts/events?status=FIRING` 筛选<br>4. `GET /api/v1/alerts/events?ruleId=2` 筛选 | 事件按fired_at降序排列，含ruleId/ruleName/severity/currentValue/threshold/status/firedAt/resolvedAt，筛选参数生效 | P0 | P0-26,P0-27 |
| TC-T02-026 | T02 | 系统设置-采集配置读写 | 后端应用启动 | 1. `GET /api/v1/settings/collection`<br>2. 验证collectorStatus/samplingRate/tagStrategy/promptStorageEnabled/retentionDays<br>3. `PUT /api/v1/settings/collection` 修改samplingRate=50<br>4. `GET` 验证修改生效 | GET返回当前配置，PUT返回更新后配置，再次GET确认修改持久化 | P1 | P0-28,P0-29 |
| TC-T02-027 | T02 | 系统设置-存储配置读写 | 后端应用启动 | 1. `GET /api/v1/settings/storage`<br>2. 验证databaseType/redisHost/redisStatus/metricsRetentionHours/traceRetentionDays/logRetentionDays | GET返回当前存储配置，redisStatus为"connected"或"disconnected" | P1 | P0-28,P0-29 |

### 2.7 现有API扩展验证

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T02-028 | T02 | Metrics Realtime扩展字段验证 | RealtimeMetricsVO已扩展 | 1. `GET /api/v1/metrics/realtime`<br>2. 验证新增字段存在且类型正确 | dau/realtimeOnline/qps为long/double，agentDistribution为{L0:n,L1:m,L2:k}，ttftP50/P95/P99≥0，intentAccuracyRate/rewriteAccuracyRate/rerouteRate/businessCompletionRate在[0,1]，conversionRate/violationRate在[0,1] | P0 | P0-06 |
| TC-T02-029 | T02 | Traces API新增筛选参数 | spans表有数据 | 1. `GET /api/v1/traces?sessionId=sess_xxx`<br>2. `GET /api/v1/traces?userId=user_xxx`<br>3. `GET /api/v1/traces?statusCode=OK`<br>4. 验证响应中ttftMs/tokenTotal/statusDot字段 | 筛选参数正确过滤，新增字段ttftMs≥0，tokenTotal>0，statusDot为"🟢 正常"或"🔴 异常" | P0 | P0-15 |
| TC-T02-030 | T02 | Logs API新增筛选参数 | logs表有数据 | 1. `GET /api/v1/logs?userId=user_xxx`<br>2. `GET /api/v1/logs?sessionId=sess_xxx`<br>3. `GET /api/v1/logs?userId=user_xxx&sessionId=sess_xxx&level=ERROR` | 新筛选参数正确过滤，与现有参数组合AND逻辑 | P0 | P0-23 |

---

## T03 — 前端核心页面

### 3.1 总览大屏

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T03-001 | T03 | 总览大屏-4区11卡KPI网格布局 | DashboardPage渲染，API返回真实数据 | 1. 访问 `/dashboard`<br>2. 检查页面布局分为4个Zone<br>3. 统计KPI卡片数量 | Zone A(系统健康)×3 + Zone B(AI性能)×3 + Zone C(语义质量)×3 + Zone D(业务效果)×2 = 共11张KPI卡片 | P0 | P0-02 |
| TC-T03-002 | T03 | 总览大屏-KPI卡片数据正确展示 | API返回固定测试数据 | 1. Mock API返回已知metrics数据<br>2. 验证每张卡片的主动值（28px）和副指标（12px）<br>3. 验证Zone A展示DAU/实时在线/QPS<br>4. 验证Zone B展示Token总量/TTFT P95/P95时延<br>5. 验证Zone C展示意图准确率/改写准确率/Reroute率<br>6. 验证Zone D展示业务转化率/违规率 | 每张卡片主数值与API返回一致，副指标格式正确（如"+12.5% ↑"），数据源归属正确（Zone A借A类数据、B/C区自建数据） | P0 | P0-02,P0-03 |
| TC-T03-003 | T03 | 总览大屏-请求&Token趋势图 | `/api/v1/metrics/history`返回数据 | 1. 访问 `/dashboard`<br>2. 定位趋势图区域<br>3. 验证图表类型为双轴（柱状+折线）<br>4. 验证X轴为6小时时间轴<br>5. 验证柱状图为请求量，折线为Token消耗 | ECharts双轴图正确渲染，左轴为请求量（柱状），右轴为Token（折线），tooltip显示数值 | P0 | P0-04 |
| TC-T03-004 | T03 | 总览大屏-Agent分布环形饼图 | `/api/v1/metrics/realtime`返回agentDistribution | 1. 访问 `/dashboard`<br>2. 定位饼图区域<br>3. 验证环形图展示L0/L1/L2三段<br>4. 验证标注格式 `{name}\n{value}次 ({percent}%)` | 环形饼图正确渲染，L0/L1/L2三段颜色不同，标注含数值和百分比，不包含Tool分类 | P0 | P0-05 |
| TC-T03-005 | T03 | 总览大屏-3s实时轮询刷新 | 后端API正常，轮询就绪 | 1. 访问 `/dashboard`<br>2. 打开Network面板<br>3. 观察 `/api/v1/metrics/realtime` 请求频率<br>4. 修改后端数据<br>5. 等待页面刷新 | 每3s发起一次 `/api/v1/metrics/realtime` 请求，页面数据随新数据更新 | P0 | P0-06 |
| TC-T03-006 | T03 | 总览大屏-亮色主题效果 | antdTheme.js配置完成 | 1. 访问 `/dashboard`<br>2. 截图或目视检查<br>3. 验证背景色为 `#f0f2f5`<br>4. 验证卡片背景为白色 `#ffffff` | 整体亮色Ant Design 5风格，与dashboard-v15.html视觉一致 | P0 | P0-01 |

### 3.2 会话回放

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T03-007 | T03 | 会话回放-6个筛选器渲染 | SessionViewerPage渲染 | 1. 访问 `/sessions`<br>2. 检查筛选栏<br>3. 定位所有筛选器 | SessionID输入框、UserID输入框、渠道下拉、意图下拉、智能体层级下拉、状态下拉 共6个筛选器 | P0 | P0-09 |
| TC-T03-008 | T03 | 会话回放-列表列完整 | `/api/v1/sessions`返回数据 | 1. 访问 `/sessions`<br>2. 检查表格列 | 列：会话ID/用户ID/渠道/轮次/首意图/最终意图/执行智能体(→连接)/时长/Token/状态(dot+中文："🟢 正常")/时间/操作 | P0 | P0-09 |
| TC-T03-009 | T03 | 会话回放-Modal打开与关闭 | sessions列表有数据 | 1. 点击某行的"查看"按钮<br>2. 验证Modal弹出<br>3. 检查Modal内容：对话气泡/Turn详情/意图流/Token总结条<br>4. 关闭Modal | Modal全屏打开，对话气泡蓝色(用户)和绿色(AI)正确渲染，Turn详情可展开，Token总结条在底部 | P0 | P0-10 |
| TC-T03-010 | T03 | 会话回放-对话气泡颜色区分 | session有user和AI消息 | 1. 打开会话详情Modal<br>2. 检查用户消息气泡颜色<br>3. 检查AI回复气泡颜色 | 用户消息气泡为蓝色背景、左对齐；AI回复气泡为绿色背景、右对齐 | P0 | P0-10 |
| TC-T03-011 | T03 | 会话回放-Turn详情展开 | session有多个turn | 1. 打开会话详情Modal<br>2. 点击某个turn的展开按钮<br>3. 检查详情内容 | 展开后显示：traceId/intent/agentLevel/agentName/durationMs/tokenInput/tokenOutput/ttftMs/rerouteTriggered/businessOutcome/rewriteOriginal/rewriteResult | P0 | P0-10 |

### 3.3 链路追踪

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T03-012 | T03 | 链路追踪-列表独占布局 | TraceExplorerPage渲染 | 1. 访问 `/traces`<br>2. 检查页面布局<br>3. 确认无内联详情面板 | 页面仅显示TraceTable列表，无右侧详情面板（已重构为Modal模式） | P0 | P0-11 |
| TC-T03-013 | T03 | 链路追踪-列表列增强 | `/api/v1/traces`返回数据 | 1. 访问 `/traces`<br>2. 检查表格列定义 | 列含：时间/TraceID/UserID/SessionID/意图/Agent链(→连接)/LLM调用/TTFT/Token/耗时/状态(dot+中文："🟢 正常") | P0 | P0-15 |
| TC-T03-014 | T03 | 链路追踪-点击弹出全屏Modal | traces列表有数据 | 1. 点击某行的TraceID<br>2. 验证Modal弹出为全屏<br>3. 验证Modal内容包含三区 | Modal全屏打开，包含：输入/输出双色卡片 + Agent层级树 + Span瀑布图 | P0 | P0-11 |
| TC-T03-015 | T03 | 链路追踪-输入输出双色卡片 | Trace详情有input/output | 1. 打开Trace详情Modal<br>2. 定位IO卡片区<br>3. 检查输入卡片颜色和文字<br>4. 检查输出卡片颜色和文字 | 蓝底蓝边卡片显示输入内容（`user.query`），中间白色箭头，绿底绿边卡片显示输出内容（`ai.response`） | P0 | P0-12 |
| TC-T03-016 | T03 | 链路追踪-Agent层级树L0/L1/L2结构 | Trace包含多层Span | 1. 打开Trace详情Modal<br>2. 定位Agent层级树<br>3. 验证节点层级和badge | SpanTree递归渲染L0→L1-LLM1→L1-LLM2→L2层级，每个节点含tag badge(L0蓝色/L1橙色/L2绿色)，L1展示双LLM结构 | P0 | P0-13 |
| TC-T03-017 | T03 | 链路追踪-Span瀑布图 | Trace包含Span耗时数据 | 1. 打开Trace详情Modal<br>2. 定位Span瀑布图<br>3. 验证横向柱状图<br>4. 验证TTFT虚线 | WaterfallChart渲染HTTP→L0→L1-LLM1→L1-LLM2→L2横向柱状图，颜色编码区分Agent层级，TTFT位置有红色虚线标记 | P0 | P0-14 |
| TC-T03-018 | T03 | 链路追踪-改写对比展示 | L1 turn有rewriteOriginal/rewriteResult | 1. 打开Trace详情Modal<br>2. 定位改写对比区域<br>3. 检查改写前后文本 | 改写前原文和改写后文本并排展示，差异可辨识 | P0 | P0-13 |
| TC-T03-place01 | T03 | 违规率KPI占位 | DashboardPage渲染 | 1. 访问 `/dashboard`<br>2. 定位Zone D区域<br>3. 检查违规率卡片 | 违规率卡片显示指标名+图标，数值区域标注"暂无"（使用 Empty 组件），不报错 | P0 | P0-02 |

---

## T04 — AI洞察+告警设置

### 4.1 AI洞察 6个TAB

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T04-001 | T04 | AI洞察-6个TAB切换 | AIInsightsPage渲染 | 1. 访问 `/insights`<br>2. 验证默认选中第一个TAB<br>3. 依次点击：准确率分析→Agent性能→Token成本→智能体工具→业务转化漏斗→用户满意度<br>4. 每切换一个TAB验证内容区域变化 | 6个TAB全部可点击切换，每个TAB内容独立渲染，URL更新为 `/insights/:tab` | P0 | P0-16 |
| TC-T04-002 | T04 | 准确率TAB-5条折线渲染 | `/api/v1/ai/intent-accuracy-trend`返回数据 | 1. 切换到"准确率分析"TAB<br>2. 定位折线图区域<br>3. 验证5条折线 | 5条折线：总体准确率(粗线)、TRANSFER、BILL_QUERY、WEALTH_CONSULT、CHAT，X轴时间Y轴准确率(0-1)，图例可点击切换显示 | P0 | P0-17 |
| TC-T04-003 | T04 | 准确率TAB-改写根因TOP3卡片 | 有改写失败数据 | 1. 切换到"准确率分析"TAB<br>2. 定位根因卡片区<br>3. 验证TOP3卡片 | 3张根因卡片展示TOP3改写失败原因，含标题+次数+占比 | P0 | P0-17 |
| TC-T04-004 | T04 | 准确率TAB-混淆矩阵热力图 | `/api/v1/ai/confusion-matrix`返回数据 | 1. 切换到"准确率分析"TAB<br>2. 定位混淆矩阵区域<br>3. 验证热力图渲染 | HeatmapChart渲染，行列标签与labels一致，对角线单元格颜色最深（正确分类），tooltip显示数值 | P0 | P0-17 |
| TC-T04-005 | T04 | Agent性能TAB-双维度子页签切换 | `/api/v1/ai/agent-performance`返回数据 | 1. 切换到"Agent性能"TAB<br>2. 验证默认选中"Agent维度"<br>3. 点击"LLM维度"子页签<br>4. 验证数据和图表切换 | agent子页签显示byAgent数据，llm子页签显示byModel数据，切换时图表和KPI卡片同步更新 | P0 | P0-18 |
| TC-T04-006 | T04 | Agent性能TAB-箱线图 | agent_performance有TTFT分布数据 | 1. 切换到"Agent性能"TAB→"Agent维度"<br>2. 定位箱线图<br>3. 验证各Agent层级箱线 | BoxplotChart按L0/L1/L2分组渲染，每组合min/Q1/median/Q3/max + 异常点 | P0 | P0-18 |
| TC-T04-007 | T04 | Agent性能TAB-六色散点图 | agent_performance有TTFT分布数据 | 1. 切换到"Agent性能"TAB→"Agent维度"<br>2. 定位散点图<br>3. 验证颜色编码 | ScatterChart 6个series不同颜色，固定映射实现"看色识Agent"，非visualMap渐变 | P0 | P0-18 |
| TC-T04-008 | T04 | Token成本TAB-堆叠柱状图+饼图+明细表 | `/api/v1/ai/token-cost`返回数据 | 1. 切换到"Token成本"TAB<br>2. 验证堆叠柱状图（按模型）<br>3. 验证饼图（按意图）<br>4. 验证明细表 | 堆叠柱状图input/output分层，饼图各意图占比，明细表含模型/意图/输入Token/输出Token/预估费用 | P0 | P0-19 |
| TC-T04-009 | T04 | 【P1移入】智能体工具TAB-工具调用统计+Skill统计 | tool-stats和skill-stats返回数据 | 1. 切换到"智能体工具"TAB<br>2. 验证工具调用统计区域<br>3. 验证Skill业务效果区域 | 工具调用区：表格含toolName/toolProvider/totalCalls/successRate/avgDurationMs；Skill区：表格含skillName/intent/completionRate/dropOffRate/avgTurns | P1 | P0-20 |
| TC-T04-010 | T04 | 转化漏斗TAB-漏斗+环形+明细 | `/api/v1/ai/conversion-funnel`返回数据 | 1. 切换到"业务转化漏斗"TAB<br>2. 验证漏斗图<br>3. 验证放弃率环形图<br>4. 验证明细表 | 漏斗图5阶段递减，环形图显示各阶段放弃率，明细表含stage/count/rate/dropOff | P0 | P0-21 |
| TC-T04-011 | T04 | 满意度TAB-4区域渲染 | `/api/v1/ai/satisfaction`返回数据 | 1. 切换到"用户满意度"TAB<br>2. 验证满意度分布（三栏进度条）<br>3. 验证7天趋势折线<br>4. 验证不满意原因饼图<br>5. 验证低分会话列表 | 分布1-5分进度条，趋势折线7点，原因饼图TOP3，低分列表含sessionId/score/query/reason | P0 | P0-22 |
| TC-T04-sat01 | T04 | 满意度反馈提交 | 会话回放Modal已打开 | 1. 打开会话回放Modal<br>2. 定位Modal底部 👍/👎 按钮<br>3. 点击 👍 按钮<br>4. 验证POST /api/v1/ai/satisfaction请求发送<br>5. 验证返回200 | 👍/👎 按钮可用，点击后发送POST请求（含sessionId+rating），返回200，页面无报错 | P0 | P0-22 |
| TC-T04-sat02 | T04 | 满意度数据聚合 | 已提交多条满意度反馈 | 1. POST提交多条反馈（含好评/中评/差评）<br>2. `GET /api/v1/ai/satisfaction`<br>3. 验证响应中distribution字段 | distribution含satisfied/neutral/unsatisfied三态计数，数量与提交一致，trend含7天数据 | P0 | P0-22 |
| TC-T04-sat03 | T04 | 会话详情含满意度 | 会话已提交满意度反馈 | 1. `GET /api/v1/sessions/{sessionId}`（已提交反馈的会话）<br>2. 验证响应中满意度字段 | 返回含satisfactionRating（1-5整数）+ satisfactionReason（可选字符串）字段 | P0 | P0-08,P0-22 |
| TC-T04-place01 | T04 | 转人工率占位 | AIInsightsPage渲染 | 1. 访问 `/insights`<br>2. 定位转人工率展示区域<br>3. 检查显示内容 | 转人工率区域显示标题，数值标注"暂无数据"，不报错不白屏 | P0 | P0-16 |

### 4.2 告警规则页

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T04-012 | T04 | 告警规则页-双栏布局 | AlertRulesPage渲染 | 1. 访问 `/alerts`<br>2. 检查页面布局 | 左侧：AlertRuleTable告警规则表格，右侧：AlertTimeline告警闭环时间线 | P0 | P0-27 |
| TC-T04-013 | T04 | 告警规则表-字段完整 | alert_rules表有数据 | 1. 访问 `/alerts`<br>2. 检查规则表列 | 列：名称/描述/监控指标/阈值/持续时间/严重级别/状态(启用/禁用开关)/通知方式/操作(编辑/删除) | P0 | P0-27 |
| TC-T04-014 | T04 | 告警规则-新增规则Modal | 点击新增按钮 | 1. 点击"新增规则"按钮<br>2. 填写表单：名称/P95时延过高、指标/p95_latency、条件/GT、阈值/3000、持续时间/5min、级别/WARNING<br>3. 提交 | Modal包含完整表单字段，提交成功后列表刷新显示新规则，右侧时间线可选显示创建事件 | P0 | P0-27 |
| TC-T04-015 | T04 | 告警闭环时间线 | alert_events表有事件记录 | 1. 访问 `/alerts`<br>2. 检查右侧时间线<br>3. 验证事件状态标识 | 时间线按fired_at降序显示，事件含规则名称/severity标签/currentValue/threshold/状态标签(FIRING=红/ACKNOWLEDGED=黄/RESOLVED=绿)/时间 | P0 | P0-27 |

### 4.3 系统设置页

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T04-016 | T04 | 系统设置页-双栏布局 | SystemSettingsPage渲染 | 1. 访问 `/settings`<br>2. 检查页面布局 | 左侧：CollectionConfig采集配置，右侧：StorageConfig存储配置 | P0 | P0-29 |
| TC-T04-017 | T04 | 采集配置-字段展示 | `/api/v1/settings/collection`返回数据 | 1. 访问 `/settings`<br>2. 检查采集配置区 | 展示：Collector状态(running指示器)、Collector端点、采样率(百分比)、Tag策略、Prompt存储开关、数据保留天数 | P0 | P0-29 |

### 4.4 三件套互跳验证（Q16）

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T04-018 | T04 | 三件套互跳-日志TraceID→Trace详情 | 日志行含traceId | 1. 访问 `/logs`<br>2. 在日志表格中找到一条含TraceID的记录<br>3. 点击TraceID链接 | 跳转到 `/traces` 页面并自动打开该Trace的详情Modal | P0 | Q16,P0-24 |
| TC-T04-019 | T04 | 三件套互跳-日志SessionID→会话回放 | 日志行含sessionId | 1. 访问 `/logs`<br>2. 在日志表格中找到一条含SessionID的记录<br>3. 点击SessionID链接 | 跳转到 `/sessions` 页面并自动打开该会话的详情Modal | P0 | Q16,P0-24 |
| TC-T04-020 | T04 | 三件套互跳-Trace详情→会话回放 | Trace详情Modal打开 | 1. 打开Trace详情Modal<br>2. 在元信息区域找到session.id<br>3. 点击session.id | 跳转到会话回放页并打开该会话Modal | P0 | Q16 |
| TC-T04-021 | T04 | 三件套互跳-会话回放→Trace详情 | 会话回放Modal打开 | 1. 打开会话回放Modal<br>2. 在Turn详情中找到trace.id<br>3. 点击trace.id | 跳转到链路追踪页并打开该Trace详情Modal | P0 | Q16 |
| TC-T04-022 | T04 | 三件套互跳-AI洞察满意度→会话回放 | 满意度TAB低分列表显示 | 1. 切换到"用户满意度"TAB<br>2. 在低分会话列表中找到一条记录<br>3. 点击sessionId | 跳转到会话回放页并打开该会话Modal | P0 | Q16 |
| TC-T04-023 | T04 | 三件套互跳-AI洞察漏斗→Trace详情 | 漏斗TAB明细表显示 | 1. 切换到"业务转化漏斗"TAB<br>2. 在明细表中找到trace.id<br>3. 点击trace.id | 跳转到链路追踪页并打开该Trace详情Modal | P0 | Q16 |

---

## T05 — OTel埋点+集成联调

### 5.1 15项MVP指标上报验证

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T05-llm01 | T05 | llm.token.input 验证 | ChatClientWrapper已集成，手机银行发起LLM调用 | 1. 手机银行发送任意消息触发LLM调用<br>2. 检查H2 metrics_agg表中llm.token.input记录<br>3. 验证tag含model<br>4. 验证Counter值非负 | Counter含model tag、数值非负、ChatClientWrapper拦截生效 | P0 | P0-T05-A |
| TC-T05-llm02 | T05 | llm.token.output 验证 | ChatClientWrapper已集成，手机银行发起LLM调用 | 1. 手机银行发送任意消息触发LLM调用<br>2. 检查H2 metrics_agg表中llm.token.output记录<br>3. 验证tag含model<br>4. 验证Counter值非负 | Counter含model tag、数值非负、ChatClientWrapper拦截生效 | P0 | P0-T05-A |
| TC-T05-llm03 | T05 | llm.first_token.latency 验证 | 手机银行处理流式WEALTH_INTERPRET场景 | 1. 手机银行发起理财咨询流式对话<br>2. 检查Span中llm.first_token_time属性<br>3. 检查H2 metrics_agg表中llm.first_token.latency Histogram<br>4. 验证首chunk时间戳<br>5. 验证单位为ms | Histogram bucket分布正确、首chunk时间戳准确、单位ms、流式WEALTH_INTERPRET场景验证通过 | P0 | P0-T05-A |
| TC-T05-llm04 | T05 | llm.operation.duration 验证 | ChatClientWrapper已集成，手机银行发起LLM调用 | 1. 手机银行发送消息触发LLM调用<br>2. 等待调用完成<br>3. 检查H2 metrics_agg表中llm.operation.duration Histogram<br>4. 验证tag含model | Histogram值>0、调用完成时触发、tag含model | P0 | P0-T05-A |
| TC-T05-llm05 | T05 | llm.error.count 验证 | 手机银行LLM调用可模拟超时/错误 | 1. Mock LLM调用超时(>30s)<br>2. 检查H2 metrics_agg表中llm.error.count Counter<br>3. 验证tag含error_type=timeout<br>4. Mock LLM返回500错误<br>5. 验证error_type=server_error | Counter递增、LLM超时/错误时触发、tag含error_type、不含user.id等禁用Tag | P0 | P0-T05-A |
| TC-T05-ag01 | T05 | agent.router.decision.outcome 验证 | 手机银行触发不同路由决策 | 1. 发送触发ROUTE_FOLLOW_UP的消息<br>2. 发送触发REROUTE的消息<br>3. 检查H2 metrics_agg表中对应Counter记录<br>4. 验证tag layer/decision/domain | tag layer/decision/domain齐全、各决策类型计数准确 | P0 | P0-T05-A |
| TC-T05-ag02 | T05 | agent.intent.accuracy 验证 | 手机银行处理多种意图请求 | 1. 发送正确意图+模糊意图+错误意图+消歧意图的消息<br>2. 检查H2 metrics_agg表中agent.intent.accuracy Gauge<br>3. 验证四态标签<br>4. 验证混淆矩阵6×6维度 | 四态标签{correct,fuzzy,error,disambiguated}齐全、混淆矩阵6×6维度正确 | P0 | P0-T05-A |
| TC-T05-ag03 | T05 | agent.rewrite.accuracy 验证 | 手机银行处理含实体和金额的改写场景 | 1. 发送含实体和金额的查询消息<br>2. 检查H2 metrics_agg表中agent.rewrite.accuracy Gauge<br>3. 验证规则引擎判定逻辑<br>4. 验证实体守恒+金额归一检测 | 规则引擎判定正确、实体守恒+金额归一检测通过、review标记新增实体 | P0 | P0-T05-A |
| TC-T05-ag04 | T05 | agent.workflow.execution.duration 验证 | 手机银行处理完整和中断的工作流 | 1. 模拟COMPLETED工作流（完整会话）<br>2. 模拟INTERRUPTED工作流（中断后恢复）<br>3. 检查H2 metrics_agg表中Histogram记录<br>4. 验证tag含intent和graph | tag含intent/graph、COMPLETED和INTERRUPTED都记录、Histogram值>0 | P0 | P0-T05-A |
| TC-T05-ag05 | T05 | agent.workflow.interrupt 验证 | 手机银行模拟工作流中断场景 | 1. 模拟interruptBefore触发中断<br>2. 模拟ask→END触发中断<br>3. 检查H2 metrics_agg表中Counter记录<br>4. 验证中断节点名 | 中断节点名正确、interruptBefore和ask→END双来源均记录 | P0 | P0-T05-A |
| TC-T05-ag06 | T05 | agent.slot.askback.total 验证 | 手机银行模拟多轮追问会话 | 1. 模拟会话中Agent多次追问用户<br>2. 检查H2 metrics_agg表中Histogram记录<br>3. 验证分桶<br>4. 验证每会话累计追问轮数 | 直方图分桶正确、每会话累计追问轮数准确 | P0 | P0-T05-A |
| TC-T05-ag07 | T05 | agent.tool.call.count 验证 | 手机银行触发手机银行核心系统 API 调用 | 1. 手机银行发送需要工具调用的消息<br>2. 检查H2 metrics_agg表中agent.tool.call.count Counter<br>3. 验证tag含tool_name | tag含tool_name、手机银行核心系统 API 调用时触发、Counter递增 | P0 | P0-T05-A |
| TC-T05-ag08 | T05 | agent.tool.call.duration 验证 | 手机银行触发工具调用（成功+失败） | 1. 模拟工具调用成功<br>2. 模拟工具调用失败<br>3. 检查H2 metrics_agg表中Histogram记录<br>4. 验证tag含tool_name | tag含tool_name、成功和失败都记录、Histogram值>0 | P0 | P0-T05-A |
| TC-T05-ag09 | T05 | 【P1移入】agent.skill.outcome 验证 | 手机银行触发L2 Skill执行 | 1. 手机银行发送触发L2 Skill的消息<br>2. 检查H2 metrics_agg表中agent.skill.outcome Counter<br>3. 验证tag含skill_name和result | tag含skill_name和result、L2执行完成处触发、Counter递增 | P1 | P0-T05-A |
| TC-T05-ag10 | T05 | agent.session/business三指标联动验证 | 手机银行处理完整会话（含成功/放弃/业务结果） | 1. 模拟完整成功会话<br>2. 模拟会话中途放弃<br>3. 检查agent.session.completed/abandoned/business.outcome三指标<br>4. 验证域/意图tag | 三指标联动正确、域/意图tag完整、completed按outcome分组、abandoned按stage分组、business.outcome按domain/action/result/reason分组 | P0 | P0-T05-A |

### 5.2 Tag基数约束验证（Q14）

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T05-005 | T05 | Q14-user.id不作为Metric Tag（含llm.error.count） | 手机银行上报数据，H2有数据 | 1. 查询H2 metrics_agg表所有tags字段<br>2. 搜索tags中是否包含"user.id"<br>3. 搜索tags中是否包含"session.id"<br>4. 查询llm.error.count指标的tags<br>5. 验证llm.error.count不包含user.id/session.id等禁用Tag | tags JSON中不包含"user.id"和"session.id"字段；llm.error.count仅含model和error_type Tag，不含user.id等高基数Tag | P0 | Q14 |
| TC-T05-006 | T05 | Q14-Metric Tag在允许列表内 | 手机银行上报数据 | 1. 提取H2 metrics_agg表中所有tags的key<br>2. 与架构设计§2.4允许列表(15个Tag)比对 | 所有Metric Tag key在允许列表内，无超范围Tag | P0 | Q14 |
| TC-T05-007 | T05 | Q14-user.id/session.id在Span attributes中存在 | 手机银行上报Span数据 | 1. 查询H2 spans表attributes字段<br>2. 搜索"user.id"和"session.id" | Span attributes中包含"user.id"和"session.id"（高基数OK），但不在Metric Tag中 | P0 | Q14 |

### 5.3 端到端集成场景

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T05-008 | T05 | 端到端-用户发消息到前端展示 | 全部服务启动 | 1. 手机银行用户发送"我想转账5000元"<br>2. 等待完整的L0→L1→L2处理完成<br>3. 检查可观测后端接收到Trace数据<br>4. 前端总览大屏KPI更新<br>5. 前端Trace列表出现新记录 | OTel上报→后端接收→H2存储→前端展示 全链路通，KPI数据刷新，新Trace可查询和查看详情 | P0 | P0-T05-C |
| TC-T05-009 | T05 | 端到端-流式SSE场景埋点完整性 | 手机银行处理流式对话 | 1. 手机银行用户发起流式对话<br>2. 模拟SSE逐Token返回<br>3. 检查Span中TTFT属性<br>4. 检查token统计 | Span中llm.first_token_time存在且非负，token统计(input+output)与实际一致 | P0 | P0-T05-B |
| TC-T05-010 | T05 | 异常路径-LLM超时时埋点 | 手机银行LLM调用超时 | 1. Mock LLM调用超时(>30s)<br>2. 检查Span状态<br>3. 检查error.type属性 | Span状态为ERROR，error.type="timeout"，Span仍在H2中可查询 | P0 | P0-T05-B |
| TC-T05-011 | T05 | 异常路径-LLM返回错误时埋点 | 手机银行LLM返回错误 | 1. Mock LLM返回500错误<br>2. 检查Span状态和attributes | Span状态为ERROR，含error.type和error.message，business.outcome="fail" | P0 | P0-T05-B |
| TC-T05-012 | T05 | Reroute场景Span event验证 | 手机银行触发Reroute | 1. 发送触发Reroute的消息(如意图切换)<br>2. 检查对应Span的events | Span包含"reroute.triggered" event，含from_domain/reroute_intent/reroute_count属性 | P0 | P0-T05-A |

### 5.4 Q1 — Span + Metric 混合采集验证

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T05-q1-01 | T05 | Q1-Span与Metric同位置写入 | 手机银行埋点代码就绪，同时创建Span和Counter/Histogram | 1. 手机银行触发含埋点的LLM调用<br>2. 检查H2 spans表中对应trace数据<br>3. 检查H2 metrics_agg表中对应指标记录 | spans表中存在对应trace数据 + metrics_agg表中存在对应指标，同一埋点位置双写成功 | P0 | Q1 |
| TC-T05-q1-02 | T05 | Q1-MetricTag基数约束 | 手机银行上报数据，H2有数据 | 1. 查询H2 metrics_agg表所有tags字段<br>2. 搜索tags中是否包含禁用字段：user.id/session.id/trace.id/原始prompt/原始金额<br>3. 验证所有Metric Tag在允许列表内 | metrics_agg表中所有tags字段不包含user.id/session.id/trace.id/原始prompt/原始金额等禁用字段 | P0 | Q1 |

### 5.5 Q2 — ObsChatModel 包装方案验证

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T05-q2-01 | T05 | Q2-ObsChatModel包装点 | ModelConfig已配置6个ChatModel | 1. 检查Spring Bean注入链<br>2. 验证ChatClient Bean注入的ChatModel类型<br>3. 检查6个ChatModel是否均被ObsChatModel包装 | ModelConfig中6个ChatModel均被ObsChatModel包装，ChatClient Bean注入的是包装后的ChatModel（非原始OpenAiChatModel） | P0 | Q2 |
| TC-T05-q2-02 | T05 | Q2-流式末尾usage token | 手机银行发起WEALTH_INTERPRET流式调用，末尾chunk含usage | 1. 手机银行发起理财咨询流式对话<br>2. 检查Span中llm.token.output属性<br>3. 验证token.estimated字段 | llm.token.output为精确值（来自末尾usage chunk），token.estimated=false | P0 | Q2 |
| TC-T05-q2-03 | T05 | Q2-流式估算token兜底 | Mock LLM流式末尾usage不可用 | 1. Mock LLM流式响应不含末尾usage chunk<br>2. 手机银行发起流式对话<br>3. 检查Span中llm.token.output和token.estimated | llm.token.output为估算值（兜底估算逻辑生效），token.estimated=true | P0 | Q2 |
| TC-T05-q2-04 | T05 | Q2-TTFT+TPOT计算 | 手机银行分别触发流式和非流式LLM调用 | 1. 流式调用：记录首chunk时间戳<br>2. 非流式调用：记录call()返回时刻<br>3. 验证TTFT值<br>4. 验证TPOT = (总耗时-TTFT)/output_tokens | 流式：首chunk时间戳 = TTFT；非流式：call()返回时 = TTFT；TPOT = (总耗时-TTFT)/output_tokens 计算正确 | P0 | Q2 |

### 5.6 Q3 — AI 洞察数据源判定规则验证

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-T05-q3-01 | T05 | Q3-意图正确判定(correct) | 手机银行处理意图识别流程，L1-LLM2 intent = L2 intent | 1. 发送"我要转账5000元"消息<br>2. 验证L1-LLM2识别intent=TRANSFER<br>3. 验证L2最终执行intent=TRANSFER<br>4. 检查Span中intent.accuracy属性 | intent.accuracy=correct 写入Span，L1-LLM2 intent = L2 intent | P0 | Q3 |
| TC-T05-q3-02 | T05 | Q3-意图模糊判定(fuzzy) | 手机银行处理意图识别流程，L1-LLM2 intent ≠ L2 intent但未REROUTE | 1. 发送模糊意图消息<br>2. 验证L1-LLM2 intent ≠ L2 intent<br>3. 验证未触发REROUTE<br>4. 验证L2执行成功<br>5. 检查Span中intent.accuracy | intent.accuracy=fuzzy，意图虽有偏差但结果可用且未触发REROUTE | P0 | Q3 |
| TC-T05-q3-03 | T05 | Q3-意图错误判定(error) | 手机银行触发REROUTE场景 | 1. 发送导致意图识别错误的消息<br>2. 验证L1-LLM2 intent ≠ L2 intent<br>3. 验证触发REROUTE<br>4. 检查Span中intent.accuracy | intent.accuracy=error，L1-LLM2 intent ≠ L2 intent 且触发REROUTE | P0 | Q3 |
| TC-T05-q3-04 | T05 | Q3-消歧判定(disambiguated) | 手机银行触发意图组消歧流程 | 1. 发送歧义消息触发L1-LLM2输出意图组<br>2. 用户回答后重识别<br>3. 验证消歧流程完整<br>4. 检查Span中intent.accuracy | intent.accuracy=disambiguated，L1-LLM2输出意图组→用户回答后重识别，消歧流程完整 | P0 | Q3 |
| TC-T05-q3-05 | T05 | Q3-混淆矩阵6×6维度 | 有跨6种意图的历史数据 | 1. 收集L1-LLM2 intent(行) × L2 intent(列)数据<br>2. 验证值域{TRANSFER/BILL_QUERY/WEALTH_CONSULT/WEALTH_INTERPRET/CHAT/UNSUPPORTED}<br>3. 验证对角线>非对角线<br>4. 验证totalSamples=Σmatrix | 混淆矩阵6×6维度正确，值域覆盖全部6种意图，对角线(正确分类)计数>非对角线 | P0 | Q3 |
| TC-T05-q3-06 | T05 | Q3-改写实体守恒检测 | 手机银行处理含实体(人名/金额)的改写 | 1. 发送"帮张三转5000元"消息<br>2. 检查改写后文本是否保留"张三"和"5000"实体<br>3. 若实体丢失，检查rewrite.accuracy | 原始含实体(人名/金额)改写后不得丢失；若丢失则rewrite.accuracy rule_check=fail；若新增实体标记review=true | P0 | Q3 |
| TC-T05-q3-07 | T05 | Q3-改写根因关键字匹配 | 手机银行产生多种改写失败场景 | 1. 模拟代词指代不明场景<br>2. 模拟金额格式未标准化("五万"→未归一)<br>3. 模拟上下文关联断裂(多轮丢失前轮实体)<br>4. 模拟其他不匹配场景<br>5. 验证各场景根因分类 | 根因分类正确：{代词指代不明/金额格式未标准化/上下文关联断裂/其他}，含"万""千"金额覆盖，"其他"为兜底桶 | P0 | Q3 |

---

## 边缘情况与异常路径

| ID | 模块 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 | 优先级 | 关联需求 |
|----|------|---------|---------|---------|---------|:------:|---------|
| TC-EDGE-001 | 边缘 | 空数据场景-新部署无历史数据 | 数据库为空(仅DDL) | 1. 访问 `/dashboard`<br>2. 访问 `/sessions`<br>3. 访问 `/traces`<br>4. 访问 `/insights`<br>5. 访问 `/logs` | 所有页面正常加载，不白屏不报错，KPI卡片显示0或"--"，列表显示空状态占位符(如"暂无数据") | P0 | — |
| TC-EDGE-002 | 边缘 | 大量数据场景-分页极限 | sessions表有10000+条记录 | 1. `GET /api/v1/sessions?page=0&size=100`<br>2. 验证响应时间<br>3. `GET /api/v1/sessions?page=500&size=20`（远超实际页数） | 正常分页响应<500ms，超出范围页码返回空content[]，totalElements正确 | P1 | — |
| TC-EDGE-003 | 边缘 | API返回500时前端降级展示 | Mock `/api/v1/metrics/realtime` 返回500 | 1. 访问 `/dashboard`<br>2. 观察KPI卡片和图表区域 | KPI卡片显示"--"或上次缓存值，图表区域显示错误提示(非白屏)，不阻塞其他页面功能 | P0 | — |
| TC-EDGE-004 | 边缘 | Redis不可用时降级 | 停止Redis服务 | 1. 启动可观测后端<br>2. `GET /api/v1/metrics/realtime`<br>3. `GET /api/v1/settings/storage` | 后端不崩溃，metrics/realtime返回H2历史数据(非实时)，settings/storage中redisStatus="disconnected"，前端可降级提示 | P1 | — |
| TC-EDGE-005 | 边缘 | 时间范围无效-开始时间晚于结束时间 | — | 1. `GET /api/v1/sessions?from=2025-07-10T00:00:00Z&to=2025-07-09T00:00:00Z` | 返回 `{code:400, message:"开始时间不能晚于结束时间"}` 或返回空结果（业务容错） | P1 | — |
| TC-EDGE-006 | 边缘 | SQL注入防护-筛选参数输入 | — | 1. `GET /api/v1/sessions?userId=' OR '1'='1`<br>2. `GET /api/v1/sessions?sessionId='; DROP TABLE sessions; --` | 参数被安全转义，不执行恶意SQL，返回正常查询结果或无结果 | P0 | — |
| TC-EDGE-007 | 边缘 | XSS防护-前端展示用户输入 | 会话数据userQuery含 `<script>alert(1)</script>` | 1. 打开会话回放Modal<br>2. 查看userQuery内容 | 脚本标签被转义显示为纯文本，不执行JavaScript | P0 | — |
| TC-EDGE-008 | 边缘 | 并发请求场景 | 前端模拟多页面同时轮询 | 1. 同时打开 `/dashboard` 和 `/sessions` 两个标签页<br>2. 观察后端日志<br>3. 验证数据一致性 | 后端处理并发请求不报错，两个页面数据独立正确 | P1 | — |
| TC-EDGE-009 | 边缘 | 跨域请求验证 | 从不同origin访问API | 1. 从 `http://localhost:3000` 发送API请求到 `http://localhost:9090`<br>2. 检查响应头 | 响应含 `Access-Control-Allow-Origin` 头，CORS配置正确 | P1 | — |
| TC-EDGE-010 | 边缘 | 超长字符串输入 | — | 1. `GET /api/v1/sessions?sessionId=` + 10000字符<br>2. POST创建告警规则，name=10000字符 | 参数被截断或返回 `{code:400, message:"参数长度超出限制"}`，不导致服务崩溃 | P1 | — |
| TC-EDGE-011 | 边缘 | app-err.log解析-TraceID关联 | 手机银行产生 `Failed to parse chat.json: Unexpected token 'T'` 错误日志 | 1. 检查该日志通过OTel上报到可观测后端<br>2. 在 `/logs` 页面搜索 "Failed to parse chat.json"<br>3. 检查日志trace_id字段 | 日志存在，trace_id字段可关联到对应Trace，点击TraceID可跳转到Trace详情 | P0 | P0-T05-C |
| TC-EDGE-012 | 边缘 | TTFT字段非负验证 | 手机银行上报LLM调用Span | 1. 在H2 spans表中查询有TTFT的Span<br>2. 验证llm.first_token_time值<br>3. 在前端查看Trace详情TTFT展示 | llm.first_token_time ≥ 0，前端展示TTFT值不带负号 | P0 | P0-T05-B |

---

## 用例统计与覆盖矩阵

### 按任务统计

| 任务 | P0 | P1 | P2 | 合计 |
|------|:---:|:---:|:---:|:---:|
| T01 项目基础设施 | 9 | 1 | 0 | **10** |
| T02 后端数据层 | 24 | 6 | 0 | **30** |
| T03 前端核心页面 | 17 | 2 | 0 | **19** |
| T04 AI洞察+告警设置 | 24 | 3 | 0 | **27** |
| T05 OTel埋点+集成联调 | 35 | 1 | 0 | **36** |
| 边缘情况与异常路径 | 6 | 6 | 0 | **12** |
| **合计** | **115** | **19** | **0** | **134** |

### P0需求覆盖矩阵

| P0需求 | 覆盖用例 |
|------|------|
| P0-01 亮色主题迁移 | TC-T01-009, TC-T03-006 |
| P0-02 4区11卡KPI网格 | TC-T03-001, TC-T03-002 |
| P0-03 KPI卡片组件 | TC-T03-002 |
| P0-04 请求&Token趋势图 | TC-T03-003 |
| P0-05 Agent分布饼图 | TC-T03-004 |
| P0-06 后端扩充RealtimeMetricsVO | TC-T01-002, TC-T02-028, TC-T03-005 |
| P0-07 会话列表API | TC-T01-001, TC-T02-001~006 |
| P0-08 会话详情API | TC-T02-007~009 |
| P0-09 会话列表页(筛选+表格) | TC-T03-007, TC-T03-008 |
| P0-10 会话回放Modal | TC-T03-009~011 |
| P0-11 Trace列表→Modal重构 | TC-T03-012, TC-T03-014 |
| P0-12 输入/输出双色卡片 | TC-T03-015 |
| P0-13 Agent层级树重构 | TC-T03-016, TC-T03-018 |
| P0-14 Span瀑布图 | TC-T03-017 |
| P0-15 列表列增强 | TC-T02-029, TC-T03-013 |
| P0-16 TAB导航框架 | TC-T04-001 |
| P0-17 准确率分析TAB | TC-T02-022, TC-T02-023, TC-T04-002~004 |
| P0-18 Agent性能TAB | TC-T02-010~013, TC-T04-005~007 |
| P0-19 Token成本TAB | TC-T02-014~016, TC-T04-008 |
| P0-20 智能体工具TAB | TC-T02-017, TC-T02-018, TC-T04-009 |
| P0-21 业务转化漏斗TAB | TC-T02-019, TC-T02-020, TC-T04-010 |
| P0-22 用户满意度TAB | TC-T02-021, TC-T04-011 |
| P0-23 日志筛选栏增强 | TC-T02-030 |
| P0-24 UI对齐目标(日志) | TC-T04-018, TC-T04-019 |
| P0-25 告警规则后端 | TC-T01-001, TC-T02-024 |
| P0-26 告警事件后端 | TC-T02-025 |
| P0-27 告警规则页 | TC-T02-024, TC-T02-025, TC-T04-012~015 |
| P0-28 系统设置后端 | TC-T01-004, TC-T02-026, TC-T02-027 |
| P0-29 系统设置页 | TC-T02-026, TC-T02-027, TC-T04-016, TC-T04-017 |
| P0-30 前端路由完善 | TC-T01-007, TC-T01-008 |
| P0-31 亮色主题全局应用 | TC-T01-009 |

### 14个新API覆盖

| API | 覆盖用例 |
|------|------|
| `GET /api/v1/sessions` | TC-T02-001~006, TC-EDGE-002, TC-EDGE-006, TC-EDGE-010 |
| `GET /api/v1/sessions/{sessionId}` | TC-T02-007~009 |
| `GET /api/v1/ai/agent-performance` | TC-T02-010~013 |
| `GET /api/v1/ai/token-cost` | TC-T02-014~016 |
| `GET /api/v1/ai/tool-stats` | TC-T02-017 |
| `GET /api/v1/ai/skill-stats` | TC-T02-018 |
| `GET /api/v1/ai/conversion-funnel` | TC-T02-019, TC-T02-020 |
| `GET /api/v1/ai/satisfaction` | TC-T02-021 |
| `GET /api/v1/ai/intent-accuracy-trend` | TC-T02-022 |
| `GET /api/v1/ai/confusion-matrix` | TC-T02-023 |
| `CRUD /api/v1/alerts/rules` | TC-T02-024 |
| `GET /api/v1/alerts/events` | TC-T02-025 |
| `GET/PUT /api/v1/settings/collection` | TC-T02-026 |
| `GET/PUT /api/v1/settings/storage` | TC-T02-027 |

### 3个扩展API覆盖

| 扩展API | 覆盖用例 |
|------|------|
| `GET /api/v1/metrics/realtime` (扩展字段) | TC-T02-028 |
| `GET /api/v1/traces` (新增参数+响应字段) | TC-T02-029 |
| `GET /api/v1/logs` (新增参数) | TC-T02-030 |

### 15项MVP指标覆盖

| # | 指标名 | 覆盖用例 |
|---|------|------|
| 1-5 | llm.* 5项（ObsChatModel自动拦截） | TC-T05-llm01~llm05 |
| 6-15 | agent.* 10项（手动埋点） | TC-T05-ag01~ag10 |
| Q1 | Span+Metric混合采集 | TC-T05-q1-01~02 |
| Q2 | ObsChatModel包装方案 | TC-T05-q2-01~04 |
| Q3 | AI洞察数据源判定 | TC-T05-q3-01~07 |

### 关键设计决策覆盖

| 决策 | 覆盖用例 |
|------|------|
| Q2 意图准确率 | TC-T04-002（验证弱标注计算逻辑：L2执行成功/总意图数） |
| Q3 AI洞察判定 | TC-T05-q3-01~07（四态标签/混淆矩阵/改写实体守恒/根因分类） |
| Q6 H2存储 | TC-T01-006 |
| Q14 Tag基数约束 | TC-T05-005~007 |
| Q16 三件套互跳 | TC-T04-018~023 |
| Q1 混合采集 | TC-T05-q1-01~02（Span+Metric同位置写入/Metric Tag基数约束） |
| Q2 ObsChatModel | TC-T05-q2-01~04（包装点/流式Token/TTFT+TPOT双路径） |

---

> **下一步**：由QA团队按本文档执行测试，测试结果记录在测试报告中。测试准入/准出标准见《AI可观测-测试用例设计-WorkBuddy-V1.md》§6.4。

---

## 文档版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| V1 | 2025-07-10 | 初始版本：116条测试用例，覆盖全部31项P0需求、14个新API、3个扩展API、15项MVP指标 |
| V1.1 | 2025-07-10 | 增量更新：新增13条用例（Q1混合采集2条 + Q2 ObsChatModel包装4条 + Q3 AI洞察判定7条），总计129条 |
| V1.2 | 2025-07-10 | 范围调整5项：全文"MCP"→"工具调用"重命名；Skill相关3条P0→P1（TC-T02-018/TC-T04-009/TC-T05-ag09）加"【P1移入】"前缀；满意度新增3条（TC-T04-sat01~03）；违规率/转人工率占位新增2条（TC-T03-place01/TC-T04-place01）；agent.tool.call说明更新。总计134条（P0=115, P1=19） |
