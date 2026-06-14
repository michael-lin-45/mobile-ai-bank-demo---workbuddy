# Plan B+ 评价报告（源码级验证版）

> **评价基准**: Spring AI Alibaba 1.1.2.0 graph-core + agent-framework 源码反编译验证  
> **评价维度**: 1)提议的合理性 2)架构的合理性 3)流程调用的合理性 4)改动的大小 5)架构以后的可扩展性  
> **评价时间**: 2026-06-14  
> **关键修正**: 源码验证推翻了4路评审中"ReactAgent.asNode()中断退化"的核心结论

---

## 零、源码级验证结论（4路评审修正）

### 被推翻的结论

4路评审（Oracle/Metis/Momus/Librarian）的核心判断之一是：

> "ReactAgent.asNode()返回的AgentToSubCompiledGraphNodeAdapter不实现InterruptableAction，因此Plan B+的中断能力相对于Plan B有结构性退化。"

**这个结论是错误的。** 原因：SubCompiledGraphNodeAction（Plan B使用的类）**也不实现InterruptableAction**。

### 源码验证事实

#### 事实1：两个Adapter都不实现InterruptableAction

```
SubCompiledGraphNodeAction  implements AsyncNodeActionWithConfig, ResumableSubGraphAction
AgentToSubCompiledGraphNodeAdapter implements NodeActionWithConfig, ResumableSubGraphAction
```

两者都**不实现InterruptableAction**。中断不是在父图node boundary发生的。

#### 事实2：中断传播机制完全相同

框架的中断传播走的是**子图Flux流嵌入**机制，而非父图node的InterruptableAction检查：

```
子图内部InterruptableAction节点触发(如InterruptionHook/HITL)
  → 子图GraphRunner产生GraphResponse.done(InterruptionMetadata)
  → InterruptionMetadata通过Flux流嵌入到父图
  → 父图NodeExecutor.processGraphResponseFlux()检测到instanceof InterruptionMetadata
  → context.setReturnFromEmbedWithValue(InterruptionMetadata)
  → 父图MainGraphExecutor.execute()下一轮检查returnFromEmbed
  → 父图暂停，返回GraphResponse.done(InterruptionMetadata)
```

**Plan B和Plan B+走的是完全相同的传播路径。**

#### 事实3：Resume机制完全相同

两者都实现ResumableSubGraphAction接口：

```
SubCompiledGraphNodeAction.getResumeSubGraphId() → resumeSubGraphId(nodeId)
AgentToSubCompiledGraphNodeAdapter.getResumeSubGraphId() → resumeSubGraphId(nodeId)
```

Resume时GraphRunnerContext.initializeFromResume()检测到ResumableSubGraphAction：
```java
if (startCheckpointNextNodeAction instanceof ResumableSubGraphAction resumableAction) {
    this.config = RunnableConfig.builder(config)
        .addMetadata(resumableAction.getResumeSubGraphId(), true)
        .build();
}
```

两个Adapter读取相同的metadata key，执行相同的updateState()恢复逻辑。

#### 事实4：框架中断检查的两条路径

**路径A：静态配置中断**（GraphRunnerContext.shouldInterrupt()）
- 检查CompileConfig.interruptsBefore/interruptsAfter（节点名集合）
- 与节点是否实现InterruptableAction无关
- 用于"编译时声明"哪些节点前后暂停

**路径B：动态InterruptableAction中断**（NodeExecutor.executeNode()）
- 在**每个节点执行前**检查`nodeAction instanceof InterruptableAction`
- 如果是，调用`interruptableAction.interrupt(nodeId, state, config)`
- 如果返回Optional.of(InterruptionMetadata)，图暂停
- 在**节点执行后**还有`interruptAfter()`检查

**关键**：路径B只作用于**直接子节点**——InterruptionHook和HumanInTheLoopHook是ReactAgent内部图中的节点，它们在子图自己的执行循环中触发路径B，产生的InterruptionMetadata通过路径A的Flux流嵌入机制传播到父图。

### 修正后的对比

