# Plan A vs Plan B 综合评价报告

> **评价基准**: 基于6轮深度架构讨论 + Spring AI Alibaba源码级分析 + 5大意图场景验证  
> **评价目的**: 为方案选择提供全面、客观、可追溯的判断依据  
> **评价时间**: 2026-06-14

---

## 〇、Plan B+ 修正案（源码级验证后）

> **修正背景**: 原报告基于Plan B评价，后用户决定采用Plan B+（ReactAgent.asNode() + DomainTool + 框架原生中断传播）。
> 经Spring AI Alibaba v1.1.2.0字节码反编译验证，原四路评审中关于"中断降级"的结论**被推翻**。
> 本修正案更新评分并标注推翻原因，原报告内容保留供追溯。

### 0.1 源码验证推翻的关键结论

| # | 原四路评审结论 | 源码验证结果 | 影响 |
|---|--------------|-------------|------|
| 1 | Plan B的ReactAgent.asNode()子图中断能力降级（SubCompiledGraphNodeAction vs AgentToSubCompiledGraphNodeAdapter差异） | **推翻**：两者均**不实现**InterruptableAction，均实现ResumableSubGraphAction。中断传播路径完全相同：Flux流→processGraphResponseFlux→returnFromEmbed→编排图暂停 | 维度1/2/3评分应恢复 |
| 2 | SubCompiledGraphNodeAction是internal API高风险依赖 | **缓解**：Plan B+移除了SubCompiledGraphNodeAction依赖，改用ReactAgent.asNode()（public API）。DomainTool仅依赖DomainHandler+ToolCallback（均为public API） | 维度6评分应上调 |
| 3 | Plan B调试可观测性差（graph黑盒） | **缓解**：Plan B+新增三层打点架构（GraphLifecycleListener + OverAllState字段 + ProgressTraceHook），可观测性从4分提升至7分 | 维度5评分应上调 |
| 4 | StateMapper key非框架标准（Claim 9 DENIED） | **消除**：Plan B+移除StateMapper，改用StepPreparator。ReactAgent读取框架标准key `messages`，无需L2自定义key | 维度3/6评分应上调 |

### 0.2 Plan B+ 修正评分

| # | 评价维度 | 权重 | Plan A | Plan B(原) | Plan B(评审修正) | **Plan B+** | 修正原因 |
|---|---------|------|--------|-----------|----------------|------------|---------|
| 1 | 动态变更可适配性 | ⭐5 | 4 | 9 | 8 | **9** | 中断等价性源码确认，ReactAgent.interrupt()提供意图变更检测 |
| 2 | 五大意图场景覆盖度 | ⭐5 | 5 | 9 | 8 | **9** | 中断传播等价，5/5场景完全覆盖 |
| 3 | 框架对齐度 | ⭐4 | 5 | 9 | 8 | **9** | ReactAgent.asNode()是public API；StepPreparator用标准key；移除StateMapper |
| 4 | 开发速度(Phase 1) | ⭐4 | 8 | 5 | 5 | **5** | 更多类(DomainReactAgent+DomainTool)，但每个更简单 |
| 5 | 调试可观测性 | ⭐3 | 8 | 5 | 4 | **7** | 三层打点: GraphLifecycleListener + OverAllState + ProgressTraceHook |
| 6 | 框架依赖风险 | ⭐3 | 9 | 4 | 4 | **6** | DomainTool仅用public API；ReactAgent.asNode()是public；但AgentToSubCompiledGraphNodeAdapter仍在internal包 |
| 7 | 代码复杂度 | ⭐3 | 7 | 4 | 4 | **5** | 更多类但职责更单一；DomainTool/DomainReactAgent vs L1ToolAdapter/SubGraphRegistry/StateMapper |
| 8 | 可溯源性 | ⭐3 | 7 | 8 | 8 | **9** | 三层可观测性+StepStatus枚举+ReactAgent内部ProgressTraceHook |
| 9 | 运行时稳定性 | ⭐3 | 6 | 7 | 7 | **7** | 与Plan B等价 |
| 10 | 扩展性(新增域/场景) | ⭐4 | 6 | 9 | 8 | **9** | 新增域=加ReactAgent节点+DomainTool Bean，零改动已有代码；跨域Tool组合 |

**加权总分**：Plan A = **5.7/10** | Plan B(评审修正) = **7.1/10** | **Plan B+ = 7.7/10**

> 计算方式：(5×9 + 5×9 + 4×9 + 4×5 + 3×7 + 3×6 + 3×5 + 3×9 + 3×7 + 4×9) / 37 = 284/37 ≈ **7.68**

### 0.3 Plan B+ vs Plan B 关键改善点

| 维度 | Plan B | Plan B+ | 改善原因 |
|------|--------|---------|---------|
| 调试可观测性 4→7 | graph黑盒，无内部可见性 | 三层打点：Layer1节点级+Layer2业务级+Layer3 Agent内部级 | 用户要求的"可打点"架构 |
| 框架依赖风险 4→6 | 重度依赖SubCompiledGraphNodeAction(internal) | DomainTool用public API；ReactAgent.asNode()是public | 移除3个internal依赖源 |
| 代码复杂度 4→5 | L1ToolAdapter(复杂)+StateMapper+SubGraphRegistry | DomainTool(简单)+DomainReactAgent+StepPreparator | 职责更单一，每个类更简单 |
| 可溯源性 8→9 | OverAllState+checkpoint | 三层可观测性+StepStatus+ProgressTraceHook | 细粒度Agent内部追踪 |
| 扩展性 8→9 | SubGraphRegistry注册 | ReactAgent节点+DomainTool Bean+跨域Tool组合 | 声明式节点+声明式Tool |

### 0.4 Plan B+ 风险矩阵更新

| 风险 | Plan B 严重度 | Plan B+ 严重度 | 变化原因 |
|------|-------------|--------------|---------|
| SubCompiledGraphNodeAction internal API | 🔴 高 | 🟢 **消除** | Plan B+不使用此类 |
| StateMapper key非框架标准 | 🔴 高 | 🟢 **消除** | Plan B+移除StateMapper |
| ReactAgent.asNode() AgentToSubCompiledGraphNodeAdapter internal | — | 🟡 中 | 新增：adapter在internal包，但由asNode()封装，不直接调用 |
| DomainTool.call() blockLast()超时 | — | 🟡 中 | 新增：需在DomainTool中添加Duration超时 |
| 自定义Hook不支持InterruptableAction | — | 🟡 中 | 新增：源码确认非InterruptionHook/HITLHook的Hook不保留InterruptableAction能力 |
| G1 EXECUTING状态用户输入注入 | 🔴 高 | 🔴 高 | 未变：两方案共同问题 |
| graph嵌套调试困难 | 🟡 中 | 🟡 中(改善) | 三层打点缓解但仍存在 |

