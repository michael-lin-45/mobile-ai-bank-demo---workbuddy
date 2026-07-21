package com.mobileagent.app.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 上下文泄漏回归测试（治未病）。
 *
 * <p>修复目标：WebFlux/Reactor 下 OTel Context 跨线程/跨请求泄漏（trace 耗时 2373098ms / TTFT=0 / intent 串标）。
 * 根因是未启用 Reactor 自动上下文传播，导致手写 INTERNAL span（DomainRouter / ObsDocumentRetriever /
 * ObsReRanker，与本测试 startSpan() 同模式）在 startSpan() 时取到的 Context.current() 是上一个请求残留上下文。
 *
 * <p>本测试覆盖<b>同步上下文卫生契约</b>：模拟两个顺序请求在同一线程上执行，断言
 * (1) 两次请求拿到独立 traceId（不跨请求合并）；
 * (2) 每个 child span 与【本次】请求的 root 共享 traceId（不继承上一个请求残留）；
 * (3) 全部 scope 关闭后，当前上下文无残留 span，且新建 span 取得全新 traceId（证明线程局部已清理，不会泄漏）。
 * 这与 OtelContextConfig 启用 Hooks.enableAutomaticContextPropagation() 后，agent 的 reactor 桥接
 * 在运行期为每个请求提供干净、独立的 Context 同源。
 *
 * <p>说明：纯 OTel-through-Reactor 的端到端模拟需 agent 的 reactor 插桩（opentelemetry-reactor-1.0，
 * 运行期由 javaagent 提供、不在单测 classpath 内），故单测聚焦同步卫生契约 + 确认 OtelContextConfig 可正常加载启用。
 * 完整效果需联调 agent 运行主程序确认。
 */
class OtelContextPropagationTest {

    private static OpenTelemetrySdk sdk;
    private static Tracer tracer;

    @BeforeAll
    static void setup() {
        // 触发 OtelContextConfig 类加载（静态块调用 Hooks.enableAutomaticContextPropagation()）。
        // 若配置类或启用调用有问题，这里会抛异常使测试失败。
        assertDoesNotThrow(() ->
                Class.forName("com.mobileagent.app.observability.OtelContextConfig"));

        // 无 processor 的 SdkTracerProvider：span 不导出，但 SpanContext/traceId 仍可用于断言
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        GlobalOpenTelemetry.set(sdk);
        tracer = sdk.getTracer("test-leak");
    }

    @AfterAll
    static void tearDown() {
        sdk.close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void noCrossRequestContextLeak() {
        // ── 请求 1 ──
        Span root1 = tracer.spanBuilder("SERVER-req1").startSpan();
        Span child1;
        try (Scope s1 = root1.makeCurrent()) {
            // 与 DomainRouter/ObsDocumentRetriever/ObsReRanker 同模式：startSpan() 以 Context.current() 为 parent
            child1 = tracer.spanBuilder("L0:DomainRouter").setSpanKind(SpanKind.INTERNAL).startSpan();
            // child 必须与本次 root1 同 trace（即 parent 正确指向 root1）
            assertEquals(root1.getSpanContext().getTraceId(),
                    child1.getSpanContext().getTraceId(),
                    "请求1 的 child 必须与本次 root1 同 trace（parent 正确）");
        } // s1.close() 清除线程局部 —— 关键清理动作
        child1.end();
        root1.end();

        // ── 请求 2：同一线程，不重新 makeCurrent 任何 span（模拟线程池复用、无残留上下文）──
        Span root2 = tracer.spanBuilder("SERVER-req2").startSpan();
        Span child2;
        try (Scope s2 = root2.makeCurrent()) {
            child2 = tracer.spanBuilder("L0:DomainRouter").setSpanKind(SpanKind.INTERNAL).startSpan();
            assertEquals(root2.getSpanContext().getTraceId(),
                    child2.getSpanContext().getTraceId(),
                    "请求2 的 child 必须与本次 root2 同 trace（parent 正确）");
        }
        child2.end();
        root2.end();

        // 两次请求必须是独立 traceId（不得跨请求合并 → 否则 intent 串标、duration 虚高）
        assertNotEquals(root1.getSpanContext().getTraceId(),
                root2.getSpanContext().getTraceId(),
                "两次请求必须是独立 traceId，不得跨请求合并");
        // child2 绝不得继承请求1 的 trace（无跨请求上下文泄漏的铁证）
        assertNotEquals(root1.getSpanContext().getTraceId(),
                child2.getSpanContext().getTraceId(),
                "child2 不得继承请求1 的 trace，证明无跨请求上下文泄漏");

        // ── 线程局部清理断言：全部 scope 关闭后，当前上下文不应残留任何 span ──
        Span stale = Span.fromContext(Context.current());
        assertFalse(stale.getSpanContext().isValid(),
                "全部 scope 关闭后当前上下文不应残留 span（证明线程局部已清理，不会跨请求泄漏）");

        // 额外场景：无当前上下文时新建的 span 必须取得全新 traceId（不偷偷继承任何历史 root）
        Span orphan = tracer.spanBuilder("orphan-after-cleanup").startSpan();
        assertNotEquals(root1.getSpanContext().getTraceId(), orphan.getSpanContext().getTraceId(),
                "orphan 不得继承 root1 的 trace");
        assertNotEquals(root2.getSpanContext().getTraceId(), orphan.getSpanContext().getTraceId(),
                "orphan 不得继承 root2 的 trace");
        orphan.end();
    }
}
