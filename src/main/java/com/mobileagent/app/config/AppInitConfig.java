package com.mobileagent.app.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.router.IntentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 应用初始化配置 - 将Graph Bean绑定到IntentRegistry
 */
@Slf4j
@Configuration
public class AppInitConfig {

    private final IntentRegistry intentRegistry;
    private final CompiledGraph transferGraph;
    private final CompiledGraph billQueryGraph;
    private final CompiledGraph wealthConsultGraph;
    private final CompiledGraph wealthInterpretGraph;

    public AppInitConfig(IntentRegistry intentRegistry,
                         @Qualifier("transferGraph") CompiledGraph transferGraph,
                         @Qualifier("billQueryGraph") CompiledGraph billQueryGraph,
                         @Qualifier("wealthConsultGraph") CompiledGraph wealthConsultGraph,
                         @Qualifier("wealthInterpretGraph") CompiledGraph wealthInterpretGraph) {
        this.intentRegistry = intentRegistry;
        this.transferGraph = transferGraph;
        this.billQueryGraph = billQueryGraph;
        this.wealthConsultGraph = wealthConsultGraph;
        this.wealthInterpretGraph = wealthInterpretGraph;
    }

    @PostConstruct
    public void bindGraphs() {
        intentRegistry.bindGraph("TRANSFER", transferGraph);
        intentRegistry.bindGraph("BILL_QUERY", billQueryGraph);
        intentRegistry.bindGraph("WEALTH_CONSULT", wealthConsultGraph);
        intentRegistry.bindGraph("WEALTH_INTERPRET", wealthInterpretGraph);
        log.info("[AppInitConfig] All graphs bound to IntentRegistry: TRANSFER, BILL_QUERY, WEALTH_CONSULT, WEALTH_INTERPRET");
    }
}
