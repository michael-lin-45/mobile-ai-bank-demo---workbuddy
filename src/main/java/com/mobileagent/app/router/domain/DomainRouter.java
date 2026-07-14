package com.mobileagent.app.router.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.data.SubGraphProperties;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.observability.AgentSpanContext;
import com.mobileagent.app.observability.ObservabilityMetrics;
import com.mobileagent.app.orchestration.OrchestrationStateService;
import com.mobileagent.app.orchestration.model.OrchestrationStatus;
import com.mobileagent.app.util.JsonParseUtils;
import com.mobileagent.app.util.TemplateUtils;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * L0领域路由器 - 确定性优先 + 模型兜底
 *
 * 路由优先级:
 * 1. 确定性路由: 关键词匹配 → 意图明确直接路由(0ms)
 * 2. 模型路由: chatHistory + lastActiveDomain → 意图不明确时调LLM
 *    2a. 先结合对话历史判断
 *    2b. 历史也判断不出 → lastActiveDomain兜底
 *
 * 领域关键词从 application.yml 的 routing.domains 读取，
 * 新增领域只需改yml，无需改Java代码。
 *
 * 合并说明: 以主分支A的逻辑为骨架(ORCHESTRATION域处理 + 编排激活强制路由ORCHESTRATION
 * + 确定性路由仅短路UNSUPPORTED)，保留本分支B的可观测埋点
 * (AgentSpanContext托管span / ObservabilityMetrics路由与意图计数 / OTel L0 span)。
 */
@Slf4j
@Component
public class DomainRouter {

    private final Map<String, Set<String>> domainKeywords;

    private static final String UNSUPPORTED_DOMAIN = "UNSUPPORTED";
    private static final String ORCHESTRATION_DOMAIN = "ORCHESTRATION";

    private final ChatClient domainChatClient;
    private final GlobalSessionStateStore globalSessionStore;
    private final ObservabilityMetrics obsMetrics;
    private final ObjectMapper objectMapper;
    private final int l0MaxPairs;
    private final long lastDomainExpireMinutes;
    private final OrchestrationStateService orchStateService;

    public DomainRouter(@Qualifier("domainChatClient") ChatClient domainChatClient,
                        GlobalSessionStateStore globalSessionStore,
                        ObservabilityMetrics obsMetrics,
                        SubGraphProperties routingProperties,
                        ObjectMapper objectMapper,
                        @org.springframework.beans.factory.annotation.Value("${routing.history.l0-max-pairs:10}") int l0MaxPairs,
                        @org.springframework.beans.factory.annotation.Value("${session.last-domain.expire-minutes:5}") long lastDomainExpireMinutes,
                        OrchestrationStateService orchStateService) {
        this.domainChatClient = domainChatClient;
        this.globalSessionStore = globalSessionStore;
        this.obsMetrics = obsMetrics;
        this.objectMapper = objectMapper;
        this.l0MaxPairs = l0MaxPairs;
        this.lastDomainExpireMinutes = lastDomainExpireMinutes;
        this.orchStateService = orchStateService;

        Map<String, Set<String>> keywords = new LinkedHashMap<>();
        for (var entry : routingProperties.getDomains().entrySet()) {
            keywords.put(entry.getKey(), new HashSet<>(entry.getValue().getKeywords()));
        }
        this.domainKeywords = Collections.unmodifiableMap(keywords);
        log.info("[DomainRouter] Loaded domain keywords from config: {}", domainKeywords.keySet());
    }

    // ==================== 路由结果 ====================

    public record DomainResult(String domain, String unsupportedFeature, double confidence, String rawResponse) {
        public boolean isUnsupported() {
            return "UNSUPPORTED".equals(domain);
        }
    }

    // ==================== 主入口 ====================

