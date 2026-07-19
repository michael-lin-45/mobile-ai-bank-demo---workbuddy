package com.mobileagent.app.observability.rag;

import java.util.List;

/**
 * 文档检索契约接口（映射 ChatModel 在 LLM 埋点中的角色）。
 *
 * <p>既是「最小参考检索器」{@link MinimalReferenceRetriever} 的实现目标，
 * 也是将来接入真实向量库（PgVector / Redis VectorStore 等）的<b>扩展点</b>：
 * 只需新增一个 {@code DocumentRetriever} 实现，并在 {@link RagConfig} 中替换 Bean 即可，
 * 埋点装饰器 {@link ObsDocumentRetriever} 与上层 {@link RagPipeline} 无需改动。
 *
 * <p>设计约定（与 ObsChatModel 一致）：检索为<b>同步</b>操作，返回即结果。
 */
public interface DocumentRetriever {

    /**
     * 检索与 query 相关的文档。
     *
     * @param query 用户查询（原始文本，内部自行分词打分）
     * @param topK  返回文档数量上限（<=0 视为返回全部候选）
     * @return 相关性降序排列的文档列表（可为空，禁止返回 null）
     */
    List<RagDocument> retrieve(String query, int topK);
}
