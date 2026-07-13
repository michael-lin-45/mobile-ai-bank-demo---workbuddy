package com.mobileagent.app.controller;

import com.mobileagent.app.data.ChunkType;
import com.mobileagent.app.data.StreamChunk;
import com.mobileagent.app.domain.DomainHandler;
import com.mobileagent.app.memory.GlobalSessionContext;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.infrastructure.SseOutputAdapter;
import com.mobileagent.app.observability.ObservabilityMetrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import com.mobileagent.app.router.registry.DomainServiceRegistry;
import com.mobileagent.app.router.domain.DomainRouter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 银行主控制器 - L0调度层
 *
 * 架构 (L0→L1→L2):
 * 1. L0: DomainRouter判断领域(WEALTH/TRANSFER/BILL/UNSUPPORTED/CHAT)
 * 2. 分发到L1 Service
 * 3. L1: 各领域Service内部路由(FOLLOW/RESUME等) + 自管状态
 * 4. L2: 子智能体Graph执行
 *
 * REROUTE机制:
 * - L1返回REROUTE chunk时，排除当前域重新路由
 * - 最多重试MAX_REROUTE次，超限后CHAT域兜底
 * - REROUTE不对用户可见，只记录最终结果到messages
 *
 * 消息写入:
 * - UserMessage: 管道入口写入
 * - CHUNK: 累积到StringBuilder，不写messages
 * - 终结chunk: 拼接累积文本 → addAssistantMessage()
 */
@Slf4j
@RestController
@RequestMapping("/api/bank")
public class BankController {

    @org.springframework.beans.factory.annotation.Value("${routing.reroute.max-attempts:2}")
    private int maxRerouteAttempts;

    private final DomainRouter domainRouter;
    private final DomainServiceRegistry domainServiceRegistry;
    private final GlobalSessionStateStore globalSessionStore;
    private final ObservabilityMetrics obsMetrics;
    private final SseOutputAdapter sseAdapter;
    private final com.mobileagent.app.observability.SessionBridge sessionBridge;

    public BankController(DomainRouter domainRouter,
                          DomainServiceRegistry domainServiceRegistry,
                          GlobalSessionStateStore globalSessionStore,
                          ObservabilityMetrics obsMetrics,
                          SseOutputAdapter sseAdapter,
                          com.mobileagent.app.observability.SessionBridge sessionBridge) {
        this.domainRouter = domainRouter;
        this.domainServiceRegistry = domainServiceRegistry;
        this.globalSessionStore = globalSessionStore;
        this.obsMetrics = obsMetrics;
        this.sseAdapter = sseAdapter;
        this.sessionBridge = sessionBridge;
    }

    @PostMapping(value = "/chat", produces = {
            MediaType.TEXT_EVENT_STREAM_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public Object chat(@RequestParam String sessionId,
                       @RequestBody Map<String, String> req,
                       @RequestHeader(value = "Accept", defaultValue = MediaType.APPLICATION_JSON_VALUE) String accept,
                       HttpServletResponse response) {
        if (sessionId != null) sessionId = sessionId.trim();
        String userInput = req.get("message");
        log.info("[BankController] >>> chat: sessionId={}, message={}", sessionId, userInput);

        if (userInput == null || userInput.isBlank()) {
            StreamChunk error = StreamChunk.error("Message cannot be empty");
            if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                return sseAdapter.toSse(Flux.just(error), response);
            }
            return error.toWorkflowOutput();
        }

        // 意图识别调用入口
        Flux<StreamChunk> pipeline = buildChatPipeline(sessionId, userInput);

        if (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return sseAdapter.toSse(pipeline, response);
        }

        return sseAdapter.toJson(pipeline);
    }

    // ==================== 管道构建 ====================

    private Flux<StreamChunk> buildChatPipeline(String sessionId, String userInput) {
        long pipelineStartMs = System.currentTimeMillis();
        Set<String> excludedDomains = new HashSet<>();
        AtomicInteger rerouteCount = new AtomicInteger(0);

        GlobalSessionContext ctx = globalSessionStore.getOrCreate(sessionId);
        ctx.addUserMessage(userInput);

        // 埋点：会话创建（首次请求时 session 可能刚创建）
        obsMetrics.recordSessionCreated();

        // 创建Accumulator用于积累Chunk的回复
        AtomicReference<String> currentDomainRef = new AtomicReference<>();
        AssistantAccumulator accumulator = new AssistantAccumulator(ctx, currentDomainRef);

        return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount, currentDomainRef)
                .doOnNext(accumulator::onChunk)
                .doOnTerminate(() -> {
                    // 会话桥接：异步写入可观测后端 sessions 表
                    String reply = accumulator.getFullReply();
                    if (reply != null && !reply.isEmpty()) {
                        sessionBridge.reportSession(sessionId, userInput, reply,
                                currentDomainRef.get() != null ? currentDomainRef.get() : "CHAT",
                                currentDomainRef.get() != null ? "L0→L1→L2(" + currentDomainRef.get() + ")" : "L0→L1(CHAT)",
                                0.0,
                                System.currentTimeMillis() - pipelineStartMs,
                                0, getCurrentTraceId(), "COMPLETED");
                    }
                });
    }

