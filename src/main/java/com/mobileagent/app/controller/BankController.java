package com.mobileagent.app.controller;

import com.mobileagent.app.data.WorkflowStatus;
import com.mobileagent.app.domain.DomainHandler;
import com.mobileagent.app.router.DomainServiceRegistry;
import com.mobileagent.app.router.DomainRouter;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 银行主控制器 - L0调度层
 *
 * 架构 (L0→L1→L2):
 * 1. L0: DomainRouter判断领域(WEALTH/TRANSFER/BILL/UNSUPPORTED/CHAT)
 * 2. 分发到L1 Service，附带全局跨域对话历史(用于跨域指代消解)
 * 3. L1: 各领域Service内部路由(FOLLOW/RESUME等) + 自管状态 + 用全局历史增强改写
 * 4. L2: 子智能体Graph执行
 *
 * REROUTE机制:
 * - L1返回WorkflowStatus.REROUTE时，排除当前域重新路由
 * - 最多重试MAX_REROUTE次，超限后CHAT域兜底
 * - REROUTE不对用户可见，只记录最终结果到全局ChatMemory
 *
 * 状态管理:
 * - 不依赖AgentStateManager，各L1 Service自管状态
 * - clearSession: 遍历所有注册的DomainHandler清理
 * - getState: 聚合各L1 Service的状态描述
 *
 * 对话历史管理:
 * - 全局ChatMemory: 记录所有对话,供L0 DomainRouter手动读取,同时格式化后传给L1做跨域指代消解
 * - 领域ChatMemory: 各L1 Service独立维护,供ContextRouter/IntentRouter手动读取
 * - 全局跨域历史: L0传给L1的字符串快照,不缓存,仅作改写参考
 */
@Slf4j
@RestController
@RequestMapping("/api/bank")
public class BankController {

    /** REROUTE最大重试次数 (可配置) */
    @org.springframework.beans.factory.annotation.Value("${routing.reroute.max-attempts:2}")
    private int maxRerouteAttempts;

    private final DomainRouter domainRouter;
    private final DomainServiceRegistry domainServiceRegistry;
    private final ChatMemory chatMemory;
    private final int globalContextMaxPairs;

    public BankController(DomainRouter domainRouter,
                          DomainServiceRegistry domainServiceRegistry,
                          ChatMemory chatMemory,
                          @org.springframework.beans.factory.annotation.Value("${routing.history.global-context-max-pairs:10}") int globalContextMaxPairs) {
        this.domainRouter = domainRouter;
        this.domainServiceRegistry = domainServiceRegistry;
        this.chatMemory = chatMemory;
        this.globalContextMaxPairs = globalContextMaxPairs;
    }