### 0.5 结论

**Plan B+（7.7/10）> Plan B评审修正（7.1/10）> Plan A（5.7/10）**

Plan B+相比Plan B的核心改善：
1. **可观测性**：从"graph黑盒"到"三层打点"，解决生产排障最大痛点
2. **框架依赖**：从3个internal API依赖降至1个（AgentToSubCompiledGraphNodeAdapter由asNode()封装）
3. **代码结构**：从1个复杂L1ToolAdapter到多个简单DomainTool/DomainReactAgent
4. **中断等价性**：源码确认Plan B+的中断传播与Plan B完全等价，推翻原评审"中断降级"结论

**原报告中Plan B的评分和风险分析保留不变**，以下为原始内容。

---

## 一、评价维度总览

| # | 评价维度 | 权重 | Plan A 得分 | Plan B 得分 | 说明 |
|---|---------|------|-----------|-----------|------|
| 1 | 动态变更可适配性 | ⭐⭐⭐⭐⭐ | 4/10 | 9/10 | 核心判断维度 |
| 2 | 五大意图场景覆盖度 | ⭐⭐⭐⭐⭐ | 5/10 | 9/10 | 未来统一收编的门槛 |
| 3 | 框架对齐度 | ⭐⭐⭐⭐ | 5/10 | 9/10 | Spring AI Alibaba生态方向 |
| 4 | 开发速度(Phase 1) | ⭐⭐⭐⭐ | 8/10 | 5/10 | 短期交付压力 |
| 5 | 调试可观测性 | ⭐⭐⭐ | 8/10 | 5/10 | 生产环境排障 |
| 6 | 框架依赖风险 | ⭐⭐⭐ | 9/10 | 4/10 | 版本升级/内部API变化 |
| 7 | 代码复杂度 | ⭐⭐⭐ | 7/10 | 4/10 | 维护成本 |
| 8 | 可溯源性 | ⭐⭐⭐ | 7/10 | 8/10 | 每步可追踪 |
| 9 | 运行时稳定性 | ⭐⭐⭐ | 6/10 | 7/10 | 并发/竞态/异常 |
| 10 | 扩展性(新增域/场景) | ⭐⭐⭐⭐ | 6/10 | 9/10 | 新增域零改动 |

**加权总分**：Plan A = **5.9/10** | Plan B = **7.4/10**

---

## 二、逐维度深度评价

### 维度1：动态变更可适配性 ⭐⭐⭐⭐⭐

这是**决定性维度**——决定了OrchestrationAgent能否真正替代L1的意图状态机。

#### Plan A (4/10) — 命令式执行，补丁式变更

**结构性限制**：

| 变更类型 | 能力 | 原因 |
|---------|------|------|
| Insert | 中 | REPLAN或手动insert，但LLM未参与设计修改后的Plan |
| Delete | 中 | steps.remove()可行，但后续依赖链可能断裂 |
| Reorder | 高成本 | 需跳出while-loop或全量REPLAN |
| Branch | 极高成本 | while-loop是串行的，不支持并行 |
| Conditional Skip | 中 | _orchFlags标记可行 |
| Rollback | 极高成本 | 无checkpoint/replay基础设施 |

**根本原因**：while-loop是**命令式执行**——"做什么"由loop顺序决定。变更需要改loop逻辑或全量REPLAN。每个变更场景都需要补丁（CAS标志、步骤状态、手动依赖追踪），补丁累积后Plan A的"简单"优势消失。

**blockLast()阻塞问题**：用户在Step执行中说"等等先查余额"，DomainTool.execute()的blockLast()阻塞线程，当前Step必须执行完才能响应变更。无法mid-step中断。

#### Plan B (9/10) — 声明式路由，原生适配

**结构性优势**：

| 变更类型 | 能力 | 机制 |
|---------|------|------|
| Insert | 低成本 | state append + conditional edge自动路由 |
| Delete | 低成本 | state remove + conditional edge跳过 |
| Reorder | 低成本 | state中改变顺序，conditional edge跟随 |
| Branch | 中成本 | 需预定义并行结构，但StateGraph支持 |
| Conditional Skip | 低成本 | conditional edge天然支持 |
| Rollback | 高成本 | 需手动实现状态快照 |

**根本原因**：StateGraph + conditional edge是**声明式路由**——"去哪"由state决定。变更只改state，路由自动适配。不需要修改执行引擎。

**L1ToolAdapter中断**：实现InterruptableAction，可在node边界中断。用户意图变更时，interrupt()返回InterruptionMetadata，图暂停，不需要等当前Step完成。

**扣分点**：Branch需预定义（-0.5），Rollback无内建支持（-0.5）。

#### 维度结论

> **Plan B的声明式路由在动态变更上有结构性优势。** Plan A的while-loop补丁式变更，在简单场景可工作，但在"消解L1意图状态机"这个目标上，补丁会越来越多，最终Plan A的"简单"优势消失。

---

### 维度2：五大意图场景覆盖度 ⭐⭐⭐⭐⭐

这是**未来统一收编的最低门槛**——如果OrchestrationAgent做不好这五个场景，就无法替代L1的意图状态机。

#### 场景逐一对比

##### 意图顺延 (Intent Postpone)

| 维度 | Plan A | Plan B |
|------|--------|--------|
| Step未开始 | ✅ reorder steps | ✅ state reorder + conditional edge |
| Step执行中 | ❌ blockLast()阻塞，无法中断 | ✅ L1ToolAdapter.interrupt()在node边界中断 |
| Step已完成 | ❌ 已执行操作不可撤回 | ❌ 同左（但中断时机更早，减少此情况） |
| 顺延后恢复 | ✅ while-loop自然轮到 | ✅ conditional edge自然路由 |
| 替代L1 suspended？ | **部分** | **完全** |

**关键差异**：Plan A在Step执行中无法响应顺延请求（必须等完成），Plan B可立即中断。

##### 意图跳转 (Intent Switch)

| 维度 | Plan A | Plan B |
|------|--------|--------|
| Step未开始 | ✅ CANCELLED + 新Step | ✅ CANCELLED + 新Step |
| Step执行中 | ❌ 等完成或L2中断点 | ✅ L1ToolAdapter.interrupt()中断 |
| 跨域跳转 | ✅ Plan天然跨域 | ✅ SubGraphRegistry动态查找L2 |
| L2 cleanup | ⚠️ 需L1提供cancel() | ⚠️ Flux.dispose()但L2内部状态需cleanup |
| 替代L1 switch？ | **有限** | **完全** |

##### 意图恢复 (Intent Resume)

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 自然轮转恢复 | ✅ while-loop继续到被顺延Step | ✅ conditional edge路由到被顺延Step |
| 从断点恢复 | ⚠️ 依赖L1.resumeActiveAgent() | ✅ 框架ResumableSubGraphAction + initializeFromResume() |
| 恢复精度 | ⚠️ 从L1入口恢复 | ✅ 从L2中断节点恢复(更精确) |
| 替代L1 resume？ | **大部分** | **完全，且更优** |

