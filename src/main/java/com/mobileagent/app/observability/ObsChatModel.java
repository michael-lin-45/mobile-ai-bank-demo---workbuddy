package com.mobileagent.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 装饰 OpenAiChatModel，拦截 call()/stream() 采集 llm.* 5 项指标。
 *
 * 指标清单:
 *   llm.token.input          — 输入 token 数 (Counter, tag: model)
 *   llm.token.output         — 输出 token 数 (Counter, tag: model)
 *   llm.first_token.latency  — 首 token 延迟 (Histogram, tag: model)
 *   llm.operation.duration   — 总操作耗时 (Histogram, tag: model)
 *   llm.error.count          — 错误次数 (Counter, tag: model + error_type)
 *
 * 流式场景 token 采集策略:
 *   1. 优先: 末尾 usage chunk (ChatResponse.getMetadata().getUsage() 非空)
 *   2. 兜底: doOnComplete 时 content.length() / 1.5 估算，标记 token.estimated=true
 *
 * TPOT = (总耗时 - TTFT) / output_tokens，在 Wrapper 内计算。
 *
 * 在 ModelConfig.buildChatModel() 返回处包一层即可，业务代码零改动。
 *
 * ─────────────────────────────────────────────────────────────
 * OTel Span 属性注入建议 (TODO — P2 改进):
 * ─────────────────────────────────────────────────────────────
 * 当前: 只记录 Micrometer Counter/Timer，不创建含业务属性的 OTel Span。
 *       自动 Span（Micrometer Bridge 生成）不含 userId / sessionId /
 *       intent 等属性，观测后端显示 "N/A" / "未采集"。
 *
 * 建议改造:
 *   1. 注入 io.micrometer.tracing.Tracer 或 OpenTelemetry tracer
 *   2. 在 call()/stream() 入口创建自定义 Span:
 *      - span.setAttribute("user_id", sessionContext.getUserId())
 *      - span.setAttribute("session_id", sessionContext.getSessionId())
 *      - span.setAttribute("intent", intentResult.getIntentName())
 *      - span.setAttribute("ai.token.system", String.valueOf(systemTokens))
 *      - span.setAttribute("ai.io.prompt", promptText)
 *   3. 或使用 AOP 切面在 Agent 层（L0/L1/L2）自动注入上下文属性
 *
 * 观测后端已支持: TraceQueryService.extractBusinessAttributes()
 * 会跨所有 Span 查找这些属性并在前端展示。
 */
@Slf4j
public class ObsChatModel implements ChatModel {

    /** 中文/英文混合文本的平均字符数 per token 估算值 */
    private static final double CHARS_PER_TOKEN_ESTIMATE = 1.5;

    private final ChatModel delegate;
    private final MeterRegistry meterRegistry;
    private final String modelName;

