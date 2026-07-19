package com.mobileagent.app.observability.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 检索文档数据模型。
 *
 * <p>作为检索器 / 重排器之间传递的统一数据结构。{@code score} 表示相关性，
 * 归一化到 0..1（参考检索器由词面重叠打分给出，接真实向量库时由向量相似度 / 重排模型给出）。
 *
 * <p>与既有埋点体系对齐：本对象不承载任何高基数字段（无 user.id / session.id / 原始 query），
 * 仅保留业务无关的通用字段，确保可安全进入 Metric Tag 预算之外、且仅用于 Span / 结果展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocument {

    /** 文档唯一标识（如 doc-transfer-1） */
    private String id;

    /** 文档正文 */
    private String content;

    /** 业务元数据（如 source / title / priority），不参与指标 Tag，仅用于重排加权与结果展示 */
    private Map<String, Object> metadata;

    /** 相关性分数，归一化到 [0, 1] */
    private double score;
}