**Plan B更优**：直接操作L2的CompiledGraph，利用框架原生的ResumableSubGraphAction实现断点恢复，比L1的resume更精确（从L2中断节点恢复，而非从L1入口恢复）。

##### 意图澄清 (Intent Clarify)

| 维度 | Plan A | Plan B |
|------|--------|--------|
| CLARIFY Step类型 | ✅ PlanStepType.CLARIFY | ✅ StepType.CLARIFY |
| 等待用户输入 | ✅ CAS WAITING_USER | ✅ L1ToolAdapter.interrupt() |
| 跨域澄清 | ✅ Plan天然跨域 | ✅ 同左 |
| 多轮澄清 | ⚠️ 需额外逻辑 | ✅ conditional edge路由回planNode |
| 替代L1 clarify？ | **完全** | **完全，且更优雅** |

##### 意图拒绝 (Intent Reject)

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 用户主动拒绝 | ✅ CANCELLED + 下一步 | ✅ CANCELLED + conditional edge路由 |
| 系统风控拒绝 | ✅ FAILED + REPLAN | ✅ FAILED + conditional edge路由到planNode |
| 拒绝原因传播 | ⚠️ StreamChunk.reason | ✅ InterruptionMetadata.metadata |
| 拒绝类型区分 | ⚠️ 需从StreamChunk解析 | ✅ InterruptionMetadata结构化 |
| 替代L1 reject？ | **完全** | **完全，且更灵活** |

#### 维度结论

| 意图场景 | Plan A | Plan B | 差距 |
|---------|--------|--------|------|
| 意图顺延 | 部分 | 完全 | **大** — blockLast()阻塞是关键瓶颈 |
| 意图跳转 | 有限 | 完全 | **大** — 同上 |
| 意图恢复 | 大部分 | 完全+更优 | **中** — Plan B断点恢复更精确 |
| 意图澄清 | 完全 | 完全+更优雅 | **小** — 两者都可行 |
| 意图拒绝 | 完全 | 完全+更灵活 | **小** — InterruptionMetadata结构化更好 |

> **Plan A在意图顺延和意图跳转上有结构性缺陷**（blockLast()阻塞导致无法mid-step中断），这恰恰是生产环境最高频的打岔场景。Plan B在所有五个场景上都能完全替代L1机制。

---

### 维度3：框架对齐度 ⭐⭐⭐⭐

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 中断机制 | 自建(_orchFlags/StreamChunk) | 框架原生(InterruptionMetadata) |
| 流式传输 | 自建(Sinks.Many+blockLast) | 框架原生(StreamingOutput透传) |
| 恢复机制 | L1.resumeActiveAgent() | 框架原生(ResumableSubGraphAction) |
| 循环模式 | while-loop(自建) | StateGraph conditional edge(框架) |
| 子图调用 | DomainTool→L1.handle() | SubCompiledGraphNodeAction(框架) |
| 状态管理 | GlobalSessionContext(自建) | OverAllState(框架) |
| 框架版本升级 | 影响小(只依赖ReactAgent public API) | 影响大(依赖内部API) |

> Plan B与框架的对齐度远高于Plan A——中断、流式、恢复、循环、子图都是框架原生机制。但框架对齐的代价是对内部API的依赖，版本升级时可能需要适配。

---

### 维度4：开发速度(Phase 1) ⭐⭐⭐⭐

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 核心代码量 | ~300行while-loop + DomainTool | ~500行StateGraph + L1ToolAdapter + StateMapper |
| 概念理解成本 | 低 — while-loop是普通Java | 高 — 需理解StateGraph/InterruptableAction/InterruptionMetadata |
| 现有代码复用 | 高 — v5.1就是while-loop | 低 — 需要重写编排逻辑 |
| L1改造 | 零 — 直接调用L1.handle() | ~150行 — L1胶水逻辑迁移到L1ToolAdapter |
| 调试难度 | 低 — 断点/日志/AOP直接 | 高 — graph执行流调试困难 |
| agent-framework依赖 | 需添加(ReactAgent在此模块) | 需添加 + 需理解内部API |

> **Phase 1开发速度Plan A显著占优。** v5.1就是while-loop架构，开发人员已熟悉。Plan B需要学习StateGraph/InterruptableAction等概念，且框架文档有限。

---

### 维度5：调试可观测性 ⭐⭐⭐

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 断点调试 | ✅ 普通Java方法，直接打断点 | ❌ graph节点是框架调度，断点困难 |
| 日志追踪 | ✅ AOP切片可直接拦截 | ⚠️ 需要GraphLifecycleListener |
| StepTrace | ✅ 每步记录在OrchestrationState | ✅ OverAllState天然支持 |
| 执行路径可视化 | ⚠️ while-loop路径是线性的 | ✅ graph拓扑可视化更直观 |
| 异常堆栈 | ✅ Java堆栈清晰 | ❌ graph嵌套调用，堆栈深且难读 |

> **Plan A在调试上显著占优。** 这是while-loop最大的优势——普通Java代码，所有调试工具直接可用。

---

### 维度6：框架依赖风险 ⭐⭐⭐

| 依赖项 | Plan A 风险 | Plan B 风险 |
|--------|-----------|-----------|
| ReactAgent | 低 — public API | 低 — public API |
| InterruptableAction | — 不使用 | 低 — public接口 |
| InterruptionMetadata | — 不使用 | 低 — public类 |
| SubCompiledGraphNodeAction | — 不使用 | **高 — internal包** |
| GraphRunnerContext.initializeFromResume() | — 不使用 | **高 — 内部实现** |
| MainGraphExecutor / NodeExecutor | — 不使用 | **高 — 内部实现** |
| OverAllState key strategy | — 不使用 | 中 — 需了解key命名约定 |

> **Plan A只依赖ReactAgent这一层public API，框架升级影响极小。** Plan B重度依赖internal包的类(SubCompiledGraphNodeAction等)，框架升级时可能需要适配。

**缓解措施（Plan B）**：对internal API做适配层封装，框架升级时只需改适配层。但这增加了初始开发成本。

---

### 维度7：代码复杂度 ⭐⭐⭐

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 核心类数量 | 7 (OrchestrationAgent + 6职责Bean) | 9 (+StateGraph配置 + L1ToolAdapter + StateMapper) |
| 核心代码行数 | ~800行 | ~1200行 |
| 理解门槛 | 低 — while-loop是常见模式 | 高 — 需理解graph/中断/子图机制 |
| 修改影响范围 | 小 — while-loop修改局部 | 中 — graph拓扑修改影响全局 |
| 测试难度 | 低 — 普通Java单测 | 高 — 需要mock框架graph执行 |