| 机制 | Plan B (SubCompiledGraphNodeAction) | Plan B+ (AgentToSubCompiledGraphNodeAdapter) |
|------|-------------------------------------|----------------------------------------------|
| 实现InterruptableAction？ | ❌ | ❌ |
| 实现ResumableSubGraphAction？ | ✅ | ✅ |
| 子图执行方式 | `subGraph.graphResponseStream()` | `childGraph.graphResponseStream()` |
| 中断传播路径 | Flux流→processGraphResponseFlux→returnFromEmbed | **完全相同** |
| Resume路径 | resumeSubGraphId metadata→updateState() | **完全相同** |
| 子图内部中断节点 | 依赖L2图内部是否有InterruptableAction | ✅ ReactAgent自动有InterruptionHook |

---

## 一、维度1：提议的合理性 — 8/10

### 核心提议

> "L2提供原子能力，并作为Tool的形式向上可被调用。领域Agent后续如果模型增强了，可以通过添加Tools的形式提供更多的能力"

### 提议价值验证

| 价值点 | 源码验证 | 评估 |
|--------|---------|------|
| L2作为Tool向上可调用 | ReactAgent的ToolCallback接口支持任意Tool | ✅ 可行 |
| 添加Tools扩展能力 | AgentToolNode.getToolCallbacks()动态加载 | ✅ 可行 |
| 消除内部API依赖 | ReactAgent.asNode()是public API | ✅ 确认 |
| 跨域Tool组合 | ReactAgent可配置多个Tool，模型决定调用 | ✅ 可行但有限制 |

### 限制条件

1. **跨域组合仅限低风险场景**：ReactAgent内部Tool选择由LLM推理（~95%准确率），转账/支付等写操作不应由LLM自主组合
2. **金额保真度**：Tool参数传递经过ReactAgent的LLM推理，可能丢失精确数值。需用ToolCallback的参数schema约束
3. **"先查后转"场景**：PlannerAgent完全可以拆成两个条件Step实现确定性执行，ReactAgent的自主组合不是唯一解

### 结论

提议方向正确，"L2作为Tool向上可调用"是一个合理的抽象。核心价值在于**域内扩展的灵活性**——新能力=新Tool，无需改图拓扑。跨域组合的价值有限且需限定场景。

---

## 二、维度2：架构的合理性 — 7/10（修正后）

### 架构对比（源码级）

```
Plan B:
  编排图 → executeStepNode → L1ToolAdapter(InterruptableAction)
                              ↓ SubCompiledGraphNodeAction
                              ↓ L2 CompiledGraph
                              ↓ (L2内部可能有InterruptableAction节点)
                              ↑ Flux流嵌入 → returnFromEmbed → 父图暂停

Plan B+:
  编排图 → executeStepNode → ReactAgent.asNode()
                              ↓ AgentSubGraphNode
                              ↓ AgentToSubCompiledGraphNodeAdapter(ResumableSubGraphAction)
                              ↓ ReactAgent内部图(AGENT_MODEL → AGENT_TOOL → loop)
                              ↓   InterruptionHook(InterruptableAction) ← 自动注入
                              ↓   HumanInTheLoopHook(InterruptableAction) ← 可选注入
                              ↑ Flux流嵌入 → returnFromEmbed → 父图暂停
```

### 架构等价性分析

| 架构属性 | Plan B | Plan B+ | 差异 |
|---------|--------|---------|------|
| 子图嵌入方式 | SubCompiledGraphNode | AgentSubGraphNode(Node+SubGraphNode) | 等价——都是Node |
| 子图执行 | graphResponseStream() | graphResponseStream() | **相同** |
| 中断传播 | Flux流→returnFromEmbed | Flux流→returnFromEmbed | **相同** |
| Resume | ResumableSubGraphAction | ResumableSubGraphAction | **相同** |
| 状态隔离 | 父图state→子图state（SubCompiledGraphNodeAction自行映射） | 父图state→子图state（AgentToSubCompiledGraphNodeAdapter自行映射） | 实现不同，机制相同 |
| L1是否在路径中 | L1ToolAdapter直接调用L2 | Tool.execute()→L1.handle()→L2 | ⚠️ Plan B+多了一层L1 |

