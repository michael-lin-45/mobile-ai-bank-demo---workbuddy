package com.mobileagent.app.config;

import com.mobileagent.app.domain.AbstractDomainService;
import com.mobileagent.app.domain.MultiSubAgentDomainService;
import com.mobileagent.app.domain.SingleSubAgentDomainService;
import com.mobileagent.app.execution.GraphExecutionService;
import com.mobileagent.app.rewriter.ContextRewriter;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.router.IntentResolver;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * L1领域服务配置 - 用Builder构建SingleSubAgentDomainService/MultiSubAgentDomainService实例
 *
 * 替代旧的@Service注解式TransferService/BillService/WealthService。
 * 所有调用逻辑由基类提供，这里只做配置（意图、领域名、模板路径等）。
 *
 * BankController通过@Qualifier注入对应的AbstractDomainService实例。
 */
@Configuration
public class DomainServiceConfig {

    // ==================== 转账 (1-1: TRANSFER) ====================

    @Bean("transferDomainService")
    public SingleSubAgentDomainService transferDomainService(
            ContextRouter contextRouter,
            ContextRewriter contextRewriter,
            GraphExecutionService graphExecutionService,
            IntentRegistry intentRegistry,
            @Qualifier("transferChatMemory") ChatMemory transferChatMemory) {
        return SingleSubAgentDomainService.builder()
                .domainName("转账")
                .logTag("TransferService")
                .intent("TRANSFER")
                .intentDescription("转账操作")
                .chatMemory(transferChatMemory)
                .contextRouter(contextRouter)
                .contextRewriter(contextRewriter)
                .graphExecutionService(graphExecutionService)
                .intentRegistry(intentRegistry)
                .build();
    }

    // ==================== 账单 (1-1: BILL_QUERY) ====================

    @Bean("billDomainService")
    public SingleSubAgentDomainService billDomainService(
            ContextRouter contextRouter,
            ContextRewriter contextRewriter,
            GraphExecutionService graphExecutionService,
            IntentRegistry intentRegistry,
            @Qualifier("billChatMemory") ChatMemory billChatMemory) {
        return SingleSubAgentDomainService.builder()
                .domainName("账单")
                .logTag("BillService")
                .intent("BILL_QUERY")
                .intentDescription("账单查询")
                .chatMemory(billChatMemory)
                .contextRouter(contextRouter)
                .contextRewriter(contextRewriter)
                .graphExecutionService(graphExecutionService)
                .intentRegistry(intentRegistry)
                .build();
    }

    // ==================== 理财 (1-N: WEALTH_CONSULT + WEALTH_INTERPRET) ====================

    @Bean("wealthDomainService")
    public MultiSubAgentDomainService wealthDomainService(
            ContextRouter contextRouter,
            IntentResolver intentResolver,
            GraphExecutionService graphExecutionService,
            IntentRegistry intentRegistry,
            @Qualifier("wealthChatMemory") ChatMemory wealthChatMemory) {
        return MultiSubAgentDomainService.builder()
                .domainName("理财")
                .logTag("WealthService")
                .chatMemory(wealthChatMemory)
                .contextRouter(contextRouter)
                .intentResolver(intentResolver)
                .graphExecutionService(graphExecutionService)
                .intentRegistry(intentRegistry)
                .routingTemplatePath("prompts/l1-routing.st")
                .intentionTemplatePath("prompts/l1-intention.st")
                .rejectedMessage("该理财功能暂不支持，目前仅支持理财咨询和理财产品解读")
                .handledIntents(List.of(
                        new AbstractDomainService.IntentInfo("WEALTH_CONSULT", "理财咨询与推荐"),
                        new AbstractDomainService.IntentInfo("WEALTH_INTERPRET", "理财产品解读")
                ))
                .maxSuspendedDepth(3)
                .suspendedExpireMinutes(20)
                .build();
    }
}