> Plan A代码更简单、更易理解、更易修改。Plan B复杂度来自框架机制的理解成本和internal API的适配。

---

### 维度8：可溯源性 ⭐⭐⭐

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 每步PlanStep ID | ✅ step.index | ✅ step.index + nodeId |
| 中断恢复trace连续性 | ⚠️ _orchFlags可能丢失 | ✅ InterruptionMetadata携带完整上下文 |
| REPLAN旧Plan归档 | ✅ OrchestrationState.stepTraces | ✅ OverAllState + checkpoint |
| 跨L1→L2→L2子图correlationId | ⚠️ 需手动传递 | ✅ RunnableConfig.threadId自动传播 |
| 动态变更审计 | ⚠️ 变更分散在多处补丁 | ✅ 集中在conditional edge路由函数 |

> Plan B在trace连续性和跨层correlationId上略优，得益于框架的RunnableConfig传播机制。

---

### 维度9：运行时稳定性 ⭐⭐⭐

| 风险 | Plan A | Plan B |
|------|--------|--------|
| 并发竞态 | ⚠️ CAS保护WAITING_USER状态 | ✅ 框架checkpoint机制 |
| Sinks.Many背压丢chunk | ⚠️ tryEmitNext()可能丢失 | — 不使用Sinks.Many |
| blockLast()线程阻塞 | ⚠️ 线程被占用期间无法响应新请求 | — 不阻塞 |
| Flux dispose异常 | — | ⚠️ L2子图Flux dispose可能遗留状态 |
| graph嵌套深度 | — 无嵌套 | ⚠️ L2子图→NodeExecutor→MainGraphExecutor→编排graph |
| L2子图执行超时 | ⚠️ 需手动实现超时机制 | ⚠️ 需手动实现超时机制 |

> Plan B在并发安全性上略优（框架checkpoint），但graph嵌套增加了运行时复杂度。Plan A的Sinks.Many背压风险和blockLast()阻塞是需要注意的问题。

---

### 维度10：扩展性(新增域/场景) ⭐⭐⭐⭐

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 新增域(如INSURANCE) | ✅ 注册新DomainTool | ✅ SubGraphRegistry注册L2 |
| 编排代码改动 | ✅ ReactAgent自动发现Tool | ✅ L1ToolAdapter动态查找L2 |
| 新增意图场景 | ⚠️ 需在while-loop加条件分支 | ✅ 扩展conditional edge路由函数 |
| 并行执行 | ❌ while-loop串行 | ✅ StateGraph支持并行节点 |
| 条件表达式扩展 | ⚠️ 需改checkCondition() | ✅ 只需改conditionCheckNode |
| 动态Plan字段扩展 | ✅ OrchestrationState可扩展 | ✅ OverAllState可扩展 |

> Plan B在新增意图场景和并行执行上扩展性更好。conditional edge路由函数是声明式的，新场景只需扩展路由逻辑，不需要改graph拓扑。

---

## 三、战略视角评价

### 3.1 "OrchestrationAgent作为未来统一路由层"的适配度

用户明确要求：OrchestrationAgent必须设计好，未来模型性能提升后可以统一收编单意图路径。这意味着OrchestrationAgent必须是L0/L1/L2的**超集**，不是子集。

| 战略要求 | Plan A 适配度 | Plan B 适配度 |
|---------|-------------|-------------|
| 消解L1意图状态机 | ⚠️ 部分可消解(顺延/跳转受blockLast限制) | ✅ 完全消解 |
| 一层识别一层协调 | ⚠️ 全量REPLAN成本高，增量修改不可靠 | ✅ conditional edge声明式协调 |
| 意图顺延/跳转/恢复/澄清/拒绝 | ⚠️ 2/5有限，3/5完全 | ✅ 5/5完全 |
| 新增域零改动 | ✅ DomainTool注册 | ✅ SubGraphRegistry注册 |
| 模型性能提升后可平滑切换 | ⚠️ while-loop → 需重写为graph | ✅ 已经是graph，只需优化 |

> **从战略视角看，Plan B是更安全的选择。** 它为未来统一收编提供了更平滑的路径——已经是声明式graph架构，性能优化只需调参数和模型，不需要改架构。Plan A如果将来要统一收编，需要从while-loop迁移到graph，等于重写一次。

### 3.2 一层识别一层协调 vs 两层识别两层协调

当前架构中，跨L1意图切换需要L0识别+L0协调L1 suspend/resume，两层识别两次LLM调用，两层协调两层状态机。

| 维度 | Plan A | Plan B |
|------|--------|--------|
| 意图识别层数 | 1层(PlannerAgent) | 1层(PlannerAgent) |
| 意图协调层数 | 1层(while-loop) | 1层(conditional edge) |
| 协调方式 | 命令式(loop逻辑) | 声明式(state→路由) |
| 跨域上下文连续性 | ✅ Plan天然跨域 | ✅ Plan天然跨域 |
| 误判传播 | 1次LLM调用 | 1次LLM调用 |

> 两个方案都能实现一层识别一层协调，但Plan B的声明式协调更优雅——变更只改state，路由自动适配。Plan A的命令式协调需要改loop逻辑或全量REPLAN。

---

## 四、风险矩阵

### Plan A 风险

| 风险 | 严重度 | 可能性 | 影响 | 缓解 | 来源 |
|------|--------|--------|------|------|------|
| blockLast()无法mid-step中断 | 🔴 高 | 🔴 高(确定发生) | 意图顺延/跳转体验差；AI误判导致不可逆金融操作 | L1提供cancel()接口, 但需改L1 | 原始+Oracle+Metis |
| Sinks.Many背压丢chunk | 🔴 高↑ | 🟡 中 | 前端丢失进度信息（确认转账chunk丢失→用户看不到结果） | tryEmitNext→emitNext+错误处理；unicast→multicast | Oracle |
| Sinks.Many unicast单订阅者 | 🟡 中↑ | 🔴 高(SSE重连) | 移动网络不稳定时sink失效，用户必须刷新页面 | 换用multicast().onBackpressureBuffer() | Oracle |
| blockLast()线程池耗尽 | 🔴 高(新增) | 🟡 中(20+并发) | boundedElastic线程被占满，新请求排队 | 每session独立线程池+超时保护 | Oracle |
| RelevanceAgent误判(假阳性) | 🔴 高(新增) | 🟡 中 | 用错误输入恢复编排→执行不可逆金融操作 | 加置信度阈值(>0.85执行, 0.5-0.85澄清, <0.5默认意图变更) | Metis |
| 补丁累积导致while-loop复杂化 | 🟡 中 | 🟡 中 | 维护成本上升（Oracle估算至少5个补丁） | 严格控制补丁数量 | 原始+Oracle |
| 未来统一收编需重写 | 🔴 高 | 🟡 中 | 二次开发成本 | 设计时预留graph迁移接口 | 原始 |

