package com.mobileagent.app.domain;

import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.data.WorkflowStatus;
import com.mobileagent.app.execution.GraphExecutionService;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1领域服务抽象基类 - 提取Single/Multi共性的状态管理和工具方法
 *
 * 共性逻辑:
 * - activeThread管理 (per session, per domain): get/set/clear/saveAccumulatedParams
 * - ChatMemory管理: addUserMessage, recordSystemReply
 * - resumeGraph模式: 新threadId + 注入accumulatedParams + 保存结果
 * - executeNewThread模式: 取graph → 新thread → 执行 → 保存 → 完成清空
 * - clearSession / getSessionStateDescription
 * - 意图自描述: getHandledIntents() 供外部查询此服务处理哪些意图
 *
 * 不共性的逻辑(由子类实现):
 * - handle(): 控制流完全不同,各自实现
 * - 依赖注入: Single用ContextRewriter, Multi用IntentResolver
 * - 状态: Multi额外有suspendedAgents + disambiguationStates
 *
 * Cancel设计:
 * - L1层不暴露cancel公共方法
 * - 子智能体执行中的取消(如"不转了"): 由L2子Graph的cancelAwareExtractParams检测并处理
 *   流程: FOLLOW_UP → resumeGraph → 子Graph自行检测取消意图 → cancelExecutionNode
 * - Multi消歧中的取消(如"算了"): 由IntentResolver.isCancelExpression()检测 → CANCELLED状态
 *   流程: 已在MultiSubAgentDomainService.handle()的switch分支中处理
 * - 未来如果L0 reactAgent需要主动取消某个L1的执行,再添加cancel接口
 */
@Slf4j
public abstract class AbstractDomainService {

    protected final String domainName;
    protected final String logTag;
    protected final ChatMemory chatMemory;
    protected final ContextRouter contextRouter;
    protected final GraphExecutionService graphExecutionService;
    protected final IntentRegistry intentRegistry;

    // ==================== 共享状态 ====================

    /** 本领域自有的activeThread (per session) */
    private final Map<String, ActiveThreadInfo> activeThreads = new ConcurrentHashMap<>();

    /** activeThread过期时间(分钟) - 与suspendedExpireMinutes保持一致 */
    private final long activeThreadExpireMinutes;

    // ==================== 共享数据类 ====================

    @Data
    public static class ActiveThreadInfo {
        private final String threadId;
        private final String intent;
        private final Instant createdAt;
        private final Instant expiresAt;
        /** 累积的参数 (如transfer.receiver, wealthConsult.riskLevel等) */
        private Map<String, Object> accumulatedParams = new HashMap<>();

