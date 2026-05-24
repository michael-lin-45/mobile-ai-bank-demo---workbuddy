# 分析: L2提参层领域相关性检测与REROUTE

> 状态: 思路分析，非实施文档 | 2026-05-24
> 
> 本文档记录对"L2 Graph提参时判断用户输入是否属于本领域"这一思路的深度分析。
> 包括可行性论证、难点剖析、多方案对比、拓展思路。不涉及实施。

---

## 1. 问题背景

### 1.1 v2设计的缺口

P1 REROUTE v2设计（见 DESIGN-P1-REROUTE.md）的检测范围：

| L1类型 | REROUTE检测 | 机制 |
|--------|------------|------|
| Multi (WealthService) | ✅ 有 | IntentRouter识别出跨域意图 → handle()层判断isOwnIntent() → REROUTE |
| Single (Transfer/Bill) | ❌ 无 | 没有意图识别层，无法在L1层判断 |

**v2的结论**: Single域不做L1层REROUTE检测，靠L0 DomainRouter源头准确性兜底。

### 1.2 为什么需要继续思考

v2的"源头兜底"策略有一个现实风险：**L0 DomainRouter的LLM路由本身就不完美**。当用户表达模糊（"朝朝盈"没有明确领域关键词），LLM路由可能误分类。如果L1/L2都无法纠正，用户会被困在错误流程中。

**核心矛盾**: Multi有IntentRouter可以自救，Single没有任何"自救"机制。

---

## 2. 原始思路: L2提参时做领域相关性判断

### 2.1 灵感来源: cancel检测

当前L2已有`cancelAwareExtractParams()`——在提参前先问LLM"用户是否想取消"：

```
cancelAwareExtractParams(state):
  1. 检查_cancelSignal → 有则直接返回cancel
  2. 关键字匹配 → "算了""取消"等 → cancel
  3. LLM判断 → "用户是否想放弃当前操作" → cancel/not cancel
  4. 非cancel → 正常提参
```

**类比**: 能否用同样模式做领域相关性判断？

```
domainAwareExtractParams(state):
  1. 判断用户输入是否属于本领域
  2. 不属于 → 返回_rerouteSignal
  3. 属于 → 正常提参
```

### 2.2 表面吸引力

| 优势 | 说明 |
|------|------|
| 零额外LLM调用（理想情况） | 在extractParams的LLM调用中顺带判断 |
| 覆盖Single域 | L2是Single域唯一的LLM交互点，不经过L2无法检测 |
| 统一模式 | 和cancel检测共享"前置判断→分流"的模式 |
| 对所有域一致 | Multi和Single的L2都做同样的判断 |

---

## 3. 难点剖析

### 3.1 难点一: cancel判断 vs 领域相关性判断的本质差异

cancel检测和领域相关性检测看似对称，实则不在同一个难度级别：

| 维度 | cancel检测 | 领域相关性检测 |
|------|-----------|---------------|
| **判断对象** | 用户是否想**放弃**当前操作 | 用户输入是否**属于**当前领域 |
| **语义空间** | 窄：cancel是明确的否定意图，表达方式有限 | 宽：需要理解语义是否匹配领域，空间巨大 |
| **决策边界** | 清晰：用户说"算了""取消"→ cancel | **模糊**："我要转账"和"解读朝朝盈"都无参数，但前者属于转账 |
| **"不知道"的归属** | 没有中间态：要么cancel要么不cancel | 有中间态：**不确定是否属于本域** |
| **LLM判断难度** | 低（否定意图容易识别） | **高**（需要区分"没提到参数"vs"不属于此域"） |
| **误判后果** | 误判cancel→用户说"我没想取消"，可恢复 | 误判reroute→中断正常提参流程，用户体验差 |

**关键推演**：