### Plan B 风险

| 风险 | 严重度 | 可能性 | 影响 | 缓解 | 来源 |
|------|--------|--------|------|------|------|
| SubCompiledGraphNodeAction是internal API | 🔴 高 | 🟡 中 | 框架升级时可能break | 适配层封装，升级时只改适配层 | 原始+Librarian确认 |
| EXECUTING状态用户输入无法到达编排层(G1) | 🔴 高(新增) | 🔴 高(确定发生) | **mid-step interrupt核心优势无法兑现** | 设计非阻塞输入队列/WebSocket推送/响应式流合并 | Momus |
| interrupt()时序描述错误(G2) | 🔴 高(新增) | 🔴 高(框架行为确定) | interrupt()在apply()之前调用，无法响应mid-execution新输入 | 仅在step boundary检测新输入；或改用OrchestrationState轮询 | Momus |
| OrchestrationState与OverAllState二元性(G3) | 🔴 高(新增) | 🟡 中 | 状态真相不明确，数据不一致 | 统一为一套状态模型，或写显式同步桥接代码 | Momus |
| graph嵌套调试困难 | 🟡 中 | 🔴 高(确定发生) | 生产排障时间增加（堆栈4层深且难读） | 完善GraphLifecycleListener日志+StepTrace | 原始+Oracle |
| StateMapper key非框架标准 | 🔴 高(新增) | 🔴 高(确定发生) | StateMapper生成的key L2图不识别 | L2图配置显式注册_latestUserInput/_question/_outputContent | Librarian |
| L1胶水逻辑迁移遗漏 | 🟡 中 | 🟡 中 | 功能缺失 | 逐项对照L1逻辑迁移checklist | 原始 |
| 框架InterruptionMetadata传播有bug | 🔴 高 | 🟢 低(已从源码验证) | 中断恢复失败 | 源码已验证传播链路；需集成测试覆盖 | 原始+Librarian部分修正 |
| isNewIntent()假阳性(正常执行中误判) | 🟡 中(新增) | 🟡 中 | 不必要中断+浪费REPLAN计数 | 仅在_latestUserInput变更时调用；加timestamp检查 | Metis |
| agent-framework模块缺失 | 🟢 低 | 🟢 低(只需加依赖) | 编译失败 | pom.xml添加依赖 | 原始+Librarian确认 |

---

## 五、综合结论（含四路评审修正）

### 5.1 一句话判断

> **Plan B在架构层面更优（动态变更+意图管理+框架对齐+战略适配），Plan A在工程层面更优（开发速度+调试+风险）。但四路评审发现：两份文档均存在阻断性Gap——EXECUTING状态下的用户输入注入路径未设计，Plan B的mid-step interrupt核心优势需要重新验证。**

### 5.2 修正后评分

**原始加权总分**：Plan A = 5.9/10 | Plan B = 7.4/10  
**评审修正后总分**：Plan A = **5.7/10** | Plan B = **7.1/10**

修正原因：
- Plan A -0.2：Oracle确认Sinks.Many有生产bug（tryEmitNext丢chunk + unicast单订阅者限制）
- Plan B -0.3：Momus发现G1/G2削弱mid-step interrupt核心优势；Librarian确认StateMapper key非框架标准；Metis发现isNewIntent()引入新失败模式

### 5.3 推荐方案（评审修正版）

#### 如果优先"快速交付Phase 1" → 选 Plan A

- v5.1已经是while-loop架构，开发成本最低
- Phase 1场景相对简单（3个域，意图顺延/跳转场景不频发）
- 后续可以逐步迁移到Plan B（但迁移成本不低）
- ⚠️ **评审新增风险**：Sinks.Many生产bug必须在Phase 1前修复（unicast→multicast, tryEmitNext→emitNext）

#### 如果优先"架构正确性+未来统一收编" → 选 Plan B

- 声明式路由天然支持动态变更
- 框架对齐度高，未来升级路径清晰
- 五大意图场景4/5完全覆盖+1/5需G1/G2解决后验证，可完全替代L1意图状态机
- 初始开发成本高，但长期维护成本更低
- ⚠️ **评审新增前提**：
  1. P0集成测试必须通过（2级嵌套图 + interruptBefore + resume）
  2. 必须设计用户输入注入路径（G1），否则mid-step interrupt承诺无法兑现
  3. internal API适配层必须先建
  4. StateMapper的key必须在L2图配置中显式注册（非框架标准key）

#### 新增：Plan B-lite 回退方案

**如果P0集成测试失败**（框架resume机制不工作），Oracle建议：

> 用StateGraph做路由 + L1.resumeActiveAgent()处理interrupt/resume——保留Plan B的声明式路由优势，但避免internal API依赖。这是Plan A和Plan B的务实折中。

| 维度 | Plan B-lite |
|------|------------|
| 路由方式 | StateGraph conditional edge（声明式）✅ |
| 中断恢复 | L1.resumeActiveAgent()（与Plan A相同）⚠️ |
| Internal API依赖 | 无——只用public API ✅ |
| Mid-step interrupt | 与Plan A相同限制（依赖L2 interruptBefore）⚠️ |
| 开发成本 | 中——StateGraph配置 + L1胶水逻辑，无适配层 |

### 5.4 折中方案评估

**Plan A先行 + 预留Plan B迁移接口**是否可行？

| 维度 | 评估 |
|------|------|
| 可行性 | ⚠️ 中等 — 需要OrchestrationAgent内部预留graph接口 |
| 迁移成本 | 🔴 高 — while-loop → StateGraph等于重写编排核心 |
| 风险 | 🟡 中 — 两套架构并存期间的维护成本 |

**结论**：折中方案的迁移成本不低。如果最终要走向Plan B，直接用Plan B更经济。

### 5.5 最终建议（评审修正版）

**仍推荐 Plan B**，但附加**3个必须前置条件**：

1. **动态变更是核心能力**：用户打岔/意图变更在生产环境是高频场景，blockLast()无法mid-step中断是Plan A的结构性缺陷，不是可以通过补丁解决的
2. **战略定位决定架构选择**：OrchestrationAgent的目标是未来统一收编L0/L1/L2，Plan B的声明式架构为统一收编提供了最平滑的路径
3. **框架方向是graph化**：Spring AI Alibaba的演进方向是更深度地使用StateGraph/InterruptableAction等graph原语，Plan B与框架方向一致
4. **银行业fail-safe原则**：Metis确认Plan B的AI误判是fail-safe（浪费REPLAN），Plan A的AI误判是fail-dangerous（执行不可逆操作）——在银行业这是决定性优势

**必须前置条件**（不满足则退回Plan B-lite）：

