package com.mobileagent.app.observability.rag;

import com.mobileagent.app.observability.AgentSpanContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 检索埋点装饰器（沿用 {@code ObsChatModel} 装饰器模式）。
 *
 * <p>包裹任意 {@link DocumentRetriever} 实现，在「调 delegate → 记录指标 → 建/结 Span」三件套上
 * 完成 RAG 检索可观测，业务代码零改动。与 {@code ObsChatModel} 的差异（刻意简化）：
 * 检索为<b>同步</b>操作，无需流式 TTFT / held-span 复杂度，采用更简单的
 * 「创建 span → 执行 → finally 中 end span + record metrics」结构。
 *
 * <p>记录指标（经 {@link RagMetrics} → {@link com.mobileagent.app.observability.MetricsRegistry}）：
 * <ul>
 *   <li>{@code rag.retrieval.latency}  — 检索耗时</li>
 *   <li>{@code rag.retrieval.documents}— 返回文档数</li>
 *   <li>{@code rag.retrieval.hit_rate} — 最佳返回分 ≥ 相关性阈值记 1.0，否则 0.0</li>
 * </ul>
 *
 * <p>异常路径：try/catch 包裹，{@code recordRetrievalError} + {@code span.recordException} +
 * {@code span.setStatus(ERROR)} + {@code span.end()}，再原样抛出（不影响主业务，与 ObsChatModel 一致）。
 */
@Slf4j
public class ObsDocumentRetriever implements DocumentRetriever {

    private final DocumentRetriever delegate;
    private final RagMetrics ragMetrics;
    private final String retrieverName;
    private final String defaultAgentLayer;
    private final String defaultAgentName;

    /**
     * @param delegate          被装饰的原始检索器（如 MinimalReferenceRetriever）
     * @param ragMetrics        RAG 指标门面
     * @param retrieverName     检索器名，用于 metric tag 与 span 名（如 "minimal-reference"）
     * @param defaultAgentLayer ThreadLocal 缺失时兜底层级（默认 "RAG"）
     * @param defaultAgentName  ThreadLocal 缺失时兜底名称（默认 retrieverName）
     */
    public ObsDocumentRetriever(DocumentRetriever delegate, RagMetrics ragMetrics,
                                String retrieverName, String defaultAgentLayer, String defaultAgentName) {
        this.delegate = delegate;
        this.ragMetrics = ragMetrics;
        this.retrieverName = retrieverName != null && !retrieverName.isBlank() ? retrieverName : "unknown";
        this.defaultAgentLayer = defaultAgentLayer != null && !defaultAgentLayer.isBlank()
                ? defaultAgentLayer : RagConstants.DEFAULT_AGENT_LAYER;
        this.defaultAgentName = defaultAgentName != null && !defaultAgentName.isBlank()
                ? defaultAgentName : this.retrieverName;
    }

    @Override
    public List<RagDocument> retrieve(String query, int topK) {
        long start = System.nanoTime();
        ResolvedCtx rc = resolve();
        String collection = RagConstants.DEFAULT_COLLECTION;
        Span span = startBusinessSpan(rc, collection);
        boolean ended = false;
        try {
            if (span != null) {
                setSpanAttribute(span, "rag.query", truncate(query));
            }

            List<RagDocument> docs = delegate.retrieve(query, topK);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            int docCount = (docs != null) ? docs.size() : 0;
            double bestScore = bestScore(docs);
            double hitRate = bestScore >= RagConstants.RELEVANCE_HIT_THRESHOLD ? 1.0 : 0.0;

            if (span != null) {
                setSpanAttribute(span, "rag.doc.count", String.valueOf(docCount));
                setSpanAttribute(span, "rag.hit", hitRate >= 1.0 ? "true" : "false");
            }

            ragMetrics.recordRetrieval(latencyMs, docCount, hitRate,
                    RagConstants.TAG_COLLECTION, collection,
                    RagConstants.TAG_RETRIEVER, this.retrieverName);

            if (span != null) {
                span.end();
                ended = true;
            }
            return docs;
        } catch (Exception e) {
            if (span != null) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                setSpanAttribute(span, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
                span.end();
                ended = true;
            }
            ragMetrics.recordRetrievalError(this.retrieverName, e.getClass().getSimpleName());
            throw e;
        } finally {
            if (span != null && !ended) {
                span.end();
            }
        }
    }

    /** 取候选集最高相关性分（空/空列表返回 0.0） */
    private double bestScore(List<RagDocument> docs) {
        double best = 0.0;
        if (docs != null) {
            for (RagDocument d : docs) {
                if (d != null && d.getScore() > best) {
                    best = d.getScore();
                }
            }
        }
        return best;
    }

    // ==================== OTel Span Helpers（同步，mirror ObsChatModel） ====================

    /**
     * 创建含业务属性的 OTel Span。层级来源优先级：
     *   1. AgentSpanContext (ThreadLocal) 显式 set 的层级 / 名称（真实 RAG 链路接入时）
     *   2. 构造时绑定的 defaultAgentLayer / defaultAgentName（缺失时兜底，确保 Span 带 agent.layer）
     * Tracer 不可用时返回 null（不影响主业务）。
     */
    private Span startBusinessSpan(ResolvedCtx rc, String collection) {
        try {
            io.opentelemetry.api.trace.Tracer tracer = GlobalOpenTelemetry.getTracer(RagConstants.TRACER_NAME);
            Span span = tracer.spanBuilder(RagConstants.SPAN_RETRIEVE)
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            setSpanAttribute(span, "agent.layer", rc.layer);
            setSpanAttribute(span, "agent.name", rc.name);
            setSpanAttribute(span, "intent", rc.intent);
            setSpanAttribute(span, "rag.collection", collection);
            setSpanAttribute(span, "rag.retriever", this.retrieverName);
            return span;
        } catch (Exception e) {
            log.debug("[ObsDocumentRetriever] Failed to create business span: {}", e.getMessage());
            return null;
        }
    }

    /** 解析业务上下文：优先 AgentSpanContext (ThreadLocal)，缺失回退构造兜底 */
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
            log.debug("[ObsDocumentRetriever] Failed to set span attribute {}: {}", key, e.getMessage());
        }
    }

    /** 解析后的业务上下文（层级 / 名称 / intent） */
    private static final class ResolvedCtx {
        String layer;
        String name;
        String intent;
    }
}