    public DomainResult route(String sessionId, String userInput, Set<String> excludedDomains) {
        // Orchestration-aware routing: if orchestration is active (WAITING_USER), force route to ORCHESTRATION
        OrchestrationStateService.OrchestrationState orchState = orchStateService.getOrCheckExpired(sessionId);
        if (orchState != null && orchState.getStatus() == OrchestrationStatus.WAITING_USER) {
            log.info("[DomainRouter] Orchestration active (WAITING_USER), forcing route to ORCHESTRATION for session={}", sessionId);
            obsMetrics.recordIntentRecognized(ORCHESTRATION_DOMAIN, 1.0);
            obsMetrics.recordRouterDecision("L0", "orch_active", ORCHESTRATION_DOMAIN);
            return new DomainResult(ORCHESTRATION_DOMAIN, null, 1.0, "ORCHESTRATION:active");
        }

        // 1. 确定性路由: 关键词匹配 (仅用于UNSUPPORTED等无需LLM的场景)
        // 正常意图判断交给LLM，避免关键词误判多意图
        DomainResult deterministic = routeDeterministic(userInput);
        if (deterministic != null && UNSUPPORTED_DOMAIN.equals(deterministic.domain())
                && !excludedDomains.contains(deterministic.domain())) {
            // ── 可观测补全（保留B）──────────────────────────────────────────────
            // 确定性路由虽不调 LLM，仍显式创建 L0 span，保证 L0 计数 = 请求数
            Span l0Span = GlobalOpenTelemetry.getTracer("obs-chat-model")
                    .spanBuilder("L0:DomainRouter")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            try {
                l0Span.setAttribute("agent.name", "DomainRouter");
                l0Span.setAttribute("agent.layer", "L0");
                l0Span.setAttribute("intent", deterministic.domain());
                l0Span.setAttribute("routing.mode", "deterministic");
                l0Span.setAttribute("session_id", sessionId != null ? sessionId.trim() : "");
            } finally {
                l0Span.end();
            }

            updateLastDomain(sessionId, deterministic.domain());
            log.info("[DomainRouter] Deterministic (UNSUPPORTED): domain={} for input='{}'", deterministic.domain(), userInput);
            obsMetrics.recordRouterHit();
            obsMetrics.recordIntentRecognized(deterministic.domain(), deterministic.confidence());
            obsMetrics.recordRouterDecision("L0", "hit", deterministic.domain());
            return deterministic;
        }

        // 2. 模型路由
        try {
            String chatHistory = formatChatHistory(sessionId);
            String lastDomainContext = formatLastDomainContext(sessionId);
            String excludedDomainsContext = formatExcludedDomainsContext(excludedDomains);
            String systemPrompt = buildDomainPrompt(userInput, chatHistory, lastDomainContext, excludedDomainsContext);

            long startMs = System.currentTimeMillis();

            var llmSample = obsMetrics.startLlmTimer();
            // 托管模式：domain 在 LLM 返回后才解析，需延迟回填到 span.intent
            AgentSpanContext ctx = AgentSpanContext.setWithHeldSpan("L0", "DomainRouter", null, sessionId, null);
            String content;
            try {
                content = domainChatClient.prompt()
                        .system(systemPrompt)
                        .user(userInput)
                        .call()
                        .content();
            } catch (Exception e) {
                // LLM 调用失败：ObsChatModel 已在异常路径自行 end span；此处回填兜底领域(CHAT)并清理上下文
                ctx.commitIntent("CHAT", java.util.Map.of());
                throw e;
            }
            obsMetrics.stopLlmTimer(llmSample);
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[DomainRouter] LLM call completed in {}ms | input='{}'", elapsedMs, userInput);
            log.info("[DomainRouter] LLM raw response: {}", content);

            DomainResult result = parseDomainResponse(content);
            log.info("[DomainRouter] Model resolved: domain={} (feature={}) for input='{}'", result.domain(), result.unsupportedFeature(), userInput);

            obsMetrics.recordRouterLlmFallback();
            obsMetrics.recordIntentRecognized(result.domain(), result.confidence());
            obsMetrics.recordRouterDecision("L0", "llm_fallback", result.domain());

            if (!"CHAT".equals(result.domain()) && !result.isUnsupported()) {
                updateLastDomain(sessionId, result.domain());
            }
            // 回填真实领域识别结果到 span.intent（托管模式结束 span）
            ctx.commitIntent(result.domain(), java.util.Map.of());
            return result;

        } catch (Exception e) {
            log.error("[DomainRouter] LLM call failed, defaulting to CHAT", e);
            obsMetrics.recordRouterFail();
            obsMetrics.recordRouterDecision("L0", "fail", "CHAT");
            return new DomainResult("CHAT", null, 0.3, null);
        } finally {
            // 兜底：若上方未成功 commit（如埋点/状态更新异常），仍结束 held span 并清理 ThreadLocal，防止 span 泄漏
            AgentSpanContext.clear();
        }
    }

    // ==================== 确定性路由 ====================

    private DomainResult routeDeterministic(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;

        String input = userInput.trim();

        String keywordDomain = matchDomainKeywords(input);
        if (keywordDomain != null) {
            String unsupportedFeature = "UNSUPPORTED".equals(keywordDomain) ? extractUnsupportedFeature(input) : null;
            return new DomainResult(keywordDomain, unsupportedFeature, 1.0, "DETERMINISTIC:keyword");
        }

        return null;
    }

