package com.mobileagent.app.observability.rag;

import java.util.List;

/**
 * 文档重排契约接口。
 *
 * <p>对初检候选集做二次精排（如 score + metadata 加权、或真实 Cross-Encoder），
 * 输出 Top-K 精排结果。是将来接入真实重排模型的<b>扩展点</b>。
 *
 * <p>设计约定（与 ObsChatModel 一致）：重排为<b>同步</b>操作。
 */
public interface ReRanker {

    /**
     * 对候选文档重排。
     *
     * @param query      用户查询
     * @param candidates 初检候选集（来自 {@link DocumentRetriever}）
     * @param topK       精排后返回数量上限（<=0 视为返回全部）
     * @return 重排后（相关性降序）的文档列表（可为空，禁止返回 null）
     */
    List<RagDocument> rerank(String query, List<RagDocument> candidates, int topK);
}
