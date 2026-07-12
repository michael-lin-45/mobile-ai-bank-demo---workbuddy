# 待解决问题列表 (Pending Issues) — mobile-ai-bank-demo

> 最后更新: 2026-07-10
> 维护人: 小爪
> 说明: 本列表汇总在「L1→span intent 写入」「R26 展示优化验证」「test3 人工多轮对话排查」中暴露的未解决/待决策问题。
> 已修复项见文末「已解决」。

---

## 🔴 P1 — SenseNova LLM 端点全部 404（最高优先级阻塞）
- **严重度**: 阻塞（Blocker）
- **现象**: 所有路由层(L0/L1-LLM1/L1-LLM2)与 L2 子图 LLM 调用均返回 `404 NOT_FOUND`：
  `org.springframework.ai.retry.NonTransientAiException: 404 - {"error": {"code": 5,"message": "NOT_FOUND","details": []}}`
  更早(16:04/16:06)还出现过 `Failed to resolve 'token.sensenova.cn'`（DNS 解析失败，后已恢复）。
- **根因**: `application.yml` 的 6 个模型全部指向 `https://token.sensenova.cn/v1` + `SenseNova 6.7 Flash-Lite` + 同一 key。直连探针确认该端点 DNS 可解析但返回 **HTTP 404**（body 空），即模型名/key 在该端点无效或已停服。
- **影响**:
  - 所有依赖 LLM 的路由判定走兜底（L0→CHAT、L1→SWITCH/UNKNOWN）。
  - #2 意图链无法显示真实 `FOLLOW/SWITCH/RESUME/wealth-filter`，只能显示兜底值。
  - 转账 L2 图中 slot 缺失时可模板兜底（如「请问您要转给谁？」），但任何需要 LLM 提参/生成的步骤失败。
- **下一步**: 需切到可用模型。候选：DashScope `qwen-plus`/`qwen-turbo`（需提供 base-url + api-key，项目当前无此 key）。或修复 SenseNova key/model。
- **备注**: 会话记录里的 `model: qwen-plus` 是 `SessionService.java:391` 的硬编码 placeholder，**不反映真实模型**，勿被误导。

## ✅ P2 — L0 DomainRouter LLM 失败兜底 last_domain（代码已修复，待重启验证）
- **严重度**: 高（设计缺陷）→ **已修复（代码编译通过，待用户重启 Core 验证）**
- **现象**: test3 Q2「张三」（在「转账」语境下本应继续转账）→ 因 L0 的 LLM 404 → 兜底成 `CHAT` 域 → ChatService 的 LLM 也 404 → 报「聊天服务暂时不可用: 404」。domain 从 TRANSFER 切到 CHAT（会话链 `TRANSFER → CHAT`）。
- **根因**: `DomainRouter.route()` 中：
  - 确定性路由只做关键词匹配（「张三」无任何领域关键词）→ 必走模型路由；
  - 模型路由 LLM 失败后 `catch` 直接 `return CHAT`（第 130-134 行），**没有回退到已存在的 `last_domain=TRANSFER`**；
  - `last_domain` 仅在「LLM 成功」路径的 prompt 里作为上下文，LLM 失败时完全不用。
- **对比**: L2 转账图对 LLM 失败有模板兜底（round1「请问您要转给谁？」即如此），而 L0 路由层无此兜底 → 多轮对话在路由层就断掉。
- **影响**: LLM 抖动/故障时，同一意图的多轮追问（如「张三」「500」「确认」）会被误判为闲聊，体验崩坏；这也是用户误以为「Core 不支持多轮上下文」的直接原因。
- **已修复（2026-07-10 R31）**: `DomainRouter.route()` 新增 `resolveFallbackDomain(sessionId, excludedDomains)`：
  - 优先沿用 `last_domain`（存在 + 非 CHAT/UNSUPPORTED + 不在 excludedDomains）→ 续期并返回；否则返回 `CHAT`。
  - 内层 catch（LLM 抛异常）与外层 catch 均调用之回填 span.intent 与返回值。
  - 编译 `./mvnw.cmd -o -q compile` EXIT=0。
- **注意**: 改完需用户 `start-all.ps1` 重启 Core（自动 `mvn package`）生效。该修复仅改善"路由不崩"；LLM 全挂时「张三」现在会留在 TRANSFER 走模板追问，但 L2 若仍需 LLM 提参仍会失败——不过配合 P3 的 L1/L2 降级，「张三」可端到端走通转账图槽位（见 P3）。

