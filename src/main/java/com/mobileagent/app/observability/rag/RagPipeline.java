package com.mobileagent.app.observability.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 编排器：retrieve → rerank，返回 {@link RagRetrieveResult}。
 *
 * <p>持有<b>已装饰</b>的 {@link DocumentRetriever} / {@link ReRanker}（由 {@link RagConfig} 套好
 * ObsDocumentRetriever / ObsReRanker），业务方仅依赖接口，零感知埋点。本类只做编排与结果聚合，
 * 不重复记录指标（指标由装饰器落 {@link com.mobileagent.app.observability.MetricsRegistry}）。
 *
 * <p>不侵入既有 Agent 链路（ChatService / L1 路由等），可独立经诊断端点验证，也可将来由调用方
 * 在真实 RAG 问答链路中插入（配合 {@code AgentSpanContext.set} 回填业务上下文）。
 */
@Slf4j
public class RagPipeline {

    private final DocumentRetriever retriever;
    private final ReRanker reranker;

    public RagPipeline(DocumentRetriever retriever, ReRanker reranker) {
        this.retriever = retriever;
        this.reranker = reranker;
    }

    /**
     * 执行一次「检索 → 重排」。
     *
     * @param query 用户查询
     * @param topK  返回数量上限
     * @return 端到端结果（含文档列表与摘要指标）
     */
    public RagRetrieveResult retrieveAndRerank(String query, int topK) {
        long start = System.currentTimeMillis();

        List<RagDocument> docs = retriever.retrieve(query, topK);
        List<RagDocument> reranked = reranker.rerank(query, docs, topK);

        long totalMs = System.currentTimeMillis() - start;

        double topKRelevance = averageTopK(reranked, topK);
        double hitRate = bestScore(reranked) >= RagConstants.RELEVANCE_HIT_THRESHOLD ? 1.0 : 0.0;

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("collection", RagConstants.DEFAULT_COLLECTION);
        diagnostics.put("topK", topK);

        return RagRetrieveResult.builder()
                .query(query)
                .requestedTopK(topK)
                .documents(reranked)
                .retrievedCount(reranked != null ? reranked.size() : 0)
                .topKRelevance(topKRelevance)
                .retrievalHitRate(hitRate)
                .totalLatencyMs(totalMs)
                .diagnostics(diagnostics)
                .build();
    }

    /** 取前 k 篇文档分数的均值（归一化到 0..1） */
    private double averageTopK(List<RagDocument> docs, int topK) {
        if (docs == null || docs.isEmpty() || topK <= 0) {
            return 0.0;
        }
        int n = Math.min(topK, docs.size());
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

    /** 取候选集最高相关性分 */
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

    private double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