### ⚠️ Plan B+的架构差异（真实问题）

**L1重新出现在路径中**——这是唯一真实的架构退化：

- Plan B: `L1ToolAdapter.apply()` → `SubCompiledGraphNodeAction` → L2 CompiledGraph（**绕过L1**）
- Plan B+: `DomainTool.execute()` → `L1.handle()` → L2（**经过L1**）

这意味着：
1. L1的handle()逻辑（意图状态机、意图路由）仍在执行路径中
2. 新增域仍需改L1代码（至少新增handle()分支）
3. OrchestrationAgent收编L1的目标被推迟

但这个差异的本质是**Tool内部实现选择**——如果DomainTool直接调用L2（跳过L1），这个差异就不存在。这取决于实现而非架构。

### 结论

Plan B+的架构在**中断/恢复**层面与Plan B等价（源码级确认）。真实差异在于L1是否在执行路径中——这是实现选择，非架构限制。扣分点：ReactAgent内部多了一层LLM推理（AGENT_MODEL节点），增加了执行路径的不确定性。

---

## 三、维度3：流程调用的合理性 — 7/10（修正后）

### 流程对比（源码级修正）

#### 中断流程（Plan B）

```
1. 编排图执行executeStepNode
2. L1ToolAdapter.apply() → SubCompiledGraphNodeAction.apply()
3. SubCompiledGraphNodeAction启动子图graphResponseStream()
4. L2内部节点（如果有InterruptableAction）触发中断
5. 子图GraphRunner产生GraphResponse.done(InterruptionMetadata)
6. InterruptionMetadata通过Flux流传到父图
7. 父图NodeExecutor.processGraphResponseFlux()检测到
8. context.setReturnFromEmbedWithValue(InterruptionMetadata)
9. 父图MainGraphExecutor下一轮检查returnFromEmbed → 暂停
```

#### 中断流程（Plan B+）

```
1. 编排图执行executeStepNode
2. AgentToSubCompiledGraphNodeAdapter.apply() → 启动childGraph.graphResponseStream()
3. ReactAgent内部图执行：AGENT_MODEL → [InterruptionHook.beforeModel] → AGENT_TOOL → loop
4. InterruptionHook.interrupt()检查 → 触发中断
5. ReactAgent子图GraphRunner产生GraphResponse.done(InterruptionMetadata)
6. InterruptionMetadata通过Flux流传到父图
7. 父图NodeExecutor.processGraphResponseFlux()检测到
8. context.setReturnFromEmbedWithValue(InterruptionMetadata)
9. 父图MainGraphExecutor下一轮检查returnFromEmbed → 暂停
```

**步骤数相同（9步），中断传播机制相同。**

#### 之前错误的分析

4路评审说"Plan B+中断需要4步（3步依赖模型判断）"——这是错的。InterruptionHook的interrupt()方法检查的是`agentThreadState.get(INTERRUPTION_FEEDBACK_KEY)`——这是一个ConcurrentHashMap操作，不依赖LLM。触发条件是外部调用`reactAgent.interrupt(config)`或`reactAgent.updateAgentState(state, config)`，与LLM推理无关。

### 真实的流程差异

| 流程属性 | Plan B | Plan B+ | 影响 |
|---------|--------|---------|------|
| 每步LLM调用次数 | 1次（PlannerAgent） | 2次（PlannerAgent + ReactAgent内部AGENT_MODEL） | 🟡 延迟增加，成本增加 |
| Tool选择 | 确定性（PlannerAgent指定） | LLM推理（ReactAgent模型选择） | 🟡 ~95%准确率 |
| 金额保真度 | PlannerAgent重写→L2 | PlannerAgent重写→ReactAgent→Tool参数→L2 | 🟡 多一层LLM跳转 |
| 执行顺序 | PlannerAgent条件Step | ReactAgent推理决定 | 🟡 低风险可接受，高风险不行 |

### 结论