## ✅ P3 — ContextRouter/SubGraphRouter LLM 失败降级为「沿用当前活跃意图」（代码已修复，待重启验证）
- **严重度**: 中（已知行为）→ **已修复（代码编译通过，待重启验证）**
- **现象（原）**: L1 上下文路由 LLM 失败时 `catch` 兜底 `SWITCH`；SubGraphRouter 兜底 `UNKNOWN` → `SubGraphResolver` 第125行因 `intentName=UNKNOWN` 直接 `REJECTED`（"Intent completely unidentifiable"）。
- **关键发现（R31）**: P2 单独改 L0 仍不够——即便 L0→TRANSFER，`SingleSubAgentDomainService.handleWithLastQuestion` 调 `ContextRouter` 返回 SWITCH（非 FOLLOW）→ 走到 `SubGraphRouter`→`UNKNOWN`→`SubGraphResolver.rejected()`，「张三」仍断。故 L1/L2 必须一并降级。
- **已修复（2026-07-10 R31，与 P2 一并实施）**:
  - `ContextRouter.route()` 新增 `resolveFallbackRouteType(currentAgent, lastQuestion)`：当**存在活跃意图(currentAgent≠"无")且子智能体正在等待回答(lastQuestion 非空)**时，LLM 异常兜底 `FOLLOW`（继续当前 agent），否则 `SWITCH`。内层/外层 catch 均使用。
  - `SubGraphRouter.rewriteAndIdentify()`：catch 分支 `intentName` 从 `UNKNOWN` 改为**沿用 `currentAgent`**（非空时），避免 `SubGraphResolver` 因 UNKNOWN 返回 REJECTED 打断多轮；`refinedRouteType` 沿用 `phase1Result.getRouteType()`。
  - **仅影响 LLM 异常路径**，LLM 正常时走原解析返回，行为 100% 不变。
  - 编译 `./mvnw.cmd -o -q compile` EXIT=0。
- **效果**: 即便 LLM 全挂，test3 式场景也能端到端走通——L0→TRANSFER，L1（handleWithLastQuestion 见活跃转账 agent+待回答问题）→ ContextRouter 异常兜底 FOLLOW → `resumeActiveAgent` 继续转账图 → 槽位模板处理「张三」并追问「金额」。不再出现「聊天服务暂时不可用: 404」或 REJECTED。
- **下一步**: LLM 恢复后验证真实 `FOLLOW/SWITCH/RESUME` 是否准确落链（P6）。

## 🟡 P4 — SessionService 的 model 字段硬编码占位 "qwen-plus"
- **严重度**: 低（展示误导）
- **现象**: 会话/轮次详情 `model` 字段恒为 `qwen-plus`（`SessionService.java:391`），与真实模型无关。
- **根因**: 注释 `// placeholder — real model info from OTel trace attributes TBD`，尚未从 span 属性取真实模型。
- **下一步**: 从 trace span 的模型属性回填真实 model（如 `SenseNova 6.7 Flash-Lite` 或未来 qwen-plus），避免误导排查。

## 🟡 P5 — Core 诊断日志 [DIAG] 仍残留（临时）
- **严重度**: 低（交付前需清理）
- **现象**: `ObsChatModel.call()`、`AgentSpanContext` 内有多处 `log.info("[...][DIAG] ...")` 诊断行，定位 held-span 机制用，定位后应移除，避免污染生产日志。
- **下一步**: 确认 Bug B 机制稳定后删除所有 `[DIAG]` 日志。

## ⚪ P6 — R26 #2 意图链真实值验证仍挂起
- **严重度**: 中（验收阻塞）
- **状态**: #1(trace Agents链) ✅ 已验证 / #3(会话每轮L2名+前端折叠) ✅ 已验证 / #2(意图链单轮真实值) ⏸ 待 LLM 恢复或 P2 修复后验证。
- **说明**: #2 的机制(Bug B)已修且生效（链上出现 SWITCH/UNKNOWN），但真实路由词需 LLM 成功调用才能产生。

---

## 已解决（本批次）
- ✅ **Bug B**: `ObsChatModel.call()` catch 块在托管模式下不再提前 `span.end()`，改由 router `commitIntent(兜底)` 负责结束并回填。编译通过(`./mvnw.cmd -o compile` EXIT=0)。验证：test3 round1 链 `TRANSFER Domain → SWITCH → UNKNOWN → TransferGraph` 证明兜底值已入 span。
- ✅ **L1→span intent 托管机制**: 经 `core.log` 的 `[DIAG]` 证明 `setWithHeldSpan`/`attachSpan`/`commitIntent` 链路已通（之前担心的 held=false 顾虑排除，rc.spanCtx 强引用修复生效）。
- ✅ **seed 脚本随机 session_id**: `seed_all.py`/`seed.sh`/`seed_new.sh` 改为随机 RUN 前缀，根治会话叠加污染。
- ✅ **R26 #1/#3 验证通过**: seed 后 0 个旧格式 Agents 链；会话每轮显示 L2 名 + 前端折叠正常。
- ✅ **P2 / P3 代码修复（2026-07-10 R31）**: L0→last_domain、L1→FOLLOW(活跃+待答)、L1-LLM2→沿用 currentAgent 三处 LLM 异常兜底已完成并编译通过(`mvnw.cmd -o compile` EXIT=0)，**仅影响 LLM 异常路径**。使 LLM 全挂时 test3 式多轮（如「张三」）能端到端走通转账图槽位，不再 REJECTED/闲聊。待用户重启 Core 验证。