    /**
     * @param delegate      被装饰的原始 ChatModel (如 OpenAiChatModel)
     * @param meterRegistry Micrometer MeterRegistry (OTel 导出)
     * @param modelName     模型名称，用于 metric tag (如 "qwen-plus")
     */
    public ObsChatModel(ChatModel delegate, MeterRegistry meterRegistry, String modelName) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.modelName = modelName != null ? modelName : "unknown";
    }

    // ==================== 非流式 call() ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.nanoTime();
        io.opentelemetry.api.trace.Span span = startBusinessSpan();
        Scope scope = span != null ? span.makeCurrent() : null;
        try {
            // Set prompt attribute on span
            if (span != null) {
                String promptText = prompt != null && prompt.getInstructions() != null
                        ? prompt.getInstructions().toString() : "";
                setSpanAttribute(span, "ai.io.prompt", promptText);
            }
            ChatResponse response = delegate.call(prompt);
            long totalNs = System.nanoTime() - start;
            long ttftMs = TimeUnit.NANOSECONDS.toMillis(totalNs);

            // Set response attributes on span
            if (span != null) {
                String responseText = response.getResult() != null && response.getResult().getOutput() != null
                        ? response.getResult().getOutput().getText() : "";
                setSpanAttribute(span, "ai.io.response", responseText);
                Usage usage0 = extractUsage(response);
                if (usage0 != null) {
                    setSpanAttribute(span, "ai.token.input", String.valueOf(usage0.getPromptTokens() != null ? usage0.getPromptTokens() : 0));
                    setSpanAttribute(span, "ai.token.output", String.valueOf(usage0.getCompletionTokens() != null ? usage0.getCompletionTokens() : 0));
                }
            }

            Usage usage = extractUsage(response);
            if (usage != null) {
                recordMetrics(usage, ttftMs, ttftMs, false);
            } else {
                // 非流式无 usage 是小概率事件，用返回内容长度估算
                String content = response.getResult() != null && response.getResult().getOutput() != null
                        ? response.getResult().getOutput().getText() : "";
                long estimatedOutputTokens = content != null
                        ? Math.max(1, Math.round(content.length() / CHARS_PER_TOKEN_ESTIMATE)) : 1;
                long tpotMs = 0; // 非流式 TTFT == 总耗时，TPOT = 0
                recordEstimatedMetrics(0, estimatedOutputTokens, ttftMs, ttftMs, tpotMs);
            }

            return response;
        } catch (Exception e) {
            recordError(e);
            if (span != null) {
                setSpanAttribute(span, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
            }
            throw e;
        } finally {
            if (scope != null) scope.close();
            if (span != null) span.end();
        }
    }

    // ==================== 流式 stream() ====================

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.nanoTime();
        io.opentelemetry.api.trace.Span span = startBusinessSpan();
        Scope scope = span != null ? span.makeCurrent() : null;
        // Set prompt attribute on span
        if (span != null) {
            String promptText = prompt != null && prompt.getInstructions() != null
                    ? prompt.getInstructions().toString() : "";
            setSpanAttribute(span, "ai.io.prompt", promptText);
        }
        AtomicLong ttftNs = new AtomicLong(0);
        AtomicBoolean firstChunk = new AtomicBoolean(true);
        StringBuilder contentAccumulator = new StringBuilder();
        AtomicLong usageInputTokens = new AtomicLong(0);
        AtomicLong usageOutputTokens = new AtomicLong(0);
        AtomicBoolean usageCollected = new AtomicBoolean(false);

        return delegate.stream(prompt)
            .doOnNext(response -> {
                long now = System.nanoTime();
                // 记录首 token 时间
                if (firstChunk.getAndSet(false)) {
                    ttftNs.set(now - start);
                }
                // 检测末尾 usage chunk
                Usage usage = extractUsage(response);
                if (usage != null) {
                    usageInputTokens.set(usage.getPromptTokens() != null ? usage.getPromptTokens() : 0);
                    usageOutputTokens.set(usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0);
                    usageCollected.set(true);
                }
                // 累积内容
                if (response.getResult() != null && response.getResult().getOutput() != null) {
                    String text = response.getResult().getOutput().getText();
                    if (text != null) {
                        contentAccumulator.append(text);
                    }
                }
            })
            .doOnComplete(() -> {
                long totalNs = System.nanoTime() - start;
                long ttftMs = ttftNs.get() > 0
                        ? TimeUnit.NANOSECONDS.toMillis(ttftNs.get())
                        : TimeUnit.NANOSECONDS.toMillis(totalNs);
                long totalMs = TimeUnit.NANOSECONDS.toMillis(totalNs);

                // Set response attributes on span
                if (span != null) {
                    setSpanAttribute(span, "ai.io.response", contentAccumulator.toString());
                    if (usageCollected.get()) {
                        setSpanAttribute(span, "ai.token.input", String.valueOf(usageInputTokens.get()));
                        setSpanAttribute(span, "ai.token.output", String.valueOf(usageOutputTokens.get()));
                    }
                    if (scope != null) scope.close();
                    span.end();
                }

                if (usageCollected.get()) {
                    long tpotMs = usageOutputTokens.get() > 0
                            ? (totalMs - ttftMs) / usageOutputTokens.get() : 0;
                    recordStreamMetrics(usageInputTokens.get(), usageOutputTokens.get(),
                            ttftMs, totalMs, tpotMs);
                } else {
                    // 兜底估算
                    String content = contentAccumulator.toString();
                    long estimatedOutput = content.length() > 0
                            ? Math.max(1, Math.round(content.length() / CHARS_PER_TOKEN_ESTIMATE)) : 1;
                    long tpotMs = (totalMs - ttftMs) / estimatedOutput;
                    recordEstimatedMetrics(0, estimatedOutput, ttftMs, totalMs, tpotMs);
                    log.debug("[ObsChatModel] Using estimated tokens: contentLen={}, estimatedOutput={}, model={}",
                            content.length(), estimatedOutput, modelName);
                }
            })
            .doOnError(e -> {
                recordError(e);
                if (span != null) {
                    setSpanAttribute(span, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR);
                    if (scope != null) scope.close();
                    span.end();
                }
            });
    }

    // ==================== 指标记录 ====================

    /**
     * 基于精确 Usage 记录指标（非流式 + 流式 usage chunk）
     */
    private void recordMetrics(Usage usage, long ttftMs, long totalMs, boolean estimated) {
        safeRecord(() -> {
            long inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            long outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            long tpotMs = outputTokens > 0 ? (totalMs - ttftMs) / outputTokens : 0;

            if (inputTokens > 0) {
                Counter.builder("llm.token.input")
                        .description("LLM input token count")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(inputTokens, Integer.MAX_VALUE)));
            }

            if (outputTokens > 0) {
                Counter.builder("llm.token.output")
                        .description("LLM output token count")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(outputTokens, Integer.MAX_VALUE)));
            }

            Timer.builder("llm.first_token.latency")
                    .description("Time to first token")
                    .tag("model", modelName)
                    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(ttftMs, TimeUnit.MILLISECONDS);

            Timer.builder("llm.operation.duration")
                    .description("Total LLM operation duration")
                    .tag("model", modelName)
                    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(totalMs, TimeUnit.MILLISECONDS);

            if (estimated) {
                Counter.builder("llm.token.estimated")
                        .description("Token estimation fallback used (not from usage chunk)")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .increment();
            }
        });
    }

    /**
     * 基于精确 Usage 记录流式指标
     */
    private void recordStreamMetrics(long inputTokens, long outputTokens,
                                      long ttftMs, long totalMs, long tpotMs) {
        safeRecord(() -> {
            if (inputTokens > 0) {
                Counter.builder("llm.token.input")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(inputTokens, Integer.MAX_VALUE)));
            }
            if (outputTokens > 0) {
                Counter.builder("llm.token.output")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(outputTokens, Integer.MAX_VALUE)));
            }
            Timer.builder("llm.first_token.latency")
                    .tag("model", modelName)
                    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(ttftMs, TimeUnit.MILLISECONDS);

            Timer.builder("llm.operation.duration")
                    .tag("model", modelName)
                    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(totalMs, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * 兜底估算记录（无 usage chunk 时）
     */
    private void recordEstimatedMetrics(long inputTokens, long outputTokens,
                                         long ttftMs, long totalMs, long tpotMs) {
        safeRecord(() -> {
            if (inputTokens > 0) {
                Counter.builder("llm.token.input")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(inputTokens, Integer.MAX_VALUE)));
            }
            if (outputTokens > 0) {
                Counter.builder("llm.token.output")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(outputTokens, Integer.MAX_VALUE)));
            }
            Timer.builder("llm.first_token.latency")
                    .tag("model", modelName)
                    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(ttftMs, TimeUnit.MILLISECONDS);

            Timer.builder("llm.operation.duration")
                    .tag("model", modelName)
                    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(totalMs, TimeUnit.MILLISECONDS);

            Counter.builder("llm.token.estimated")
                    .tag("model", modelName)
                    .register(meterRegistry)
                    .increment();

            if (tpotMs > 0) {
                Timer.builder("llm.token.per.output.time")
                        .tag("model", modelName)
                        .register(meterRegistry)
                        .record(tpotMs, TimeUnit.MILLISECONDS);
            }
        });
    }

    /**
     * 记录错误指标
     */
    private void recordError(Throwable e) {
        safeRecord(() -> {
            String errorType = e != null ? e.getClass().getSimpleName() : "unknown";
            Counter.builder("llm.error.count")
                    .description("LLM error count")
                    .tag("model", modelName)
                    .tag("error_type", errorType)
                    .register(meterRegistry)
                    .increment();
        });
    }

    // ==================== Helpers ====================

    // ==================== OTel Span Helpers ====================

    /**
     * 创建含业务属性的 OTel Span。
     * 从 AgentSpanContext (ThreadLocal) 读取 agent 层级 / intent / sessionId 等信息。
     * 如果 Tracer 或 AgentSpanContext 不可用，返回 null（不影响主业务）。
     */
    private io.opentelemetry.api.trace.Span startBusinessSpan() {
        try {
            io.opentelemetry.api.trace.Tracer otelTracer = GlobalOpenTelemetry.getTracer("obs-chat-model");
            AgentSpanContext ctx = AgentSpanContext.get();
            String spanName;
            if (ctx != null && ctx.getAgentLayer() != null) {
                spanName = ctx.getAgentLayer() + ":" + modelName;
            } else {
                spanName = "llm:" + modelName;
            }

            io.opentelemetry.api.trace.SpanBuilder builder = otelTracer.spanBuilder(spanName)
                    .setSpanKind(SpanKind.INTERNAL);

            io.opentelemetry.api.trace.Span span = builder.startSpan();

            // Set base attributes directly on span
            setSpanAttribute(span, "agent.name", ctx != null ? ctx.getAgentName() : modelName);
            setSpanAttribute(span, "model.name", modelName);

            // Set business attributes from AgentSpanContext
            if (ctx != null) {
                setSpanAttribute(span, "agent.layer", ctx.getAgentLayer());
                setSpanAttribute(span, "intent", ctx.getIntent());
                setSpanAttribute(span, "session_id", ctx.getSessionId());
                setSpanAttribute(span, "user_id", ctx.getUserId());
                log.info("[ObsChatModel] Business span created: name={}, layer={}, intent={}, sessionId={}, span={}",
                    spanName, ctx.getAgentLayer(), ctx.getIntent(), ctx.getSessionId(), span);
            } else {
                log.info("[ObsChatModel] Business span created: name={}, no AgentSpanContext", spanName);
            }

            // Also propagate via Baggage so child HTTP spans inherit these attributes
            if (ctx != null) {
                io.opentelemetry.api.baggage.Baggage baggage = io.opentelemetry.api.baggage.Baggage.builder()
                        .put("agent.layer", ctx.getAgentLayer() != null ? ctx.getAgentLayer() : "")
                        .put("intent", ctx.getIntent() != null ? ctx.getIntent() : "")
                        .put("session_id", ctx.getSessionId() != null ? ctx.getSessionId() : "")
                        .build();
                io.opentelemetry.context.Context.current().with(baggage).makeCurrent();
            }

            return span;
        } catch (Exception e) {
            log.debug("[ObsChatModel] Failed to create business span: {}", e.getMessage());
            return null;
        }
    }

    private void setSpanAttribute(io.opentelemetry.api.trace.Span span, String key, String value) {
        if (span == null || key == null || value == null) return;
        try {
            String truncated = value.length() > 2000 ? value.substring(0, 2000) : value;
            span.setAttribute(key, truncated);
        } catch (Exception e) {
            log.debug("[ObsChatModel] Failed to set span attribute {}: {}", key, e.getMessage());
        }
    }

    /**
     * 从 ChatResponse 提取 Usage 信息。
     * 优先从 metadata.usage 获取，其次从 metadata 中的 usage 相关 key 获取。
     */
    private Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            // 验证 usage 是否有效（promptTokens 或 completionTokens 至少有一个 > 0）
            if ((usage.getPromptTokens() != null && usage.getPromptTokens() > 0)
                    || (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0)) {
                return usage;
            }
        }
        return null;
    }

    /** 安全记录 — try-catch 包裹，不影响主业务 */
    private void safeRecord(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[ObsChatModel] Failed to record metric: {}", e.getMessage());
        }
    }
}