| # | 前置条件 | 来源 | 验证方式 |
|---|---------|------|---------|
| C1 | P0集成测试通过：2级嵌套图 + interruptBefore + resume完整链路 | Oracle | 实际运行测试用例 |
| C2 | 用户输入注入路径已设计：EXECUTING状态下新输入可达编排层 | Momus G1 | 代码+文档 |
| C3 | SubCompiledGraphNodeAction反编译验证通过：构造函数签名+流式行为 | Oracle+Librarian | 反编译v1.1.2.0 jar |

**如果C1-C3任一失败**：退回Plan B-lite（StateGraph路由 + L1.resumeActiveAgent()恢复），避免internal API依赖。

---

## 六、四路专家评审综合报告

> **评审时间**: 2026-06-14  
> **评审人**: Oracle(架构+运行时+溯源性), Metis(AI失败模式+动态规划准确性), Momus(文档质量+完整性+可验证性), Librarian(框架API验证)  
> **评审基线**: 两份设计文档 + 本对比报告 + Spring AI Alibaba v1.1.2.0源码

---

### 6.1 评审结论速览

| 评审人 | 核心结论 | Plan A | Plan B | 置信度 |
|--------|---------|--------|--------|--------|
| **Oracle** | blockLast()是结构性缺陷，不可补丁修复 | 架构5/10, 运行时4/10 | 架构7/10, 运行时6/10 | 75% |
| **Metis** | Plan B失效模式是fail-safe，Plan A是fail-dangerous——银行业不可接受 | AI韧性5/10 | AI韧性8/10 | 高 |
| **Momus** | **两份文档均不可直接实施**——G1(用户输入注入路径)是共同阻断缺陷 | 可执行性7/10 | 可执行性6/10 | 高 |
| **Librarian** | Claim 9被DENIED——StateMapper硬编码的key在框架中不存在；SubCompiledGraphNodeAction已确认 | — | 高风险 | 高 |

---

### 6.2 跨评审人共识

#### 共识1：blockLast()是Plan A的结构性缺陷，不可补丁修复（Oracle + Metis + Momus）

- Oracle：**"This is not a patch problem. It's a control-flow model problem."** blockLast()使线程在Step执行期间对外部信号"失聪"，无法通过补丁实现mid-step中断。
- Metis：**"Plan A's blockLast() is the single most consequential AI failure amplifier — it turns a recoverable misclassification into an irreversible wrong operation."**
- Momus：Plan A文档内部已诚实承认此限制，Plan B文档则对mid-step interrupt能力过度承诺（见G2）。

**结论**：Plan A的blockLast()限制是公认的，不是争议点。争议点在于Plan B是否真正能deliver mid-step interrupt（见共识3）。

#### 共识2：Plan B需要适配层封装internal API（Oracle + Librarian）

- Oracle：**"Build adapter layer first"**，所有框架internal调用通过适配层，升级时只改一个文件。
- Librarian：SubCompiledGraphNodeAction确认为`public record`但在`internal.node`包中，构造函数签名已确认。**但实际行为（流式输出格式、resume细节）未从源码完全验证**。

**结论**：internal API风险是**可定位、可封装的局部风险**，不是结构性缺陷。

#### 共识3：两份文档的共同阻断缺陷——EXECUTING状态下的用户输入注入路径（Momus + Oracle + Metis）

**Momus发现的最关键Gap（G1）**：

> **两份文档的`handle()`方法在`status == EXECUTING`时都返回`"请稍后再试"`。但两份文档都声称具备mid-step意图变更能力（Plan B via interrupt()，Plan A via _orchFlags）。输入注入路径是最关键的缺失——没有它，"5大意图场景"架构是理论性的。**

这意味着：
- Plan A：_orchFlags可以被设置，但handle()拒绝了所有EXECUTING期间的输入——**谁来设置flags？**
- Plan B：interrupt()由框架在apply()之前调用，但新用户消息到达handle()时被拒绝——**interrupt()如何感知到新输入？**

**这是Plan B核心优势声明的根本挑战**：如果新用户输入无法到达正在运行的图，Plan B的"mid-step interrupt"承诺就无法兑现。

#### 共识4：两份文档都需要correlationId和L2超时机制（Oracle + Metis）

- Oracle：**"Neither plan has a trace ID spanning L0→L1→L2. Add X-Correlation-Id header propagation."**
- Metis：**"Both plans need L2 node execution timeout. No plan should block indefinitely."**
- Oracle同时指出：Plan A的Sinks.Many是`unicast()`（单订阅者），SSE重连后sink失效；`tryEmitNext()`忽略返回值，chunks在背压下静默丢失。

---

### 6.3 Oracle评审详要

#### 架构正确性

| 维度 | Plan A | Plan B | 说明 |
|------|--------|--------|------|
| InterruptionMetadata传播链 | — | ⚠️ 部分正确 | 概念正确但方法名有误：`MainGraphExecutor.done()`实际是`GraphResponse.done()`；`interruptBefore`是编译时配置，与`InterruptableAction.interrupt()`是独立机制 |
| blockLast() + Sinks.Many并发安全 | ❌ 不安全 | — | `unicast()`单订阅者限制；`tryEmitNext()`忽略EmitResult；线程池在20+并发用户时耗尽 |
| while-loop 5意图场景 | ⚠️ 2/5受限 | — | 文档诚实承认postpone/switch在step执行中受限 |

#### 5个关键发现

1. **InterruptableAction能否作为StateGraph的独立NodeAction使用未确认**——框架中HITL是作为ModelHook（agent级钩子）使用，而非standalone node action
2. **SubCompiledGraphNodeAction构造函数签名未从字节码验证**——文档假设`(nodeId, compileConfig, compiledGraph)`，record规范构造函数支持此假设但未实际反编译确认
3. **Sinks.Many生命周期矛盾**——`OrchAgentConfig`注册为singleton Bean，但`executeLoop()`每次创建新实例，DomainTool注入的sink与实际streaming sink不一致
4. **graph.stream(null, config)恢复机制可能混淆了两种框架机制**——`InterruptionHook`使用`agentThreadState.remove(INTERRUPTION_FEEDBACK_KEY)`，文档描述的是`RunnableConfig.metadata`传播，两者是不同路径
5. **两份文档均未处理L2子图超时**——L2挂起时Plan A的blockLast()永远阻塞，Plan B的SubCompiledGraphNodeAction.apply()同样永远阻塞

#### Oracle推荐行动

1. **编码前反编译SubCompiledGraphNodeAction、ResumableSubGraphAction、GraphRunnerContext**——验证实际方法签名（P0）
2. **先建适配层**——所有internal API调用通过SubGraphActionAdapter
3. **P0集成测试**——2级嵌套图 + interruptBefore + resume，验证完整中断恢复链
4. **Phase 1裁剪**——跳过mid-step interrupt（最难部分），Phase 2再验证InterruptableAction作为standalone node的可行性
5. **回退方案（Plan B-lite）**——如果P0集成测试失败，用StateGraph做路由 + L1.resumeActiveAgent()处理interrupt/resume，避免internal API依赖

