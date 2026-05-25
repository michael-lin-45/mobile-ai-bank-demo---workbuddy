package com.mobileagent.app.domain;

import com.mobileagent.app.data.RoutingResult;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.rewriter.ContextRewriter;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.execution.GraphExecutionService;
import com.mobileagent.app.util.TemplateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Objects;

/**
 * 单子智能体领域服务 - 1-1关系 (1个L1对应1个L2)
 *
 * 适用场景: 转账(TRANSFER), 账单(BILL_QUERY), 余额查询, 支付, 存款 等
 * 特点: 只有一个子意图，无需suspendedAgents、无需消歧、无需Phase2
 *
 * 控制流 (极简2分支):
 * 1. Phase1: ContextRouter (simple模板) → FOLLOW_UP / SWITCH_NEW
 * 2. FOLLOW_UP + activeThread → resumeGraph(注入accumulatedParams) ← 核心bug修复
 * 3. FOLLOW_UP + 无activeThread → ContextRewriter改写 → 降级SWITCH_NEW
 * 4. SWITCH_NEW → executeNewThread(固定intent)
 *
 * Cancel:
 * - 不暴露cancel公共方法
 * - 用户说"取消"→ FOLLOW_UP → resumeGraph → 子Graph的cancelAwareExtractParams检测并处理
 */
@Slf4j
public class SingleSubAgentDomainService extends AbstractDomainService {

    private static final String DEFAULT_ROUTING_TEMPLATE = "prompts/l1-routing-simple.st";
    private static final String DEFAULT_REWRITER_TEMPLATE = "prompts/l1-context-rewrite.st";

    private final String intent;
    private final String intentDescription;
    private final String routingTemplatePath;
    private final String rewriterTemplatePath;
    private final ContextRewriter contextRewriter;

    // ==================== 构造(Builder) ====================

    private SingleSubAgentDomainService(Builder builder) {
        super(builder.domainName, builder.logTag, builder.chatMemory,
                builder.contextRouter, builder.graphExecutionService, builder.intentRegistry,
                builder.activeThreadExpireMinutes);
        this.intent = builder.intent;
        this.intentDescription = builder.intentDescription;
        this.routingTemplatePath = builder.routingTemplatePath != null
                ? builder.routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
        this.rewriterTemplatePath = builder.rewriterTemplatePath != null
                ? builder.rewriterTemplatePath : DEFAULT_REWRITER_TEMPLATE;
        this.contextRewriter = builder.contextRewriter;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String domainName;
        private String logTag;
        private String intent;
        private String intentDescription;
        private String routingTemplatePath;
        private String rewriterTemplatePath;
        private ChatMemory chatMemory;
        private ContextRouter contextRouter;
        private ContextRewriter contextRewriter;
        private GraphExecutionService graphExecutionService;
        private IntentRegistry intentRegistry;
        private long activeThreadExpireMinutes = 20;

        public Builder domainName(String domainName) { this.domainName = domainName; return this; }
        public Builder logTag(String logTag) { this.logTag = logTag; return this; }
        public Builder intent(String intent) { this.intent = intent; return this; }
        /** 意图描述 (如"转账操作")，供getHandledIntents()返回 */
        public Builder intentDescription(String intentDescription) { this.intentDescription = intentDescription; return this; }
        /** 路由模板路径，默认 "prompts/l1-routing-simple.st" */
        public Builder routingTemplatePath(String routingTemplatePath) { this.routingTemplatePath = routingTemplatePath; return this; }
        /** 改写模板路径，默认 "prompts/l1-context-rewrite.st" */
        public Builder rewriterTemplatePath(String rewriterTemplatePath) { this.rewriterTemplatePath = rewriterTemplatePath; return this; }
        public Builder chatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; return this; }
        public Builder contextRouter(ContextRouter contextRouter) { this.contextRouter = contextRouter; return this; }
        public Builder contextRewriter(ContextRewriter contextRewriter) { this.contextRewriter = contextRewriter; return this; }
        public Builder graphExecutionService(GraphExecutionService graphExecutionService) { this.graphExecutionService = graphExecutionService; return this; }
        public Builder intentRegistry(IntentRegistry intentRegistry) { this.intentRegistry = intentRegistry; return this; }
        /** activeThread过期时间(分钟)，默认20分钟 */
        public Builder activeThreadExpireMinutes(long activeThreadExpireMinutes) { this.activeThreadExpireMinutes = activeThreadExpireMinutes; return this; }

