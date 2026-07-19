package com.mobileagent.app.observability.rag;

/**
 * RAG 埋点层共享常量。
 *
 * <p>固化指标名、Tag Key、默认集合名、相关性阈值、OTel Tracer / Span 命名等，避免散落硬编码。
 *
 * <p>指标最终名 = {@code deepflux.} + 下表（由 {@link com.mobileagent.app.observability.MetricsRegistry}
 * 自动补齐前缀），与存量 {@code agent.* / llm.* / gen_ai.*} 指标隔离。
 */
public final class RagConstants {

    private RagConstants() {
        // 工具类，禁止实例化
    }

    // ───────────────────────── 指标名（最终 = deepflux. + 下表） ─────────────────────────

    /** 检索延迟（Timer / Histogram，tag: collection, retriever） */
    public static final String METRIC_RETRIEVAL_LATENCY = "rag.retrieval.latency";

    /** 检索返回文档数（DistributionSummary，tag: collection, retriever；取均值 = 平均召回文档数） */
    public static final String METRIC_RETRIEVAL_DOCUMENTS = "rag.retrieval.documents";

    /** 检索命中率（DistributionSummary，二值 0/1，tag: collection, retriever；下游均值即命中率） */
    public static final String METRIC_RETRIEVAL_HIT_RATE = "rag.retrieval.hit_rate";

    /** 重排延迟（Timer / Histogram，tag: collection, reranker） */
    public static final String METRIC_RERANK_LATENCY = "rag.rerank.latency";

    /** Top-K 相关性（DistributionSummary，0..1，tag: collection, reranker） */
    public static final String METRIC_TOPK_RELEVANCE = "rag.topk.relevance";

    /** 检索异常计数（Counter，防御，tag: retriever, error_type；镜像 llm.error.count） */
    public static final String METRIC_RETRIEVAL_ERROR = "rag.retrieval.error";

    /** 重排异常计数（Counter，防御，tag: reranker, error_type） */
    public static final String METRIC_RERANK_ERROR = "rag.rerank.error";

    // ───────────────────────── Tag Key（低基数，符合 §2.4 预算） ─────────────────────────

    public static final String TAG_COLLECTION = "collection";
    public static final String TAG_RETRIEVER = "retriever";
    public static final String TAG_RERANKER = "reranker";
    public static final String TAG_ERROR_TYPE = "error_type";

    // ───────────────────────── 默认值 ─────────────────────────

    /** 默认集合名（参考语料） */
    public static final String DEFAULT_COLLECTION = "reference-corpus";

    /**
     * 相关性命中阈值：召回文档中最佳 score >= 该值视为「命中」，hit_rate 记 1.0，否则 0.0。
     * 接真实检索器时定义不变（仅 score 来源变化）。
     */
    public static final double RELEVANCE_HIT_THRESHOLD = 0.5;

    /** 重排 metadata 中用于加权的优先级键名 */
    public static final String METADATA_PRIORITY_KEY = "priority";

    // ───────────────────────── OTel Tracer / Span 命名 ─────────────────────────

    /** OTel Tracer 名 */
    public static final String TRACER_NAME = "obs-rag";

    /** 检索 Span 名 */
    public static final String SPAN_RETRIEVE = "RAG:retrieve";

    /** 重排 Span 名 */
    public static final String SPAN_RERANK = "RAG:rerank";

    /** 业务上下文缺失时的兜底层级（确保 Span 始终带 agent.layer，不退化） */
    public static final String DEFAULT_AGENT_LAYER = "RAG";

    /** query 写入 Span 属性前的截断长度（与 ObsChatModel 一致，<=2000 字符） */
    public static final int SPAN_QUERY_MAX_LENGTH = 2000;
}