    @PostMapping("/chat")
    public WorkflowOutput chat(@RequestParam String sessionId,
                               @RequestBody Map<String, String> req) {
        String userInput = req.get("message");
        log.info("[BankController] >>> chat: sessionId={}, message={}", sessionId, userInput);

        if (userInput == null || userInput.isBlank()) {
            return WorkflowOutput.error("Message cannot be empty");
        }

        try {
            Set<String> excludedDomains = new HashSet<>();
            WorkflowOutput output = null;
            String currentDomain = null;
            int rerouteCount = 0;

            while (rerouteCount < maxRerouteAttempts) {
                // L0: 领域路由(带排除列表)
                DomainRouter.DomainResult domainResult = domainRouter.route(sessionId, userInput, excludedDomains);
                currentDomain = domainResult.domain();
                log.info("[BankController] L0 domain: {}, unsupportedFeature: {}, excluded: {}, rerouteCount: {}",
                        currentDomain, domainResult.unsupportedFeature(), excludedDomains, rerouteCount);

                // L0路由到了被排除的域 → LLM没尊重排除提示，直接算失败
                if (excludedDomains.contains(currentDomain)) {
                    rerouteCount++;
                    log.warn("[BankController] L0 routed to excluded domain={}, counting as failed attempt ({}/{})",
                            currentDomain, rerouteCount, maxRerouteAttempts);
                    continue;
                }

                // 格式化全局跨域历史(供L1做跨域指代消解)
                String globalChatHistory = ChatHistoryUtils.formatAndTruncate(
                        chatMemory, sessionId, globalContextMaxPairs);

                // 分发到L1 DomainHandler
                output = dispatchToDomain(domainResult, sessionId, userInput, globalChatHistory);

                // 非REROUTE → 结束循环
                if (output.getStatus() != WorkflowStatus.REROUTE) break;

                // REROUTE → 排除当前域，重新路由
                excludedDomains.add(currentDomain);
                rerouteCount++;
                log.info("[BankController] REROUTE #{}: domain={} excluded, rerouteIntent={}, rerouteHint={}, excluded={}",
                        rerouteCount, currentDomain, output.getRerouteIntent(), output.getRerouteHint(), excludedDomains);
            }

            // 超过maxRerouteAttempts → 返回错误给用户
            if (output != null && output.getStatus() == WorkflowStatus.REROUTE) {
                log.info("[BankController] Max reroute attempts ({}) exceeded, returning error to user", maxRerouteAttempts);
                output = WorkflowOutput.completed(null, "抱歉，暂时无法识别您的请求，请换个方式描述。");
            }

            // 记录到全局ChatMemory(只记录最终结果，不记录中间REROUTE)
            chatMemory.add(sessionId, new UserMessage(userInput));
            recordSystemReply(sessionId, output);

            return output;

        } catch (Exception e) {
            log.error("[BankController] Error processing chat", e);
            return WorkflowOutput.error("处理请求时出错: " + e.getMessage());
        }
    }

    // ==================== 领域分发 ====================

    /**
     * 分发到L1 DomainHandler
     */
    private WorkflowOutput dispatchToDomain(DomainRouter.DomainResult domainResult,
                                             String sessionId, String userInput,
                                             String globalChatHistory) {
        if (domainResult.isUnsupported()) {
            String feature = domainResult.unsupportedFeature() != null
                    ? domainResult.unsupportedFeature() : "该";
            return WorkflowOutput.completed(null, feature + "功能暂不支持");
        }

        DomainHandler handler = domainServiceRegistry.getHandler(domainResult.domain());
        if (handler != null) {
            return handler.handle(sessionId, userInput, globalChatHistory);
        }

        // 未注册的域 → CHAT兜底
        log.warn("[BankController] No handler registered for domain: {}, falling back to CHAT", domainResult.domain());
        DomainHandler chatHandler = domainServiceRegistry.getHandler("CHAT");
        if (chatHandler != null) {
            return chatHandler.handle(sessionId, userInput, globalChatHistory);
        }

        return WorkflowOutput.completed(null, "功能暂不支持");
    }

    // ==================== 会话管理 ====================

    /**
     * 获取会话状态 - 聚合所有L1 Service的状态
     */
    @GetMapping("/state")
    public Map<String, Object> getState(@RequestParam String sessionId) {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("sessionId", sessionId);
        for (Map.Entry<String, DomainHandler> entry : domainServiceRegistry.getDomainNames()
                .stream().collect(java.util.stream.Collectors.toMap(d -> d, d -> domainServiceRegistry.getHandler(d))).entrySet()) {
            DomainHandler handler = entry.getValue();
            if (handler instanceof com.mobileagent.app.domain.AbstractDomainService ads) {
                state.put(entry.getKey().toLowerCase(), ads.getSessionStateDescription(sessionId));
            }
        }
        return state;
    }

    /**
     * 清除会话 - 遍历所有DomainHandler清理各自状态
     */
    @DeleteMapping("/session")
    public Map<String, String> clearSession(@RequestParam String sessionId) {
        for (String domain : domainServiceRegistry.getDomainNames()) {
            DomainHandler handler = domainServiceRegistry.getHandler(domain);
            if (handler instanceof com.mobileagent.app.domain.AbstractDomainService ads) {
                ads.clearSession(sessionId);
            }
        }
        domainRouter.clearLastDomain(sessionId);
        chatMemory.clear(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }

    // ==================== 内部方法 ====================

    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        ChatHistoryUtils.recordReply(chatMemory, sessionId, output, null);
    }
}
