# 回归测试 FAIL 记录 (2025-05-25)

总成绩: 223 PASS / 8 FAIL = 96.5%
**本次跨域上下文改造引入的新FAIL: 0**

---

## FAIL 清单

### 类型A: TRANSFER RESUME params丢失 (4个, 已知Bug)
场景: 转账中断后切换到其他领域, 再切回转账时, 之前累积的参数(receiver等)丢失,
导致L2直接用默认值完成转账, 状态从预期的INTERRUPTED变成COMPLETED。

| TestID | 类别 | 步骤 | 输入 | 期望 | 实际 | 原因 |
|--------|------|------|------|------|------|------|
| RS-05 | 意图恢复 | S4 | "500" | TRANSFER/INTERRUPTED | TRANSFER/COMPLETED | RESUME后accumulatedParams丢失, L2直接完成转账(缺少receiver) |
| RJ-05 | 跳转后回到 | S5 | "500" | TRANSFER/INTERRUPTED | TRANSFER/COMPLETED | 同上 |
| RJ-09 | 跳转后回到 | S5 | "1000" | TRANSFER/INTERRUPTED | TRANSFER/COMPLETED | 同上 |

注: TestRunner描述中已标注"TRANSFER RESUME params丢失Bug(已知)"

### 类型B: L2提参幻觉 - BILL_QUERY一步完成 (2个)

| TestID | 类别 | 步骤 | 输入 | 期望 | 实际 | 原因 |
|--------|------|------|------|------|------|------|
| DIR-06 | 直达 | S1 | "最近一周的消费记录" | BILL_QUERY/INTERRUPTED | BILL_QUERY/COMPLETED | L2幻觉: 一步完成未问收支类型,直接返回账单 |
| CT-06 | 多轮延续 | S1 | "上周消费记录" | BILL_QUERY/INTERRUPTED | BILL_QUERY/COMPLETED | 同上, L2跳过追问直接出结果 |

### 类型C: WEALTH_CONSULT 状态翻转 (2个)

| TestID | 类别 | 步骤 | 输入 | 期望 | 实际 | 原因 |
|--------|------|------|------|------|------|------|
| RJ-01 | 跳转后回到 | S5 | "稳健型" | WEALTH_CONSULT/COMPLETED | WEALTH_CONSULT/INTERRUPTED | L2幻觉: 已有风险偏好仍追问领域, 应直接出推荐 |
| RJ-06 | 跳转后回到 | S4 | "稳健型" | WEALTH_CONSULT/INTERRUPTED | WEALTH_CONSULT/COMPLETED | 反向翻转: L2跳过问领域直接出结果 |

---

## 根因分析

1. **类型A (TRANSFER RESUME)**: SAA框架的 `stream()` 每次从START重新执行graph, 恢复时accumulatedParams注入机制存在缺陷, 部分参数未正确恢复到graph state中
2. **类型B (BILL一步完成)**: L2 extractParams幻觉, 模型在未获取完整参数时自行编造默认值
3. **类型C (WEALTH状态翻转)**: L2 extractParams对已有参数判断不一致, 时而认为参数齐全时而认为缺失

以上均为**改造前已存在的Bug**, 与本次跨域上下文传递改造无关。
