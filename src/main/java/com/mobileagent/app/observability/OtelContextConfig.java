package com.mobileagent.app.observability;

import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * 启用 Reactor 自动上下文传播，修复 WebFlux / 响应式链上的 OTel Context 跨线程、跨请求泄漏。
 *
 * <p>问题根因（与 trace 耗时 2373098ms / TTFT=0 / intent 串标同源）：
 * 未启用时，OTel {@code Context} 不会随 Reactor 的 subscriber {@code Context} 在响应式线程跳转间传播。
 * 于是手写 INTERNAL span（{@code DomainRouter} / {@code ObsDocumentRetriever} / {@code ObsReRanker}）
 * 在 {@code startSpan()} 时取到的 {@code Context.current()} 是线程池上<b>上一个请求残留</b>的上下文，
 * 被当成 parent → traceId 跨请求合并（36 请求塌缩成 ~10 traceId）、intent 串标、duration 虚高到几百秒；
 * 根 span 时间基准被拉歪还导致观测后端 TTFT 差值 >60s 触发 {@code return 0L} 坏钳制。
 *
 * <p>修复：启用 Reactor 自动上下文传播后，随应用启动挂载的 OTel Java agent 的 reactor 插桩
 * （agent jar 内 {@code reactor/v3_4/operator/ContextPropagationOperatorInstrumentation}）
 * 会把 OTel {@code Context} 与 Reactor {@code Context} 桥接起来——每个请求的 span 在响应式链路各算子内
 * 都能取到<b>当前请求</b>的正确上下文，从根本上杜绝跨请求泄漏。
 *
 * <p>用静态初始化块而非 {@code @PostConstruct}，是为了：(1) 不引入 jakarta/javax 歧义；
 * (2) 保证在类被组件扫描加载时（Spring 上下文 refresh 阶段，远早于请求进入）即最早生效。
 * {@link Hooks#enableAutomaticContextPropagation()} 幂等，可重复调用。
 */
@Configuration
public class OtelContextConfig {

    static {
        Hooks.enableAutomaticContextPropagation();
    }
}
