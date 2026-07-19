package com.mobileagent.app.observability.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 最小参考重排器（内存，确定性，零外部依赖）。
 *
 * <p>实现 {@link ReRanker}：以「初检 score + metadata 优先级加权」做二次精排，
 * 输出 Top-K。不依赖任何外部重排模型，可单测、可 Spring Boot 集成测试。
 *
 * <p>重排公式：finalScore = clamp01(baseScore + priorityBonus)，
 * 其中 priorityBonus 由 metadata 的 {@code priority} 字段决定（high/medium/low）。
 *
 * <p>扩展点：接真实 Cross-Encoder 重排时，仅需新增一个 {@link ReRanker} 实现并在
 * {@link RagConfig} 替换 Bean。
 */
@Slf4j
public class MinimalReferenceReRanker implements ReRanker {

    /** 优先级 → 加权 bonus（归一化到 0..1 内，避免越界） */
    private static final Map<String, Double> PRIORITY_WEIGHT = Map.of(
            "high", 0.15,
            "medium", 0.05,
            "low", 0.0
    );

    @Override
    public List<RagDocument> rerank(String query, List<RagDocument> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RagDocument> reranked = candidates.stream()
                .map(this::withRerankScore)
                .sorted(Comparator.comparingDouble((RagDocument d) -> d.getScore()).reversed())
                .collect(Collectors.toList());
        int k = (topK <= 0) ? reranked.size() : Math.min(topK, reranked.size());
        return reranked.subList(0, k);
    }

    /** 在初检分基础上叠加 metadata 优先级加权，得到精排分 */
    private RagDocument withRerankScore(RagDocument src) {
        double base = clamp01(src.getScore());
        double bonus = 0.0;
        Map<String, Object> meta = src.getMetadata();
        if (meta != null && meta.containsKey(RagConstants.METADATA_PRIORITY_KEY)) {
            Object p = meta.get(RagConstants.METADATA_PRIORITY_KEY);
            String pStr = (p != null) ? p.toString().toLowerCase(Locale.ROOT) : "";
            bonus = PRIORITY_WEIGHT.getOrDefault(pStr, 0.0);
        }
        double finalScore = clamp01(base + bonus);
        return RagDocument.builder()
                .id(src.getId())
                .content(src.getContent())
                .metadata(meta != null ? new HashMap<>(meta) : null)
                .score(finalScore)
                .build();
    }

    private double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
