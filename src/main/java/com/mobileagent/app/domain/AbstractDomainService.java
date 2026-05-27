package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.data.WorkflowStatus;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1领域服务抽象基类 - 提取Single/Multi共性的状态管理和工具方法
 *
 * 核心设计（官方模式二）:
 * - threadId = sessionId（GES推导，L1不传递）
 * - accumulatedParams 不再需要在L1层保存（checkpoint自动保留完整OverAllState）
 * - ActiveAgentInfo 只保留 intent + lastQuestion
 * - resumeActiveAgent: 调用GES.resumeGraph(graph, intent, userInput, sessionId)
 * - executeNewAgent: 调用GES.executeGraph(graph, intent, rewrittenInput, sessionId)
 *
 * 不共性的逻辑(由子类实现):
 * - handle(): 控制流完全不同,各自实现
 * - 依赖注入: Single和Multi都用IntentRouter(通过IntentResolver或直接)
 * - 状态: Multi额外有suspendedAgents + disambiguationStates
 *
 * Cancel设计:
 * - L1层不暴露cancel公共方法
 * - 子智能体执行中的取消(如"不转了"): 由L2子Graph的cancelAwareExtractParams检测并处理
 * - Multi消歧中的取消(如"算了"): 由IntentResolver.isCancelExpression()检测 → CANCELLED状态
 */
@Slf4j
public abstract class AbstractDomainService implements DomainHandler {

    protected final String domainName;
    protected final String logTag;
    protected final ChatMemory chatMemory;
    protected final ContextRouter contextRouter;
    protected final GraphExecutionEngine graphExecutionEngine;
    protected final IntentRegistry intentRegistry;

    // ==================== 共享状态 ====================

    /** 本领域自有的activeAgent (per session) */
    private final Map<String, ActiveAgentInfo> activeAgents = new ConcurrentHashMap<>();

    /** activeAgent过期时间(分钟) - 与suspendedExpireMinutes保持一致 */
    private final long activeAgentExpireMinutes;

    // ==================== 共享数据类 ====================

    @Data
    public static class ActiveAgentInfo {
        private final String intent;
        private final Instant createdAt;
        private final Instant expiresAt;
        /** L2子智能体最后的提问(INTERRUPTED时设置,COMPLETED时清空) */
        private String lastQuestion;