    private Flux<StreamChunk> dispatchWithReroute(String sessionId, String userInput,
                                                   Set<String> excludedDomains,
                                                   AtomicInteger rerouteCount,
                                                   AtomicReference<String> currentDomainRef) {
        // L0层意图路由
        DomainRouter.DomainResult domainResult = domainRouter.route(sessionId, userInput, excludedDomains);
        String currentDomain = domainResult.domain();
        currentDomainRef.set(currentDomain);

        log.info("[BankController] L0 domain: {}, unsupportedFeature: {}, excluded: {}, rerouteCount: {}",
                currentDomain, domainResult.unsupportedFeature(), excludedDomains, rerouteCount.get());

        if (excludedDomains.contains(currentDomain)) {
            rerouteCount.incrementAndGet();
            obsMetrics.recordReroute(currentDomain);
            log.warn("[BankController] L0 routed to excluded domain={}, counting as failed attempt ({}/{})",
                    currentDomain, rerouteCount.get(), maxRerouteAttempts);
            if (rerouteCount.get() >= maxRerouteAttempts) {
                return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求，请换个方式描述。"));
            }
            return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount, currentDomainRef);
        }

        Flux<StreamChunk> resultFlux = dispatchToDomain(domainResult, sessionId, userInput);

        return resultFlux.flatMap(chunk -> {
            if (chunk.getType() != ChunkType.REROUTE) {
                return Flux.just(chunk);
            }
            excludedDomains.add(currentDomain);
            rerouteCount.incrementAndGet();
            obsMetrics.recordReroute(currentDomain);
            log.info("[BankController] REROUTE #{}: domain={} excluded, rerouteIntent={}, rerouteHint={}, excluded={}",
                    rerouteCount.get(), currentDomain, chunk.getRerouteIntent(), chunk.getRerouteHint(), excludedDomains);
            if (rerouteCount.get() >= maxRerouteAttempts) {
                return Flux.just(StreamChunk.complete(null, "抱歉，暂时无法识别您的请求，请换个方式描述。"));
            }
            return dispatchWithReroute(sessionId, userInput, excludedDomains, rerouteCount, currentDomainRef);
        });
    }

    // ==================== 领域分发 ====================

    private Flux<StreamChunk> dispatchToDomain(DomainRouter.DomainResult domainResult,
                                                String sessionId, String userInput) {
        if (domainResult.isUnsupported()) {
            String feature = domainResult.unsupportedFeature() != null
                    ? domainResult.unsupportedFeature() : "该";
            return Flux.just(StreamChunk.complete(null, feature + "功能暂不支持"));
        }

        DomainHandler handler = domainServiceRegistry.getHandler(domainResult.domain());
        if (handler != null) {
            return handler.handle(sessionId, userInput);
        }

        log.warn("[BankController] No handler registered for domain: {}, falling back to CHAT", domainResult.domain());
        DomainHandler chatHandler = domainServiceRegistry.getHandler("CHAT");
        if (chatHandler != null) {
            return chatHandler.handle(sessionId, userInput);
        }

        return Flux.just(StreamChunk.complete(null, "功能暂不支持"));
    }

    // ==================== 会话管理 ====================

    @GetMapping("/state")
    public Map<String, Object> getState(@RequestParam String sessionId) {
        if (sessionId != null) sessionId = sessionId.trim();
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

    @DeleteMapping("/session")
    public Map<String, String> clearSession(@RequestParam String sessionId) {
        if (sessionId != null) sessionId = sessionId.trim();
        for (String domain : domainServiceRegistry.getDomainNames()) {
            DomainHandler handler = domainServiceRegistry.getHandler(domain);
            if (handler instanceof com.mobileagent.app.domain.AbstractDomainService ads) {
                ads.clearSession(sessionId);
            }
        }
        domainRouter.clearLastDomain(sessionId);
        globalSessionStore.clearSession(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }

    /** Get current OTel traceId from context */
    private String getCurrentTraceId() {
        try {
            Span currentSpan = Span.current();
            if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
                return currentSpan.getSpanContext().getTraceId();
            }
        } catch (Exception e) {
            log.debug("[BankController] Failed to get traceId: {}", e.getMessage());
        }
        return "";
    }

    // ==================== 流式助手消息累积器 ====================

    /**
     * 流式助手消息累积器
     *
     * 累积 CHUNK → 终结 chunk 时写入 messages
     */
    public static class AssistantAccumulator {
        private final GlobalSessionContext ctx;
        private final AtomicReference<String> domainRef;
        private final StringBuilder accumulator = new StringBuilder();
        /** 非流式场景下 terminal chunk 的 replyContent（getFullReply 兜底用） */
        private String lastTerminalReply = null;

        public AssistantAccumulator(GlobalSessionContext ctx, AtomicReference<String> domainRef) {
            this.ctx = ctx;
            this.domainRef = domainRef;
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
                    ctx.addAssistantMessage(fullReply);
                    // 缓存 terminal chunk 的内容，确保非流式场景下 getFullReply() 有值
                    if (accumulator.length() == 0) {
                        this.lastTerminalReply = fullReply;
                    }
                }
            }
        }

        public String getFullReply() {
            if (accumulator.length() > 0) return accumulator.toString();
            return lastTerminalReply != null ? lastTerminalReply : "";
        }
    }
}