    private String matchDomainKeywords(String input) {
        Set<String> unsupportedKw = domainKeywords.get(UNSUPPORTED_DOMAIN);
        if (unsupportedKw != null && containsAny(input, unsupportedKw)) return UNSUPPORTED_DOMAIN;

        List<String> hitDomains = new ArrayList<>();
        for (var entry : domainKeywords.entrySet()) {
            if (UNSUPPORTED_DOMAIN.equals(entry.getKey())) continue;
            if (containsAny(input, entry.getValue())) {
                hitDomains.add(entry.getKey());
            }
        }

        if (hitDomains.size() > 1) {
            log.info("[DomainRouter] Multi-domain keywords hit {}, routing to ORCHESTRATION for input='{}'", hitDomains, input);
            return ORCHESTRATION_DOMAIN;
        }

        if (hitDomains.size() == 1) return hitDomains.get(0);
        return null;
    }

    private boolean containsAny(String input, Set<String> keywords) {
        for (String keyword : keywords) {
            if (input.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * Find which domains have keywords matching the given input.
     * Used by OrchestrationAgent for L1 context classification (inherit vs preserve).
     *
     * @param userInput user input to match against domain keywords
     * @return set of domain names whose keywords match the input
     */
    public Set<String> findDomainsInInput(String userInput) {
        if (userInput == null || userInput.isBlank()) return Set.of();
        String input = userInput.trim();
        Set<String> result = new LinkedHashSet<>();
        for (var entry : domainKeywords.entrySet()) {
            if (UNSUPPORTED_DOMAIN.equals(entry.getKey())) continue;
            if (containsAny(input, entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private String extractUnsupportedFeature(String input) {
        Set<String> unsupportedKw = domainKeywords.get(UNSUPPORTED_DOMAIN);
        if (unsupportedKw != null) {
            for (String keyword : unsupportedKw) {
                if (input.contains(keyword)) return keyword;
            }
        }
        return "该";
    }

    // ==================== lastDomain管理 ====================

    private void updateLastDomain(String sessionId, String domain) {
        long expireAt = System.currentTimeMillis() + lastDomainExpireMinutes * 60 * 1000;
        globalSessionStore.getOrCreate(sessionId).setLastDomain(domain, expireAt);
        log.debug("[DomainRouter] Updated lastDomain: session={}, domain={}", sessionId, domain);
    }

    private String formatLastDomainContext(String sessionId) {
        String domain = globalSessionStore.getOrCreate(sessionId).getLastDomain();
        if (domain == null) return "(无)";
        return "最近活跃领域: " + domain;
    }

    public void clearLastDomain(String sessionId) {
        globalSessionStore.getOrCreate(sessionId).clearLastDomain();
    }

    private String formatExcludedDomainsContext(Set<String> excludedDomains) {
        if (excludedDomains == null || excludedDomains.isEmpty()) return "(无)";
        return "以下领域已被排除，不要路由到: " + String.join(", ", excludedDomains);
    }

    // ==================== Prompt构建 ====================

    private String formatChatHistory(String sessionId) {
        return globalSessionStore.getOrCreate(sessionId).formatRecentMessages(l0MaxPairs);
    }

    private String buildDomainPrompt(String userInput, String chatHistory, String lastDomainContext,
                                      String excludedDomainsContext) {
        String template = loadTemplate("prompts/l0-domain.st");
        return template
                .replace("{message}", userInput)
                .replace("{chat_history}", chatHistory)
                .replace("{last_domain_context}", lastDomainContext)
                .replace("{excluded_domains_context}", excludedDomainsContext);
    }

    // ==================== 响应解析 ====================

    private DomainResult parseDomainResponse(String content) {
        try {
            String json = JsonParseUtils.extractJson(content);
            var node = objectMapper.readTree(json);

            String domain = node.has("domain") ? node.get("domain").asText() : "CHAT";
            domain = normalizeDomain(domain);

            String unsupportedFeature = null;
            if ("UNSUPPORTED".equals(domain) && node.has("unsupported_feature") && !node.get("unsupported_feature").isNull()) {
                unsupportedFeature = node.get("unsupported_feature").asText();
            }

            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;
            return new DomainResult(domain, unsupportedFeature, confidence, content);

        } catch (Exception e) {
            log.warn("[DomainRouter] Failed to parse domain response, raw='{}'", content, e);
            return new DomainResult("CHAT", null, 0.3, content);
        }
    }

    private String normalizeDomain(String domain) {
        if (domain == null) return "CHAT";
        String upper = domain.toUpperCase();
        if (domainKeywords.containsKey(upper)) return upper;
        if (ORCHESTRATION_DOMAIN.equals(upper)) return upper;
        return "CHAT";
    }

    // ==================== 模板加载 ====================

    private String loadTemplate(String path) {
        return TemplateUtils.loadTemplate(path);
    }
}
