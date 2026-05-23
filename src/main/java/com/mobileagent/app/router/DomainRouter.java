package com.mobileagent.app.router;

import com.mobileagent.app.util.ChatHistoryUtils;
import com.mobileagent.app.util.JsonParseUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L0领域路由器 - 确定性优先 + 模型兜底
 *
 * 路由优先级:
 * 1. 确定性路由: 关键词匹配 → 意图明确直接路由(0ms)
 * 2. 模型路由: chatHistory + lastActiveDomain → 意图不明确时调LLM
 *    2a. 先结合对话历史判断
 *    2b. 历史也判断不出 → lastActiveDomain兜底
 *
 * lastActiveDomain:
 * - L0自有状态，不依赖L1的AgentStateManager
 * - 当L0路由到非CHAT/UNSUPPORTED领域时记录
 * - 可配置过期时间(默认5分钟)
 *
 * 输出: WEALTH / TRANSFER / BILL / UNSUPPORTED / CHAT
 */
@Slf4j
@Component
public class DomainRouter {

    // ==================== 领域关键词 ====================

    private static final Set<String> BILL_KEYWORDS = Set.of(
            "账单", "明细", "消费", "支出", "收入", "收支", "开销", "流水");

    private static final Set<String> TRANSFER_KEYWORDS = Set.of(
            "转账", "转钱", "汇款", "打款", "付款", "转给", "打给", "赚钱给", "打钱给");

    private static final Set<String> WEALTH_KEYWORDS = Set.of(
            "理财", "投资", "收益", "基金", "推荐", "咨询", "解读");

    private static final Set<String> UNSUPPORTED_KEYWORDS = Set.of(
            "贷款", "信用卡", "活动", "积分", "开户", "挂失", "存款", "保险");

    // ==================== 实例字段 ====================

    private final ChatClient domainChatClient;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;
    private final int judgmentMaxPairs;
    private final long lastDomainExpireMinutes;

    /** L0自有状态: 最近活跃领域 (sessionId → LastDomainEntry) */
    private final Map<String, LastDomainEntry> lastActiveDomains = new ConcurrentHashMap<>();

    // ==================== 构造 ====================

    public DomainRouter(@Qualifier("domainChatClient") ChatClient domainChatClient,
                        ChatMemory chatMemory,
                        @org.springframework.beans.factory.annotation.Value("${routing.history.judgment-max-pairs:5}") int judgmentMaxPairs,
                        @org.springframework.beans.factory.annotation.Value("${session.last-domain.expire-minutes:5}") long lastDomainExpireMinutes) {
        this.domainChatClient = domainChatClient;
        this.chatMemory = chatMemory;
        this.objectMapper = new ObjectMapper();
        this.judgmentMaxPairs = judgmentMaxPairs;
        this.lastDomainExpireMinutes = lastDomainExpireMinutes;
    }

    // ==================== 路由结果 ====================

    public record DomainResult(String domain, String unsupportedFeature, double confidence, String rawResponse) {
        public boolean isUnsupported() {
            return "UNSUPPORTED".equals(domain);
        }
    }

    /** lastActiveDomain条目 */
    private record LastDomainEntry(String domain, Instant setAt) {}

    // ==================== 主入口 ====================

    /**
     * L0领域路由 - 确定性优先，模型兜底
     */
    public DomainResult route(String sessionId, String userInput) {
        // 1. 确定性路由: 关键词匹配
        DomainResult deterministic = routeDeterministic(userInput);
        if (deterministic != null) {
            updateLastDomain(sessionId, deterministic.domain());
            log.info("[DomainRouter] Deterministic: domain={} for input='{}'", deterministic.domain(), userInput);
            return deterministic;
        }

        // 2. 模型路由: chatHistory + lastActiveDomain
        try {
            String chatHistory = formatChatHistory(sessionId);
            String lastDomainContext = formatLastDomainContext(sessionId);
            String systemPrompt = buildDomainPrompt(userInput, chatHistory, lastDomainContext);

            long startMs = System.currentTimeMillis();

            String content = domainChatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[DomainRouter] LLM call completed in {}ms | input='{}'", elapsedMs, userInput);
            log.debug("[DomainRouter] LLM raw response: {}", content);

            DomainResult result = parseDomainResponse(content);
            log.info("[DomainRouter] Model resolved: domain={} (feature={}) for input='{}'", result.domain(), result.unsupportedFeature(), userInput);

            // 路由到非CHAT/UNSUPPORTED领域时更新lastDomain
            if (!"CHAT".equals(result.domain()) && !result.isUnsupported()) {
                updateLastDomain(sessionId, result.domain());
            }
            return result;

        } catch (Exception e) {
            log.error("[DomainRouter] LLM call failed, defaulting to CHAT", e);
            return new DomainResult("CHAT", null, 0.3, null);
        }
    }