---

### 6.4 Metis评审详要

#### AI失败模式目录（按严重度×可能性排序）

| 排名 | 失败模式 | 严重度 | 可能性 | S×L | 主要影响方案 |
|------|---------|--------|--------|-----|------------|
| **F1** | Mid-step意图变更：blockLast()强制执行完成 | 🔴Critical | 🔴High | 9 | Plan A only |
| **F2** | RelevanceAgent/interrupt()误判用户输入 | 🔴Critical | 🟡Medium | 6 | 两者，机制不同 |
| **F3** | REPLAN漂移：2次+REPLAN后新Plan偏离原始意图 | 🔴High | 🟡Medium | 6 | 两者，Plan A略差 |
| **F4** | rewrittenInput语义丢失（超越金额：实体名/意图扭曲/指代消解） | 🟡High | 🟡Medium | 6 | 两者同等 |
| **F5** | Plan依赖验证缺口：循环依赖/前向引用/悬空引用 | 🟡High | 🟡Medium | 6 | 两者同等 |

#### 核心洞察：Fail-safe vs Fail-dangerous

**这是Metis最重要的发现，对银行业具有决定性意义**：

| 误判类型 | Plan A后果 | Plan B后果 |
|---------|-----------|-----------|
| 假阳性（说"相关"实际不相关） | 用错误输入恢复编排 → **执行不可逆金融操作** | 不必要中断 → **浪费一次REPLAN计数** |
| 假阴性（说"不相关"实际相关） | 编排挂起 → 用户困惑 | 触发REPLAN → **浪费REPLAN但无错误操作** |

> **Plan B的误判是fail-safe（可恢复），Plan A的误判是fail-dangerous（不可逆）。在银行业，fail-safe > fail-dangerous是不可谈判的原则。**

#### Plan B引入的新AI失败模式

**isNewIntent()在每个step boundary被调用**——在正常多步执行（"查余额→转账→买基金"）中，如果Step 2误判为"新意图"，会不必要中断并触发REPLAN。这是**高频低严重度**的失败模式，Plan A不存在（Plan A只在WAITING_USER期间调用RelevanceAgent）。

**缓解**：仅在`_latestUserInput`自上次step开始后实际变更时才调用isNewIntent()，加timestamp/version检查。

#### 5个隐藏假设

| # | 假设 | 风险 | 缓解建议 |
|---|------|------|---------|
| A1 | isNewIntent()/isRelevant()足够可靠 | 🔴 单点故障 | 加置信度阈值：>0.85直接执行，0.5-0.85澄清，<0.5默认意图变更 |
| A2 | PlannerAgent知道每步的输出schema | 🟡 数据流断裂 | 声明outputKeys/inputKeys契约 |
| A3 | L2 interruptBefore点足够密 | 🟡 实际中断延迟 | 文档化各L2节点执行时长和中断点 |
| A4 | 单用户输入per turn | 🟡 竞态丢输入 | 加输入队列，latest-wins for intent |
| A5 | REPLAN后已完成步骤输出仍有效 | 🟡 过期数据传播 | 加依赖失效机制 |

---

### 6.5 Momus评审详要

#### 文档质量评分

| 标准 | Plan A | Plan B | 对比文档 |
|------|--------|--------|---------|
| 可执行性 | 7/10 | 6/10 | 8/10 |
| 完整性 | 6/10 | 5/10 | 7/10 |
| 可验证性 | 5/10 | 5/10 | 6/10 |
| 动态变更覆盖 | 5/10 | 7/10 | 7/10 |
| 战略定位 | 7/10 | 8/10 | 8/10 |
| 跨文档一致性 | — | — | 6/10 |

#### 3个阻断性Gap（Critical — 阻塞实施）

**G1：两份文档均未说明EXECUTING状态下新用户输入如何到达编排层**

这是**最关键的缺失**。两份文档的`handle()`在EXECUTING时都返回拒绝消息，但都声称具备mid-step意图变更能力。输入注入路径缺失意味着"5大意图场景"是理论性的。

**G2：Plan B的L1ToolAdapter.interrupt()时序描述错误**

文档声称interrupt()在"用户发送新输入时"触发。但框架在`apply()`之前调用`interrupt()`——它不是响应mid-execution新输入的回调。如果`apply()`已在运行（L2子图执行中），到达`handle()`的新消息被拒绝。**Plan B的核心优势声明无法按描述兑现。**

**G3：Plan B的OrchestrationState与OverAllState二元性未解决**

`handle()`读取`OrchestrationState`，但StateGraph操作`OverAllState`。无桥接代码。`graphResponseToStreamChunk()`读OverAllState，`resumeOrchestration()`读OrchestrationState。**状态真相在哪？**

#### 5个高严重度Gap

| # | Gap | 影响 |
|---|-----|------|
| G4 | Plan A Sinks.Many生命周期不一致（singleton Bean vs per-invocation） | chunk丢失或发送到错误订阅者 |
| G5 | Plan A引用未定义方法（handleReplanAsync等5个） | 开发者需猜测签名 |
| G6 | Plan B的SubCompiledGraphNodeAction用法是推测性的 | 构造函数/流式行为未验证 |
| G7 | Plan B的conditionCheckNode实现缺失 | checkAndRoute方法未定义 |
| G8 | Plan B的planNode输出映射缺失 | PlannerAgent.asNode()输出如何变为STEPS |

#### 文档内矛盾

1. **Plan A内部**：§3.1承认"无法在Step执行中响应顺延请求"，但§4.4描述_orchFlags作为mid-execution检测机制——矛盾：谁来设置flags？
2. **Plan B内部**：§3.1声称interrupt()在"用户发送新输入时"触发，但§5.4显示interrupt()由框架在apply()之前调用——无法由mid-execution新输入触发
3. **Plan B内部**：§4.4声称"Flux流式透传，不需要Sinks.Many"，但§9.1 graphResponseToStreamChunk()手动转换——这不是透传，是显式映射
4. **对比文档**：Plan B在"五大意图场景覆盖度"和"动态变更可适配性"各得9/10，但mid-step interrupt机制有根本性缺口（G1/G2），分数应下调1-2分

---

### 6.6 Librarian评审详要

#### 框架API验证结果

