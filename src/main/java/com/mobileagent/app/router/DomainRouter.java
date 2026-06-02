package com.mobileagent.app.router;

import com.mobileagent.app.config.RoutingProperties;
import com.mobileagent.app.execution.GlobalSessionStore;
import com.mobileagent.app.util.JsonParseUtils;
import com.mobileagent.app.util.TemplateUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Slf4j
@Component
public class DomainRouter {

    private final Map<String, Set<String>> domainKeywords;

    private static final String UNSUPPORTED_DOMAIN = "UNSUPPORTED";

    private final ChatClient domainChatClient;
    private final GlobalSessionStore globalSessionStore;
    private final ObjectMapper objectMapper;
    private final int l0MaxPairs;
    private final long lastDomainExpireMinutes;

    public DomainRouter(@Qualifier("domainChatClient") ChatClient domainChatClient,
                        GlobalSessionStore globalSessionStore,
                        RoutingProperties routingProperties,
                        @org.springframework.beans.factory.annotation.Value("${routing.history.l0-max-pairs:10}") int l0MaxPairs,
                        @org.springframework.beans.factory.annotation.Value("${session.last-domain.expire-minutes:5}") long lastDomainExpireMinutes) {
        this.domainChatClient = domainChatClient;
        this.globalSessionStore = globalSessionStore;
        this.objectMapper = new ObjectMapper();
        this.l0MaxPairs = l0MaxPairs;
        this.lastDomainExpireMinutes = lastDomainExpireMinutes;

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
        // 1. 确定性路由: 关键词匹配
        DomainResult deterministic = routeDeterministic(userInput);
        if (deterministic != null && !excludedDomains.contains(deterministic.domain())) {
            updateLastDomain(sessionId, deterministic.domain());
            log.info("[DomainRouter] Deterministic: domain={} for input='{}'", deterministic.domain(), userInput);
            return deterministic;
        }

        // 2. 模型路由
        try {
            String chatHistory = formatChatHistory(sessionId);
            String lastDomainContext = formatLastDomainContext(sessionId);
            String excludedDomainsContext = formatExcludedDomainsContext(excludedDomains);
            String systemPrompt = buildDomainPrompt(userInput, chatHistory, lastDomainContext, excludedDomainsContext);

            long startMs = System.currentTimeMillis();

            String content = domainChatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[DomainRouter] LLM call completed in {}ms | input='{}'", elapsedMs, userInput);
            log.info("[DomainRouter] LLM raw response: {}", content);

            DomainResult result = parseDomainResponse(content);
            log.info("[DomainRouter] Model resolved: domain={} (feature={}) for input='{}'", result.domain(), result.unsupportedFeature(), userInput);

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

    private DomainResult routeDeterministic(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;

        String input = userInput.trim();

        String keywordDomain = matchDomainKeywords(input);
        if (keywordDomain != null) {
            String unsupportedFeature = "UNSUPPORTED".equals(keywordDomain) ? extractUnsupportedFeature(input) : null;
            return new DomainResult(keywordDomain, unsupportedFeature, 1.0, "DETERMINISTIC:keyword");
        }

        DomainResult ruleResult = routeByRules(input);
        if (ruleResult != null) {
            return ruleResult;
        }

        return null;
    }

    private DomainResult routeByRules(String input) {
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
            log.info("[DomainRouter] Multi-domain keywords hit {}, delegating to LLM for input='{}'", hitDomains, input);
            return null;
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
        return "CHAT";
    }

    // ==================== 模板加载 ====================

    private String loadTemplate(String path) {
        return TemplateUtils.loadTemplate(path, this::getDefaultDomainPrompt);
    }

    private String getDefaultDomainPrompt() {
        return """
            你是手机银行领域路由器，判断用户【当前消息】此刻的意图所属领域。

            五个领域: WEALTH(理财), TRANSFER(转账), BILL(账单), UNSUPPORTED(银行功能但暂不支持), CHAT(闲聊)

            当前会话状态:
            {last_domain_context}

            排除领域:
            {excluded_domains_context}

            ===对话历史(每条消息带领域标签)===
            {chat_history}
            ===对话历史结束===

            注意: 对话历史中每条消息前的[转账]/[理财]/[账单]/[闲聊]标签表示该消息所属的领域，
            帮助你在跨域对话中准确判断当前消息的领域归属。

            ===用户当前消息===
            {message}
            ===当前消息结束===

            ★★★ 最高优先级原则 ★★★
            判断领域时，只看【当前消息】本身。对话历史和会话状态只用于: 当前消息意图不明确(如短回答"张三""500""稳健")时辅助判断。
            当前消息意图明确时，历史信息完全忽略，绝不让历史影响判断。

            ★★★ 话术类型分析 ★★★
            【操作型话术】锚点=核心动词: "转1000到理财"→TRANSFER(动词=转), "买理财"→WEALTH(动词=买理财), "查那个理财的账单"→BILL(动词=查+名词=账单)
            【问题型话术】锚点=核心名词+问法动词: "解读刚才转账的理财"→WEALTH(问法=解读), "理财花了多少"→BILL(核心=花了多少)

            判断规则:
            1. 当前消息意图清晰 → 按话术类型分析路由，历史不影响:
               含"账单/明细/消费/支出/收入/收支/开销/流水" → BILL
               含"转账/转/转钱/汇款/打款/付款/转给/打给" → TRANSFER
               含"理财/投资/收益/基金/推荐/咨询/解读" → WEALTH
               含"贷款/信用卡/活动/积分/开户/挂失/存款/保险" → UNSUPPORTED
               多域冲突用话术类型分析:
                 "转1000到理财产品" → 操作型,动词=转 → TRANSFER
                 "解读刚才转账的理财" → 问题型,问法=解读 → WEALTH
                 "查那个理财的账单" → 操作型,名词=账单 → BILL
            2. 当前消息意图不明确(短回答) → 才参考对话历史:
               历史问"转给谁？"，当前"张三" → TRANSFER
               历史问"风险偏好？"，当前"稳健" → WEALTH
            3. 对话历史也无法判断 → 才参考最近活跃领域
            4. 只有取消词 → 路由到最近活跃领域
            5. 排除领域规则: 如果排除列表非空，不要路由到被排除的领域；如果所有业务领域都被排除，路由到CHAT

            实战案例:
            "先看看我这个月的开支情况" → BILL(名词=开支→账单) | "那收入呢" → BILL(短回答,历史在查账) | "好，转3000吧" → TRANSFER(短回答,历史问转多少)
            "听说有个朝朝盈的理财，先帮我解读一下" → WEALTH(问法=解读) | "科技吧，最近比较火" → WEALTH(短回答,历史问领域偏好)
            "OK，给我妈转4000家用" → TRANSFER(动词=转) | "算了，不看了" → 最近活跃领域

            严格输出JSON:
            {
              "domain": "WEALTH | TRANSFER | BILL | UNSUPPORTED | CHAT",
              "unsupported_feature": "当domain=UNSUPPORTED时填写具体功能名，否则null",
              "confidence": 0.0-1.0
            }
            """;
    }
}