        public ActiveThreadInfo(String threadId, String intent, Instant createdAt, Instant expiresAt) {
            this.threadId = threadId;
            this.intent = intent;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public void setAccumulatedParams(Map<String, Object> params) {
            this.accumulatedParams = params != null ? new HashMap<>(params) : new HashMap<>();
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
                                    GraphExecutionService graphExecutionService,
                                    IntentRegistry intentRegistry,
                                    long activeThreadExpireMinutes) {
        this.domainName = domainName;
        this.logTag = logTag;
        this.chatMemory = chatMemory;
        this.contextRouter = contextRouter;
        this.graphExecutionService = graphExecutionService;
        this.intentRegistry = intentRegistry;
        this.activeThreadExpireMinutes = activeThreadExpireMinutes;
    }

    // ==================== activeThread管理 ====================

    protected ActiveThreadInfo getOwnActiveThread(String sessionId) {
        ActiveThreadInfo info = activeThreads.get(sessionId);
        if (info != null && info.getExpiresAt().isBefore(Instant.now())) {
            activeThreads.remove(sessionId);
            log.info("[{}] ActiveThread expired: session={}, intent={}, createdAt={}",
                    logTag, sessionId, info.getIntent(), info.getCreatedAt());
            return null;
        }
        return info;
    }

    protected void setOwnActiveThread(String sessionId, String threadId, String intent) {
        if (threadId == null || intent == null) {
            activeThreads.remove(sessionId);
        } else {
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(activeThreadExpireMinutes * 60);
            activeThreads.put(sessionId, new ActiveThreadInfo(threadId, intent, now, expiresAt));
        }
    }

    protected void clearOwnActiveThread(String sessionId) {
        activeThreads.remove(sessionId);
    }

    /** 清理过期的activeThread (由子类的@Scheduled方法调用) */
    protected void cleanupExpiredActiveThreads() {
        Instant now = Instant.now();
        activeThreads.entrySet().removeIf(entry -> {
            if (entry.getValue().getExpiresAt().isBefore(now)) {
                log.debug("[{}] Auto cleaned expired activeThread: session={}, intent={}",
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

    // ==================== accumulatedParams保存 ====================

    /**
     * 从WorkflowOutput中保存accumulatedParams到自有activeThread
     *
     * 这是状态自管的关键: GES返回accumulatedParams，L1 Service负责保存
     */
    protected void saveAccumulatedParams(String sessionId, WorkflowOutput output) {
        if (output == null || output.getAccumulatedParams() == null) return;
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        if (active != null && !output.getAccumulatedParams().isEmpty()) {
            active.setAccumulatedParams(output.getAccumulatedParams());
            log.info("[{}] Saved accumulated params: {}", logTag, output.getAccumulatedParams());
        }
    }

    // ==================== 工具方法 ====================

    protected String generateThreadId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 构建当前活跃意图字符串(供ContextRouter prompt用)
     */
    protected String buildCurrentAgent(String sessionId) {
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        return active != null ? active.getIntent() : "无";
    }

    // ==================== 共享流程方法 ====================

    /**
     * FOLLOW_UP + activeThread → resumeGraph模式
     *
     * Single和Multi都有这个分支，逻辑完全一致:
     * 1. addUserMessage
     * 2. 生成新threadId
     * 3. setActiveThread(保持原intent)
     * 4. resumeGraph(注入accumulatedParams)
     * 5. saveAccumulatedParams
     * 6. recordSystemReply
     */
    protected WorkflowOutput resumeActiveThread(String sessionId, String userInput, ActiveThreadInfo active) {
        log.info("[{}] FOLLOW_UP with own activeThread: intent={}, params={}",
                logTag, active.getIntent(), active.getAccumulatedParams());
        addUserMessage(sessionId, userInput);

        String newThreadId = generateThreadId();
        setOwnActiveThread(sessionId, newThreadId, active.getIntent());

        WorkflowOutput resumeResult = graphExecutionService.resumeGraph(
                active.getIntent(), newThreadId, userInput, sessionId,
                active.getAccumulatedParams());
        saveAccumulatedParams(sessionId, resumeResult);
        recordSystemReply(sessionId, resumeResult);

        // 完成后清空activeThread，避免下次FOLLOW_UP复用旧参数
        if (WorkflowStatus.COMPLETED.equals(resumeResult.getStatus())) {
            clearOwnActiveThread(sessionId);
        }

        return resumeResult;
    }

    /**
     * 新建thread执行Graph模式 (handleSwitchNew的核心)
     *
     * Single和Multi都有这个逻辑:
     * 1. 取Graph
     * 2. 新threadId
     * 3. setActiveThread
     * 4. executeGraph
     * 5. saveAccumulatedParams
     * 6. COMPLETED → clearActiveThread
     *
     * 注意: suspend当前线程的逻辑由子类在调用前处理(Single不suspend, Multi先suspend再调用)
     *
     * @param sessionId 会话ID
     * @param intent 意图名(Single为固定值, Multi为动态值)
     * @param rewrittenInput 改写后的输入
     * @return 执行结果
     */
    protected WorkflowOutput executeNewThread(String sessionId, String intent, String rewrittenInput) {
        var graph = intentRegistry.getGraph(intent);
        if (graph == null) {
            return WorkflowOutput.error("Graph not found for intent: " + intent);
        }

        String newThreadId = generateThreadId();
        setOwnActiveThread(sessionId, newThreadId, intent);

        WorkflowOutput result = graphExecutionService.executeGraph(graph, intent, newThreadId, rewrittenInput, sessionId, null);
        saveAccumulatedParams(sessionId, result);

        if (WorkflowStatus.COMPLETED.equals(result.getStatus())) {
            clearOwnActiveThread(sessionId);
        }

        return result;
    }

    // ==================== 抽象方法 ====================

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