```
场景A: 用户说"我要转账" → TransferGraph
  extractParams: 无参数可提取
  domain_match: ??? → true（显然属于转账域）
  
  但"我要转账"和"解读朝朝盈"在"参数可提取性"维度上完全一样！

场景B: 用户说"解读朝朝盈" → TransferGraph（误分类）
  extractParams: 无参数可提取（理想情况）
  domain_match: ??? → false（不属于转账域）
  
  A和B的区别不在于"有没有参数"，而在于"语义方向"
```

**结论**: 领域相关性判断需要**语义理解**，不是简单的模式匹配。cancel检测本质上是模式匹配（否定表达有固定模式），领域相关性判断本质上是**语义分类**。

### 3.2 难点二: LLM的配合性偏见 (Sycophancy Bias)

LLM被分配了"转账参数提取器"的角色后，倾向于**配合**而不是**拒绝**：

当前`buildExtractPrompt()`:
```
你是一个银行转账参数提取器。从用户输入中提取转账相关参数。
用户输入: 解读一下朝朝盈
提取规则:
- receiver: 收款人姓名,如"张三"、"李四"
```

LLM看到"朝朝盈"，很可能将其解读为收款人名字：
```json
{"receiver": "朝朝盈"}
```

这不是LLM"出错"——在转账语境下，"朝朝盈"确实可以是人名。LLM做了合理推断，但这个推断在用户意图层面是错误的。

**即使加入domain_match字段**：
```
Step 1: 判断用户输入是否与转账相关 (domain_match: true/false)
Step 2: 如果相关，提取参数

用户输入: 解读一下朝朝盈
```

LLM可能返回：
- 理想: `{"domain_match": false}` ← 正确
- 现实风险: `{"domain_match": true, "receiver": "朝朝盈"}` ← 配合性偏见

**为什么LLM倾向于说"true"**：
1. 角色锚定：已经是"转账参数提取器"，说"不相关"等于否定自身角色
2. 提取偏好：LLM更倾向于"从输入中找到点什么"而不是"拒绝输入"
3. 缺乏拒绝训练：大多数LLM的训练数据中，"拒绝"是负面行为

### 3.3 难点三: SWITCH_NEW vs FOLLOW_UP的区分

在FOLLOW_UP场景中，用户正在回答我们的追问，输入**必然属于本域**：

```
Transfer: "请问您要转给谁？"
用户: "张三"  ← FOLLOW_UP，必然属于转账域

Transfer: "请问您要转多少金额？"
用户: "500"  ← FOLLOW_UP，必然属于转账域
```

但用户的FOLLOW_UP回答也可能是**意图切换**：

```
Transfer: "请问您要转给谁？"
用户: "算了，帮我解读一下朝朝盈"  ← FOLLOW_UP中包含意图切换
```

当前的cancel检测已经覆盖了"算了"这种case。但如果没有"算了"前缀呢？

```
Transfer: "请问您要转给谁？"
用户: "解读一下朝朝盈"  ← 无取消前缀，纯意图切换
```

这种情况下：
- cancel检测: 不cancel（用户没说"不要了"）
- domain_match检测: 如果做，应该返回"不属于转账域"
- 但当前v2设计中，FOLLOW_UP直接走L2 resume，L1不做任何检测

**FOLLOW_UP中的领域相关性检测是否需要？这是一个开放问题。**

### 3.4 难点四: L2职责边界

当前L2的职责很清晰：

```
L2 = 状态机: extractParams → paramRouter → askXxx / executeXxx
```

加入domain_match后：

```
L2 = 领域判断 + 状态机: domainMatch? → extractParams → paramRouter → ...
```

**职责膨胀的风险**：
- L2从"领域无关的参数提取执行器"变成"领域感知的判断器"
- 每个L2子图（Transfer、Bill、WealthConsult、WealthInterpret）都需要加domain_match逻辑
- domain_match的判断标准因域而异（转账域的"相关性"≠理财域的"相关性"）
- 维护成本从N个提参prompt变成N个提参prompt + N个领域判断prompt