    // ==================== 确定性路由 ====================

    /**
     * 确定性路由 - 关键词匹配，意图明确时直接返回
     *
     * 后续可扩展: 接入传统小模型、正则匹配等
     * @return 非null表示确定性命中，null表示需要模型判断
     */
    private DomainResult routeDeterministic(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;

        String input = userInput.trim();

        // 关键词匹配(按优先级: 先匹配UNSUPPORTED，避免被其他规则截胡)
        String keywordDomain = matchDomainKeywords(input);
        if (keywordDomain != null) {
            String unsupportedFeature = "UNSUPPORTED".equals(keywordDomain) ? extractUnsupportedFeature(input) : null;
            return new DomainResult(keywordDomain, unsupportedFeature, 1.0, "DETERMINISTIC:keyword");
        }

        // TODO: 规则/小模型判断短回答路由 (当前为空函数，后续接入)
        // 场景: 用户回答"稳健"/"娱乐"/"500"等短输入，无领域关键词
        // 应结合chatHistory判断: 历史问"风险偏好？"→当前"稳健"→WEALTH
        // 或结合lastActiveDomain兜底: 最近领域=WEALTH→当前"能源的"→WEALTH
        DomainResult ruleResult = routeByRules(input);
        if (ruleResult != null) {
            return ruleResult;
        }

        return null; // 需要模型判断
    }

    /**
     * 规则/小模型路由 - 结合chatHistory和lastActiveDomain判断短回答
     * 当前为空函数，后续接入小模型或规则引擎
     *
     * @return 非null表示规则命中，null表示需要模型判断
     */
    private DomainResult routeByRules(String input) {
        // TODO: 接入小模型或规则引擎
        // 输入: sessionId(用于读chatHistory/lastActiveDomain), input(用户当前消息)
        // 逻辑:
        //   1. 读取chatHistory最后一条assistant消息，提取系统提问的领域
        //   2. 如果用户输入是在回答系统提问 → 路由到对应领域
        //   3. 否则检查lastActiveDomain兜底
        // 输出: DomainResult 或 null(无法判断)
        return null;
    }

    /**
     * 关键词匹配 - 检查输入是否包含领域关键词
     * @return 命中的领域名，null表示无匹配
     */
    private String matchDomainKeywords(String input) {
        // UNSUPPORTED优先检查(避免"贷款"等被其他规则截胡)
        if (containsAny(input, UNSUPPORTED_KEYWORDS)) return "UNSUPPORTED";
        if (containsAny(input, TRANSFER_KEYWORDS)) return "TRANSFER";
        if (containsAny(input, BILL_KEYWORDS)) return "BILL";
        if (containsAny(input, WEALTH_KEYWORDS)) return "WEALTH";
        return null;
    }

    /** 检查input是否包含keywords中任一关键词 */
    private boolean containsAny(String input, Set<String> keywords) {
        for (String keyword : keywords) {
            if (input.contains(keyword)) return true;
        }
        return false;
    }

    /** 从UNSUPPORTED关键词中提取具体功能名 */
    private String extractUnsupportedFeature(String input) {
        for (String keyword : UNSUPPORTED_KEYWORDS) {
            if (input.contains(keyword)) return keyword;
        }
        return "该";
    }

    // ==================== lastActiveDomain管理 ====================

    private void updateLastDomain(String sessionId, String domain) {
        lastActiveDomains.put(sessionId, new LastDomainEntry(domain, Instant.now()));
        log.debug("[DomainRouter] Updated lastDomain: session={}, domain={}", sessionId, domain);
    }