中断流程等价（源码级确认），之前"中断4步链"结论错误。真实的流程差异在于**每步多一次LLM调用**和**Tool选择的不确定性**。这些是可控的——高风险域用确定性Tool选择（Plan B模式），低风险域允许ReactAgent自主选择。

---

## 四、维度4：改动的大小 — 6/10

### 代码量对比

| 组件 | Plan B | Plan B+ | 变化 |
|------|--------|---------|------|
| 编排图拓扑 | 4节点（START→plan→execute→condition） | 4+N节点（每个域一个ReactAgent节点） | 图变大 |
| L1ToolAdapter | 核心（~200行） | **删除** | -200行 |
| StateMapper | 核心（~100行） | **删除**（ReactAgent内部处理） | -100行 |
| SubGraphRegistry | 核心（~50行） | **删除** | -50行 |
| DomainReactAgent | 无 | **新增**×3（~150行/个） | +450行 |
| DomainTool | 无 | **新增**×3（~80行/个） | +240行 |
| IntentChangeHook | 无 | **新增**（~100行） | +100行 |
| 编排图配置 | ~50行 | ~150行（多节点多边） | +100行 |

**净变化**：+540行（Plan B ~1200行 → Plan B+ ~1740行）

### 关键不是行数，是架构性质

| 性质 | Plan B | Plan B+ |
|------|--------|---------|
| 路由方式 | 动态路由（SubGraphRegistry按domainId查找） | 静态拓扑（条件边到固定ReactAgent节点） |
| 新增域 | 新增L2→注册到SubGraphRegistry→零改动 | 新增ReactAgent Bean + 新增图节点 + 新增条件边 |
| 确定性 | 高（L1ToolAdapter是确定性代码） | 中（ReactAgent内部有LLM推理层） |

### 结论

行数增加可接受（+45%），但架构性质从动态路由变为静态拓扑——这影响了扩展性（见维度5）。

---

## 五、维度5：架构以后的可扩展性 — 7/10

### 扩展场景逐项分析

| 扩展场景 | Plan B | Plan B+ | 分析 |
|---------|--------|---------|------|
| 新增Tool到现有域 | 需改L2图定义 | 改ReactAgent的tools列表 | ✅ Plan B+优——只改配置 |
| 新增L2子图到现有域 | 需改L1 handle() | 新增DomainTool Bean | ✅ Plan B+优——更解耦 |
| 新增域 | 注册SubGraphRegistry→零改动 | 新增ReactAgent Bean + 图节点 + 条件边 | ⚠️ Plan B优——零改动vs多改动 |
| 新增意图场景 | 改编排图条件边 | 改编排图条件边+可能改ReactAgent内部 | 🟡 相当 |
| 扩展到10+域 | 零拓扑变化 | 10+固定节点+10+条件边 | ⚠️ Plan B优——图不可维护 |
| Phase 2全量替代L1 | OrchestrationState已绕过L1 | L1仍在路径中 | ⚠️ Plan B优——真正绕过 |
| 模型升级扩展能力 | 改L2图 | 改Tool列表+模型自动学习 | ✅ Plan B+优——更灵活 |

### 关键洞察

Plan B+的扩展性优势集中在**域内纵向扩展**（加Tool），劣势在**跨域横向扩展**（加域）。

- 域内纵向扩展：Plan B+ >> Plan B（新能力=新Tool，零代码）
- 跨域横向扩展：Plan B >> Plan B+（零改动 vs 改图拓扑）

对于银行业务，域的数量相对固定（3-5个核心域），但每个域的能力会持续扩展。**纵向扩展更频繁**，Plan B+的优势更实际。

---

## 六、可观测性设计（currentStep/currentAction/nextAction）

### 无论选Plan B还是Plan B+，都应作为Phase 1必须项

### OverAllState新增字段

| 字段 | 类型 | KeyStrategy | 说明 | 谁写入 | 谁读取 |
|------|------|-------------|------|--------|--------|
| `orch_currentStepIndex` | Integer | ReplaceStrategy | 当前执行到Plan第几步 | conditionCheckNode | 前端/调试 |
| `orch_currentAction` | String | ReplaceStrategy | 当前正在做什么 | executeStepNode | 前端/调试 |
| `orch_nextAction` | String | ReplaceStrategy | 下一步将做什么 | conditionCheckNode | 前端/调试 |
| `orch_stepStatus` | String | ReplaceStrategy | 当前步骤状态 | executeStepNode | 前端/调试 |

