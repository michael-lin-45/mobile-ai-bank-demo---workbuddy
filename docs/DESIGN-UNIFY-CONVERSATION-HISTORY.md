# 统一对话历史改造设计文档

> 分支: `global_context_memory` | 日期: 2026-06-02

---

## 目录

1. [改造背景与目标](#1-改造背景与目标)
2. [现状分析](#2-现状分析)
3. [核心设计决策](#3-核心设计决策)
4. [新增数据结构](#4-新增数据结构)
5. [组件变更清单](#5-组件变更清单)
6. [接口参数变更](#6-接口参数变更)
7. [配置项变更](#7-配置项变更)
8. [提示词优化](#8-提示词优化)
9. [删除清单](#9-删除清单)
10. [Corner Case 与风险](#10-corner-case-与风险)
11. [实施步骤](#11-实施步骤)
12. [验证方案](#12-验证方案)

---

## 1. 改造背景与目标

### 1.1 问题

当前系统在 4 个地方记录对话历史：

| 存储位置 | 写入者 | 内容 |
|---------|--------|------|
| 全局 ChatMemory | BankController | 所有域的 user/assistant 消息 |
| transferChatMemory | TransferService | 转账域 user/assistant 消息 |
| billChatMemory | BillService | 账单域 user/assistant 消息 |
| wealthChatMemory | WealthService | 理财域 user/assistant 消息 |

问题：
- **信息冗余**：L0 写全局 ChatMemory，L1 又写自己的域 ChatMemory，同一消息被记录两次
- **概念错位**：ChatMemory 是 Spring AI 的 LLM 对话记忆组件，语义上记录的是"与模型的交互"，而一进一出的 user/assistant 消息是业务层面的对话记录，两者不等价
- **维护成本**：每个 L1 都要维护自己的 ChatMemory bean、domainSessionId 隔离、AssistantWriter 等样板代码
- **clearSession 需遍历**：清会话时要遍历所有域 ChatMemory + 全局 ChatMemory + GlobalSessionStore

### 1.2 目标

- **单一数据源**：所有对话历史统一存入 `GlobalSessionContext` 的 `ConversationHistory`
- **去除 ChatMemory**：删除所有 Spring AI ChatMemory 相关 bean 和代码
- **代码精简**：去掉 `domainSessionId`、`AssistantWriter`、`StreamingChatMemoryWriter`、`ReadOnlyMemoryAdvisor` 等冗余组件
- **提示词优化**：统一 `{chat_history}` 占位符，去除 `{global_chat_history}` 双历史，用域标签降低噪声

---

## 2. 现状分析

### 2.1 当前 ChatMemory 使用全景

```
BankController
  ├── 写: StreamingChatMemoryWriter.writeUserMessage(chatMemory, sessionId, userInput)
  ├── 写: AssistantWriter.onChunk(terminalChunk) → chatMemory.add(sessionId, AssistantMessage)
  ├── 读: ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, globalContextMaxPairs) → globalChatHistory
  └── 清: chatMemory.clear(sessionId)

DomainRouter
  └── 读: ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs)

ContextRouter
  └── 读: ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs)
           (chatMemory 由 L1 传入 = 域级 ChatMemory)

SubGraphRouter
  └── 读: ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs)
           (chatMemory 由 L1 传入 = 域级 ChatMemory)

AbstractDomainService
  ├── 写: chatMemory.add(domainSessionId(sessionId), new UserMessage(userInput))
  ├── 写: AssistantWriter(chatMemory, domainSessionId(sessionId)).onChunk(chunk)
  └── 传: contextRouter.route(..., chatMemory) / subGraphRouter.rewriteAndIdentify(..., chatMemory)

ChatService
  ├── 读: ReadOnlyMemoryAdvisor(chatMemory) — advisor 自动注入历史
  └── 写: advisor 自动写回（ReadOnlyMemoryAdvisor 不写回）
```

### 2.2 当前 globalChatHistory 传递链

```
BankController.formatAndTruncate(chatMemory) → globalChatHistory 字符串
  → dispatchToDomain(domainResult, ..., globalChatHistory)
    → DomainHandler.handle(sessionId, userInput, globalChatHistory)
      → SingleSubAgent: 传给 SubGraphRouter.rewriteAndIdentify(..., globalChatHistory)
      → MultiSubAgent:   传给 SubGraphResolver.resolve(..., globalChatHistory)
      → ChatService:     拼到 user prompt 中
```

### 2.3 当前提示词占位符

| 模板 | chat_history 来源 | global_chat_history |
|------|-------------------|---------------------|
| `l0-domain.st` | 全局 ChatMemory | 无 |
| `l1-context.st` | 域级 ChatMemory | 无 |
| `l1-context-simple.st` | 域级 ChatMemory | 无 |
| `l1-intention.st` | 域级 ChatMemory | globalChatHistory 参数 |

---

## 3. 核心设计决策

### 3.1 不用 OverAllState.state.messages，用专用 ConversationHistory

**原因**：

1. **key 冲突**：`messages` 已被 L2 子 Graph 使用（`input.put("messages", rewrittenInput)`），AppendStrategy 的语义是 Graph 执行流的消息追加，不是业务对话历史
2. **语义不匹配**：AppendStrategy 追加的是扁平值，对话历史需要结构化记录（角色、域标签、时间戳）和截断/过滤能力
3. **并发安全**：OverAllState 不是线程安全的，而 CopyOnWriteArrayList 是

**决策**：在 `GlobalSessionContext` 上新增 `ConversationHistory` 字段，独立于 `OverAllState.state`。

### 3.2 对话记录格式：结构化对象

每条记录存储为 `ConversationRecord`：

```java
public record ConversationRecord(
    String role,       // "USER" | "ASSISTANT"
    String content,    // 消息文本
    String domain,     // "TRANSFER" | "BILL" | "WEALTH" | "CHAT" | null(未分类)
    Instant timestamp  // 写入时间
) {}
```

**为什么不存字符串**：结构化对象支持按域过滤、按对数截断、按角色格式化——字符串做不到这些。

### 3.3 统一 {chat_history}，去掉 {global_chat_history}

改造后只有一个历史源 `ConversationHistory`，通过不同的格式化方法输出不同视图：

| 消费者 | 格式化方法 | 输出示例 |
|--------|-----------|---------|
| L0 DomainRouter | `formatAll(maxPairs)` | `[转账] 用户: 我要转账\n[转账] 助手: 请问转给谁？\n[理财] 用户: 推荐理财` |
| L1 ContextRouter | `formatByDomain(domain, maxPairs, contextPairs)` | `[本域] 用户: 我要转账\n[本域] 助手: 转给谁？\n[他域] 用户: 查账单\n[他域] 助手: 哪个时段？` |
| L1 SubGraphRouter | `formatByDomain(domain, maxPairs, contextPairs)` | 同上（但 contextPairs 可不同） |
| ChatService | `formatAll(maxPairs)` | 同 L0 |

### 3.4 域标签降噪策略

统一历史可能引入跨域噪声。解决方案：

1. **域标签**：每条消息前加 `[转账]`/`[理财]`/`[账单]`/`[闲聊]` 标签，LLM 可据此识别跨域消息
2. **本域/他域分区**：L1 的 `{chat_history}` 中，本域消息在前（更相关），他域消息在后（仅供参考）
3. **提示词增强**：明确告知 LLM "当前域是XX，重点关注本域消息，他域消息仅用于消解跨域指代"

### 3.5 写入时机

| 消息类型 | 写入时机 | 写入者 |
|---------|---------|--------|
| UserMessage | BankController.buildChatPipeline() 入口 | BankController |
| AssistantMessage | 终结 chunk 到达时（累积流式 chunk 后一次性写入） | BankController |

L1 不再写入对话历史——所有写入集中在 BankController，L1 只读。

---

## 4. 新增数据结构

### 4.1 ConversationRecord

```java
package com.mobileagent.app.execution;

import java.time.Instant;

/**
 * 对话历史记录 - 业务层面的 user/assistant 消息对
 */
public record ConversationRecord(
    String role,       // "USER" | "ASSISTANT"
    String content,    // 消息文本
    String domain,     // 所属领域(TRANSFER/BILL/WEALTH/CHAT), null 表示尚未分类
    Instant timestamp
) {}
```

### 4.2 ConversationHistory

```java
package com.mobileagent.app.execution;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 对话历史 - 统一管理 session 级的 user/assistant 消息
 *
 * 线程安全: CopyOnWriteArrayList 保证并发读写的安全性
 * 单一数据源: 替代之前的全局 ChatMemory + 3 个域级 ChatMemory
 */
public class ConversationHistory {

    private final CopyOnWriteArrayList<ConversationRecord> records = new CopyOnWriteArrayList<>();

    // ==================== 写入 ====================

    /** 追加用户消息 */
    public void addUserMessage(String content, String domain) {
        records.add(new ConversationRecord("USER", content, domain, Instant.now()));
    }

    /** 追加助手消息 */
    public void addAssistantMessage(String content, String domain) {
        records.add(new ConversationRecord("ASSISTANT", content, domain, Instant.now()));
    }

    // ==================== 读取 ====================

    /** 获取全部记录 */
    public List<ConversationRecord> getAll() {
        return List.copyOf(records);
    }

    /** 获取最近 N 对记录 (1 对 = 1 条 USER + 1 条 ASSISTANT) */
    public List<ConversationRecord> getRecentPairs(int maxPairs) {
        return truncateToPairs(records, maxPairs);
    }

    /** 获取指定域的记录 */
    public List<ConversationRecord> getByDomain(String domain) {
        return records.stream()
                .filter(r -> domain.equals(r.domain()))
                .collect(Collectors.toList());
    }

    /** 获取指定域的最近 N 对记录 */
    public List<ConversationRecord> getByDomainRecentPairs(String domain, int maxPairs) {
        List<ConversationRecord> domainRecords = getByDomain(domain);
        return truncateToPairs(domainRecords, maxPairs);
    }

    // ==================== 格式化 (供 Prompt 使用) ====================

    /**
     * 格式化全部历史 - 供 L0 DomainRouter 和 ChatService 使用
     *
     * 输出示例:
     * [转账] 用户: 我要转账
     * [转账] 助手: 请问转给谁？
     * [理财] 用户: 推荐理财
     * [理财] 助手: 请问风险偏好？
     */
    public String formatAll(int maxPairs) {
        List<ConversationRecord> recent = getRecentPairs(maxPairs);
        return formatRecords(recent, true);
    }

    /**
     * 格式化指定域历史 + 上下文 - 供 L1 ContextRouter/SubGraphRouter 使用
     *
     * 本域消息在前(更相关)，他域消息在后(仅作参考)
     * contextPairs: 额外包含的他域最近 N 对消息(用于跨域指代消解)
     *
     * 输出示例 (domain=TRANSFER, contextPairs=3):
     * [本域] 用户: 我要转账
     * [本域] 助手: 转给谁？
     * ---其他领域参考---
     * [理财] 用户: 推荐理财
     * [理财] 助手: 稳健型推荐...
     * [账单] 用户: 查账单
     */
    public String formatByDomain(String domain, int domainPairs, int contextPairs) {
        // 本域消息
        List<ConversationRecord> domainRecords = getByDomainRecentPairs(domain, domainPairs);

        // 他域消息 (最近 contextPairs 对，排除本域)
        List<ConversationRecord> otherRecords = records.stream()
                .filter(r -> !domain.equals(r.domain()))
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> truncateToPairs(list, contextPairs)));

        StringBuilder sb = new StringBuilder();
        if (!domainRecords.isEmpty()) {
            sb.append(formatRecords(domainRecords, false)); // 本域不加域标签
        }
        if (!otherRecords.isEmpty()) {
            if (!domainRecords.isEmpty()) sb.append("\n");
            sb.append("---其他领域参考---\n");
            sb.append(formatRecords(otherRecords, true)); // 他域加域标签
        }
        return sb.length() > 0 ? sb.toString() : "(无历史对话)";
    }

    // ==================== 工具方法 ====================

    /** 清空历史 */
    public void clear() {
        records.clear();
    }

    /** 记录总数 */
    public int size() {
        return records.size();
    }

    // ==================== 内部方法 ====================

    /**
     * 将记录列表截断到最近 maxPairs 对
     * 从列表末尾向前取，确保取到完整对
     */
    private static List<ConversationRecord> truncateToPairs(List<ConversationRecord> list, int maxPairs) {
        if (list == null || list.isEmpty() || maxPairs <= 0) return List.of();
        int maxMessages = maxPairs * 2;
        int start = Math.max(0, list.size() - maxMessages);
        return list.subList(start, list.size());
    }

    /**
     * 格式化记录列表为文本
     * @param withDomainTag 是否添加域标签
     */
    private String formatRecords(List<ConversationRecord> records, boolean withDomainTag) {
        StringBuilder sb = new StringBuilder();
        for (ConversationRecord r : records) {
            String role = "USER".equals(r.role()) ? "用户" : "助手";
            if (withDomainTag && r.domain() != null) {
                String domainLabel = domainDisplayName(r.domain());
                sb.append("[").append(domainLabel).append("] ");
            }
            sb.append(role).append(": ").append(r.content()).append("\n");
        }
        return sb.toString().trim();
    }

    /** 领域名中文映射 */
    private String domainDisplayName(String domain) {
        return switch (domain) {
            case "TRANSFER" -> "转账";
            case "BILL" -> "账单";
            case "WEALTH" -> "理财";
            case "CHAT" -> "闲聊";
            default -> domain;
        };
    }
}
```

### 4.3 GlobalSessionContext 变更

```java
// 新增字段
private final ConversationHistory conversationHistory;

// 新增方法
public ConversationHistory getConversationHistory() {
    return conversationHistory;
}

// clear() 增加清理
public void clear() {
    state.clear();
    conversationHistory.clear();
}
```

---

## 5. 组件变更清单

### 5.1 BankController

**当前**：
- 写 UserMessage 到全局 ChatMemory
- 用 AssistantWriter 写 AssistantMessage 到全局 ChatMemory
- 格式化 globalChatHistory 传给 L1

**改造后**：
- 写 UserMessage 到 `globalSessionStore.getOrCreate(sessionId).getConversationHistory().addUserMessage(content, domain)`
- 用新的 AssistantAccumulator 写 AssistantMessage（不依赖 ChatMemory）
- 不再格式化 globalChatHistory 参数（L1 自己从 ConversationHistory 读）
- **DomainHandler.handle() 去掉 globalChatHistory 参数**

```java
// 改造前
Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory);

// 改造后
Flux<StreamChunk> handle(String sessionId, String userInput);
```

**AssistantAccumulator 替代 StreamingChatMemoryWriter.AssistantWriter**：

```java
/**
 * 流式助手消息累积器
 *
 * 累积 CHUNK → 终结 chunk 时写入 ConversationHistory
 * 替代之前的 StreamingChatMemoryWriter.AssistantWriter
 */
public static class AssistantAccumulator {
    private final ConversationHistory history;
    private final String domain;
    private final StringBuilder accumulator = new StringBuilder();

    public AssistantAccumulator(ConversationHistory history, String domain) {
        this.history = history;
        this.domain = domain;
    }

    public void onChunk(StreamChunk chunk) {
        if (chunk.getType() == ChunkType.CHUNK) {
            accumulator.append(chunk.getContent());
        }
        if (chunk.isTerminal() && chunk.getType() != ChunkType.REROUTE) {
            String fullReply = accumulator.length() > 0
                    ? accumulator.toString()
                    : chunk.getReplyContent();
            if (fullReply != null && !fullReply.isEmpty()) {
                history.addAssistantMessage(fullReply, domain);
            }
        }
    }
}
```

**写入 domain 时机**：BankController 在 `dispatchWithReroute()` 中已获得 `domainResult.domain()`，用此作为 domain 参数。

### 5.2 DomainRouter

**当前**：
- 注入 ChatMemory，用 `ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs)` 读取

**改造后**：
- 注入 `GlobalSessionStore`
- 用 `globalSessionStore.getOrCreate(sessionId).getConversationHistory().formatAll(judgmentMaxPairs)` 读取
- 去掉 `ChatMemory` 依赖

### 5.3 AbstractDomainService

**当前**：
- `protected final ChatMemory chatMemory`
- `addUserMessage(sessionId, userInput)` → 写域 ChatMemory
- `domainSessionId(sessionId)` → sessionId + "@" + logTag 隔离
- `resumeActiveAgent()` / `executeNewAgent()` 中创建 `AssistantWriter(chatMemory, domainSessionId(sessionId))`

**改造后**：
- **删除** `chatMemory` 字段
- **删除** `domainSessionId()` 方法
- **删除** `addUserMessage()` 方法
- **删除** 所有 `AssistantWriter` 创建
- **删除** `handle()` 中的 `addUserMessage(sessionId, userInput)` 调用（BankController 统一写入）
- **新增** `protected final GlobalSessionStore globalSessionStore`（已有，保留）
- `handle()` 签名去掉 `globalChatHistory` 参数

### 5.4 SingleSubAgentDomainService

**当前**：
- `handle(sessionId, userInput, globalChatHistory)` 传给 ContextRouter 和 SubGraphRouter
- ContextRouter.route() 传入 chatMemory
- SubGraphRouter.rewriteAndIdentify() 传入 chatMemory + globalChatHistory

**改造后**：
- `handle(sessionId, userInput)` — 去掉 globalChatHistory
- ContextRouter.route() 传入格式化后的历史字符串（从 ConversationHistory 读取）
- SubGraphRouter.rewriteAndIdentify() 传入格式化后的历史字符串（不再需要 chatMemory 和 globalChatHistory 两个参数）
- Builder 去掉 `chatMemory` 字段

### 5.5 MultiSubAgentDomainService

**当前**：
- 同 SingleSubAgent，额外有 SubGraphResolver 调用
- `handleResume()` 中创建 `AssistantWriter(chatMemory, domainSessionId(sessionId))`

**改造后**：
- 同 SingleSubAgent 的变更
- SubGraphResolver.resolve() 不再传入 chatMemory 和 globalChatHistory
- `handleResume()` 不再创建 AssistantWriter（BankController 统一写）
- Builder 去掉 `chatMemory` 字段

### 5.6 ChatService

**当前**：
- 用 `ReadOnlyMemoryAdvisor(chatMemory)` 注入历史
- 用 `ChatMemory.CONVERSATION_ID` advisor 参数
- 手动拼接 globalChatHistory 到 user prompt

**改造后**：
- 注入 `GlobalSessionStore`
- 用 `conversationHistory.formatAll(maxPairs)` 格式化历史，拼入 system prompt 或 user prompt
- 去掉 `ReadOnlyMemoryAdvisor`
- 去掉 advisor 参数
- `chatChatClient` 不再需要 ChatMemory 依赖

### 5.7 ContextRouter

**当前**：
- `route(sessionId, userInput, currentAgent, pendingAgents, templatePath, domainName, chatMemory, lastQuestion)`
- 内部 `formatChatHistory(chatMemory, sessionId)` → `ChatHistoryUtils.formatAndTruncate()`

**改造后**：
- `route(sessionId, userInput, currentAgent, pendingAgents, templatePath, domainName, chatHistory, lastQuestion)`
- 参数变更：`ChatMemory chatMemory` → `String chatHistory`（已格式化的历史字符串）
- 删除内部 `formatChatHistory()` 方法
- 删除 `judgmentMaxPairs` 字段（格式化由调用方负责）
- 删除 `ChatHistoryUtils` 依赖

### 5.8 SubGraphRouter

**当前**：
- `rewriteAndIdentify(sessionId, userInput, phase1Result, currentAgent, pendingAgents, sessionState, disambigContext, templatePath, chatMemory, globalChatHistory, domainIntentScopeList)`
- 内部 `formatChatHistory(chatMemory, sessionId)` → `ChatHistoryUtils.formatAndTruncate()`

**改造后**：
- `rewriteAndIdentify(sessionId, userInput, phase1Result, currentAgent, pendingAgents, sessionState, disambigContext, templatePath, chatHistory, domainIntentScopeList)`
- 参数变更：`ChatMemory chatMemory` + `String globalChatHistory` → `String chatHistory`（已格式化的统一历史）
- 删除内部 `formatChatHistory()` 方法
- 删除 `judgmentMaxPairs` 字段
- 模板替换 `{global_chat_history}` 逻辑删除，只替换 `{chat_history}`

### 5.9 SubGraphResolver

**当前**：
- `resolve(sessionId, userInput, phase1Result, chatMemory, inDisambiguation, disambiguationGroupId, hasSuspendedAgents, suspendedAgents, intentRoutingTemplatePath, globalChatHistory, domainIntentScopeList)`
- 透传 chatMemory 和 globalChatHistory 给 SubGraphRouter

**改造后**：
- `resolve(sessionId, userInput, phase1Result, chatHistory, inDisambiguation, disambiguationGroupId, hasSuspendedAgents, suspendedAgents, intentRoutingTemplatePath, domainIntentScopeList)`
- 参数变更：`ChatMemory chatMemory` + `String globalChatHistory` → `String chatHistory`
- 透传 chatHistory 给 SubGraphRouter

### 5.10 DomainServiceConfig

**改造后**：
- 去掉所有 `@Qualifier("xxxChatMemory") ChatMemory` 参数
- Builder 去掉 `.chatMemory(...)` 调用

### 5.11 ModelConfig

**改造后**：
- 删除 `chatMemory()` bean
- 删除 `wealthChatMemory()` bean
- 删除 `transferChatMemory()` bean
- 删除 `billChatMemory()` bean
- `chatChatClient` 去掉 `ReadOnlyMemoryAdvisor` 和 `ChatMemory` 参数
- 删除 `ChatMemoryRepository` 注入

### 5.12 GraphExecutionEngine

**不变**。GES 不依赖 ChatMemory。

### 5.13 GlobalSessionStore / GlobalSessionContext

**改造后**：
- `GlobalSessionContext` 新增 `ConversationHistory conversationHistory` 字段
- `GlobalSessionContext.clear()` 增加 `conversationHistory.clear()`
- `GlobalSessionStore.createNewContext()` 中创建 `ConversationHistory` 实例

---

## 6. 接口参数变更

### 6.1 DomainHandler.handle()

```java
// 改造前
Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory);

// 改造后
Flux<StreamChunk> handle(String sessionId, String userInput);
```

**原因**：L1 从 GlobalSessionStore 自己读取对话历史，不需要 BankController 传入字符串。

### 6.2 ContextRouter.route()

```java
// 改造前
RoutingResult route(String sessionId, String userInput,
                    String currentAgent, String pendingAgents,
                    String templatePath, String domainName,
                    ChatMemory chatMemory, String lastQuestion);

// 改造后
RoutingResult route(String sessionId, String userInput,
                    String currentAgent, String pendingAgents,
                    String templatePath, String domainName,
                    String chatHistory, String lastQuestion);
```

### 6.3 SubGraphRouter.rewriteAndIdentify()

```java
// 改造前
RoutingResult rewriteAndIdentify(String sessionId, String userInput,
                                  RoutingResult phase1Result,
                                  String currentAgent, String pendingAgents,
                                  String sessionState, String disambigContext,
                                  String templatePath,
                                  ChatMemory chatMemory,
                                  String globalChatHistory,
                                  String domainIntentScopeList);

// 改造后
RoutingResult rewriteAndIdentify(String sessionId, String userInput,
                                  RoutingResult phase1Result,
                                  String currentAgent, String pendingAgents,
                                  String sessionState, String disambigContext,
                                  String templatePath,
                                  String chatHistory,
                                  String domainIntentScopeList);
```

### 6.4 SubGraphResolver.resolve()

```java
// 改造前
RoutingResolution resolve(String sessionId, String userInput, RoutingResult phase1Result,
                           ChatMemory chatMemory,
                           boolean inDisambiguation, String disambiguationGroupId,
                           boolean hasSuspendedAgents, Map<String, ?> suspendedAgents,
                           String intentRoutingTemplatePath,
                           String globalChatHistory,
                           String domainIntentScopeList);

// 改造后
RoutingResolution resolve(String sessionId, String userInput, RoutingResult phase1Result,
                           String chatHistory,
                           boolean inDisambiguation, String disambiguationGroupId,
                           boolean hasSuspendedAgents, Map<String, ?> suspendedAgents,
                           String intentRoutingTemplatePath,
                           String domainIntentScopeList);
```

### 6.5 L1 内部调用方式

**改造前**（SingleSubAgentDomainService 示例）：
```java
// Phase1: ContextRouter
RoutingResult phase1 = contextRouter.route(domainSessionId(sessionId), userInput,
        currentAgent, pendingAgents, contextRoutingTemplatePath, domainName, chatMemory, lastQuestion);

// Phase2: SubGraphRouter
RoutingResult phase2 = subGraphRouter.rewriteAndIdentify(domainSessionId(sessionId), userInput, phase1,
        currentAgent, pendingAgents, sessionState, disambigContext,
        intentRoutingTemplatePath, chatMemory, globalChatHistory, domainIntentScopeList);
```

**改造后**：
```java
// 从 ConversationHistory 获取格式化历史
ConversationHistory history = globalSessionStore.getOrCreate(sessionId).getConversationHistory();
String chatHistory = history.formatByDomain(domainName, domainPairs, contextPairs);

// Phase1: ContextRouter
RoutingResult phase1 = contextRouter.route(sessionId, userInput,
        currentAgent, pendingAgents, contextRoutingTemplatePath, domainName, chatHistory, lastQuestion);

// Phase2: SubGraphRouter (复用同一个 chatHistory 字符串)
RoutingResult phase2 = subGraphRouter.rewriteAndIdentify(sessionId, userInput, phase1,
        currentAgent, pendingAgents, sessionState, disambigContext,
        intentRoutingTemplatePath, chatHistory, domainIntentScopeList);
```

**注意**：sessionId 不再需要 `domainSessionId()` 前缀，因为不再有 ChatMemory 的隔离需求。ContextRouter 和 SubGraphRouter 内部也不再需要 sessionId 读取 ChatMemory（历史字符串由调用方传入）。

---

## 7. 配置项变更

### 7.1 删除配置

```yaml
# 删除 - 不再需要 ChatMemory 存储上限
routing:
  history:
    max-pairs: 10     # 删除 (原 ChatMemory bean 的 maxMessages)
```

### 7.2 修改配置

```yaml
routing:
  history:
    # L0 域路由使用的历史对数 (从 ConversationHistory.formatAll 读取)
    l0-max-pairs: 10
    # L1 域内历史对数 (从 ConversationHistory.formatByDomain 的 domainPairs 参数)
    l1-domain-pairs: 6
    # L1 跨域上下文对数 (从 ConversationHistory.formatByDomain 的 contextPairs 参数)
    l1-context-pairs: 3
    # ChatService 使用的历史对数
    chat-max-pairs: 10
```

**配置语义变化**：

| 旧配置 | 新配置 | 说明 |
|--------|--------|------|
| `routing.history.max-pairs: 10` | 删除 | ChatMemory 存储上限，不再需要 |
| `routing.history.judgment-max-pairs: 6` | `routing.history.l1-domain-pairs: 6` | L1 域内历史对数 |
| `routing.history.global-context-max-pairs: 10` | `routing.history.l0-max-pairs: 10` | L0 全量历史对数 |

**新增配置**：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `routing.history.l1-context-pairs` | 3 | L1 跨域上下文对数（原来没有独立配置，和 global-context-max-pairs 合用） |
| `routing.history.chat-max-pairs` | 10 | ChatService 使用的历史对数 |

### 7.3 注入方式变更

| 组件 | 旧注入 | 新注入 |
|------|--------|--------|
| DomainRouter | `@Value("${routing.history.global-context-max-pairs:10}")` | `@Value("${routing.history.l0-max-pairs:10}")` |
| ContextRouter | `@Value("${routing.history.judgment-max-pairs:5}")` | 删除（不再自行格式化） |
| SubGraphRouter | `@Value("${routing.history.judgment-max-pairs:5}")` | 删除（不再自行格式化） |
| AbstractDomainService | 无 | `@Value("${routing.history.l1-domain-pairs:6}")` + `@Value("${routing.history.l1-context-pairs:3}")` |
| ChatService | 无 | `@Value("${routing.history.chat-max-pairs:10}")` |
| BankController | `@Value("${routing.history.global-context-max-pairs:10}")` | 删除（不再格式化 globalChatHistory） |

---

## 8. 提示词优化

### 8.1 统一 {chat_history} 占位符

所有模板统一使用 `{chat_history}`，删除 `{global_chat_history}`。

### 8.2 l0-domain.st

**变更**：
- `{chat_history}` 内容现在带域标签（`[转账] 用户: ...`）
- 提示词增加对域标签的说明

**关键修改**：
```
===对话历史(每条消息带领域标签)===
{chat_history}
===对话历史结束===

注意: 对话历史中每条消息前的[转账]/[理财]/[账单]/[闲聊]标签表示该消息所属的领域，
帮助你在跨域对话中准确判断当前消息的领域归属。
```

其余逻辑不变（最高优先级原则、话术类型分析、判断规则等均保留）。

### 8.3 l1-context.st (Multi域用)

**变更**：
- `{chat_history}` 内容分本域和他域两个区段
- 删除对 `{global_chat_history}` 的引用（此模板本身没有）

**关键修改**：
```
===对话历史===
{chat_history}
===对话历史结束===

注意: 对话历史分为"本域"和"其他领域参考"两部分。
- 本域消息: 当前领域内用户的对话，直接用于判断 FOLLOW/SWITCH/RESUME
- 其他领域参考: 仅用于理解跨域指代(如"刚才说的那个理财")，不作为路由判断依据
```

其余路由判断逻辑不变。

### 8.4 l1-context-simple.st (Single域用)

**变更**：同 l1-context.st，增加对域标签分区的说明。

**关键修改**：
```
===对话历史===
{chat_history}
===对话历史结束===

注意: 对话历史分为"本域"和"其他领域参考"两部分。
- 本域消息: 当前领域内的对话，用于判断 FOLLOW/SWITCH
- 其他领域参考: 仅用于理解跨域指代，不影响路由判断
```

### 8.5 l1-intention.st

**变更**：
- 合并 `{chat_history}` 和 `{global_chat_history}` 为统一的 `{chat_history}`
- 提示词说明分区语义

**关键修改**：
```
===对话历史===
{chat_history}
===对话历史结束===

注意: 对话历史分为"本域"和"其他领域参考"两部分。
- 本域消息: 用于理解当前领域上下文，识别意图和改写输入
- 其他领域参考: 仅用于消解跨域指代(如"刚才转的500"→从转账历史中找到金额)
- 改写时，跨域指代必须从他域参考中消解，不要遗漏

★★★ 改写边界(必须遵守) ★★★
- 只补全用户明确说过的内容(指代消解+参数继承)，不添加用户没说的信息
- 禁止为用户未指定的参数填默认值
- 未指定的参数由子智能体追问，改写器不应替用户做决定
```

删除原来的 `{global_chat_history}` 区段及其说明。

### 8.6 DomainRouter 默认 prompt (getDefaultDomainPrompt)

**变更**：在 `===对话历史===` 后增加域标签说明。其余不变。

### 8.7 ContextRouter 默认 prompt (getDefaultRoutingPrompt)

**变更**：在 `===对话历史===` 后增加分区说明。其余不变。

### 8.8 SubGraphRouter 默认 prompt (getDefaultRewritePrompt)

**变更**：合并两个历史区段为一个 `{chat_history}`，增加分区说明。其余不变。

---

## 9. 删除清单

### 9.1 删除的 Java 类

| 类 | 原因 |
|----|------|
| `StreamingChatMemoryWriter` | 被 `AssistantAccumulator`(内嵌在 BankController) 替代 |
| `ReadOnlyMemoryAdvisor` | ChatService 不再用 advisor 注入历史 |

### 9.2 删除的 Bean

| Bean | 位置 | 原因 |
|------|------|------|
| `chatMemory` | ModelConfig | 全局 ChatMemory 不再需要 |
| `transferChatMemory` | ModelConfig | 域 ChatMemory 不再需要 |
| `billChatMemory` | ModelConfig | 域 ChatMemory 不再需要 |
| `wealthChatMemory` | ModelConfig | 域 ChatMemory 不再需要 |
| `inMemoryChatMemoryRepository` | ChatMemoryConfig | ChatMemory 仓储不再需要 |
| `redisChatMemoryRepository` | ChatMemoryConfig | ChatMemory 仓储不再需要 |

### 9.3 删除的类/文件

| 文件 | 原因 |
|------|------|
| `ChatMemoryConfig.java` | 整个 ChatMemory 配置类不再需要 |
| `RedisChatMemoryRepository.java` | ChatMemory 仓储不再需要 |
| `ReadOnlyMemoryAdvisor.java` | 不再用 advisor 注入历史 |

### 9.4 删除的字段/方法

| 组件 | 删除项 | 原因 |
|------|--------|------|
| AbstractDomainService | `chatMemory` 字段 | 不再需要 |
| AbstractDomainService | `domainSessionId()` 方法 | ChatMemory 隔离不再需要 |
| AbstractDomainService | `addUserMessage()` 方法 | BankController 统一写入 |
| SingleSubAgentDomainService.Builder | `chatMemory` 字段 | 不再注入 |
| MultiSubAgentDomainService.Builder | `chatMemory` 字段 | 不再注入 |
| ContextRouter | `judgmentMaxPairs` 字段 | 不再自行格式化 |
| ContextRouter | `formatChatHistory()` 方法 | 历史由调用方传入 |
| SubGraphRouter | `judgmentMaxPairs` 字段 | 不再自行格式化 |
| SubGraphRouter | `formatChatHistory()` 方法 | 历史由调用方传入 |
| DomainServiceConfig | 所有 `@Qualifier("xxxChatMemory")` 参数 | 不再注入 |

### 9.5 删除的依赖

| 依赖 | 组件 | 说明 |
|------|------|------|
| `ChatMemory` | BankController, DomainRouter, AbstractDomainService, ContextRouter, SubGraphRouter, SubGraphResolver, ChatService | 全部移除 |
| `ChatMemoryRepository` | ModelConfig, ChatMemoryConfig | 全部移除 |
| `UserMessage` / `AssistantMessage` | BankController, AbstractDomainService | Spring AI message 类型不再用于历史记录 |

### 9.6 删除的 import

从各文件中清理所有 `org.springframework.ai.chat.memory.*` 相关 import。

### 9.7 删除的配置

```yaml
# application.yml 中删除
routing:
  history:
    max-pairs: 10     # 删除
```

### 9.8 删除的注释

所有包含 "ChatMemory" 概念的注释（如 "ChatMemory写入", "领域ChatMemory", "ChatMemory隔离" 等）需要清理或替换为 "ConversationHistory" 相关描述。

---

## 10. Corner Case 与风险

### 10.1 流式 AssistantMessage 写入时序

**问题**：流式 Graph 返回多个 CHUNK + 1 个终结 chunk。AssistantMessage 只能在终结 chunk 时写入。

**解决方案**：`AssistantAccumulator` 累积 CHUNK 文本，终结 chunk 时一次性 `addAssistantMessage()`。与原 `StreamingChatMemoryWriter.AssistantWriter` 逻辑完全一致。

### 10.2 REROUTE 时的 domain 写入

**问题**：REROUTE 时用户消息可能被路由到多个域，最终 domain 在 REROUTE 循环中确定。

**解决方案**：UserMessage 在 BankController 入口处写入时 domain 暂时记为 null 或首次路由的 domain。如果 REROUTE 后 domain 变化，需要更新最近一条 UserMessage 的 domain。

**实现**：
```java
// BankController.buildChatPipeline()
// 写入 UserMessage 时先用 null domain
conversationHistory.addUserMessage(userInput, null);

// 在 dispatchWithReroute() 确定最终 domain 后，更新最近一条 UserMessage 的 domain
conversationHistory.updateLastUserDomain(finalDomain);
```

在 `ConversationHistory` 中新增方法：
```java
/** 更新最近一条 UserMessage 的 domain (REROUTE 后使用) */
public void updateLastUserDomain(String domain) {
    // 从后向前找最近的 USER 记录，更新 domain
    for (int i = records.size() - 1; i >= 0; i--) {
        if ("USER".equals(records.get(i).role())) {
            ConversationRecord old = records.get(i);
            records.set(i, new ConversationRecord(old.role(), old.content(), domain, old.timestamp()));
            return;
        }
    }
}
```

**注意**：`CopyOnWriteArrayList.set()` 是线程安全的。

### 10.3 AssistantMessage 的 domain

**问题**：Assistant 的回复属于哪个域？跟随最后一条 UserMessage 的域？

**解决方案**：是的，AssistantMessage 的 domain 与触发该回复的 UserMessage 的 domain 一致。BankController 在确定最终 domain 后，用该 domain 写入 AssistantMessage。

### 10.4 ChatService 不写 ConversationHistory？

**问题**：ChatService 的回复也应该是 ConversationHistory 的一部分，否则用户后续说"你刚才说什么"时找不到。

**解决方案**：ChatService 的回复也写 ConversationHistory。BankController 统一处理——ChatService 返回的 StreamChunk 中 COMPLETE chunk 的 content 就是 ChatService 的回复，AssistantAccumulator 会自动写入。

### 10.5 消歧中的消息记录

**问题**：MultiSubAgentDomainService 进入消歧模式时，会返回 DISAMBIGUATION chunk。这个"消歧追问"算不算 AssistantMessage？

**解决方案**：算。消歧追问是系统对用户的回复，应该记录到 ConversationHistory。AssistantAccumulator 会处理 DISAMBIGUATION 类型的终结 chunk（`chunk.getReplyContent()` 返回 question 文本）。

### 10.6 L1 读取历史的 domain 过滤

**问题**：SingleSubAgentDomainService（如 TransferService）的 domain 如何确定？

**解决方案**：SingleSubAgent 有固定的 `intent` 字段（如 "TRANSFER"），对应 L0 的 domain 名 "TRANSFER"。`formatByDomain` 用 domain="TRANSFER" 过滤。

但注意：L0 domain 和 L2 intent 的映射关系——

| L0 Domain | L1 Service | L2 Intent |
|-----------|-----------|-----------|
| TRANSFER | TransferService (Single) | TRANSFER |
| BILL | BillService (Single) | BILL_QUERY |
| WEALTH | WealthService (Multi) | WEALTH_CONSULT / WEALTH_INTERPRET |

问题：BillService 的 intent 是 "BILL_QUERY"，但 L0 domain 是 "BILL"。ConversationHistory 中存的 domain 是 L0 domain（"BILL"），而 BillService 需要用 "BILL" 过滤。

**解决方案**：在 `AbstractDomainService` 中新增一个 `domainKey` 字段，与 L0 domain 对应（而非 L2 intent）。SingleSubAgent 构造时自动将 intent 映射为 domainKey：

```java
// SingleSubAgentDomainService
// domainKey = L0 domain name (e.g., "TRANSFER", "BILL")
// intent = L2 intent name (e.g., "TRANSFER", "BILL_QUERY")
```

DomainServiceConfig 中配置时需要同时提供 domainKey 和 intent：
```java
SingleSubAgentDomainService.builder()
    .domainKey("BILL")      // L0 domain name, 用于 ConversationHistory 过滤
    .intent("BILL_QUERY")   // L2 intent name, 用于 Graph 执行
    ...
```

### 10.7 中途域切换的对话历史连续性

**场景**：用户在转账域 → 切到理财域 → 又回到转账域。转账域的 FOLLOW_UP 判断需要看到之前转账域的对话。

**解决方案**：ConversationHistory 存储全部历史，`formatByDomain("TRANSFER", ...)` 会过滤出所有转账域的消息（包括切走之前的），不会丢失。

### 10.8 并发安全

**问题**：同一 session 的两个请求可能并发写入 ConversationHistory。

**分析**：移动银行场景下，单个用户的请求是串行的（一次一问一答）。但极端情况下可能有并发。

**解决方案**：`CopyOnWriteArrayList` 保证线程安全的读和写。`updateLastUserDomain()` 使用 `set()` 也是线程安全的。

### 10.9 ConversationHistory 大小限制

**问题**：长时间对话会导致 ConversationHistory 无限增长。

**解决方案**：
- `formatAll()` 和 `formatByDomain()` 只取最近 N 对，不限制存储大小
- 但存储也需要上限，防止内存泄漏。在 `addUserMessage()` / `addAssistantMessage()` 中检查，超过阈值时淘汰最老的记录
- 阈值可配置：`routing.history.max-stored-pairs: 50`（默认 50 对）

```java
// ConversationHistory 中
private static final int DEFAULT_MAX_STORED_PAIRS = 50;

public void addUserMessage(String content, String domain) {
    records.add(new ConversationRecord("USER", content, domain, Instant.now()));
    evictIfNeeded();
}

private void evictIfNeeded() {
    // 超过 maxStoredPairs * 2 条记录时，删除最老的
    while (records.size() > maxStoredPairs * 2) {
        records.remove(0);
    }
}
```

### 10.10 BankController.clearSession()

**改造前**：
```java
// 遍历所有 L1 clearSession
for (String domain : domainServiceRegistry.getDomainNames()) {
    DomainHandler handler = domainServiceRegistry.getHandler(domain);
    if (handler instanceof AbstractDomainService ads) {
        ads.clearSession(sessionId);
    }
}
domainRouter.clearLastDomain(sessionId);
chatMemory.clear(sessionId);
globalSessionStore.clearSession(sessionId);
```

**改造后**：
```java
for (String domain : domainServiceRegistry.getDomainNames()) {
    DomainHandler handler = domainServiceRegistry.getHandler(domain);
    if (handler instanceof AbstractDomainService ads) {
        ads.clearSession(sessionId);
    }
}
domainRouter.clearLastDomain(sessionId);
globalSessionStore.clearSession(sessionId);  // ConversationHistory 在此清理
```

删除 `chatMemory.clear(sessionId)`。

---

## 11. 实施步骤

### Wave 1: 基础设施 (无破坏性)

1. **创建 ConversationRecord** — 新增 record 类
2. **创建 ConversationHistory** — 新增类，含全部方法和线程安全
3. **修改 GlobalSessionContext** — 新增 conversationHistory 字段
4. **修改 GlobalSessionStore** — createNewContext() 中创建 ConversationHistory
5. **修改 application.yml** — 新增配置项，旧配置保留（兼容过渡期）

### Wave 2: BankController 改造

6. **BankController** — 替换 ChatMemory 写入为 ConversationHistory，创建 AssistantAccumulator，去掉 globalChatHistory 格式化
7. **DomainHandler.handle()** — 去掉 globalChatHistory 参数

### Wave 3: L0 改造

8. **DomainRouter** — 注入 GlobalSessionStore，替换 ChatHistoryUtils.formatAndTruncate() 为 ConversationHistory.formatAll()

### Wave 4: L1 改造

9. **ContextRouter** — 参数 ChatMemory → String chatHistory，删除 formatChatHistory()
10. **SubGraphRouter** — 参数 ChatMemory + globalChatHistory → String chatHistory，删除 formatChatHistory()，删除 {global_chat_history} 替换
11. **SubGraphResolver** — 参数 ChatMemory + globalChatHistory → String chatHistory
12. **AbstractDomainService** — 删除 chatMemory/domainSessionId/addUserMessage/AssistantWriter，handle() 去掉 globalChatHistory，新增从 ConversationHistory 格式化历史的方法
13. **SingleSubAgentDomainService** — 适配新接口，Builder 去掉 chatMemory
14. **MultiSubAgentDomainService** — 适配新接口，Builder 去掉 chatMemory，handleResume 去掉 AssistantWriter

### Wave 5: ChatService 改造

15. **ChatService** — 注入 GlobalSessionStore，手动格式化历史，去掉 ReadOnlyMemoryAdvisor

### Wave 6: 配置和 Bean 清理

16. **DomainServiceConfig** — 去掉所有 ChatMemory 参数
17. **ModelConfig** — 删除 4 个 ChatMemory bean，chatChatClient 去掉 advisor
18. **删除 ChatMemoryConfig.java**
19. **删除 RedisChatMemoryRepository.java**
20. **删除 ReadOnlyMemoryAdvisor.java**
21. **删除 StreamingChatMemoryWriter.java**
22. **清理 ChatHistoryUtils** — 删除 formatAndTruncate(ChatMemory, ...) 和 recordReply()，保留 truncate() 工具方法或删除
23. **清理 application.yml** — 删除旧配置项
24. **清理所有 import** — 移除 org.springframework.ai.chat.memory.* 的无用 import

### Wave 7: 提示词优化

25. **l0-domain.st** — 增加域标签说明
26. **l1-context.st** — 增加分区说明
27. **l1-context-simple.st** — 增加分区说明
28. **l1-intention.st** — 合并两个历史区段，增加分区说明
29. **DomainRouter.getDefaultDomainPrompt()** — 增加域标签说明
30. **ContextRouter.getDefaultRoutingPrompt()** — 增加分区说明
31. **SubGraphRouter.getDefaultRewritePrompt()** — 合并历史区段，增加分区说明

### Wave 8: 注释和代码清理

32. **全局清理** — 删除所有 "ChatMemory" 相关注释，替换为 ConversationHistory 相关描述
33. **删除垃圾注释** — 清理过时、冗余、误导性注释
34. **代码整理** — 确保 import 干净，无未使用引用

---

## 12. 验证方案

### 12.1 编译验证

```bash
mvn compile -f D:\mobile-agent\mobile-ai-demo-enhanced-fix\pom.xml
```

确保无编译错误。

### 12.2 功能测试用例

| # | 场景 | 输入序列 | 预期 |
|---|------|---------|------|
| 1 | 直达转账 | "我要转账" | L0→TRANSFER, L1→SWITCH_NEW, L2→ASK_RECEIVER |
| 2 | 转账接续 | "张三" | L0→TRANSFER, L1→FOLLOW, L2→ASK_AMOUNT |
| 3 | 转账完成 | "500" | L0→TRANSFER, L1→FOLLOW, L2→COMPLETED |
| 4 | 域切换(转账→账单) | "查账单" | L0→BILL, L1→SWITCH_NEW, L2→ASK_TIME |
| 5 | 跨域指代 | 先"解读朝朝盈"→再"转1000到刚才的理财" | L0→TRANSFER, 改写包含"朝朝盈" |
| 6 | 理财消歧 | "看看理财" | L1→DISAMBIGUATION, 追问后识别 |
| 7 | 理财域内切换 | 推荐中→"解读朝朝盈" | L1→SWITCH, 挂起推荐, 启动解读 |
| 8 | 理财恢复 | "继续推荐" | L1→RESUME, 恢复推荐流程 |
| 9 | 闲聊 | "今天天气怎样" | L0→CHAT, ChatService回复 |
| 10 | REROUTE | 模糊意图导致 L1 返回 REROUTE | 重新路由到正确域 |
| 11 | 取消 | "算了不转了" | L1→FOLLOW, L2→CANCEL |
| 12 | 清会话 | DELETE /session | 所有状态清空，ConversationHistory 清空 |

### 12.3 历史内容验证

对每个场景，通过 `GET /api/bank/state?sessionId=xxx` 检查状态，或通过日志检查 ConversationHistory 的写入/读取是否正确。

### 12.4 回归测试

运行现有测试脚本（如 `run-all-tests.ps1`），确保改造前后测试通过率不降低。

---

## 附录 A: ChatMemory 依赖图 (改造前)

```
ChatMemoryRepository (InMemory / Redis)
  ├── chatMemory (全局)        ← BankController 写/读, DomainRouter 读, ChatService advisor 读
  ├── transferChatMemory       ← TransferService 写/读, ContextRouter 读, SubGraphRouter 读
  ├── billChatMemory           ← BillService 写/读, ContextRouter 读, SubGraphRouter 读
  └── wealthChatMemory         ← WealthService 写/读, ContextRouter 读, SubGraphRouter 读
```

## 附录 B: ConversationHistory 依赖图 (改造后)

```
GlobalSessionStore
  └── GlobalSessionContext
        ├── state (OverAllState)         ← L2 子图数据注入 (不变)
        └── conversationHistory          ← 唯一对话历史存储
              ├── BankController 写 (UserMessage + AssistantMessage)
              ├── DomainRouter 读 (formatAll)
              ├── L1 DomainService 读 (formatByDomain)
              └── ChatService 读 (formatAll)
```

## 附录 C: 消息写入时序 (改造后)

```
用户: "我要转账"
  │
  ▼
BankController.buildChatPipeline()
  │ 1. conversationHistory.addUserMessage("我要转账", null)  ← domain 暂空
  │ 2. dispatchWithReroute()
  │    2a. DomainRouter.route() → domain=TRANSFER
  │    2b. conversationHistory.updateLastUserDomain("TRANSFER")
  │    2c. dispatchToDomain(TRANSFER)
  │        → TransferService.handle(sessionId, "我要转账")
  │           → ContextRouter / SubGraphRouter (从 conversationHistory 读取)
  │           → executeNewAgent() → L2 Graph
  │    2d. AssistantAccumulator.onChunk(terminalChunk)
  │        → conversationHistory.addAssistantMessage("请问转给谁？", "TRANSFER")
  │
  ▼
ConversationHistory:
  [USER, "我要转账", TRANSFER]
  [ASSISTANT, "请问转给谁？", TRANSFER]
```
