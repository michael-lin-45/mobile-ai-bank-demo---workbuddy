package com.mobileagent.app.controller;

import com.mobileagent.app.domain.BillService;
import com.mobileagent.app.domain.ChatService;
import com.mobileagent.app.domain.TransferService;
import com.mobileagent.app.domain.WealthService;
import com.mobileagent.app.router.DomainRouter;
import com.mobileagent.app.data.WorkflowOutput;
import com.mobileagent.app.manager.AgentStateManager;
import com.mobileagent.app.util.ChatHistoryUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 银行主控制器 - L0调度层
 *
 * 架构 (L0→L1→L2):
 * 1. L0: DomainRouter判断领域(WEALTH/TRANSFER/BILL/UNSUPPORTED/CHAT)
 * 2. 分发到L1 Service
 * 3. L1: 各领域Service内部路由(FOLLOW_UP/RESUME等)
 * 4. L2: 子智能体Graph执行
 *
 * 对话历史管理:
 * - 全局ChatMemory: 记录所有对话,供L0 DomainRouter手动读取
 * - 领域ChatMemory: 各L1 Service独立维护,供ContextRouter/IntentRouter手动读取
 */
@Slf4j
@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final DomainRouter domainRouter;
    private final WealthService wealthService;
    private final TransferService transferService;
    private final BillService billService;
    private final ChatService chatService;
    private final ChatMemory chatMemory;
    private final AgentStateManager stateManager;

    public BankController(DomainRouter domainRouter,
                          WealthService wealthService,
                          TransferService transferService,
                          BillService billService,
                          ChatService chatService,
                          ChatMemory chatMemory,
                          AgentStateManager stateManager) {
        this.domainRouter = domainRouter;
        this.wealthService = wealthService;
        this.transferService = transferService;
        this.billService = billService;
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.stateManager = stateManager;
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
            // L0: 领域路由
            DomainRouter.DomainResult domainResult = domainRouter.route(sessionId, userInput);
            log.info("[BankController] L0 domain: {}, unsupportedFeature: {}",
                    domainResult.domain(), domainResult.unsupportedFeature());

            // 分发到L1 Service
            WorkflowOutput output;
            if (domainResult.isUnsupported()) {
                String feature = domainResult.unsupportedFeature() != null
                        ? domainResult.unsupportedFeature() : "该";
                output = WorkflowOutput.completed(null, feature + "功能暂不支持");
            } else {
                output = switch (domainResult.domain()) {
                    case "WEALTH" -> wealthService.handle(sessionId, userInput);
                    case "TRANSFER" -> transferService.handle(sessionId, userInput);
                    case "BILL" -> billService.handle(sessionId, userInput);
                    default -> chatService.handle(sessionId, userInput);
                };
            }

            // 记录到全局ChatMemory
            chatMemory.add(sessionId, new UserMessage(userInput));
            recordSystemReply(sessionId, output);

            return output;

        } catch (Exception e) {
            log.error("[BankController] Error processing chat", e);
            return WorkflowOutput.error("处理请求时出错: " + e.getMessage());
        }
    }

    // ==================== 会话管理 ====================

    @GetMapping("/state")
    public Map<String, Object> getState(@RequestParam String sessionId) {
        return stateManager.getSessionStateDescription(sessionId) != null
                ? Map.of("sessionId", sessionId,
                "state", stateManager.getSessionStateDescription(sessionId))
                : Map.of("sessionId", sessionId, "state", "empty");
    }

    @DeleteMapping("/session")
    public Map<String, String> clearSession(@RequestParam String sessionId) {
        stateManager.clearSession(sessionId);
        domainRouter.clearLastDomain(sessionId);
        chatMemory.clear(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }

    // ==================== 内部方法 ====================

    private void recordSystemReply(String sessionId, WorkflowOutput output) {
        ChatHistoryUtils.recordReply(chatMemory, sessionId, output, null);
    }
}
