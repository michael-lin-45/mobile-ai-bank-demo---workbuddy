package com.mobileagent.app.observability.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 最小参考检索器（内存，确定性，零外部依赖）。
 *
 * <p>实现 {@link DocumentRetriever}：对语料做<b>词面重叠打分</b>（Jaccard 相似度，归一化到 0..1），
 * 按相关性降序返回 Top-K。不依赖任何向量库 / Embedding，可单测、可 Spring Boot 集成测试。
 *
 * <p>分词策略兼容中英文混合：CJK 字符逐字成 token；连续拉丁字母/数字成词；标点/空白分隔。
 * 因此「如何转账」类的查询能与语料中转账文档产生重叠，稳定命中。
 *
 * <p>扩展点：接真实向量库时，仅需新增一个 {@link DocumentRetriever} 实现并在
 * {@link RagConfig} 替换 Bean，本类与装饰器、编排层均无需改动。
 */
@Slf4j
public class MinimalReferenceRetriever implements DocumentRetriever {

    private final RagSampleCorpus corpus;

    public MinimalReferenceRetriever(RagSampleCorpus corpus) {
        this.corpus = corpus;
    }

    @Override
    public List<RagDocument> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> queryTokens = tokenize(normalize(query));

        List<ScoredDoc> scored = new ArrayList<>();
        for (RagDocument doc : corpus.getDocuments()) {
            Set<String> docTokens = tokenize(normalize(doc.getContent()));
            double score = lexicalOverlap(queryTokens, docTokens);
            RagDocument copy = RagDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .metadata(doc.getMetadata() != null ? new java.util.HashMap<>(doc.getMetadata()) : null)
                    .score(clamp01(score))
                    .build();
            scored.add(new ScoredDoc(copy, clamp01(score)));
        }

        scored.sort(Comparator.comparingDouble((ScoredDoc s) -> s.score).reversed());;
        int k = (topK <= 0) ? scored.size() : Math.min(topK, scored.size());
        return scored.subList(0, k).stream().map(s -> s.doc).collect(Collectors.toList());
    }

    /** 词面重叠打分：Jaccard 相似度 = |A∩B| / |A∪B|，归一化到 0..1 */
    private double lexicalOverlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) {
            return 0.0;
        }
        return (double) intersection.size() / union.size();
    }

    /**
     * 归一化分词：CJK 逐字、拉丁/数字连续成词，统一小写。
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c)) {
                flushLatin(latin, tokens);
                continue;
            }
            if (isCjk(c)) {
                flushLatin(latin, tokens);
                tokens.add("c:" + c);
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        return tokens;
    }

    private void flushLatin(StringBuilder sb, Set<String> tokens) {
        if (sb.length() > 0) {
            tokens.add("w:" + sb.toString());
            sb.setLength(0);
        }
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    private double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    /** 加权打分临时载体 */
    private static final class ScoredDoc {
        final RagDocument doc;
        final double score;

        ScoredDoc(RagDocument doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }
}
