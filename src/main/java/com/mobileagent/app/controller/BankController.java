package com.mobileagent.app.controller;

import com.mobileagent.app.data.ChunkType;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.domain.DomainHandler;
import com.mobileagent.app.execution.GlobalSessionStore;
import com.mobileagent.app.execution.StreamingChatMemoryWriter;
import com.mobileagent.app.infrastructure.SseOutputAdapter;
import com.mobileagent.app.router.DomainServiceRegistry;
import com.mobileagent.app.router.DomainRouter;
import com.mobileagent.app.util.ChatHistoryUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
 * - L1返回REROUTE chunk时，排除当前域重新路由
 * - 最多重试MAX_REROUTE次，超限后CHAT域兜底
 * - REROUTE不对用户可见，只记录最终结果到全局ChatMemory
 *
 * Content Negotiation:
 * - Accept: text/event-stream → SSE (SseEmitter, OpenAI兼容格式)
 * - Accept: application/json  → JSON (blockLast取终结chunk)
 *
 * ChatMemory写入:
 * - UserMessage: 管道入口写入
 * - CHUNK: 累积到StringBuilder，不写ChatMemory
 * - 终结chunk: 拼接累积文本 → chatMemory.add(AssistantMessage)
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
    private final GlobalSessionStore globalSessionStore;
    private final SseOutputAdapter sseAdapter;
    private final int globalContextMaxPairs;

    public BankController(DomainRouter domainRouter,
                          DomainServiceRegistry domainServiceRegistry,
                          ChatMemory chatMemory,
                          GlobalSessionStore globalSessionStore,
                          SseOutputAdapter sseAdapter,
                          @org.springframework.beans.factory.annotation.Value("${routing.history.global-context-max-pairs:10}") int globalContextMaxPairs) {
        this.domainRouter = domainRouter;
        this.domainServiceRegistry = domainServiceRegistry;
        this.chatMemory = chatMemory;
        this.globalSessionStore = globalSessionStore;
        this.sseAdapter = sseAdapter;
        this.globalContextMaxPairs = globalContextMaxPairs;
    }

    @PostMapping(value = "/chat", produces = {
            MediaType.TEXT_EVENT_STREAM_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public Object chat(@RequestParam String sessionId,
                       @RequestBody Map<String, String> req,
                       @RequestHeader(value = "Accept", defaultValue = MediaType.APPLICATION_JSON_VALUE) String accept,
                       HttpServletResponse response) {
        String userInput = req.get("message");
        log.info("[BankController] >>> chat: sessionId={}, message={}", sessionId, userInput);

        if (userInput == null || userInput.isBlank()) {
            StreamChunk error = StreamChunk.error("Message cannot be empty");
            if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                return sseAdapter.toSse(Flux.just(error), response);
            }
            return error.toWorkflowOutput();
        }

        Flux<StreamChunk> pipeline = buildChatPipeline(sessionId, userInput);

        if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return sseAdapter.toSse(pipeline, response);
        }

        return sseAdapter.toJson(pipeline);
    }

    // ==================== 管道构建 ====================

    private Flux<StreamChunk> buildChatPipeline(String sessionId, String userInput) {
        Set<String> excludedDomains = new HashSet<>();
        AtomicInteger rerouteCount = new AtomicInteger(0);

        // UserMessage: 管道入口写入
        StreamingChatMemoryWriter.writeUserMessage(chatMemory, sessionId, userInput);

        // AssistantMessage: 累积CHUNK + 终结chunk时写入
        StreamingChatMemoryWriter.AssistantWriter assistantWriter =
                new StreamingChatMemoryWriter.AssistantWriter(chatMemory, sessionId);

        return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount)
                .doOnNext(assistantWriter::onChunk);
    }

    private Flux<StreamChunk> dispatchWithReroute(String sessionId, String userInput,
                                                   Set<String> excludedDomains,
                                                   AtomicInteger rerouteCount) {
        DomainRouter.DomainResult domainResult = domainRouter.route(sessionId, userInput, excludedDomains);
        String currentDomain = domainResult.domain();

        log.info("[BankController] L0 domain: {}, unsupportedFeature: {}, excluded: {}, rerouteCount: {}",
                currentDomain, domainResult.unsupportedFeature(), excludedDomains, rerouteCount.get());

        // L0路由到了被排除的域 → LLM没尊重排除提示，直接算失败
        if (excludedDomains.contains(currentDomain)) {
            rerouteCount.incrementAndGet();
            log.warn("[BankController] L0 routed to excluded domain={}, counting as failed attempt ({}/{})",
                    currentDomain, rerouteCount.get(), maxRerouteAttempts);
            if (rerouteCount.get() >= maxRerouteAttempts) {
                return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求，请换个方式描述。"));
            }
            return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount);
        }

        String globalChatHistory = ChatHistoryUtils.formatAndTruncate(
                chatMemory, sessionId, globalContextMaxPairs);

        // 分发到L1 DomainHandler
        Flux<StreamChunk> resultFlux = dispatchToDomain(domainResult, sessionId, userInput, globalChatHistory);

        return resultFlux.flatMap(chunk -> {
            if (chunk.getType() != ChunkType.REROUTE) {
                return Flux.just(chunk);
            }
            excludedDomains.add(currentDomain);
            rerouteCount.incrementAndGet();
            log.info("[BankController] REROUTE #{}: domain={} excluded, rerouteIntent={}, rerouteHint={}, excluded={}",
                    rerouteCount.get(), currentDomain, chunk.getRerouteIntent(), chunk.getRerouteHint(), excludedDomains);
            if (rerouteCount.get() >= maxRerouteAttempts) {
                return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求，请换个方式描述。"));
            }
            return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount);
        });
    }

    // ==================== 领域分发 ====================

    /**
     * 分发到L1 DomainHandler
     */
    private Flux<StreamChunk> dispatchToDomain(DomainRouter.DomainResult domainResult,
                                                String sessionId, String userInput,
                                                String globalChatHistory) {
        if (domainResult.isUnsupported()) {
            String feature = domainResult.unsupportedFeature() != null
                    ? domainResult.unsupportedFeature() : "该";
            return Flux.just(StreamChunk.complete(null, feature + "功能暂不支持"));
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

        return Flux.just(StreamChunk.complete(null, "功能暂不支持"));
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
        globalSessionStore.clearSession(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }
}
