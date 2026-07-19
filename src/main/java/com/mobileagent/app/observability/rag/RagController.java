package com.mobileagent.app.observability.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 诊断 / 参考检索端点（E2E 验证用，不侵入真实问答链路）。
 *
 * <p>暴露 {@code GET /api/v1/rag/retrieve?query=...&topK=5}，触发一次参考检索 → 重排，
 * 使 {@code deepflux.rag.*} 五项指标经 {@link com.mobileagent.app.observability.MetricsRegistry}
 * 落库，供 QA 经 {@code /actuator/metrics} 断言（点号命名，如 {@code deepflux.rag.retrieval.latency}）。
 *
 * <p><b>双重守卫（生产不暴露）</b>：
 *   1. {@code @Profile("dev")} —— 非 dev 环境 Bean 不创建；
 *   2. {@code observability.rag.reference.enabled}（默认 false）—— 即使 dev，未显式开启时也返回 503。
 *
 * <p>本期仅交付内存参考实现（{@link MinimalReferenceRetriever} / {@link MinimalReferenceReRanker}），
 * 不接真实 VectorStore；{@link DocumentRetriever} / {@link ReRanker} 接口即将来接真实库的扩展点。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@Profile("dev")
public class RagController {

    private final RagPipeline ragPipeline;

    @Value("${observability.rag.reference.enabled:false}")
    private boolean referenceEnabled;

    public RagController(RagPipeline ragPipeline) {
        this.ragPipeline = ragPipeline;
    }

    @GetMapping("/retrieve")
    public ResponseEntity<?> retrieve(@RequestParam String query,
                                      @RequestParam(defaultValue = "5") int topK) {
        if (!referenceEnabled) {
            log.warn("[RagController] RAG reference endpoint disabled "
                    + "(observability.rag.reference.enabled=false)");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "RAG reference endpoint disabled",
                    "hint", "enable observability.rag.reference.enabled=true (requires dev profile)"));
        }

        log.info("[RagController] retrieve: query={}, topK={}", query, topK);
        RagRetrieveResult result = ragPipeline.retrieveAndRerank(query, topK);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", result.getQuery());
        body.put("requestedTopK", result.getRequestedTopK());
        body.put("retrievedCount", result.getRetrievedCount());
        body.put("topKRelevance", result.getTopKRelevance());
        body.put("retrievalHitRate", result.getRetrievalHitRate());
        body.put("totalLatencyMs", result.getTotalLatencyMs());
        body.put("documents", result.getDocuments().stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("score", d.getScore());
                    m.put("content", d.getContent());
                    m.put("metadata", d.getMetadata() != null ? d.getMetadata() : Map.of());
                    return m;
                })
                .collect(Collectors.toList()));
        return ResponseEntity.ok(body);
    }
}