### 步骤状态枚举

```java
public enum StepStatus {
    PENDING,           // 等待执行
    RUNNING,           // 正在执行
    WAITING_USER,      // 等待用户输入（中断后）
    INTERRUPTED,       // 已中断
    COMPLETED,         // 执行完成
    FAILED,            // 执行失败
    SKIPPED            // 条件跳过
}
```

### Plan B+中的特殊考虑

ReactAgent.asNode()内部执行对父图是黑盒。`orch_currentAction`需要从ReactAgent内部透传：

```java
// DomainTool.execute()中更新父图state
@Override
public String execute(ToolContext context) {
    // 更新进度信息到父图state
    OverAllState parentState = (OverAllState) context.getState();
    parentState.update("orch_currentAction", "正在查询账户余额...");
    
    // 调用L1/L2
    Flux<StreamChunk> result = l1Handler.handle(request);
    // ...
}
```

或者通过ReactAgent的Hook机制在每次模型调用前后更新：

```java
// 自定义ProgressHook
public class ProgressHook extends ModelHook implements AgentHook {
    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        state.update("orch_currentAction", "Agent开始推理...");
        return CompletableFuture.completedFuture(Map.of());
    }
}
```

---

## 七、综合评分（源码级修正后）

| # | 维度 | 4路评审得分 | 源码验证后得分 | 修正原因 |
|---|------|-----------|-------------|---------|
| 1 | 提议的合理性 | 6/10 | **8/10** | 消除内部API依赖+域内扩展灵活性被低估 |
| 2 | 架构的合理性 | 5/10 | **7/10** | 中断等价性确认，L1回归是实现选择非架构限制 |
| 3 | 流程调用的合理性 | 5/10 | **7/10** | "中断4步链"结论错误，中断机制等价 |
| 4 | 改动的大小 | 6/10 | **6/10** | 无变化 |
| 5 | 可扩展性 | 5/10 | **7/10** | 域内纵向扩展优势被低估，这是更频繁的场景 |

**综合得分**：7.0/10（vs 4路评审5.2/10）

---

## 八、最终建议

### Plan B 作为Phase 1主干 + Plan B+作为Phase 2增强

| Phase | 方案 | 核心组件 | 说明 |
|-------|------|---------|------|
| **Phase 1** | Plan B | L1ToolAdapter + SubGraphRegistry | 保证5/5意图场景 + 中断 + L1绕过 + 动态路由 |
| **Phase 2** | Plan B+局部增强 | 对低风险域引入ReactAgent.asNode() | 域内Tool扩展能力 |
| **Phase 3** | 框架演进 | 等框架支持更多Agent-as-Node能力 | 全量迁移 |

### Phase 2"局部增强"方式

```
Phase 1 (全部确定性):
  executeStepNode → L1ToolAdapter (所有域)

Phase 2 (混合模式):
  executeStepNode → 条件边路由:
    TRANSFER域 → L1ToolAdapter (确定性，HITL保护)
    WEALTH域 → WealthReactAgent.asNode() (可扩展)
    BILL域   → BillReactAgent.asNode() (可扩展)
```

### 为什么不是直接Plan B+？

1. **Phase 1需要确定性**：银行业首期上线，每一步的执行路径必须100%可预测
2. **L1绕过是Phase 1目标**：OrchestrationAgent收编L1是战略方向，Plan B+推迟了这个目标
3. **动态路由更灵活**：新增域零改动 vs 改图拓扑
4. **混合模式两全**：高风险域用Plan B的确定性，低风险域用Plan B+的扩展性

### 可观测性作为Phase 1必须项

无论选Plan B还是Plan B+，`currentStep/currentAction/nextAction/stepStatus`这4个字段都应在Phase 1实现。这是生产环境排障的基础设施。