---

## 4. 方案对比: 六种思路

### 4.1 方案A: L2提参prompt内嵌domain_match

**在extractParams的LLM调用中同时返回domain_match字段**

```
你是一个银行转账参数提取器。
先判断用户输入是否与转账相关，再提取参数。

用户输入: 解读一下朝朝盈

输出JSON:
{
  "domain_match": true/false,
  "receiver": "...",
  "amount": ...
}
```

| 维度 | 评价 |
|------|------|
| 额外LLM成本 | 零（复用提参调用） |
| 可靠性 | **低** — LLM配合性偏见导致domain_match倾向于true |
| 侵入性 | 中 — 改buildExtractPrompt()和parseExtractResult() |
| L2纯净性 | 低 — 提参prompt混合了领域判断逻辑 |

**核心问题**: 提参和领域判断在同一个prompt中冲突。LLM的角色是"提取器"，让它同时做"拒绝判断"是矛盾指令。

### 4.2 方案B: L2独立LLM调用做domain_match（两步分离）

**先独立调用LLM判断领域相关性，再调LLM提参**

```
Step 1 (独立LLM调用): 
  "用户输入'解读一下朝朝盈'是否与银行转账（给他人转钱）相关？只回答true或false。"
  
Step 2 (仅在Step1=true时执行):
  buildExtractPrompt() + callExtractModel()
```

| 维度 | 评价 |
|------|------|
| 额外LLM成本 | 每次SWITCH_NEW多一次调用 (~200-500ms) |
| 可靠性 | **中高** — 独立调用避免了配合性偏见，LLM的角色是"判断者"而非"提取器" |
| 侵入性 | 中 — 加domainAwareExtractParams()，不改已有prompt |
| L2纯净性 | 中 — 加了判断步骤，但和提参分离 |
| FOLLOW_UP | 不需要判断（用户在回答追问），不增加延迟 |

**关键设计**: 只在SWITCH_NEW（首次进入L2）时做domain_match判断。FOLLOW_UP时跳过，因为：
1. 用户在回答我们的追问，输入必然与本域相关
2. FOLLOW_UP频率远高于SWITCH_NEW，不做判断可节省大量延迟

**实现模式**（类似cancelAwareExtractParams）：

```java
// AbstractGraphConfig中新增
protected Map<String, Object> domainAwareExtractParams(OverAllState state) {
    // 只在首次调用时检测（通过state中是否有历史参数判断）
    boolean isFirstCall = !state.value("_domainChecked").isPresent();
    if (isFirstCall) {
        boolean domainMatch = detectDomainMatch(state);
        state.update("_domainChecked", true);
        if (!domainMatch) {
            return Map.of("_rerouteSignal", true);
        }
    }
    return null; // 继续正常提参
}

// 子类的extractParamsNode:
private Map<String, Object> extractParamsNode(OverAllState state) {
    Map<String, Object> cancelResult = cancelAwareExtractParams(state);
    if (cancelResult != null) return cancelResult;
    
    Map<String, Object> rerouteResult = domainAwareExtractParams(state);  // 新增
    if (rerouteResult != null) return rerouteResult;
    
    // ... 原有提参逻辑 ...
}
```

**本质**: 这就是"给Single加独立LLM域校验"，只是位置在L2而非L1。

### 4.3 方案C: L2提参后置质量检测

**不在提参前判断，而在提参后检测提取质量**

思路：如果LLM提取出的参数"不靠谱"（如receiver="朝朝盈"看起来不像人名），则触发REROUTE。

```
extractParams: {receiver: "朝朝盈"}
→ 质量检测: "朝朝盈"是合理的收款人名字吗？
→ LLM: "不像，更像是理财产品名称"
→ _rerouteSignal
```

