package com.mobileagent.app.observability.rag;

import com.mobileagent.app.observability.AgentSpanContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 重排埋点装饰器（沿用 {@code ObsChatModel} 装饰器模式）。
 *
 * <p>包裹任意 {@link ReRanker} 实现，完成 RAG 重排可观测。结构与
 * {@link ObsDocumentRetriever} 完全一致（同步装饰器三件套）。
 *
 * <p>记录指标（经 {@link RagMetrics} → {@link com.mobileagent.app.observability.MetricsRegistry}）：
 * <ul>
 *   <li>{@code rag.rerank.latency} — 重排耗时</li>
 *   <li>{@code rag.topk.relevance} — Top-K 文档平均相关性（0..1）</li>
 * </ul>
 *
 * <p>异常路径：{@code recordRerankError} + {@code span.recordException} +
 * {@code span.setStatus(ERROR)} + {@code span.end()}，再原样抛出。
 */
@Slf4j
public class ObsReRanker implements ReRanker {

    private final ReRanker delegate;
    private final RagMetrics ragMetrics;
    private final String rerankerName;
    private final String defaultAgentLayer;
    private final String defaultAgentName;

    /**
     * @param delegate         被装饰的原始重排器（如 MinimalReferenceReRanker）
     * @param ragMetrics       RAG 指标门面
     * @param rerankerName     重排器名，用于 metric tag 与 span 名（如 "minimal-reference"）
     * @param defaultAgentLayer ThreadLocal 缺失时兜底层级（默认 "RAG"）
     * @param defaultAgentName  ThreadLocal 缺失时兜底名称（默认 rerankerName）
     */
    public ObsReRanker(ReRanker delegate, RagMetrics ragMetrics,
                       String rerankerName, String defaultAgentLayer, String defaultAgentName) {
        this.delegate = delegate;
        this.ragMetrics = ragMetrics;
        this.rerankerName = rerankerName != null && !rerankerName.isBlank() ? rerankerName : "unknown";
        this.defaultAgentLayer = defaultAgentLayer != null && !defaultAgentLayer.isBlank()
                ? defaultAgentLayer : RagConstants.DEFAULT_AGENT_LAYER;
        this.defaultAgentName = defaultAgentName != null && !defaultAgentName.isBlank()
                ? defaultAgentName : this.rerankerName;
    }

    @Override
    public List<RagDocument> rerank(String query, List<RagDocument> candidates, int topK) {
        long start = System.nanoTime();
        ResolvedCtx rc = resolve();
        String collection = RagConstants.DEFAULT_COLLECTION;
        Span span = startBusinessSpan(rc, collection);
        boolean ended = false;
        try {
            if (span != null) {
                setSpanAttribute(span, "rag.query", truncate(query));
            }

            List<RagDocument> reranked = delegate.rerank(query, candidates, topK);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            int k = (topK > 0) ? topK : ((reranked != null) ? reranked.size() : 0);
            double topKRelevance = averageTopK(reranked, k);

            if (span != null) {
                setSpanAttribute(span, "rag.rerank.count",
                        String.valueOf(reranked != null ? reranked.size() : 0));
                setSpanAttribute(span, "rag.topk.relevance",
                        String.format(Locale.ROOT, "%.4f", topKRelevance));
            }

            ragMetrics.recordRerank(latencyMs, topKRelevance,
                    RagConstants.TAG_COLLECTION, collection,
                    RagConstants.TAG_RERANKER, this.rerankerName);

            if (span != null) {
                span.end();
                ended = true;
            }
            return reranked;
        } catch (Exception e) {
            if (span != null) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                setSpanAttribute(span, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
                span.end();
                ended = true;
            }
            ragMetrics.recordRerankError(this.rerankerName, e.getClass().getSimpleName());
            throw e;
        } finally {
            if (span != null && !ended) {
                span.end();
            }
        }
    }

    /** 取重排后前 k 篇文档分数的均值（归一化到 0..1） */
    private double averageTopK(List<RagDocument> docs, int k) {
        if (docs == null || docs.isEmpty() || k <= 0) {
            return 0.0;
        }
        int n = Math.min(k, docs.size());
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            RagDocument d = docs.get(i);
            if (d != null) {
                sum += clamp01(d.getScore());
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    private double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    // ==================== OTel Span Helpers（同步，mirror ObsChatModel） ====================

    private Span startBusinessSpan(ResolvedCtx rc, String collection) {
        try {
            io.opentelemetry.api.trace.Tracer tracer = GlobalOpenTelemetry.getTracer(RagConstants.TRACER_NAME);
            Span span = tracer.spanBuilder(RagConstants.SPAN_RERANK)
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            setSpanAttribute(span, "agent.layer", rc.layer);
            setSpanAttribute(span, "agent.name", rc.name);
            setSpanAttribute(span, "intent", rc.intent);
            setSpanAttribute(span, "rag.collection", collection);
            setSpanAttribute(span, "rag.reranker", this.rerankerName);
            return span;
        } catch (Exception e) {
            log.debug("[ObsReRanker] Failed to create business span: {}", e.getMessage());
            return null;
        }
    }

    private ResolvedCtx resolve() {
        ResolvedCtx rc = new ResolvedCtx();
        AgentSpanContext ctx = AgentSpanContext.get();
        if (ctx != null) {
            rc.layer = ctx.getAgentLayer();
            rc.name = ctx.getAgentName();
            rc.intent = ctx.getIntent();
        }
        if (rc.layer == null || rc.layer.isBlank()) rc.layer = defaultAgentLayer;
        if (rc.name == null || rc.name.isBlank()) rc.name = defaultAgentName;
        return rc;
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > RagConstants.SPAN_QUERY_MAX_LENGTH
                ? s.substring(0, RagConstants.SPAN_QUERY_MAX_LENGTH) : s;
    }

    private void setSpanAttribute(Span span, String key, String value) {
        if (span == null || key == null || value == null) return;
        try {
            span.setAttribute(key, value);
        } catch (Exception e) {
            log.debug("[ObsReRanker] Failed to set span attribute {}: {}", key, e.getMessage());
        }
    }

    private static final class ResolvedCtx {
        String layer;
        String name;
        String intent;
    }
}