        public SingleSubAgentDomainService build() {
            Objects.requireNonNull(domainName, "domainName is required");
            Objects.requireNonNull(logTag, "logTag is required");
            Objects.requireNonNull(intent, "intent is required");
            Objects.requireNonNull(chatMemory, "chatMemory is required");
            Objects.requireNonNull(contextRouter, "contextRouter is required");
            Objects.requireNonNull(contextRewriter, "contextRewriter is required");
            Objects.requireNonNull(graphExecutionService, "graphExecutionService is required");
            Objects.requireNonNull(intentRegistry, "intentRegistry is required");
            // 预热模板: 构造时加载到缓存,运行时零IO
            String resolvedRoutingPath = routingTemplatePath != null ? routingTemplatePath : DEFAULT_ROUTING_TEMPLATE;
            String resolvedRewriterPath = rewriterTemplatePath != null ? rewriterTemplatePath : DEFAULT_REWRITER_TEMPLATE;
            TemplateUtils.warmUp(resolvedRoutingPath, () -> "");
            TemplateUtils.warmUp(resolvedRewriterPath, () -> "");
            return new SingleSubAgentDomainService(this);
        }
    }

    // ==================== 主入口 ====================

    @Override
    public WorkflowOutput handle(String sessionId, String userInput, String globalChatHistory) {
        log.info("[{}] Handling: sessionId={}, input={}", logTag, sessionId, userInput);

        try {
            // ========== 1-1领域核心逻辑: activeThread在 → 直接FOLLOW_UP ==========
            // 原因: 1-1领域只有一个意图,如果activeThread存在(操作中断),用户回来必然是继续。
            // 不走ContextRouter,避免LLM非确定性误判SWITCH_NEW导致accumulatedParams丢失。
            ActiveThreadInfo ownActive = getOwnActiveThread(sessionId);
            if (ownActive != null) {
                return resumeActiveThread(sessionId, userInput, ownActive);
            }

            // ========== 无activeThread → 走ContextRouter判断FOLLOW_UP/SWITCH_NEW ==========
            String currentAgent = "无";
            String pendingAgents = "无"; // 1-1无suspendedAgents

            // Phase1: ContextRouter判断FOLLOW_UP/SWITCH_NEW (简化模式)
            RoutingResult phase1 = contextRouter.route(sessionId, userInput,
                    currentAgent, pendingAgents,
                    routingTemplatePath, domainName, chatMemory);
            log.info("[{}] Phase1 (no activeThread): routeType={}, confidence={}", logTag, phase1.getRouteType(), phase1.getConfidence());

            // ========== 无activeThread → 上下文改写后SWITCH_NEW ==========
            // 无论是FOLLOW_UP还是SWITCH_NEW，只要没有activeThread，
            // 用户输入可能依赖历史上下文(如"再转一笔3000"依赖之前的"给我妈")，
            // 需要ContextRewriter改写为自包含描述
            // 传入globalChatHistory用于跨域指代消解(如"刚才说的那个理财")
            String rewrittenInput = contextRewriter.rewrite(sessionId, userInput, chatMemory, domainName, rewriterTemplatePath, globalChatHistory);
            log.info("[{}] ContextRewriter: input=[{}] → rewritten=[{}], globalChatHistory=[{}]",
                    logTag, userInput, rewrittenInput,
                    globalChatHistory != null && !globalChatHistory.isBlank() ? globalChatHistory.substring(0, Math.min(200, globalChatHistory.length())) + "..." : "(空)");
            addUserMessage(sessionId, userInput);
            return handleSwitchNew(sessionId, rewrittenInput);

        } catch (Exception e) {
            log.error("[{}] Error handling message", logTag, e);
            return WorkflowOutput.error("处理" + domainName + "请求时出错: " + e.getMessage());
        }
    }

    // ==================== SWITCH_NEW执行 ====================

    /**
     * 1-1的handleSwitchNew: 无需suspend当前线程，直接覆盖
     */
    private WorkflowOutput handleSwitchNew(String sessionId, String rewrittenInput) {
        return executeNewThread(sessionId, intent, rewrittenInput);
    }

    // ==================== 定时清理 ====================

    /** 定时清理过期的activeThread, 每分钟执行一次 */
    @Scheduled(fixedRate = 60_000)
    public void scheduledCleanup() {
        cleanupExpiredActiveThreads();
    }

    // ==================== 状态管理 ====================

    @Override
    public void clearSession(String sessionId) {
        clearOwnActiveThread(sessionId);
    }

    @Override
    public String getSessionStateDescription(String sessionId) {
        ActiveThreadInfo active = getOwnActiveThread(sessionId);
        if (active != null) {
            return "当前活跃意图: " + active.getIntent() +
                    " (线程: " + active.getThreadId().substring(0, 8) + "...)" +
                    " 参数: " + active.getAccumulatedParams();
        }
        return "当前无活跃意图";
    }

    @Override
    public List<IntentInfo> getHandledIntents() {
        return List.of(new IntentInfo(intent,
                intentDescription != null ? intentDescription : domainName));
    }
}