| 维度 | 评价 |
|------|------|
| 额外LLM成本 | 每次SWITCH_NEW多一次调用 |
| 可靠性 | **中** — 参数质量判断比领域相关性判断更容易 |
| 侵入性 | 高 — 需要定义每个参数的"质量标准"，且标准因域而异 |
| 核心问题 | "张三"是合理的名字吗？是。"朝朝盈"是合理的名字吗？也许。判断标准模糊 |

**问题**: 参数质量检测和领域相关性检测是不同的问题。"张三500"提取出receiver="张三", amount=500，质量没问题，但用户的真实意图可能完全不是转账。

### 4.4 方案D: L1 pre-flight轻量校验（非LLM）

**在L1调L2之前，用规则/关键词做快速领域校验**

```java
// SingleSubAgentDomainService.handle()中
if (isFirstInteraction(sessionId)) {
    DomainCheckResult check = quickDomainCheck(userInput, domainName);
    if (check.isMismatch()) {
        return WorkflowOutput.reroute(null, null);
    }
}
```

规则示例：
- TRANSFER域：输入包含"转账""转给""汇款"等关键词 → 匹配
- BILL域：输入包含"账单""明细""收支"等关键词 → 匹配
- 都不包含 → 不确定，放行（宁可不拦截，不可误杀）

| 维度 | 评价 |
|------|------|
| 额外LLM成本 | 零 |
| 延迟 | ~1ms |
| 可靠性 | **低** — 只能检测"明显包含本域关键词"的case，漏检率高 |
| 误杀率 | 低 — 不包含关键词只是"不确定"，不是"不匹配" |
| 核心问题 | 能检测的case（"帮我转账"），DomainRouter的确定性路由已经覆盖了 |

**本质**: 和DomainRouter的确定性路由重复——如果输入包含"转账"，DomainRouter已经能正确路由到TRANSFER。真正的问题是**没有关键词的模糊表达**，规则校验帮不上忙。

### 4.5 方案E: 并行域守卫（Domain Guard）

**L2提参的同时，并行启动一个"域守卫"LLM调用**

```
L0 → SingleSubAgentDomainService.handle()
  ├── L2提参（主流程）
  └── Domain Guard（并行LLM调用）: "这个输入是否与[领域描述]相关？"
  
结果汇总:
  - 如果Domain Guard返回false + L2提参空 → REROUTE
  - 如果Domain Guard返回true → 正常流程
  - 如果Domain Guard返回false + L2提参非空 → 冲突，倾向正常流程
```

| 维度 | 评价 |
|------|------|
| 额外LLM成本 | 每次SWITCH_NEW多一次调用，但**并行执行，无额外延迟** |
| 可靠性 | **中高** — Domain Guard的LLM角色是"判断者"，不受提参偏见影响 |
| 侵入性 | 高 — 需要L1层管理并行调用和结果汇总 |
| 复杂度 | 高 — 并行+冲突解决逻辑 |

**吸引力**: 并行执行消除了延迟问题。但复杂度显著增加。

### 4.6 方案F: 源头强化——DomainRouter多级置信度

**不改L1/L2，强化L0 DomainRouter的输出信息**

思路：DomainRouter不仅返回目标域，还返回**置信度**和**备选域**。当置信度低时，L0可以主动做二次确认。

```java
// DomainRouter输出增强
public record DomainResult(
    String domain,
    double confidence,      // 新增
    String alternativeDomain, // 新增：置信度第二高的域
    boolean isDeterministic   // 新增：是否确定性路由（关键词命中）
) {}
```

```
用户: "朝朝盈"
DomainRouter: domain=WEALTH, confidence=0.4, alternativeDomain=TRANSFER, isDeterministic=false

→ 置信度0.4 < 阈值0.6 → L0不直接路由，而是问用户：
  "请问您是想了解理财产品，还是转账？"
```

