package com.mobileagent.app.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Scope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP 工具调用环绕回调（V23 B5 / 任务分解 M5）。
 *
 * <p>统一收口所有 MCP 工具调用：
 * <ul>
 *   <li>打点：recordToolCallCount / recordToolCallDuration 重载，注入 {@code category=mcp} 扩展 tag；</li>
 *   <li>追踪：发射 OTel span {@code MCP:<tool>}，挂 {@code mcp.tool.name} / {@code mcp.category} 属性；</li>
 *   <li>异常：span 标记 ERROR 并透传异常，打点 result=fail。</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>
 *   mcpToolCallback.execute("queryBalance", () -> mcpClient.call("queryBalance", args));
 * </pre>
 */
@Slf4j
@Component
public class McpToolCallback {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("mcp-tool", "1.0.0");
    private static final String CATEGORY = "mcp";

    private final ObservabilityMetrics metrics;

    public McpToolCallback(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 环绕执行 MCP 工具调用。
     *
     * @param tool 工具名（如 queryBalance）
     * @param call 实际调用（Supplier 返回结果）
     * @param <R>  返回类型
     * @return 调用结果（成功原样返回，失败向上抛出）
     */
    public <R> R execute(String tool, Supplier<R> call) {
        String spanName = "MCP:" + tool;
        String instrumentedName = "MCP:" + tool;

        Span span = TRACER.spanBuilder(spanName)
                .setAttribute("mcp.tool.name", tool)
                .setAttribute("mcp.category", CATEGORY)
                .startSpan();

        Map<String, String> extraTags = new HashMap<>();
        extraTags.put("category", CATEGORY);

        // 调用开始计数
        metrics.recordToolCallCount(instrumentedName, "start", extraTags);

        long start = System.currentTimeMillis();
        try (Scope ignored = span.makeCurrent()) {
            R result = call.get();
            span.setStatus(StatusCode.OK);
            long dur = System.currentTimeMillis() - start;
            metrics.recordToolCallDuration(instrumentedName, dur, extraTags);
            metrics.recordToolCallCount(instrumentedName, "success", extraTags);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            long dur = System.currentTimeMillis() - start;
            metrics.recordToolCallDuration(instrumentedName, dur, extraTags);
            metrics.recordToolCallCount(instrumentedName, "fail", extraTags);
            throw e;
        } finally {
            span.end();
        }
    }
}