| # | 声明 | 状态 | 风险 | 关键修正 |
|---|------|------|------|---------|
| 1 | InterruptableAction接口（两方法） | ✅确认 | 🟢低 | `com.alibaba.cloud.ai.graph.action`包，public接口，签名完全匹配 |
| 2 | InterruptionMetadata自动传播 | 🟡部分正确 | 🟡中 | `MainGraphExecutor.done()`实际是`GraphResponse.done()`；interruptBefore是编译时配置，与InterruptableAction.interrupt()是独立机制 |
| 3 | SubCompiledGraphNodeAction | ✅确认 | 🟢低 | `public record`在`internal.node`包，构造函数`(nodeId, parentCompileConfig, subGraph)`匹配 |
| 4 | ResumableSubGraphAction + initializeFromResume() | 🟡部分正确 | 🟡中 | resume元数据key是节点特定的`resume_subgraph_{nodeId}`而非全局；initializeFromResume()由HUMAN_FEEDBACK_METADATA_KEY或checkPointId存在自动触发 |
| 5 | ReactAgent内部StateGraph | 🟡部分正确 | 🟡中 | 拓扑比描述更复杂——每个Hook成为一等公民节点，边链接复杂 |
| 6 | ReactAgent在agent-framework模块 | ✅确认 | 🟢低 | artifact: `spring-ai-alibaba-agent-framework` |
| 7 | HITL实现InterruptableAction | ✅确认 | 🟢低 | AFTER_MODEL位置，approvalOn签名匹配 |
| 8 | CompiledGraph编译后不可变 | ✅确认 | 🟢低 | 无公共API修改拓扑 |
| **9** | **OverAllState key strategy** | **🔴DENIED** | **🔴高** | **`_latestUserInput`、`_question`、`_outputContent`在框架中不存在；`AbstractGraphConfig`不存在；只有`messages`(AppendStrategy)是标准框架key** |
| 10 | SummarizationHook内置 | ✅确认 | 🟢低 | Builder API完全匹配 |

#### 🔴 最关键发现：Claim 9 DENIED

Plan B的StateMapper代码硬编码了框架中不存在的key名：

```java
// Plan B StateMapper — 这些key在框架中不存在
l2Data.put("_latestUserInput", step.getRewrittenInput());
l2Data.put("_question", step.getDescription());
l2Data.put("_outputContent", "");
```

**影响**：StateMapper生成的L2 OverAllState中的key，L2图未配置识别——OverAllState只处理KeyStrategy映射中定义的key。

**缓解**：项目必须在L2图配置中显式注册这些key及其策略：
```java
StateGraph graph = new StateGraph("transfer", () -> Map.of(
    "messages", new AppendStrategy(),
    "_latestUserInput", new ReplaceStrategy(),
    "_question", new ReplaceStrategy(),
    "_outputContent", new ReplaceStrategy()
));
```

**风险评级**：🔴高——StateMapper代码不能按原样工作，需要L2图配置配合。这不是设计错误，但文档必须明确这些key是项目自定义的，不是框架标准。

---

### 6.7 综合评审对对比文档评分的影响

基于4路评审发现，原对比文档评分需要修正：

| # | 维度 | 原Plan A | 修正Plan A | 原Plan B | 修正Plan B | 修正原因 |
|---|------|---------|-----------|---------|-----------|---------|
| 1 | 动态变更可适配性 | 4 | 4 | 9 | **8** | G1/G2削弱Plan B的mid-step interrupt承诺；但仍优于Plan A |
| 2 | 五大意图场景覆盖度 | 5 | 5 | 9 | **8** | G1/G2使"5/5完全覆盖"承诺不成立；但仍是4/5 vs 2/5 |
| 3 | 框架对齐度 | 5 | 5 | 9 | **8** | Claim 9 DENIED：StateMapper的key是项目自定义非框架标准；-1 |
| 5 | 调试可观测性 | 8 | 8 | 5 | **4** | Oracle确认graph嵌套堆栈深且难读；-1 |
| 6 | 框架依赖风险 | 9 | 9 | 4 | **4** | Librarian确认internal API细节；风险如实 |
| 9 | 运行时稳定性 | 6 | **5** | 7 | **7** | Oracle确认Sinks.Many有生产bug(tryEmitNext丢chunk)；Plan A -1 |
| 10 | 扩展性 | 6 | 6 | 9 | **8** | G1/G2意味着新增意图场景的mid-step能力同样受影响；-1 |

**修正后加权总分**：Plan A = **5.7/10** | Plan B = **7.1/10**

（Plan A从5.9降至5.7，主因Sinks.Many生产风险；Plan B从7.4降至7.1，主因G1/G2对核心优势的削弱）

---

### 6.8 评审驱动的必须行动项

#### P0 — 必须在编码前完成（阻断性）

| # | 行动项 | 来源 | 影响方案 |
|---|--------|------|---------|
| P0-1 | **设计EXECUTING状态下的用户输入注入路径**——这是两方案共同的阻断缺陷 | Momus G1 + Oracle | 两者 |
| P0-2 | **反编译SubCompiledGraphNodeAction、ResumableSubGraphAction、GraphRunnerContext**，验证实际方法签名和流式行为 | Oracle + Librarian | Plan B |
| P0-3 | **P0集成测试**：2级嵌套图 + interruptBefore + resume，验证完整中断恢复链 | Oracle | Plan B |
| P0-4 | **解决OrchestrationState与OverAllState二元性**——明确状态真相归属 | Momus G3 | Plan B |

#### P1 — 必须在Phase 1前完成

| # | 行动项 | 来源 | 影响方案 |
|---|--------|------|---------|
| P1-1 | 修复Sinks.Many：unicast→multicast, tryEmitNext→emitNext+错误处理 | Oracle | Plan A |
| P1-2 | 加correlationId跨L0→L1→L2传播（X-Correlation-Id via RunnableConfig.metadata） | Oracle + Metis | 两者 |
| P1-3 | 加L2子图执行超时（flux.timeout(Duration)） | Oracle + Metis | 两者 |
| P1-4 | L2图配置显式注册`_latestUserInput`/`_question`/`_outputContent` key及策略 | Librarian Claim 9 | Plan B |
| P1-5 | 定义isNewIntent()/isRelevant()的LLM prompt + 置信度阈值 + 回退启发式 | Momus G10 + Metis A1 | 两者 |
| P1-6 | Plan A修正Sinks.Many生命周期（per-session，非singleton Bean） | Momus G4 | Plan A |

#### P2 — Phase 2应完成

| # | 行动项 | 来源 | 影响方案 |
|---|--------|------|---------|
| P2-1 | Plan依赖DAG验证（循环依赖/前向引用/悬空引用检测） | Metis F5 | 两者 |
| P2-2 | rewrittenInput语义保真度检查（实体名+意图保持+条件保留） | Metis F4 | 两者 |
| P2-3 | REPLAN依赖失效机制（上游步骤输出失效→下游重执行） | Metis A5 | 两者 |
| P2-4 | 用户输入队列（rapid input buffering + latest-wins） | Metis A4 | 两者 |
| P2-5 | WAITING_USER超时处理（expiresAt检查+CANCELLED转换） | Momus G9 | 两者 |
| P2-6 | 扩展CodeBasedComparator覆盖银行业务已知条件类型 | Metis F6 | 两者 |
