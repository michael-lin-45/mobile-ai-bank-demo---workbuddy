package com.mobileagent.app.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mobileagent.app.model.IntentRegistry;
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

    public AppInitConfig(IntentRegistry intentRegistry,
                         @Qualifier("transferGraph") CompiledGraph transferGraph,
                         @Qualifier("billQueryGraph") CompiledGraph billQueryGraph) {
        this.intentRegistry = intentRegistry;
        this.transferGraph = transferGraph;
        this.billQueryGraph = billQueryGraph;
    }

    @PostConstruct
    public void bindGraphs() {
        intentRegistry.bindGraph("TRANSFER", transferGraph);
        intentRegistry.bindGraph("BILL_QUERY", billQueryGraph);
        log.info("[AppInitConfig] All graphs bound to IntentRegistry");
    }
}
