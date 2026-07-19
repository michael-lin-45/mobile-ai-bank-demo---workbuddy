package com.mobileagent.app.observability.rag;

import com.mobileagent.app.observability.MetricsRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RAG 指标门面（语义封装层）。
 *
 * <p>封装集中式 {@link MetricsRegistry}，统一 {@code deepflux.rag.*} 命名（最终指标名 =
 * {@code deepflux.rag.retrieval.latency} 等），屏蔽底层 meter 类型细节。装饰器
 * {@link ObsDocumentRetriever} / {@link ObsReRanker} 仅依赖本门面，不直接接触 Micrometer。
 *
 * <p>五项核心指标 + 两项防御性 error Counter：
 * <ul>
 *   <li>{@code rag.retrieval.latency}  — Timer（Histogram，强制分位）</li>
 *   <li>{@code rag.retrieval.documents}— DistributionSummary（文档数，取均值）</li>
 *   <li>{@code rag.retrieval.hit_rate} — DistributionSummary（二值 0/1，取均值即命中率）</li>
 *   <li>{@code rag.rerank.latency}     — Timer（Histogram）</li>
 *   <li>{@code rag.topk.relevance}     — DistributionSummary（Top-K 平均相关性 0..1）</li>
 *   <li>{@code rag.retrieval.error}    — Counter（防御，镜像 llm.error.count）</li>
 *   <li>{@code rag.rerank.error}       — Counter（防御）</li>
 * </ul>
 *
 * <p>Timer 类指标由 {@link MetricsRegistry#recordTimer} 强制
 * {@code publishPercentileHistogram(true)}，确保可被观测后端 OtlpParser 解析为 Histogram，
 * 否则延迟恒为 0（与 ObsChatModel 注释一致）。
 *
 * <p>所有记录均经 {@link #safeRecord} try/catch 包裹，指标异常绝不抛出，不影响主业务。
 */
@Slf4j
@Component
public class RagMetrics {

    private final MetricsRegistry metricsRegistry;

    public RagMetrics(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * 记录检索指标：延迟(Timer) + 文档数(Summary) + 命中率(Summary)。
     *
     * @param latencyMs 检索耗时（毫秒）
     * @param docCount  本次检索返回文档数
     * @param hitRate   命中率（二值 0.0 / 1.0）
     * @param tags      低基数 tag 对：collection, retriever, ...（key, value 交替）
     */
    public void recordRetrieval(long latencyMs, int docCount, double hitRate, String... tags) {
        safeRecord(() -> {
            metricsRegistry.recordTimer(RagConstants.METRIC_RETRIEVAL_LATENCY, latencyMs, tags);
            metricsRegistry.recordSummary(RagConstants.METRIC_RETRIEVAL_DOCUMENTS, docCount, tags);
            metricsRegistry.recordSummary(RagConstants.METRIC_RETRIEVAL_HIT_RATE, hitRate, tags);
        });
    }

    /**
     * 记录重排指标：重排延迟(Timer) + Top-K 相关性(Summary)。
     *
     * @param latencyMs     重排耗时（毫秒）
     * @param topKRelevance Top-K 文档平均相关性（0..1）
     * @param tags          低基数 tag 对：collection, reranker, ...（key, value 交替）
     */
    public void recordRerank(long latencyMs, double topKRelevance, String... tags) {
        safeRecord(() -> {
            metricsRegistry.recordTimer(RagConstants.METRIC_RERANK_LATENCY, latencyMs, tags);
            metricsRegistry.recordSummary(RagConstants.METRIC_TOPK_RELEVANCE, topKRelevance, tags);
        });
    }

    /**
     * 记录检索异常计数。
     *
     * @param retriever 检索器名（如 minimal-reference）
     * @param errorType 异常简单类名（如 NullPointerException）
     */
    public void recordRetrievalError(String retriever, String errorType) {
        safeRecord(() -> metricsRegistry.recordCounter(RagConstants.METRIC_RETRIEVAL_ERROR,
                RagConstants.TAG_RETRIEVER, retriever,
                RagConstants.TAG_ERROR_TYPE, errorType));
    }

    /**
     * 记录重排异常计数。
     *
     * @param reranker 重排器名（如 minimal-reference）
     * @param errorType 异常简单类名
     */
    public void recordRerankError(String reranker, String errorType) {
        safeRecord(() -> metricsRegistry.recordCounter(RagConstants.METRIC_RERANK_ERROR,
                RagConstants.TAG_RERANKER, reranker,
                RagConstants.TAG_ERROR_TYPE, errorType));
    }

    /** 安全记录 —— try/catch 包裹，不影响主业务 */
    private void safeRecord(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[RagMetrics] Failed to record RAG metric: {}", e.getMessage());
        }
    }
}