| 维度 | 评价 |
|------|------|
| 覆盖范围 | Multi + Single + CHAT |
| 额外LLM成本 | 零（DomainRouter已有LLM调用） |
| 延迟 | 仅低置信度时增加一次交互 |
| 可靠性 | **高** — 在源头解决，不需要下游补检测 |
| 侵入性 | 中 — 改DomainRouter输出结构 + BankController加置信度判断 |
| 用户体验 | 低置信度时多一次确认，但比"走错流程"好 |

**核心优势**: 不需要在L1/L2补检测，从源头减少误分类。"C为根"思路。

---

## 5. 综合对比

| 方案 | 覆盖Single | 额外LLM | 额外延迟 | 可靠性 | 复杂度 | L2纯净性 |
|------|-----------|---------|---------|--------|--------|---------|
| A: prompt内嵌domain_match | ✅ | 零 | 零 | 低 | 低 | 低 |
| B: 独立LLM domain_match | ✅ | +1次/SWITCH_NEW | 200-500ms | 中高 | 中 | 中 |
| C: 提参后置质量检测 | ✅ | +1次/SWITCH_NEW | 200-500ms | 中 | 高 | 中 |
| D: L1规则校验 | ✅ | 零 | ~1ms | 低 | 低 | 高 |
| E: 并行域守卫 | ✅ | +1次/SWITCH_NEW(并行) | 零 | 中高 | 高 | 高 |
| F: 源头置信度强化 | ✅ | 零 | 仅低置信度 | 高 | 中 | 高 |
| v2: Multi handle()层 | ❌ | 零 | 零 | 高 | 低 | 高 |

---

## 6. 拓展思路

### 6.1 分层防御 (Defense in Depth)

不需要只选一个方案。可以**组合使用**：

```
Layer 1 (L0): DomainRouter置信度强化 — 拦截大部分误分类
Layer 2 (L1 Multi): IntentRouter跨域检测 — 拦截Multi域误分类
Layer 3 (L2 Single): 仅在SWITCH_NEW时做轻量domain_match — 兜底Single域误分类
```

每层都有自己的职责和覆盖范围，不互相依赖。任何一层拦截成功，用户就不会走错流程。

### 6.2 渐进式实施

```
Phase 1: v2设计（Multi handle()层检测）— 成本最低，收益最大
Phase 2: 方案F（DomainRouter置信度强化）— 源头治理，覆盖所有域
Phase 3: 方案B（L2独立domain_match）— 仅在Phase 2仍不足时实施
```

Phase 3是"核武器"——不到万不得已不使用，因为改变了L2的职责定义。

### 6.3 L2 domain_match的prompt设计思路（如果要做）

如果最终决定在L2做domain_match，prompt设计是成败关键。核心原则：**让LLM做判断者，不做提取者**。

```
你是手机银行领域守卫。判断用户输入是否与以下业务相关：

业务: 银行转账（给他人转钱）
业务描述: 用户想把钱转给他人，通常涉及收款人、金额等信息

用户输入: 解读一下朝朝盈

判断标准:
- 相关: 用户明确表达转账意图，或提供了转账所需的参数信息（收款人、金额等）
- 不相关: 用户的问题或需求与转账无关，如咨询理财、查询账单等
- 不确定: 用户输入模糊，可能相关也可能不相关

严格输出JSON:
{"domain_match": "yes"/"no"/"unsure"}
```

**设计要点**：
1. **独立角色**: "领域守卫"，不是"参数提取器"
2. **明确业务描述**: 告诉LLM"转账"到底是什么
3. **三态输出**: yes/no/unsure，"不确定"时放行而非拒绝
4. **拒绝偏好**: 宁可放行（unsure），不可误杀

### 6.4 参数可信度评分（替代domain_match的思路）

不问LLM"是否属于本域"，而是问"提取出的参数有多可信"：

