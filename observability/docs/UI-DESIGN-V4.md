# Mobile AI Bank 可观测系统 UI 设计文档 V4

> 版本: v15 | 日期: 2026-06-26 | 文件: `observability/frontend/dashboard-v15.html`
> 技术栈: 单文件 HTML + ECharts 5.5.0 + Ant Design 5 色彩体系 + Inter / JetBrains Mono 字体

---

## 目录

1. [版本演进](#1-版本演进)
2. [设计目标与原则](#2-设计目标与原则)
3. [全局架构](#3-全局架构)
4. [总览大屏](#4-总览大屏)
5. [会话回放](#5-会话回放)
6. [链路追踪](#6-链路追踪)
7. [Agent 层级架构](#7-agent-层级架构)
8. [AI 洞察](#8-ai-洞察)
9. [日志查询](#9-日志查询)
10. [告警规则与系统设置](#10-告警规则与系统设置)
11. [三层可观测模型](#11-三层可观测模型)
12. [命名规范](#12-命名规范)
13. [视觉设计系统](#13-视觉设计系统)
14. [ECharts 实践要点](#14-echarts-实践要点)
15. [交互函数索引](#15-交互函数索引)
16. [设计决策记录](#16-设计决策记录)
17. [业界参考](#17-业界参考)
18. [文件清单](#18-文件清单)

---

## 1. 版本演进

```
v1  (mockup 布局) ──┐
                    ├── v8  融合版 (暗色 + ECharts + 5 面板)
v7  (暗色工业风) ──┘
                         │
                    v12  Ant Design 5 亮色主题重构
                         │
                    v13  Agent 性能 TAB + Trace 两栏布局 + 全局 L0/L1/L2 命名统一
                         │
                    v14  Skill 业务效果统计 + MCP 工具层命名 + 散点图/箱线图精确打磨
                         │
                    v15  Agent 架构校正 + L1 双LLM结构 + 散点图多series重构
                         + Token标准化 + 智能体/工具TAB重构 + KPI摘要重设计 + 视觉细节打磨(25+项)
```

### 主题切换理由 (v10: 暗 → 亮)

| 维度 | 暗色 (v7-v9) | 亮色 (v12+) |
|------|-------------|-------------|
| 背景色 | `#080c14` 深海蓝 | `#f0f2f5` 浅灰 |
| 主色 | `#3b6cf6` 电光蓝 | `#1677ff` Ant Design 蓝 |
| 适用场景 | 长时间盯屏运维 | 演示/投屏/跨角色共享 |
| 切换原因 | 非开发用户反馈"看不清"，投屏对比度不足 | Ant Design 5 提供更成熟的色彩层级和组件体系 |

保留 V7 的 JetBrains Mono / SF Mono 等宽字体用于数据/代码/TraceID。

---

## 2. 设计目标与原则

为 Bank AI Agent 系统构建面向 **开发、算法、产品** 三类角色的统一可观测仪表盘。

| 原则 | 说明 |
|------|------|
| **分层不混杂** | 不同角色看不同面板: 总览健康 → 洞察钻取 → 链路定损 |
| **三层可观测** | Agent 技术性能 ↔ MCP 工具调用 ↔ Skill 业务效果, 三层互补 |
| **快照 + 深度** | 总览回答"健康吗", 子面板回答"哪里有问题、为什么" |
| **从宏观钻到微观** | 点击任意指标可下钻到 Session / Trace / Token 级别 |
| **术语行业对齐** | Token 全文不缩写，Agent 层级命名不带 / 分隔符，状态用中文+dot |
| **颜色即身份** | 每个 Agent/LLM 持有固定颜色，看色识人，不依赖渐变区分 |

---

## 3. 全局架构

### 3.1 布局结构

```
┌─────────────────────────────────────────────────────┐
│  Sidebar (220px, fixed)  │  Main Content             │
│  ┌─────────────────────┐ │  ┌──────────────────────┐ │
│  │ BA  Bank AI 可观测   │ │  │ Topbar (56px, sticky)│ │
│  │                      │ │  │ 总览大屏  [DEV]  ⏱ ↻│ │
│  │ 监控                 │ │  ├──────────────────────┤ │
│  │  📊 总览大屏  (active)│ │  │                      │ │
│  │  💬 会话回放          │ │  │  Content Area        │ │
│  │  🔗 链路追踪          │ │  │  (padding: 20px 24px)│ │
│  │  🧠 AI 洞察          │ │  │                      │ │
│  │  📋 日志查询          │ │  │  Cards / Charts /    │ │
│  │                      │ │  │  Tables / KPI Grids  │ │
│  │ 管理                 │ │  │                      │ │
│  │  🔔 告警规则          │ │  │                      │ │
│  │  ⚙️ 系统设置          │ │  │                      │ │
│  │                      │ │  │                      │ │
│  │ 🟢 Collector Online  │ │  │                      │ │
│  └─────────────────────┘ │  └──────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### 3.2 页面清单

| 页面 | 导航 ID | 回答的问题 | 目标用户 |
|------|---------|-----------|---------|
| 总览大屏 | `overview` | "系统健康吗?" | 运维 / Tech Lead |
| 会话回放 | `session` | "这个用户经历了什么?" | 产品 / 开发 |
| 链路追踪 | `trace` | "这次请求内部发生了什么?" | 开发 |
| AI 洞察 | `insight` | "哪里在变好? 哪里在变坏?" | 算法 / QA / 产品 |
| 日志查询 | `logs` | "特定时间段的日志长什么样?" | 开发 / 运维 |
| 告警规则 | `alerts` | "有什么告警? 怎么处理的?" | 运维 |
| 系统设置 | `settings` | "采集和存储怎么配置?" | 运维 |

### 3.3 页面切换机制

- 左侧导航 `nav-item` 点击触发 `sw(n, el)` 函数
- 切换 `.page` 的 `.active` class (display: block / none)
- 切换后 `setTimeout(initAllCharts, 100)` 重新渲染当前可见图表
- Topbar 标题同步更新 (`page-title` 元素)

### 3.4 外部依赖

| 依赖 | 用途 | 说明 |
|------|------|------|
| ECharts 5.5.0 (CDN) | 图表渲染 | 唯一 JS 库依赖 |
| Google Fonts: Inter | 正文字体 | 可变字体，4 种字重 |
| Google Fonts: JetBrains Mono | 等宽字体 | TraceID / 数据 / 代码 |

所有 CSS 和 JS 内联在单一 HTML 文件中，零本地文件依赖。

---

## 4. 总览大屏

### 4.1 四区 KPI 网格

```
Zone A — 系统健康 (蓝标 tag-l1)
├── 访问用户量 (1,284人 · DAU 8,920 · 实时在线 342)
├── 访问次数 / QPS (45.2 req/s · 6h 累计 976,128 次)
└── Agent 调用 L0/L1/L2 (3,842 次 · L0:89 · L1:42 · L2:25)

Zone B — AI 性能 (绿标 tag-l2)
├── Token 调用量 (2.34M · 输入 1.82M · 输出 0.52M)
├── 首 Token 时延 P95 (480ms · P50:210ms · P99:920ms)
└── P95 系统时延 / 错误率 (312ms · 错误率 0.12%)

Zone C — 智能体语义质量 (橙标 tag-l3)
├── 意图 / 改写准确率 (94.2% · 改写 91.5%)
├── Reroute 率 (14.2% · 低置信度触发二次识别)
└── 业务完成率 (87.6%)

Zone D — 业务效果 (黄标 tag-l4)
├── 业务转化率 (62.3% · 15/39 会话完成业务目标)
└── 违规率 (1.8% · 敏感操作拦截 / 合规检查)
```

每张 KPI 卡片包含:
- 指标名 + 图标
- 主数值 + 单位 (大号 28px 700 字重)
- 环比变化 (绿↓好 / 红↑差)
- 副指标 (12px 灰色)
- Mini Spark 折线图 (32px 高, ECharts `miniSpark()` 函数)

### 4.2 趋势图表

| 图表 ID | 类型 | 内容 | 设计要点 |
|---------|------|------|---------|
| `chart-overview` | 柱状+折线双轴 | 请求量(柱) + Token(折线), 6 小时 | `legend.bottom:2`, `grid.bottom:44` 防止图例与轴标签重叠 |
| `chart-agent-dist` | 环形饼图 | L0 / L1 / L2 分布 | 标注格式 `{b}\n{c}次 ({d}%)`，显示数值+百分比；不含 Tool 扇区 |

### 4.3 v15 调整

- **删除 Tool**: 从系统健康指标卡和 Agent 分布饼图中移除 Tool 维度，Tool 只在 MCP 工具统计层出现
- **图例下移**: 请求&Token 趋势图 grid.bottom 36→44，解决图例贴轴问题

---

## 5. 会话回放

### 5.1 会话列表

筛选栏: Session ID / User ID / 渠道 / 意图 / 智能体 / 状态

列表列: Session ID · User ID · 渠道 · 时间 · 时长 · 轮次 · 意图 · 执行智能体(仅显示L2，→ 分隔) · Token · 会话状态 · 操作(查看回放)

### 5.2 会话回放 Modal

点击"查看回放"打开全屏 Modal (`session-modal`), 内容:

```
会话头部
├── Session ID + 状态 Badge
├── 元信息: 用户 · 渠道 · 时间段 · 时长 · 轮次 · 域切换 · Token
└── 操作按钮: "查看完整链路 →" / "查看会话日志"

对话轮次 (Turn 1~N)
├── Turn Header: 轮次号 · 时间 · 意图 Badge · Agent路径 · 置信度 · 耗时 · 状态
├── 用户消息 (蓝色气泡 bubble-user)
├── AI 回复 (绿色气泡 bubble-ai)
│   └── 元信息: 耗时 · Token · 模型 · 参数提取结果 · Trace 跳转链接
└── 状态标记 (如: REROUTE · 参数追问 · 执行成功)

会话总结条
├── 意图流: 账单→转账(追问)→理财咨询→转账完成→账单复查
├── 域切换: 2 次
├── 总耗时: 8m 14s
└── 总 Token: 6,480
```

### 5.3 设计决策

**为什么用对话气泡而非时间线?** — 手机银行是聊天产品, 对话气泡更贴近用户真实体验。会话状态流转(挂起/恢复/REROUTE)是银行业务独有场景, 时间线无法表达。每轮带 Trace 跳转链接, 产品看到问题可直接给开发定位。

### 5.4 v15 调整

- **Token 列统一**: 全文 `tokens` 替代缩写 `tok`（`940 tokens` · `6,480 tokens`）
- **执行智能体分隔符**: 逗号 → `→`（`WealthConsult → TransferService`）
- **删除成本列**: 会话列表和 Modal 元信息均移除 `¥0.52` 成本显示
- **Agent 路径简化**: 仅显示 L2 Agent 名称

---

## 6. 链路追踪

### 6.1 Trace 列表 → Modal 详情 (v15 重构)

```
v14 (两栏内联): Trace列表 | 详情面板 (flex overflow 问题)
v15 (Modal弹窗): Trace列表 → 点击"查看详情" → 全屏 Modal
```

CSS 类:
- `.trace-layout` — flex 容器, `min-width:0`
- `.trace-list-col` — 独占宽度, `overflow-x:hidden`
- `.modal-overlay` / `.modal` — 全屏半透明遮罩 + 居中卡片

**为什么从内联两栏改为 Modal?** — 内联详情面板与列表共享高度空间, 产生复杂的 overflow/滚动条问题。Modal 完全独立, 不受列表高度约束, 链路详情可以完整展示。

### 6.2 Trace 列表

列: Trace ID · Session ID · User ID · 意图(Badge) · Agents路径 · 耗时 · TTFT · Token · 状态 · 查看详情按钮

#### 意图列等宽

CSS 规则: `#p-trace .tbl td:nth-child(4) .badge { min-width:82px; display:inline-block; text-align:center }`

TRANSFER / WEALTH / CHAT / BILL_QUERY 统一宽度, 视觉整齐。

#### 状态 Dot + 文字

```
OK    → 🟢 正常
ERR   → 🔴 异常
SLOW  → 🟠 偏慢
```

使用 `.dot` 类 (6px 圆点, `display:inline-block`) + 中文标签, 与会话回放列风格一致。

### 6.3 Modal 详情内容 (自上而下)

#### (1) 输入/输出双色卡片 (v15 重新设计)

```
┌──────────────────┬──┬──────────────────┐
│ 📥 原始输入       │→│ 📤 最终输出       │
│ 蓝底 #f6f8ff     │白│ 绿底 #f6fff6     │
│ 蓝色左边框        │底│ 绿色右边框        │
│ "你好，帮我转500" │  │ 转账成功！已向... │
└──────────────────┴──┴──────────────────┘
```

设计要点:
- 左侧蓝底蓝左边框 = 输入, 右侧绿底绿右边框 = 输出
- 中间 32px 白底间隔区 + 22px 圆形箭头图标 (`border:1px solid #e5e5e5`, `border-radius:50%`)
- 整体 `box-shadow`, `border-radius`, `overflow:hidden` 形成一体卡片感
- 标签使用 `uppercase` + `letter-spacing` 增强层次感
- 内容文字 `color:#1a1a1a;font-weight:500` 确保可读

#### (2) Agent 链路详情树 (v15 架构校正)

```
L0 [L1领域路由 · 置信度 0.95 · 960ms]          ← 仅 tag-l0 badge，无重复 agent-name
├── INPUT: 52 tokens
│   model: qwen-plus
│   system: 你是手机银行业务总入口，负责将用户请求路由到对应的银行业务领域(L1)
├── OUTPUT: 18 tokens · 置信度 0.95
│   domain: "TRANSFER"  confidence: 0.95
├── 🧠 LLM call: qwen-plus · 418ms · in:52 out:18
│
┄┄┄ 正常路由 → TRANSFER 领域 ┄┄┄               ← 不是 REROUTE！
│
L1 [L2业务路由 · 置信度 0.88 · 620ms]
├── INPUT: 85 tokens
│   from L0: { domain: "TRANSFER", confidence: 0.95 }
│   user_input: "你好，帮我转500给张三"
│   flow: 正常路由 L0→L1 (非 reroute)
├── 🔀 改写对比 (L1-LLM2 意图改写)
│   ├── 改写前: "你好，帮我转500给张三"
│   │   ⚠ 检测: 口语化问候 · 动词非标准化
│   ├── → 箭头
│   └── 改写后: "向张三转账500元"
│       ✅ 格式规范 (张三·500元)
├── OUTPUT: 56 tokens · L1-LLM2 改写+路由
│   { rewritten_query: "向张三转账500元", l2_agent: "TransferService", confidence: 0.88 }
├── 🧠 L1-LLM1: qwen-turbo · 280ms · in:85 out:12 → new-session
│   └── IO: system="你是会话分类助手(L1-LLM1)，判断...", response={"session_type":"new-session"}
├── 🧠 L1-LLM2: qwen-turbo · 210ms · in:12 out:56 → TransferService
│   └── IO: system="你是意图改写与路由助手(L1-LLM2)，将用户输入标准化并选择L2..."
│
┄┄┄ 意图确认 → 执行 ┄┄┄
│
L2 TransferService [转账执行 · 成功 · 3,421ms]
├── INPUT: L2 业务执行参数 (3 param)
├── OUTPUT: 交易结果 { status: "success", txn_id: "TXN453765" }
└── 🧠 LLM call: qwen-plus · 512ms · in:156 out:45
```

层级标签配色:
- L0 → `.tag-l0` (蓝底 `#e6f4ff`)
- L1 → `.tag-l1` (紫底 `#f9f0ff`)
- L2 → `.tag-l2` (绿底 `#f6ffed`)

每个 Agent 节点可:
- 点击 header 折叠/展开 (`taToggle()`)
- 点击"详情"按钮展开 IO 面板 (`tio()`)

#### (3) Span 瀑布图 (v15 标签统一为 "Agent角色 / LLM模型" 维度)

```
总耗时 / HTTP 入口               ████████████████████████ 1,240ms
L0 领域路由 / qwen-plus            ██ 98ms
L1-LLM1 会话分类 / qwen-turbo       ████ 223ms
L1-LLM2 改写+识别 / qwen-turbo          ███ 186ms
L2 转账执行 / qwen-plus                 ██████████ 602ms
                    ↑ TTFT 223ms (虚线标记)
```

颜色编码:
- `bar-http` → 灰色 `#8c8c8c`
- `bar-agent` → 蓝色 `#1677ff`
- `bar-llm` → 紫色 `#722ed1`
- `bar-tool` → 青色 `#13c2c2`
- TTFT 标记 → 黄色虚线 `#faad14`

### 6.4 v15 关键设计决策

**为什么 Span 瀑布标签要统一为 "Agent角色 / LLM模型"?** — 修复前的标签混层: `HTTP /chat`(协议)、`L0`(Agent)、`extractParams`(工具)、`LLM qwen-turbo`(LLM)、`TransferGraph`(图执行)。不同维度混在一起让人困惑。统一后每条都回答两个问题: 哪个 Agent 在做什么? 用哪个 LLM?

**为什么 Agent 链路详情在 Span 瀑布图之上?** — 链路详情是"逻辑视图"(谁调了谁), Span 瀑布是"时间视图"(花了多久)。先理解拓扑再看时延更自然。

---

## 7. Agent 层级架构

### 7.1 三层架构总览 (v15 校正版)

```
L0 Agent (1 LLM, qwen-plus):  L1领域路由
  └── 银行业务总入口 → 找到合适的 L1 领域 (TRANSFER / WEALTH / BILL 等)

L1 Agent (2 LLMs, qwen-turbo):  L2业务路由
  ├── L1-LLM1: 会话分类
  │   判断本次会话是 follow-up / switch-new / resume
  │   输出: { session_type: "new-session" }
  └── L1-LLM2: 意图改写 + 意图识别 → 选择 L2 Agent
      将用户输入标准化 + 匹配最合适的 L2 业务Agent
      ⚠ follow-up 时跳过此 LLM，直接路由到之前的 L2
      输出: { rewritten_query: "...", l2_agent: "TransferService", confidence: 0.88 }

L2 Agent (1-N LLMs, qwen-plus):  业务执行
  └── 灵活的业务Agent (转账服务 / 账单查询 / 理财咨询 / 理财解读)
      内部可能包含 1~多个 LLM
```

### 7.2 Reroute 流程说明

- **正常流程**: L0 → L1 → L2, 必经路径
- **Reroute 流程** (罕见): 某 Agent 意图识别错误, 转发到错误下一跳 → 该 Agent 发起 re-route → 请求送回 L0 重新识别
- **标记位置**: Reroute 率出现在总览大屏 KPI (14.2%), 但不出现在正常链路详情中

### 7.3 命名演化

| 旧名 | 新名 | 原因 |
|------|------|------|
| L0: "业务总入口 / L1路由" | **L1领域路由** | `/` 分隔符误导为两个概念, 改为单一短语 |
| L1: "领域路由 / L2选择" | **L2业务路由** | 同上, 明确 L1 的职责是找到 L2 |
| Span L0: "业务路由" | **领域路由** | 与 agent-desc 统一 |

### 7.4 UI 展示

- Agent header 仅保留 `tag-l0`/`tag-l1`/`tag-l2` 彩色 badge + 描述文字
- 删除了重复的 `agent-name` 文本 (badge 已标识层级)
- L2 Agent 显示具体服务名 (TransferService / BillService 等)

---

## 8. AI 洞察

### 8.1 TAB 结构

```
准确率分析 → Agent 性能 → Token 成本 → 智能体 / 工具 → 业务转化漏斗 → 用户满意度
   ↑            ↑           ↑              ↑                    ↑              ↑
 算法看       开发看       成本看       调用链看               产品看         体验看
```

TAB 切换用 `itab(n, el)` 函数, 使用 `:scope` 选择器防止子页签干扰。

### 8.2 准确率分析 TAB

内容:
- **意图识别准确率趋势** (折线图, 5 条线: L0 / L1 / L2 转账服务 / L2 账单查询 / L2 理财)
- **改写准确率分析表** (口语化金额 / 收款人补全 / 时间归一化 / 意图消歧)
- **改写失败根因 TOP3** (3 张彩色卡片):
  - 1. 代词指代不明 (红色, 58 例, 32.4%)
  - 2. 金额格式未标准化 (黄色, 42 例, 23.5%)
  - 3. 上下文关联断裂 (紫色, 31 例, 17.3%)
- **意图混淆矩阵** (热力图, `visualMap: { show: false }`, 5×5 矩阵, 隐藏底部色阶)

### 8.3 Agent 性能 TAB (v13 新增, v15 重构)

#### 双维度子页签

```
Agent 性能 TAB
├── Agent 维度 (默认, psw('agent'))
│   ├── KPI 卡片 ×4: 总调用 / 平均耗时 / 平均错误率 / 总 Token
│   ├── 性能明细表: L0/L1/转账服务/账单查询/理财咨询/理财解读
│   │   列: Agent · 级别 · 调用 · 总耗时(bar) · TTFT P50 · TTFT P95 · TPOT P50 · TPOT P95 · 总Token · 错误%
│   ├── TTFT 延迟分布 (箱线图, chart-perf-box)
│   └── TTFT vs TPOT (多series散点图, chart-perf-scatter)
│
└── LLM 维度 (psw('llm'))
    ├── KPI 卡片 ×4
    ├── 性能明细表: Agent + 模型对
    │   列: Agent · 模型 · 调用 · 总耗时 · TTFT P50 · P95 · TPOT P50 · P95 · Token(in/out) · 错误%
    ├── LLM TTFT 分布 (箱线图, chart-perf-llm-box)
    └── LLM TTFT vs TPOT (多series散点图, chart-perf-llm-scatter)
```

#### 箱线图设计

| 属性 | 值 |
|------|-----|
| 数据格式 | `[min, P25, P50, P75, P95]` — 5 值标准 |
| Agent 维度颜色 | 蓝 `#1677ff` |
| LLM 维度颜色 | 紫 `#722ed1` |
| 副标题 | "盒子 = P25~P75 \| 须线顶端 = P95（95% 请求在此时间内完成）" |
| Y 轴 | `formatter: '{value}ms'` (带单位) |
| Tooltip | P25~P95 分行显示, P50 加粗, P95 红色 |

**ECharts 陷阱**: category xAxis 下 boxplot tooltip 的 `p.data[0]` 是类别标签, 数据从索引 1 开始。tooltip formatter 使用 `p.data[2]~[5]` 对应 P25~P95。

#### 散点图设计 (v15 多series重构)

**核心变化**: 单一 scatter series + visualMap 渐变色 → 6 个独立 series, 每个 Agent/LLM 持有固定颜色

| 属性 | 旧方案 (v14) | 新方案 (v15) |
|------|-------------|-------------|
| Series 数量 | 1 | 6 |
| 颜色方案 | visualMap 绿→黄→红渐变 | 每 Agent 固定色 |
| 标签 | 黑底白框 | 颜色与数据点一致 |
| visualMap | 右侧竖条 | **移除** |
| grid.right | 65 | 15 |

**六色映射** (Agent 维度):

| Agent | 颜色 | 色值 |
|-------|------|------|
| L0 | 蓝 | `#1677ff` |
| L1 | 紫 | `#722ed1` |
| 转账服务 | 绿 | `#52c41a` |
| 账单查询 | 青 | `#13c2c2` |
| 理财咨询 | 黄 | `#faad14` |
| 理财解读 | 橙 | `#fa8c16` |

**气泡大小**: `Math.sqrt(调用量) / 4` — 开平方压缩极端差异, 避免高频 Agent 太大低频 Agent 看不见。

**优秀区标记**: `markArea` 绿色半透明区 (TTFT<220ms 且 TPOT<20ms), 放置在 L0 series 上。

**防遮挡策略** (3 层):
1. `clip: false` — 允许标签超出 grid 渲染
2. `xAxis.max` / `yAxis.max` 显式设定 — 为右侧/顶部数据点标签预留空间
3. axis name 用 `nameLocation:'middle'` 居中

### 8.4 Token 成本 TAB

- **Token 消耗趋势** (堆叠柱状图, 按模型: qwen-max / qwen-32b)
- **Token 成本拆解** (饼图, 按意图: 转账 / 账单查询 / 理财咨询 / 理财解读, 标注格式 `{b}\n{c}次 ({d}%)`)
- **Token 明细表** (意图 × 模型, 列: 调用次数 / 输入Token / 输出Token / 总Token / 占比)
- 已删除"成本(¥)"列 (v13 调整)
- **Token 术语**: 全文使用 `tokens` (非 `tok`)

### 8.5 智能体 / 工具 TAB (v15 重构)

#### 布局结构

```
┌──────────────────────┬──────────────────────┐
│ 智能体调用统计(按子图) │ 工具调用统计           │
│ chart-agent-bar      │ chart-tool-bar       │
└──────────────────────┴──────────────────────┘

┌──────────────────────────────────────────────┐
│ 工具调用明细                                   │
│ 工具名(badge) · 调用 · 成功 · 失败 · 均耗 · P95 · 错误率 · 典型错误 │
└──────────────────────────────────────────────┘

┌──────────────────────────────────────────────┐
│ KPI 摘要 (4列迷你卡片)                         │
│ 完成率 · 满意度 · 转人工率 · 对话轮次           │
└──────────────────────────────────────────────┘
```

#### 命名: 去 MCP 前缀

"工具调用统计" / "工具调用明细" — 去掉 MCP 前缀，淡化协议层概念。

#### 工具调用明细表格

工具名用彩色 Badge: transfer(蓝) / queryBill(绿) / queryBalance(青) / productSearch(紫)。
错误率颜色编码: >5% 橙色加粗, ≤5% 绿色。
典型错误补全为 2-3 个具体场景描述。

#### Skill 模块隐藏

Skill 调用统计图表 + Skill 调用明细表格暂时移除（Skill 层为远期规划，当前聚焦 Agent + 工具两层）。

#### KPI 摘要迷你卡片设计 (v15 新增)

四列等宽网格，每张卡片:
- 渐变背景 + 细边框，颜色按语义分: 完成率/满意度(绿), 转人工率(黄), 对话轮次(蓝)
- 标签大写 `uppercase` + `letter-spacing`
- 大号数值 28px 800 字重
- 底部进度条（完成率/满意度/转人工率使用 `width` 百分比进度条）
- **对话轮次无进度条**: 对话轮次没有天然上限，不使用百分比或进度条表示。仅显示纯数字 + 单位，视觉重量与其他三卡平衡。

```css
/* KPI 卡片配色 */
完成率/满意度: background: linear-gradient(135deg,#f6ffed,#f0fff0); border:1px solid #d9f7be
转人工率:      background: linear-gradient(135deg,#fffbe6,#fff7e6); border:1px solid #ffe58f
对话轮次:      background: linear-gradient(135deg,#e6f4ff,#f0f5ff); border:1px solid #bae0ff
```

### 8.6 业务转化漏斗 TAB

- **业务转化漏斗** (ECharts funnel, 标签在内部, 白色粗体)
- **分阶段放弃率** (环形饼图, 带百分比标签)
- **漏斗明细表** (阶段 · 进入数 · 完成数 · 放弃数 · 转化率 · 放弃率 · 主要放弃原因)

### 8.7 用户满意度 TAB

- **满意度分布** (三栏: 满意68% / 一般22% / 不满意10%, 带进度条)
- **满意度趋势** (7 天折线图)
- **不满意原因分布** (饼图: 回答不准确 / 响应太慢 / 无法理解意图 / 操作失败)
- **低满意度会话列表** (Session ID · User ID · 域 · 满意度 · 原因 · 时长 · 查看回放)

---

## 9. 日志查询

筛选栏: User ID / Session ID / Trace ID / 全文搜索

日志条目格式:
```
[LEVEL]  时间戳  [模块] 消息内容  trace跳转链接
[ERROR]  10:23:12.456  [TransferGraph] LLM 调用超时 model=qwen-max  trace f7e8d9…
[WARN]   10:23:10.123  [paramRouter] 参数缺失 field=receiver  trace a1b2c3…
[INFO]   10:23:08.901  [L0] domain=TRANSFER confidence=0.92 user=user_a3f2
```

级别配色: INFO(蓝) / WARN(黄) / ERROR(红)

---

## 10. 告警规则与系统设置

### 告警规则

双栏布局:
- 左: 告警规则表 (规则名 · 阈值 · 状态 · 通知方式)
- 右: 告警闭环时间线 (触发时间 → 处理动作 → 恢复时间)

### 系统设置

双栏布局:
- 左: 采集配置 (OTel Collector 状态 · Trace 采样率 · Metric Tag 策略 · Prompt 存储)
- 右: 存储后端 (Metrics: Prometheus · Trace: Tempo · Logs: Loki · 业务画像: PostgreSQL+TimescaleDB)

---

## 11. 三层可观测模型

核心设计思想: 不要把所有"Agent 内部发生的事情"混为一谈。

```
        Skill 业务效果       ← "用户的问题解决了吗?"
       ┌──────────────────┐
       │ 任务完成率 满意度   │  产品/运营视角
       │ 转人工率  对话轮次  │  按领域拆解
       └────────┬─────────┘
                │
        Agent 技术性能       ← "Agent 跑得快吗?"
       ┌──────────────────┐
       │ TTFT P50/P95     │  开发/算法视角
       │ TPOT P50/P95     │  按 Agent 层级 + LLM 模型
       │ 箱线图 + 散点图    │
       └────────┬─────────┘
                │
        MCP 工具调用        ← "底层函数正常吗?"
       ┌──────────────────┐
       │ transfer/queryBill│  开发/运维视角
       │ 成功/失败/耗时     │  按函数名拆解
       │ 错误率/典型错误    │
       └──────────────────┘
```

### 三层对比

| 维度 | Agent 性能 | MCP 工具 | Skill 业务 |
|------|-----------|----------|-----------|
| **关注点** | 技术延迟 | 函数可用性 | 业务效果 |
| **核心指标** | TTFT, TPOT, 错误率 | 调用次数, 成功率, P95 | 任务完成率, 满意度, 转人工率 |
| **展示方式** | 箱线图 + 六色散点图 | 堆叠柱状图 + 明细表 | 分组柱状图 + KPI 卡 + 明细表 |
| **钻取路径** | 哪个 Agent 慢 → 调了哪个 LLM | 哪个工具报错 → 错误分布 | 哪个领域差 → 典型问题 |

### 与"业务转化漏斗"的关系

- 漏斗 = **横向流程视角** (用户从进入 → 意图 → 参数 → 执行 → 完成走过哪些阶段)
- Skill 统计 = **纵向领域视角** (每个业务领域独立的完成率和满意度)
- 两者互补: 漏斗告诉你"丢在哪一步", Skill 告诉你"丢在哪个领域"

---

## 12. 命名规范

### 12.1 Agent 层级命名

| 旧名 | 新名 | 原因 |
|------|------|------|
| DomainRouter, DR | **L0** | 统一层级命名 |
| DomainRouter(二次), DR2 | **L1** | 同上 |
| L0: "业务总入口 / L1路由" | **L1领域路由** | `/` 分隔符误导为两个概念 |
| L1: "领域路由 / L2选择" | **L2业务路由** | 同上 |
| Span L0: "业务路由" | **L0 领域路由** | 与 agent-desc 统一 |

### 12.2 业务命名

| 旧名 | 新名 | 原因 |
|------|------|------|
| 转账, 账单, 咨询, 解读 | **转账服务, 账单查询, 理财咨询, 理财解读** | 全称无歧义 |
| TPOP | **TPOT** (Time Per Output Token) | 术语修正 |
| 工具调用统计/明细 | **工具调用统计/明细** | 去掉 MCP 前缀，淡化协议层 |
| tok (缩写) | **tokens** (全写) | 行业惯例: OpenAI/Anthropic/LangSmith 均用 tokens |

### 12.3 状态命名

| 旧名 | 新名 | 原因 |
|------|------|------|
| OK | 🟢 **正常** | 中文 + dot 图标，统一风格 |
| ERR | 🔴 **异常** | 同上 |
| SLOW | 🟠 **偏慢** | 同上 |

### 12.4 Reroute 术语界定

- **正常路由**: L0 → L1 → L2，必经流程
- **Reroute** (罕见): 某 Agent 意图错误 → 送回 L0 重识别
- **UI 呈现**: 链路详情连接器标注"正常路由 → TRANSFER 领域"（非 REROUTE）；Reroute 仅作为总览 KPI 指标存在

---

## 13. 视觉设计系统

### 13.1 CSS 变量定义

```css
:root {
  --primary: #1677ff;
  --primary-bg: #e6f4ff;
  --success: #52c41a;
  --warning: #faad14;
  --error: #ff4d4f;
  --purple: #722ed1;
  --cyan: #13c2c2;

  --bg-layout: #f0f2f5;    /* 页面背景 */
  --bg-card: #fff;          /* 卡片背景 */
  --bg-sidebar: #001529;   /* 侧边栏深色 */

  --text-primary: rgba(0,0,0,.88);
  --text-secondary: rgba(0,0,0,.65);
  --text-tertiary: rgba(0,0,0,.45);

  --border: #f0f0f0;
  --border-strong: #d9d9d9;
  --radius: 8px;
  --radius-sm: 6px;

  --font: "Inter", -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
  --mono: "JetBrains Mono", "SF Mono", "Fira Code", Consolas, monospace;

  --bubble-user: #e6f4ff;
  --bubble-ai: #f6ffed;
  --bubble-sys: #fffbe6;
}
```

### 13.2 色彩体系

| 用途 | CSS 变量 | 色值 | 场景 |
|------|---------|------|------|
| 主色/链接/强调 | `--primary` | `#1677ff` | L0 标签, 主按钮, 输入卡片背景 |
| 成功/健康 | `--success` | `#52c41a` | 正常状态, L2 标签, 完成率, 输出卡片背景 |
| 警告/延迟 | `--warning` | `#faad14` | 偏慢状态, 告警观察 |
| 错误/失败 | `--error` | `#ff4d4f` | 异常状态, P95 高亮 |
| 紫色(辅) | `--purple` | `#722ed1` | L1 标签, LLM 相关 |
| 青色(辅) | `--cyan` | `#13c2c2` | MCP 工具, 瀑布图 |

### 13.3 Badge 体系

| Badge 类 | 背景色 | 文字色 | 用途 |
|----------|--------|--------|------|
| `.badge-success` | `#f6ffed` | `#52c41a` | 正常/已完成 |
| `.badge-error` | `#fff2f0` | `#ff4d4f` | 异常/错误 |
| `.badge-warning` | `#fffbe6` | `#faad14` | 偏慢/取消 |
| `.badge-info` | `#e6f4ff` | `#1677ff` | 信息/通用 |
| `.badge-purple` | `#f9f0ff` | `#722ed1` | L1 领域 |
| `.badge-blue` | `#e6f4ff` | `#1677ff` | 转账服务 Skill |
| `.badge-green` | `#f6ffed` | `#389e0d` | 账单查询 Skill |
| `.badge-orange` | `#fff7e6` | `#d46b08` | 理财咨询 Skill |

### 13.4 状态 Dot 组件

```css
.dot { width:6px; height:6px; border-radius:50%; display:inline-block }
```

用法: `<span class="badge badge-success"><span class="dot" style="background:var(--success)"></span>正常</span>`

用于: 会话回放的"会话状态"列 + 链路追踪的"状态"列

### 13.5 图表配色

| 场景 | 颜色 | 说明 |
|------|------|------|
| 箱线图 (Agent) | `#1677ff` | 蓝 |
| 箱线图 (LLM) | `#722ed1` | 紫 |
| 散点图 多series | 六色映射 | 蓝/紫/绿/青/黄/橙, 每个 Agent 固定一色 |
| 柱状图 成功/失败/取消 | 绿/红/黄 | `#52c41a / #ff4d4f / #faad14` |
| 折线图 多系列 | 蓝/紫/绿/青/橙 | 5 色区分 Agent 层级 |
| Skill 柱状图 | 蓝/绿 | `#1677ff / #52c41a` (完成率/满意度) |
| 漏斗图 | 蓝渐变→绿 | `#1677ff → #4096ff → #69b1ff → #91caff → #52c41a` |
| 混淆矩阵 | 浅灰→蓝, 无 visualMap | `['#f5f5f5','#1677ff']`, `show:false` |

### 13.6 字体

| 用途 | 字体 | 大小 |
|------|------|------|
| 标题/正文 | Inter + 系统 sans-serif 备用 | 13-16px |
| 数据/代码/TraceID | JetBrains Mono / SF Mono / Fira Code | 11-12px |
| KPI 主数值 | Inter 700 | 28px |
| 图表轴标签 | Inter | 9-10px |

### 13.7 布局类

| 类名 | 用途 | 定义 |
|------|------|------|
| `.grid-2` | 双列网格 | `grid-template-columns: 1fr 1fr; gap: 16px` |
| `.grid-3` | 三列网格 | `grid-template-columns: repeat(3,1fr); gap: 16px` |
| `.metric-grid` | KPI 三列 | `repeat(3,1fr); gap:16px` |
| `.metric-grid.m4` | KPI 四列 | `repeat(4,1fr)` |
| `.chart-box` | 大图表 | `height: 280px` |
| `.chart-box-sm` | 小图表 | `height: 200px` |
| `.tabs` / `.tab` | TAB 导航 | 底部 2px 蓝色下划线 |
| `.tab-pane` | TAB 内容 | `display:none; .active → display:block` |
| `.modal-overlay` | 模态遮罩 | `position:fixed; z-index:1000; display:none` |
| `.modal-overlay.open` | 打开状态 | `display:flex` |

### 13.8 输入/输出卡片设计模式 (v15 新增)

双色卡片 + 箭头连接器，用于链路详情中展示输入→输出的流程:

```
┌─蓝底蓝左边框─┐  ┌─白底箭头─┐  ┌─绿底绿右边框─┐
│ 📥 原始输入  │  │    →     │  │ 📤 最终输出  │
│ (内容文字)   │  │  (⊙圆框)  │  │ (内容文字)   │
└────────────┘  └──────────┘  └────────────┘
```

- 左侧: `background:#f6f8ff; border-left:3px solid var(--primary)`
- 右侧: `background:#f6fff6; border-right:3px solid var(--success)`
- 中间: `width:32px; background:#fff` + 内含 22px 圆形箭头 `border:1px solid #e5e5e5; border-radius:50%`
- 整体: `border-radius:var(--radius-sm); overflow:hidden; box-shadow`

**原则**: 两个内容块用浅色半透明背景区分，不用纯白；箭头用圆框承载，放置在白底间隔区；文字颜色 `#1a1a1a;font-weight:500` 确保清晰。

---

## 14. ECharts 实践要点

### 14.1 boxplot tooltip 索引陷阱

Category xAxis 下 boxplot tooltip 的 `p.data` 结构:
```
[类别标签, min, Q1, median, Q3, max]
   [0]     [1]  [2]   [3]    [4]  [5]
```
数据索引从 **1** 开始而非 0 — `p.data[2]` 才是 P25, `p.data[5]` 才是 P95。

### 14.2 scatter 的 clip 行为

ECharts 默认 `clip: true`, 超出 grid 的图形/标签被裁。散点图右端标签需设 `clip: false` + 显式 `xAxis.max` 双重保险。

### 14.3 多series 散点图 (v15 最佳实践)

当数据点需要按类别区分时，用多 series + 固定颜色 替代 单 series + visualMap 渐变:
- 每个类别一个 series，通过 `name` 在 legend/tooltip/label 中识别
- 颜色映射用 JS 对象 `{ name: color }`，每 series 设 `itemStyle.color`
- 标签 formatter 用 `p.seriesName` 而非 `p.name`（多 series 下后者为 undefined）
- `markArea` 只放在第一个 series 上
- 移除 visualMap 后 `grid.right` 可从 65 缩至 15

### 14.4 visualMap 隐藏

- 热力图: `visualMap: { show: false }` — 隐藏底部色阶但保留颜色映射
- 散点图: 直接删除 `visualMap`，改用多 series 固定色

### 14.5 chart resize 时机

页面切换后图表容器从 `display:none` → `display:block`, 需 `setTimeout(initAllCharts, 100)` 等待布局完成再渲染。`initAllCharts()` 内部检查 `e.offsetWidth > 0` 跳过不可见图表。

### 14.6 全局 ECharts 主题适配

亮色主题下统一文字颜色:
```javascript
var tc = 'rgba(0,0,0,.65)';          // 图表文字
var as = { color: 'rgba(0,0,0,.45)' }; // 轴标签
var sl = { lineStyle: { color: '#f0f0f0' } }; // 分割线
var tt = { trigger: 'axis', textStyle: { color: 'rgba(0,0,0,.65)' } }; // tooltip
```

### 14.7 饼图数值标注

Agent 分布 / Token 成本拆解等饼图使用 `label.formatter: '{b}\n{c}次 ({d}%)'` 在扇区旁显示名称、数值和百分比，避免只显示名称导致需要 hover 才能看到具体数字。

---

## 15. 交互函数索引

| 函数 | 用途 | 关键细节 |
|------|------|---------|
| `sw(n, el)` | 页面切换 | 切换 `.page.active` + `.nav-item.active`, 100ms 后重渲染图表 |
| `itab(n, el)` | AI 洞察 TAB 切换 | `:scope>.card>.tabs>.tab` 限定直接子元素, 防子页签干扰 |
| `psw(n, el)` | Agent 性能子页签 | `el.closest('#tab-perf')` 限定范围, `.perf-pane` 显隐 |
| `tio(e, id)` | IO 面板展开 | `e.stopPropagation()` 防冒泡, toggle `.open` + `.active` |
| `taToggle(id)` | Agent 树折叠 | toggle `body-{id}` 的 `display`, 切换 ▼/▶ |
| `openTraceModal(e)` | Trace 详情 Modal | `e.stopPropagation()`, 标记选中行, `trace-detail-modal.classList.add('open')` |
| `closeTraceModal()` | 关闭 Trace Modal | `.classList.remove('open')`, 点击遮罩也可关闭 |
| `openSession()` | 会话 Modal | `session-modal.classList.add('open')` |
| `closeSession()` | 关闭会话 Modal | `.classList.remove('open')` |
| `initAllCharts()` | 初始化所有图表 | 遍历 mini spark + 调用 `initInsightCharts()` |
| `ch(id, opt)` | 单图表渲染 | 检查 `offsetParent`, dispose 旧实例后重建 |
| `miniSpark(id)` | Mini 折线图 | 10 个数据点的平滑折线 + 渐变面积 |

---

## 16. 设计决策记录

本节记录 v15 开发过程中经过讨论后确定的设计原则，为后续迭代提供决策依据。

### 16.1 Token 不缩写

**决策**: 全文使用 `tokens`，不使用 `tok`。
**理由**: OpenAI、Anthropic、LangSmith、Langfuse、Arize Phoenix 等行业工具均使用 `tokens` 全写。`tok` 既非标准缩写，也不像 `ms`/`MB` 那样有公认规范。

### 16.2 Agent 描述不带 `/` 分隔符

**决策**: `L1领域路由` 而非 `业务总入口 / L1路由`。
**理由**: `/` 让人误读为两个独立概念，单一短语更清晰地表达职责方向。

### 16.3 散点图多 series > visualMap

**决策**: 类别型散点图用多 series + 固定颜色，不用单 series + visualMap。
**理由**: visualMap 渐变色难以在相近位置区分不同类别，"这个绿点是谁？"问题无法解决。固定颜色实现"看色识 Agent"。

### 16.4 状态用 dot + 中文

**决策**: `🟢 正常` 而非 `OK`。
**理由**: 与业界工具 (Grafana、Datadog) 的 dot 指示器一致，中文降低非技术用户认知门槛，与会话回放风格统一。

### 16.5 链路详情 input/output 双色卡片

**决策**: 蓝底蓝边(输入) + 绿底绿边(输出) + 箭头连接。
**理由**: 颜色即语义 (蓝=进入系统, 绿=成功产出), 箭头表达方向, 白底间隔区分两个独立信息块。

### 16.6 Trace 详情用 Modal

**决策**: 从内联两栏布局改为 Modal 弹窗。
**理由**: 两栏共享高度空间导致复杂的 overflow/滚动条问题 (v14 经历多次修复仍不完美)。Modal 独立空间, 零布局冲突。

### 16.7 执行智能体用 `→` 分隔

**决策**: `WealthConsult → TransferService` 而非 `WealthConsult, TransferService`。
**理由**: 逗号暗示并列关系, `→` 表达调用链方向, 与 turn header 里的 `L0→BillService` 一致。

### 16.8 Agent 分布不含 Tool

**决策**: Agent 分布饼图和系统健康指标卡不显示 Tool 维度。
**理由**: Tool (MCP 函数) 属于三层可观测模型的中间层, 应独立展示在工具统计面板, 不与 Agent 层级 (L0/L1/L2) 混在一起。

### 16.9 KPI 摘要迷你卡片 (v15 新增)

**决策**: KPI 摘要使用四列渐变迷你卡片替代纯文字列表。
**理由**: 纯数字单调，迷你卡片带进度条提供视觉比例参考、颜色编码区分语义、渐变背景增加质感。

### 16.10 对话轮次不使用百分比/进度条

**决策**: 对话轮次仅显示纯数字，不使用环形图、百分比进度条、或任意基准的图形表示。
**理由**: 对话轮次没有天然上限(不像完成率 0-100% 或满意度 0-5)，任何人为基准都是误导。指标本身的数值就是全部信息。

### 16.11 工具层去 MCP 前缀

**决策**: "工具调用统计" / "工具调用明细" 替代 "MCP工具调用统计/明细"。
**理由**: MCP 是实现细节，对产品/非技术用户无信息增量。三层可观测模型本身已明确区分 Agent/工具/Skill 层。

### 16.12 Skill 模块暂隐

**决策**: Skill 调用统计图表和明细表格当前版本隐藏。
**理由**: Skill 为远期规划层，当前聚焦 Agent 性能 + 工具调用两层可观测，避免信息过载。

---

## 17. 业界参考

| 工具 | 借鉴设计 | 本项目的实现 |
|------|---------|-------------|
| **Langfuse** | Session grouping + Trace waterfall | 会话回放 + 链路详情层级树 |
| **LangSmith** | Trace tree + Prompt Playground | Agent 层级树 + IO 内联展开 |
| **Datadog** | 全局筛选 + KPI 卡片 | 双级 KPI 网格 + TAB 筛选 |
| **Grafana** | 仪表盘组合 | 面板侧边导航 + 卡片布局 |
| **Arize Phoenix** | Span 瀑布图 + 嵌入详情 | Span 瀑布图 + Agent 链路 Modal |
| **Weights & Biases** | 箱线图 + 散点图性能对比 | Agent 性能 TAB 箱线/散点双图 |

不做 Langfuse/LangSmith 的全功能复制, 而是 **针对 Bank AI Agent 的 L0 → L1 → L2 三层串行架构** 做定制设计。

---

## 18. 文件清单

| 文件 | 版本 | 主要变更 |
|------|------|---------|
| `dashboard-v12.html` | 基准 | Ant Design 5 主题重构, 暗→亮 |
| `dashboard-v13.html` | 里程碑 | Agent 性能 TAB + 两栏布局 + L0/L1/L2 命名 |
| `dashboard-v14.html` | 扩展 | Skill 统计 + MCP 工具命名 + 箱线图/散点图打磨 |
| `dashboard-v15.html` | **最新** | Agent 架构校正 + L1 双LLM结构 + 散点多series重构 + Token标准化 + 智能体/工具TAB重构 + KPI摘要重设计 + 视觉细节打磨 (25+项) |
| `DESIGN.md` | v9→v14 | 原始设计文档 |
| `UI-DESIGN-V2.md` | v14 | V2 版设计文档 |
| `UI-DESIGN-V3.md` | v15 | V3 版设计文档 — 新增 Agent 层级架构章节 + 设计决策记录 |
| `UI-DESIGN-V4.md` | v15 | **本文档** — 更新智能体/工具TAB重构 + KPI摘要设计 + 5条新设计决策 |

---

## 附录: 待实现

- [ ] 真实后端数据对接 (当前为 mock 数据)
- [ ] 全局筛选联动 (时间范围 / Agent / 意图 跨面板联动)
- [ ] 图表点击事件联动 (散点图点击 → 跳转 Trace 详情)
- [ ] 多意图分支动态渲染
- [ ] 用户反馈收集入口
- [ ] 告警规则 CRUD 交互
- [ ] 移动端适配 (当前仅桌面端)

---

*UI 设计文档 V4 — 由 Frontend Developer Agent 维护, 随 dashboard 版本同步更新*
*最后更新: 2026-06-26*
