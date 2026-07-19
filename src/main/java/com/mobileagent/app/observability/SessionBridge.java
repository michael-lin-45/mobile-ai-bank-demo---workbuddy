package com.mobileagent.app.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话数据桥接 — 将主应用的 chat 调用结果异步写入可观测后端的 sessions / session_turns 表。
 *
 * 设计决策：
 * - 异步执行，不阻塞 chat 响应
 * - 失败不影响主业务流程（fire-and-forget）
 * - 通过 HTTP POST 到可观测后端，后端已有的 SessionService 负责去重（同 sessionId 覆盖写）
 */
@Slf4j
@Service
public class SessionBridge {

    private final HttpClient httpClient;
    private final String observabilityBaseUrl;

    public SessionBridge(@Value("${observability.backend.url:http://127.0.0.1:9090}") String baseUrl) {
        this.observabilityBaseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 每次 chat 调用完成后异步发送 session 快照到可观测后端。
     *
     * @param sessionId  会话 ID
     * @param userInput  用户输入
     * @param aiResponse AI 回复
     * @param intent     识别的意图
     * @param agentPath  Agent 执行路径（如 "L0→TRANSFER→TransferService"）
     * @param confidence 置信度
     * @param durationMs 耗时（毫秒）
     * @param tokens     消耗的 token 数
     * @param traceId    OTel trace ID
     * @param status     状态（COMPLETED / INTERRUPTED / ERROR 等）
     */
    @Async
    public void reportSession(String sessionId, String userInput, String aiResponse,
                               String intent, String agentPath, double confidence,
                               long durationMs, int tokens, String traceId, String status,
                               boolean reRouted, String originalQuery) {
        try {
            if (sessionId != null) sessionId = sessionId.trim();
            String body = buildSessionJson(sessionId, userInput, aiResponse,
                    intent, agentPath, confidence, durationMs, tokens, traceId, status,
                    reRouted, originalQuery);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(observabilityBaseUrl + "/api/v1/sessions"))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            log.info("[SessionBridge] Sending to {} body={}", req.uri(), body);
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) { log.warn("[SessionBridge] Response status={} body={}", resp.statusCode(), resp.body()); }
            if (resp.statusCode() == 200) {
                log.debug("[SessionBridge] Reported session: sessionId={}", sessionId);
            } else {
                log.warn("[SessionBridge] Failed to report session: sessionId={}, status={}", sessionId, resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("[SessionBridge] Error reporting session: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    private static final ObjectMapper SESSION_MAPPER = new ObjectMapper();

    private String buildSessionJson(String sessionId, String userInput, String aiResponse,
                                     String intent, String agentPath, double confidence,
                                     long durationMs, int tokens, String traceId, String status,
                                     boolean reRouted, String originalQuery) {
        // 改用 Jackson 序列化（P0-3 整改）：避免手工 String.format 拼接 JSON
        // 漏转义 Unicode 控制字符（\u0000-\u001F 等）导致后端解析失败的隐患。
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("userId", extractUserId(sessionId));
        m.put("userInput", userInput);
        m.put("aiResponse", aiResponse);
        m.put("intent", intent);
        m.put("agentPath", agentPath);
        m.put("confidence", confidence);
        m.put("durationMs", durationMs);
        m.put("tokens", tokens);
        m.put("traceId", traceId);
        m.put("status", status);
        // T-N 待定项 B：reRoute 原报文透传（设计 §3.5，最小改动、不重新生成）
        m.put("reRouted", reRouted);
        if (originalQuery != null && !originalQuery.isBlank()) {
            m.put("originalQuery", originalQuery);
        }
        try {
            return SESSION_MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("[SessionBridge] JSON serialize failed, fallback to minimal: {}", e.getMessage());
            return "{\"sessionId\":\"" + escape(sessionId) + "\",\"status\":\"" + escape(status) + "\"}";
        }
    }

    /** 从 sessionId 推导 userId（简化：前缀作为 userId） */
    private String extractUserId(String sessionId) {
        if (sessionId == null) return "unknown";
        int dash = sessionId.lastIndexOf('-');
        return dash > 0 ? sessionId.substring(0, dash) : sessionId;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
