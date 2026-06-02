package com.mobileagent.app.config;

import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mobileagent.app.domain.AbstractDomainService;
import com.mobileagent.app.domain.ChatService;
import com.mobileagent.app.domain.MultiSubAgentDomainService;
import com.mobileagent.app.domain.SingleSubAgentDomainService;
import com.mobileagent.app.memory.DomainStateAware;
import com.mobileagent.app.memory.model.DomainState;
import com.mobileagent.app.memory.GlobalSessionStateStore;
import com.mobileagent.app.execution.GraphExecutionEngine;
import com.mobileagent.app.router.ContextRouter;
import com.mobileagent.app.router.DomainServiceRegistry;
import com.mobileagent.app.router.IntentRegistry;
import com.mobileagent.app.router.IntentResolver;
import com.mobileagent.app.router.IntentRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * L1领域服务配置 - 用Builder构建SingleSubAgentDomainService/MultiSubAgentDomainService实例
 *
 * 替代旧的@Service注解式TransferService/BillService/WealthService。
 * 所有调用逻辑由基类提供，这里只做配置（意图、领域名、模板路径等）。
 *
 * 每个Bean构建后注册到DomainServiceRegistry，供BankController统一分发。
 *
 * DomainStateAware 注册 Bean 是轻量级的无依赖对象,
 * 仅声明 OverAllState 中的 key/strategy/initialState, 与 domain service 本身解耦,
 * 避免 domain service → GlobalSessionStateStore → KeyStrategyFactory → domain service 的循环依赖。
 */
@Configuration
public class DomainServiceConfig {

    // ==================== 域状态注册 (零依赖, 供 KeyStrategyFactory 自动收集) ====================

    @Bean
    public DomainStateAware transferDomainStateAware() {
        return new DomainStateAware() {
            @Override public String getStateKey() { return "_transferState"; }
            @Override public com.alibaba.cloud.ai.graph.KeyStrategy getStateStrategy() { return new ReplaceStrategy(); }
            @Override public DomainState initialState() { return new DomainState("TRANSFER"); }
        };
    }

    @Bean
    public DomainStateAware billDomainStateAware() {
        return new DomainStateAware() {
            @Override public String getStateKey() { return "_billState"; }
            @Override public com.alibaba.cloud.ai.graph.KeyStrategy getStateStrategy() { return new ReplaceStrategy(); }
            @Override public DomainState initialState() { return new DomainState("BILL"); }
        };
    }

    @Bean
    public DomainStateAware wealthDomainStateAware() {
        return new DomainStateAware() {
            @Override public String getStateKey() { return "_wealthState"; }
            @Override public com.alibaba.cloud.ai.graph.KeyStrategy getStateStrategy() { return new ReplaceStrategy(); }
            @Override public DomainState initialState() { return new DomainState("WEALTH"); }
        };
    }

    // ==================== 转账 (1-1: TRANSFER) ====================

    @Bean("transferDomainService")
    public SingleSubAgentDomainService transferDomainService(
            ContextRouter contextRouter,
            IntentRouter intentRouter,
            GraphExecutionEngine graphExecutionEngine,
            IntentRegistry intentRegistry,
            GlobalSessionStateStore globalSessionStore,
            DomainServiceRegistry domainServiceRegistry,
            @Value("${routing.history.l1-max-pairs:6}") int l1MaxPairs) {
        SingleSubAgentDomainService service = SingleSubAgentDomainService.builder()
                .domainName("转账")
                .logTag("TransferService")
                .domainKey("TRANSFER")
                .intent("TRANSFER")
                .intentDescription("转账操作")
                .contextRouter(contextRouter)
                .intentRouter(intentRouter)
                .graphExecutionEngine(graphExecutionEngine)
                .intentRegistry(intentRegistry)
                .globalSessionStore(globalSessionStore)
                .l1DomainPairs(l1MaxPairs)
                .build();
        domainServiceRegistry.register("TRANSFER", service);
        return service;
    }

    // ==================== 账单 (1-1: BILL_QUERY) ====================

    @Bean("billDomainService")
    public SingleSubAgentDomainService billDomainService(
            ContextRouter contextRouter,
            IntentRouter intentRouter,
            GraphExecutionEngine graphExecutionEngine,
            IntentRegistry intentRegistry,
            GlobalSessionStateStore globalSessionStore,
            DomainServiceRegistry domainServiceRegistry,
            @Value("${routing.history.l1-max-pairs:6}") int l1MaxPairs) {
        SingleSubAgentDomainService service = SingleSubAgentDomainService.builder()
                .domainName("账单")
                .logTag("BillService")
                .domainKey("BILL")
                .intent("BILL_QUERY")
                .intentDescription("账单查询")
                .contextRouter(contextRouter)
                .intentRouter(intentRouter)
                .graphExecutionEngine(graphExecutionEngine)
                .intentRegistry(intentRegistry)
                .globalSessionStore(globalSessionStore)
                .l1DomainPairs(l1MaxPairs)
                .build();
        domainServiceRegistry.register("BILL", service);
        return service;
    }

    // ==================== 理财 (1-N: WEALTH_CONSULT + WEALTH_INTERPRET) ====================

    @Bean("wealthDomainService")
    public MultiSubAgentDomainService wealthDomainService(
            ContextRouter contextRouter,
            IntentResolver intentResolver,
            GraphExecutionEngine graphExecutionEngine,
            IntentRegistry intentRegistry,
            GlobalSessionStateStore globalSessionStore,
            DomainServiceRegistry domainServiceRegistry,
            @Value("${routing.history.l1-max-pairs:6}") int l1MaxPairs) {
        MultiSubAgentDomainService service = MultiSubAgentDomainService.builder()
                .domainName("理财")
                .logTag("WealthService")
                .domainKey("WEALTH")
                .contextRouter(contextRouter)
                .intentResolver(intentResolver)
                .graphExecutionEngine(graphExecutionEngine)
                .intentRegistry(intentRegistry)
                .globalSessionStore(globalSessionStore)
                .routingTemplatePath("prompts/l1-routing.st")
                .intentionTemplatePath("prompts/l1-intention.st")
                .rejectedMessage("该理财功能暂不支持，目前仅支持理财咨询和理财产品解读")
                .handledIntents(List.of(
                        new AbstractDomainService.IntentInfo("WEALTH_CONSULT", "理财咨询与推荐"),
                        new AbstractDomainService.IntentInfo("WEALTH_INTERPRET", "理财产品解读")
                ))
                .maxSuspendedDepth(3)
                .suspendedExpireMinutes(20)
                .l1DomainPairs(l1MaxPairs)
                .build();
        domainServiceRegistry.register("WEALTH", service);
        return service;
    }

    // ==================== CHAT ====================

    @Bean
    public ChatServiceRegistration chatServiceRegistration(ChatService chatService, DomainServiceRegistry domainServiceRegistry) {
        domainServiceRegistry.register("CHAT", chatService);
        return new ChatServiceRegistration();
    }

    private static class ChatServiceRegistration {}
}