    private String getLastDomain(String sessionId) {
        LastDomainEntry entry = lastActiveDomains.get(sessionId);
        if (entry == null) return null;
        if (entry.setAt().plusSeconds(lastDomainExpireMinutes * 60).isBefore(Instant.now())) {
            lastActiveDomains.remove(sessionId);
            return null;
        }
        return entry.domain();
    }

    /** 格式化lastActiveDomain为prompt文本 */
    private String formatLastDomainContext(String sessionId) {
        String domain = getLastDomain(sessionId);
        if (domain == null) return "(无)";
        return "最近活跃领域: " + domain;
    }

    /** 定时清理过期的lastActiveDomain，每分钟执行一次 */
    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredLastDomains() {
        Instant now = Instant.now();
        lastActiveDomains.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().setAt().plusSeconds(lastDomainExpireMinutes * 60).isBefore(now);
            if (expired) {
                log.debug("[DomainRouter] Expired lastDomain: session={}", entry.getKey());
            }
            return expired;
        });
    }

    /** 清除会话的lastDomain (供clearSession时调用) */
    public void clearLastDomain(String sessionId) {
        lastActiveDomains.remove(sessionId);
    }

    // ==================== Prompt构建 ====================

    private String formatChatHistory(String sessionId) {
        return ChatHistoryUtils.formatAndTruncate(chatMemory, sessionId, judgmentMaxPairs);
    }

    private String buildDomainPrompt(String userInput, String chatHistory, String lastDomainContext) {
        String template = loadTemplate("prompts/l0-domain.st");
        return template
                .replace("{message}", userInput)
                .replace("{chat_history}", chatHistory)
                .replace("{last_domain_context}", lastDomainContext);
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
        return switch (domain.toUpperCase()) {
            case "WEALTH" -> "WEALTH";
            case "TRANSFER" -> "TRANSFER";
            case "BILL" -> "BILL";
            case "UNSUPPORTED" -> "UNSUPPORTED";
            default -> "CHAT";
        };
    }

    // ==================== 模板加载 ====================

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[DomainRouter] Failed to load template: {}, using fallback", path);
            return getDefaultDomainPrompt();
        }
    }

    private String getDefaultDomainPrompt() {
        return """
            你是手机银行领域路由器。判断用户当前消息属于哪个业务领域。

            五个领域: WEALTH(理财), TRANSFER(转账), BILL(账单), UNSUPPORTED(银行功能但暂不支持), CHAT(闲聊)

            当前会话状态:
            {last_domain_context}

            ===对话历史===
            {chat_history}
            ===对话历史结束===

            ===用户当前消息===
            {message}
            ===当前消息结束===

            判断规则:
            1. 当前消息意图清晰 → 按当前消息路由:
               含"账单/明细/消费/支出/收入/收支/开销/流水" → BILL
               含"转账/转钱/汇款/打款/付款/转给/打给" → TRANSFER
               含"理财/投资/收益/基金/推荐/咨询/解读" → WEALTH
               含"贷款/信用卡/活动/积分/开户/挂失/存款/保险" → UNSUPPORTED
            2. 当前消息意图不明确(短回答，无领域关键词) → 结合对话历史判断:
               历史问"转给谁？"，当前"张三" → TRANSFER
               历史问"风险偏好？"，当前"稳健" → WEALTH
            3. 对话历史也无法判断 → 结合会话状态中的最近活跃领域:
               最近领域=WEALTH，当前"能源的" → WEALTH
               最近领域=TRANSFER，当前"500" → TRANSFER
            4. 只有取消词(算了/取消/不X了)，无新意图 → 路由到最近活跃领域

            !!! 绝对禁止: 当前消息包含某领域关键词时，因为历史在其他流程就路由到历史领域 !!!

            严格输出JSON:
            {
              "domain": "WEALTH | TRANSFER | BILL | UNSUPPORTED | CHAT",
              "unsupported_feature": "当domain=UNSUPPORTED时填写具体功能名，否则null",
              "confidence": 0.0-1.0
            }
            """;
    }
}
