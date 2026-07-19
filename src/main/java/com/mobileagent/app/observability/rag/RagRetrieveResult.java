package com.mobileagent.app.observability.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * RAG 端到端结果 DTO（供 {@link RagPipeline} / {@link RagController} 使用）。
 *
 * <p>聚合一次「检索 → 重排」的结果与摘要指标，供诊断端点返回，也便于将来接真实链路时复用。
 * 注意：本 DTO 中的 {@code topKRelevance} / {@code retrievalHitRate} 为展示用摘要，
 * 真实指标（{@code deepflux.rag.*}）由装饰器落 {@link com.mobileagent.app.observability.MetricsRegistry}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrieveResult {

    /** 原始查询 */
    private String query;

    /** 请求的 topK */
    private int requestedTopK;

    /** 重排后的最终文档列表（相关性降序） */
    private List<RagDocument> documents;

    /** 最终返回文档数 */
    private int retrievedCount;

    /** Top-K 平均相关性（0..1，展示用摘要） */
    private double topKRelevance;

    /** 检索命中率（二值 0/1，展示用摘要） */
    private double retrievalHitRate;

    /** 端到端总耗时（毫秒，检索 + 重排） */
    private long totalLatencyMs;

    /** 诊断信息（可选，如 collection / retriever / reranker 名称） */
    private Map<String, Object> diagnostics;
}
