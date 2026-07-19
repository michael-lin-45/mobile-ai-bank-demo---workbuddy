package com.mobileagent.app.observability.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 埋点层 Spring 装配。
 *
 * <p>把「最小参考实现」套上埋点装饰器，组装 {@link RagPipeline}：
 * <pre>
 *   MinimalReferenceRetriever  → ObsDocumentRetriever  (装饰)
 *   MinimalReferenceReRanker   → ObsReRanker          (装饰)
 *   ObsDocumentRetriever + ObsReRanker → RagPipeline
 * </pre>
 *
 * <p>返回的 Bean 类型是接口（{@link DocumentRetriever} / {@link ReRanker}），业务方仅依赖接口，
 * 将来接真实 VectorStore / 重排模型时只需替换 {@link #ragRetriever} / {@link #ragReRanker} 内部的
 * 实现，装饰器与编排层零改动（扩展点）。
 *
 * <p>本配置<b>始终加载</b>（与既有 {@code ModelConfig} 一致），埋点层作为独立基础设施存在；
 * 仅诊断端点 {@link RagController} 经 {@code @Profile("dev")} + {@code observability.rag.reference.enabled}
 * 双重守卫，生产不暴露、不侵入真实问答链路。
 */
@Slf4j
@Configuration
public class RagConfig {

    /** 种子语料（参考检索器的知识源） */
    @Bean
    public RagSampleCorpus ragSampleCorpus() {
        return new RagSampleCorpus();
    }

    /**
     * 参考检索器：MinimalReferenceRetriever 套 ObsDocumentRetriever 装饰器。
     * 返回 DocumentRetriever 接口，业务方零感知埋点。
     */
    @Bean
    public DocumentRetriever ragRetriever(RagSampleCorpus corpus, RagMetrics ragMetrics) {
        MinimalReferenceRetriever base = new MinimalReferenceRetriever(corpus);
        ObsDocumentRetriever obs = new ObsDocumentRetriever(base, ragMetrics,
                "minimal-reference", RagConstants.DEFAULT_AGENT_LAYER, "MinimalReferenceRetriever");
        log.info("[RagConfig] ragRetriever built: MinimalReferenceRetriever wrapped by ObsDocumentRetriever");
        return obs;
    }

    /**
     * 参考重排器：MinimalReferenceReRanker 套 ObsReRanker 装饰器。
     */
    @Bean
    public ReRanker ragReRanker(RagMetrics ragMetrics) {
        MinimalReferenceReRanker base = new MinimalReferenceReRanker();
        ObsReRanker obs = new ObsReRanker(base, ragMetrics,
                "minimal-reference", RagConstants.DEFAULT_AGENT_LAYER, "MinimalReferenceReRanker");
        log.info("[RagConfig] ragReRanker built: MinimalReferenceReRanker wrapped by ObsReRanker");
        return obs;
    }

    /** 编排器：持有装饰后的检索器 / 重排器 */
    @Bean
    public RagPipeline ragPipeline(DocumentRetriever ragRetriever, ReRanker ragReRanker) {
        return new RagPipeline(ragRetriever, ragReRanker);
    }
}