```
你是一个参数质量评估器。评估以下提取结果的可信度。

用户输入: 解读一下朝朝盈
提取结果: {receiver: "朝朝盈"}

评估: "朝朝盈"作为收款人名字的可信度
- 常见中文姓名: 张三、李四、王五 → 高可信度
- 品牌名/产品名: 朝朝盈、余额宝 → 低可信度

输出JSON:
{"credibility": "high"/"medium"/"low", "reason": "..."}
```

**优点**: 比"领域判断"更具体，LLM更容易给出准确判断。
**缺点**: 只能检测参数名冲突（产品名被当人名），无法检测"用户压根不想转账"的case。

### 6.5 FOLLOW_UP中的意图漂移检测

目前讨论集中在SWITCH_NEW的误分类，但FOLLOW_UP也有类似问题：

```
Transfer: "请问您要转给谁？"
用户: "帮我解读一下朝朝盈"  ← 无取消前缀，纯意图切换
```

当前流程：
1. Single: activeThread存在 → 直接FOLLOW_UP → resumeGraph
2. L2 extractParams("帮我解读一下朝朝盈") → 可能提取出receiver="朝朝盈"
3. paramRouter → ASK_AMOUNT → INTERRUPTED
4. 用户越来越偏

**检测思路**: FOLLOW_UP时，如果用户的输入**完全没有回答我们的追问**，可能是意图漂移：

```
追问: "请问您要转给谁？"
用户输入: "帮我解读一下朝朝盈"

→ 这个输入回答了"谁"吗？显然没有。
→ 但用户也没说"取消"。
→ 可能是意图切换。

判断: 不确定 → 可以选择:
  a) 放行（当前行为，可能导致参数污染）
  b) 追问（"您是想了解朝朝盈，还是转账给叫朝朝盈的人？"）
  c) REROUTE（直接切换到WEALTH）
```

这是另一个开放问题：**FOLLOW_UP中的意图漂移如何处理？**

---

## 7. 开放问题清单

| # | 问题 | 复杂度 | 优先级 |
|---|------|--------|--------|
| 1 | L2 domain_match的LLM配合性偏见如何缓解？ | 高 | P1 |
| 2 | SWITCH_NEW vs FOLLOW_UP是否应区别对待？ | 中 | P1 |
| 3 | FOLLOW_UP中的意图漂移如何检测？ | 高 | P2 |
| 4 | domain_match的三态输出(yes/no/unsure)在"unsure"时如何决策？ | 中 | P1 |
| 5 | 参数可信度评分 vs 领域相关性判断，哪个更靠谱？ | 中 | P2 |
| 6 | DomainRouter置信度强化是否比L2 domain_match更经济？ | 低 | P1 |
| 7 | 并行域守卫(方案E)的工程复杂度是否值得？ | 高 | P3 |
| 8 | 分层防御(L0+L1+L2)各层的职责边界如何划定？ | 中 | P1 |

---

## 8. 当前结论（非最终结论）

1. **L2做domain_match在方向上有价值**——它是唯一能覆盖Single域REROUTE检测的位置
2. **但实现上有三个关键阻力**：LLM配合性偏见、L2职责膨胀、额外延迟/成本
3. **v2设计（Multi only）是最安全的起点**——零成本、高可靠、不侵入L2
4. **源头治理（方案F: DomainRouter置信度强化）可能是更优解**——覆盖所有域，不改变L2职责
5. **L2 domain_match（方案B）应作为兜底手段**——仅在源头治理不足时实施
6. **FOLLOW_UP中的意图漂移是一个独立的开放问题**，不应和SWITCH_NEW的误分类混在一起设计

**建议的实施路径**:
```
第一步: v2设计（Multi handle()层检测）— 已有设计文档
第二步: 观察线上Multi REROUTE的触发频率和效果
第三步: 如果Single域误分类仍是问题 → 实施方案F（源头强化）
第四步: 如果方案F仍不足 → 实施方案B（L2独立domain_match，仅SWITCH_NEW）
```

每一步都基于前一步的实际效果决定，不提前过度设计。