        public ActiveAgentInfo(String intent, Instant createdAt, Instant expiresAt) {
            this.intent = intent;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 意图描述 - 声明此L1服务处理哪些意图
     *
     * 用于:
     * - DomainRouter/L0了解各领域服务的意图范围
     * - 未来reactAgent调度时查询可调度的意图
     * - 状态监控/调试时展示服务能力
     */
    @Data
    public static class IntentInfo {
        /** 意图名称 (如TRANSFER, WEALTH_CONSULT) */
        private final String intentName;
        /** 意图描述 (如"转账操作", "理财咨询与推荐") */
        private final String description;

        public IntentInfo(String intentName, String description) {
            this.intentName = intentName;
            this.description = description;
        }
    }

    // ==================== 构造 ====================

    protected AbstractDomainService(String domainName, String logTag,
                                    ChatMemory chatMemory,
                                    ContextRouter contextRouter,
                                    GraphExecutionEngine graphExecutionEngine,
                                    IntentRegistry intentRegistry,
                                    long activeAgentExpireMinutes) {
        this.domainName = domainName;
        this.logTag = logTag;
        this.chatMemory = chatMemory;
        this.contextRouter = contextRouter;
        this.graphExecutionEngine = graphExecutionEngine;
        this.intentRegistry = intentRegistry;
        this.activeAgentExpireMinutes = activeAgentExpireMinutes;
    }

    // ==================== activeAgent管理 ====================

    protected ActiveAgentInfo getOwnActiveAgent(String sessionId) {
        ActiveAgentInfo info = activeAgents.get(sessionId);
        if (info != null && info.getExpiresAt().isBefore(Instant.now())) {
            activeAgents.remove(sessionId);
            log.info("[{}] ActiveAgent expired: session={}, intent={}, createdAt={}",
                    logTag, sessionId, info.getIntent(), info.getCreatedAt());
            return null;
        }
        return info;
    }

    protected void setOwnActiveAgent(String sessionId, String intent) {
        if (intent == null) {
            activeAgents.remove(sessionId);
        } else {
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(activeAgentExpireMinutes * 60);
            activeAgents.put(sessionId, new ActiveAgentInfo(intent, now, expiresAt));
        }
    }

    protected void clearOwnActiveAgent(String sessionId) {
        activeAgents.remove(sessionId);
    }

    /** 清理过期的activeAgent (由子类的@Scheduled方法调用) */
    protected void cleanupExpiredActiveAgents() {
        Instant now = Instant.now();
        activeAgents.entrySet().removeIf(entry -> {
            if (entry.getValue().getExpiresAt().isBefore(now)) {
                log.debug("[{}] Auto cleaned expired activeAgent: session={}, intent={}",
                        logTag, entry.getKey(), entry.getValue().getIntent());
                return true;
            }
            return false;
        });
    }

    // ==================== ChatMemory管理 ====================

    protected void addUserMessage(String sessionId, String userInput) {
        chatMemory.add(sessionId, new UserMessage(userInput));
    }

    protected void recordSystemReply(String sessionId, WorkflowOutput output) {
        ChatHistoryUtils.recordReply(chatMemory, sessionId, output, logTag);
    }

    // ==================== lastQuestion保存 ====================

    /**
     * 从WorkflowOutput中保存lastQuestion到自有activeAgent
     *
     * 简化（官方模式二）: 不再保存accumulatedParams（checkpoint自动保留）
     */
    protected void saveL2Result(String sessionId, WorkflowOutput output) {
        if (output == null) return;
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        if (active != null) {
            if (output.getStatus() == WorkflowStatus.INTERRUPTED && output.getQuestion() != null) {
                active.setLastQuestion(output.getQuestion());
                log.debug("[{}] Saved lastQuestion: {}", logTag, output.getQuestion());
            } else if (output.getStatus() == WorkflowStatus.COMPLETED) {
                active.setLastQuestion(null);
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 构建当前活跃意图字符串(供ContextRouter prompt用)
     */
    protected String buildCurrentAgent(String sessionId) {
        ActiveAgentInfo active = getOwnActiveAgent(sessionId);
        return active != null ? active.getIntent() : "无";
    }

    // ==================== 共享流程方法 ====================

    /**
     * FOLLOW + activeAgent → resumeGraph模式（官方模式二）
     *
     * Single和Multi都有这个分支，逻辑完全一致:
     * 1. addUserMessage
     * 2. resumeGraph(graph, intent, userInput, sessionId) — 不传accumulatedParams
     * 3. saveL2Result
     * 4. recordSystemReply
     * 5. COMPLETED → clearActiveAgent
     */
    protected WorkflowOutput resumeActiveAgent(String sessionId, String userInput, ActiveAgentInfo active) {
        log.info("[{}] FOLLOW with own activeAgent: intent={}", logTag, active.getIntent());
        addUserMessage(sessionId, userInput);

        var graph = intentRegistry.getGraph(active.getIntent());
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + active.getIntent());
        }

        WorkflowOutput resumeResult = graphExecutionEngine.resumeGraph(
                graph, active.getIntent(), userInput, sessionId);
        saveL2Result(sessionId, resumeResult);
        recordSystemReply(sessionId, resumeResult);

        if (WorkflowStatus.COMPLETED.equals(resumeResult.getStatus())) {
            clearOwnActiveAgent(sessionId);
        }

        return resumeResult;
    }

    /**
     * 新建agent执行Graph模式 (handleSwitchNew的核心)
     *
     * Single和Multi都有这个逻辑:
     * 1. 取Graph
     * 2. setActiveAgent
     * 3. executeGraph
     * 4. saveL2Result
     * 5. COMPLETED → clearActiveAgent
     *
     * 注意: suspend当前agent的逻辑由子类在调用前处理(Single不suspend, Multi先suspend再调用)
     *
     * @param sessionId 会话ID
     * @param intent 意图名(Single为固定值, Multi为动态值)
     * @param rewrittenInput 改写后的输入
     * @return 执行结果
     */
    protected WorkflowOutput executeNewAgent(String sessionId, String intent, String rewrittenInput) {
        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        setOwnActiveAgent(sessionId, intent);

        WorkflowOutput result = graphExecutionEngine.executeGraph(graph, intent, rewrittenInput, sessionId);
        saveL2Result(sessionId, result);

        if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
            clearOwnActiveAgent(sessionId);
        }

        return result;
    }

    // ==================== 抽象方法 ====================

    /**
     * Auto-upgrade: ContextRouter 判 SWITCH 但 IntentRouter 识别的意图
     * 与 activeAgent 的意图一致 → 降级回 FOLLOW
     *
     * 保护场景: ContextRouter 误判导致意图丢失
     *
     * @return 如果升级成功返回 resumeActiveAgent 结果，否则返回 null（由调用方继续正常流程）
     */
    protected WorkflowOutput tryAutoUpgradeFollowUp(ActiveAgentInfo activeAgent,
                                                     RoutingResult phase2,
                                                     String sessionId, String userInput) {
        if (activeAgent != null && phase2 != null) {
            String identifiedIntent = phase2.getIntentName();
            if (identifiedIntent != null && identifiedIntent.equals(activeAgent.getIntent())) {
                log.info("[{}] Auto-upgrade SWITCH→FOLLOW: identifiedIntent={} matches activeAgent.intent={}",
                        logTag, identifiedIntent, activeAgent.getIntent());
                return resumeActiveAgent(sessionId, userInput, activeAgent);
            }
        }
        return null; // 不匹配，由调用方继续正常流程
    }

    /**
     * 处理消息 - 控制流由子类各自实现
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param globalChatHistory 全局跨域对话历史(由L0/BankController格式化后传入，不缓存，仅作改写参考)
     */
    public abstract WorkflowOutput handle(String sessionId, String userInput, String globalChatHistory);

    /** 清除会话所有状态 */
    public abstract void clearSession(String sessionId);

    /** 获取会话状态描述 */
    public abstract String getSessionStateDescription(String sessionId);

    /**
     * 获取此领域服务处理的所有意图及其描述
     *
     * 供外部(BankController/DomainRouter/未来reactAgent)查询此服务的意图范围。
     * Single返回1个, Multi返回N个。
     */
    public abstract List<IntentInfo> getHandledIntents();

    /** 获取领域名称 */
    public String getDomainName() {
        return domainName;
    }
}
