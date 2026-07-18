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
 * OTel Span 属性注入 (已实现):
 * ─────────────────────────────────────────────────────────────
 * 当前: 通过 ObsChatModel + AgentSpanContext 已创建含业务属性的 OTel Span
 *       (L0/L1 层级)，并在 span 上注入 session_id / intent / model /
 *       routing.mode 等属性，观测后端可正常展示（含 DomainRouter 确定性路由
 *       补的 L0:DomainRouter span）。下方为可进一步增强的属性清单（非必须）。
 *
 * 已落地的改造:
 *   1. 注入 OpenTelemetry tracer（GlobalOpenTelemetry.getTracer）
 *   2. 在 call()/stream() 入口创建自定义 Span:
 *      - span.setAttribute("session_id", sessionId)
 *      - span.setAttribute("intent", intent)
 *      - span.setAttribute("model", modelName)
 *      - span.setAttribute("routing.mode", "deterministic" | "llm_fallback")
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
     * 模型绑定的默认层级/名称。
     * 当 AgentSpanContext (ThreadLocal) 缺失或层级为空时（流式链路 / 跨线程场景 ThreadLocal 丢失），
     * 用此兜底，确保业务 span 始终带 agent.layer，不再退化为 "llm:" 前缀。
     */
    private final String defaultAgentLayer;
    private final String defaultAgentName;

    /**
     * @param delegate          被装饰的原始 ChatModel (如 OpenAiChatModel)
     * @param meterRegistry     Micrometer MeterRegistry (OTel 导出)
     * @param modelName         模型名称，用于 metric tag 与 span 名 (如 "qwen-plus")
     * @param defaultAgentLayer 该模型所属业务层级 (如 "L0" / "L1-LLM1" / "L2")，ThreadLocal 缺失时兜底
     * @param defaultAgentName  该模型的业务名称 (如 "DomainRouter" / "WealthInterpret")，ThreadLocal 缺失时兜底
     */
    public ObsChatModel(ChatModel delegate, MeterRegistry meterRegistry, String modelName,
                        String defaultAgentLayer, String defaultAgentName) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.modelName = modelName != null ? modelName : "unknown";
        this.defaultAgentLayer = defaultAgentLayer != null && !defaultAgentLayer.isBlank() ? defaultAgentLayer : "UNKNOWN";
        this.defaultAgentName = defaultAgentName != null && !defaultAgentName.isBlank() ? defaultAgentName : "unknown";
    }

    // ==================== 非流式 call() ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.nanoTime();
        ResolvedCtx rc = resolve();
        String agentLevel = rc.layer;
        String agentName = rc.name;
        String intent = rc.intent;
        io.opentelemetry.api.trace.Span span = startBusinessSpan(rc);
        // 托管模式：调用方会在解析出业务识别结果后通过 AgentSpanContext.commitIntent 结束 span，
        // 此处仅把 span 引用交回上下文，不自动 end，避免识别结果来不及回填。
        // 注意：必须用 resolve() 时持有的 ctx 强引用(rc.spanCtx)判断 held 与 attachSpan，
        // 不能再次 AgentSpanContext.get()——若 ChatClient.call() 内部切换线程，ThreadLocal 不可见会导致误判。
        boolean held = span != null && rc.spanCtx != null && rc.spanCtx.isHoldSpan();
        if (held) {
            rc.spanCtx.attachSpan(span);
        }
        if (log.isInfoEnabled()) {
            log.info("[ObsChatModel][DIAG] call() held={} layer={} name={} thread={} spanNull={}",
                    held, agentLevel, agentName, Thread.currentThread().getName(), span == null);
        }
        Scope scope = openScope(span, rc);
        boolean ended = false;
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
                recordMetrics(usage, ttftMs, ttftMs, false, agentLevel, agentName, intent);
            } else {
                // 非流式无 usage 是小概率事件，用返回内容长度估算
                String content = response.getResult() != null && response.getResult().getOutput() != null
                        ? response.getResult().getOutput().getText() : "";
                long estimatedOutputTokens = content != null
                        ? Math.max(1, Math.round(content.length() / CHARS_PER_TOKEN_ESTIMATE)) : 1;
                long tpotMs = 0; // 非流式 TTFT == 总耗时，TPOT = 0
                recordEstimatedMetrics(0, estimatedOutputTokens, ttftMs, ttftMs, tpotMs, agentLevel, agentName, intent);
            }

            return response;
        } catch (Exception e) {
            recordError(e);
            if (span != null) {
                setSpanAttribute(span, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                // 托管(held)模式: 此处【不】结束 span，交由调用方 commitIntent(fallback 识别结果) 结束并回填 intent。
                // 若此处提前 end，commitIntent 检测到 recording=false 会变成 no-op，
                // 导致 LLM 失败时的兜底 routeType(SWITCH/UNKNOWN) 丢失，#2 意图链退化为 CHAT。
                // 非托管模式: 维持原行为，正常在此 end。
                if (!held) {
                    span.end();
                    ended = true;
                }
            }
            throw e;
        } finally {
            if (scope != null) scope.close();
            // 非托管、且非异常已结束的路径：正常自动 end；托管模式交调用方 commitIntent 结束
            if (span != null && !ended && !held) span.end();
        }
    }

    // ==================== 流式 stream() ====================

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.nanoTime();
        ResolvedCtx rc = resolve();
        String agentLevel = rc.layer;
        String agentName = rc.name;
        String intent = rc.intent;
        io.opentelemetry.api.trace.Span span = startBusinessSpan(rc);
        // ⚠️ 关键：绝不在装配线程 makeCurrent()。
        // stream() 的 doOnNext/doOnComplete/doOnError 运行在 reactor 调度线程，与此处装配线程不同；
        // 装配线程打开的 Scope 无法在 reactor 线程 close，会残留在被线程池复用的装配线程上 →
        // 下一个请求 startBusinessSpan 误继承该残留上下文为 parent → traceId 跨请求合并、duration 虚高、intent 串标。
        // 业务 span 已在上一行用装配线程"当前(干净)上下文"创建，自带正确 parent 与独立 traceId；
        // 后续仅通过 span 引用直接写属性/结束(setSpanAttribute/span.end())，不依赖 ThreadLocal 当前上下文，故无需 openScope。
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
                    // 不在此 scope.close()：本回调在 reactor 线程执行，与装配线程不同；
                    // 已不再打开装配线程 scope，直接结束 span 即可。
                    span.end();
                }

                if (usageCollected.get()) {
                    long tpotMs = usageOutputTokens.get() > 0
                            ? (totalMs - ttftMs) / usageOutputTokens.get() : 0;
                    recordStreamMetrics(usageInputTokens.get(), usageOutputTokens.get(),
                            ttftMs, totalMs, tpotMs, agentLevel, agentName, intent);
                } else {
                    // 兜底估算
                    String content = contentAccumulator.toString();
                    long estimatedOutput = content.length() > 0
                            ? Math.max(1, Math.round(content.length() / CHARS_PER_TOKEN_ESTIMATE)) : 1;
                    long tpotMs = (totalMs - ttftMs) / estimatedOutput;
                    recordEstimatedMetrics(0, estimatedOutput, ttftMs, totalMs, tpotMs, agentLevel, agentName, intent);
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
                    // 同 doOnComplete：不做 scope.close()，直接结束 span。
                    span.end();
                }
            })
            .doOnCancel(() -> {
                // SSE 客户端提前断开时会触发 cancel 而非 complete/error；
                // 若不结束 span 会造成 span 悬挂(永不上报/永不结束)。此处兜底结束。
                if (span != null) {
                    setSpanAttribute(span, "cancelled", "true");
                    span.end();
                }
            });
    }

    // ==================== 指标记录 ====================

    /**
     * 基于精确 Usage 记录指标（非流式 + 流式 usage chunk）
     *
     * 注意: llm.first_token.latency / llm.operation.duration 必须使用
     * publishPercentileHistogram(true) 而非 publishPercentiles(...)，
     * 否则 Micrometer OTLP 导出器会将其导出为 OTLP Summary 类型，
     * 而观测后端 OtlpParser 只解析 Histogram/Sum/Gauge，导致 TTFT 永远为 0。
     */
    private void recordMetrics(Usage usage, long ttftMs, long totalMs, boolean estimated,
                               String agentLevel, String agentName, String intent) {
        safeRecord(() -> {
            long inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            long outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            long tpotMs = outputTokens > 0 ? (totalMs - ttftMs) / outputTokens : 0;

            if (inputTokens > 0) {
                Counter.builder("llm.token.input")
                        .description("LLM input token count")
                        .tag("model", modelName)
                        .tag("agent.level", agentLevel)
                        .tag("agent.name", agentName)
                        .tag("intent", intent)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(inputTokens, Integer.MAX_VALUE)));
            }

            if (outputTokens > 0) {
                Counter.builder("llm.token.output")
                        .description("LLM output token count")
                        .tag("model", modelName)
                        .tag("agent.level", agentLevel)
                        .tag("agent.name", agentName)
                        .tag("intent", intent)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(outputTokens, Integer.MAX_VALUE)));
            }

            buildTimer("llm.first_token.latency", "Time to first token", ttftMs, agentLevel, agentName, intent);
            buildTimer("llm.operation.duration", "Total LLM operation duration", totalMs, agentLevel, agentName, intent);

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
                                      long ttftMs, long totalMs, long tpotMs,
                                      String agentLevel, String agentName, String intent) {
        safeRecord(() -> {
            if (inputTokens > 0) {
                Counter.builder("llm.token.input")
                        .tag("model", modelName)
                        .tag("agent.level", agentLevel)
                        .tag("agent.name", agentName)
                        .tag("intent", intent)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(inputTokens, Integer.MAX_VALUE)));
            }
            if (outputTokens > 0) {
                Counter.builder("llm.token.output")
                        .tag("model", modelName)
                        .tag("agent.level", agentLevel)
                        .tag("agent.name", agentName)
                        .tag("intent", intent)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(outputTokens, Integer.MAX_VALUE)));
            }
            buildTimer("llm.first_token.latency", "Time to first token", ttftMs, agentLevel, agentName, intent);
            buildTimer("llm.operation.duration", "Total LLM operation duration", totalMs, agentLevel, agentName, intent);
        });
    }

    /**
     * 兜底估算记录（无 usage chunk 时）
     */
    private void recordEstimatedMetrics(long inputTokens, long outputTokens,
                                         long ttftMs, long totalMs, long tpotMs,
                                         String agentLevel, String agentName, String intent) {
        safeRecord(() -> {
            if (inputTokens > 0) {
                Counter.builder("llm.token.input")
                        .tag("model", modelName)
                        .tag("agent.level", agentLevel)
                        .tag("agent.name", agentName)
                        .tag("intent", intent)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(inputTokens, Integer.MAX_VALUE)));
            }
            if (outputTokens > 0) {
                Counter.builder("llm.token.output")
                        .tag("model", modelName)
                        .tag("agent.level", agentLevel)
                        .tag("agent.name", agentName)
                        .tag("intent", intent)
                        .register(meterRegistry)
                        .increment(Math.toIntExact(Math.min(outputTokens, Integer.MAX_VALUE)));
            }
            buildTimer("llm.first_token.latency", "Time to first token", ttftMs, agentLevel, agentName, intent);
            buildTimer("llm.operation.duration", "Total LLM operation duration", totalMs, agentLevel, agentName, intent);

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
     * 构造并注册 LLM Timer，统一打 agent/intent 标签，并强制使用
     * publishPercentileHistogram(true) 以导出为 OTLP Histogram（被后端 OtlpParser 解析）。
     */
    private void buildTimer(String name, String description, long valueMs,
                            String agentLevel, String agentName, String intent) {
        Timer.Builder tb = Timer.builder(name)
                .description(description)
                .tag("model", modelName);
        if (agentLevel != null && !agentLevel.isBlank()) tb.tag("agent.level", agentLevel);
        if (agentName != null && !agentName.isBlank()) tb.tag("agent.name", agentName);
        if (intent != null && !intent.isBlank()) tb.tag("intent", intent);
        tb.publishPercentileHistogram(true)
                .register(meterRegistry)
                .record(valueMs, TimeUnit.MILLISECONDS);
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
     * 层级来源优先级:
     *   1. AgentSpanContext (ThreadLocal) 中显式 set 的层级（同步调用路径，含 intent/sessionId 等富属性）
     *   2. 模型构造时绑定的 defaultAgentLayer / defaultAgentName（流式 / ThreadLocal 丢失时兜底，
     *      保证业务 span 始终带 agent.layer，不再退化为 "llm:" 前缀）
     * 如果 Tracer 不可用，返回 null（不影响主业务）。
     */
    private io.opentelemetry.api.trace.Span startBusinessSpan(ResolvedCtx rc) {
        try {
            io.opentelemetry.api.trace.Tracer otelTracer = GlobalOpenTelemetry.getTracer("obs-chat-model");
            String spanName = rc.layer + ":" + modelName;

            io.opentelemetry.api.trace.SpanBuilder builder = otelTracer.spanBuilder(spanName)
                    .setSpanKind(SpanKind.INTERNAL);

            io.opentelemetry.api.trace.Span span = builder.startSpan();

            // Set base attributes directly on span
            setSpanAttribute(span, "agent.name", rc.name != null ? rc.name : modelName);
            setSpanAttribute(span, "model.name", modelName);

            // Set business attributes (null 由 setSpanAttribute 内部兜底跳过)
            setSpanAttribute(span, "agent.layer", rc.layer);
            setSpanAttribute(span, "intent", rc.intent);
            // 写入 span 前强制 trim session_id / user_id：杜绝入口未清洗导致的 \n 污染存储
            setSpanAttribute(span, "session_id", rc.sessionId != null ? rc.sessionId.trim() : null);
            setSpanAttribute(span, "user_id", rc.userId != null ? rc.userId.trim() : null);
            log.info("[ObsChatModel] Business span created: name={}, layer={}, agentName={}, intent={}, sessionId={}, span={}",
                spanName, rc.layer, rc.name, rc.intent, rc.sessionId, span);

            // 构造 Baggage 供子 HTTP span 继承这些业务属性。
            // ⚠️ 切勿在此 makeCurrent()——返回的 Scope 若不 close 会破坏线程 OTel 上下文栈，
            //    线程池复用时下一请求的 server span 会挂到残留上下文 → traceId 跨请求泄漏
            //    （36 请求塌缩成 ~10 traceId、intent 串标、duration 高达 99s+）。
            //    改为存入 rc，由 call()/stream() 与 span 合并进同一个会被 finally 正常 close 的 scope。
            rc.baggage = io.opentelemetry.api.baggage.Baggage.builder()
                    .put("agent.layer", rc.layer != null ? rc.layer : "")
                    .put("intent", rc.intent != null ? rc.intent : "")
                    .put("session_id", rc.sessionId != null ? rc.sessionId.trim() : "")
                    .build();

            return span;
        } catch (Exception e) {
            log.debug("[ObsChatModel] Failed to create business span: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 打开覆盖 span + baggage 的单一 OTel Scope。
     * 关键：span 与 baggage 必须合并进【同一个】Scope，由 call()/stream() 在 finally / doOnComplete /
     * doOnError 里统一 close，确保线程 OTel 上下文栈严格平衡——杜绝旧实现中 baggage 单独 makeCurrent()
     * 却从不 close 导致的 traceId 跨请求泄漏。span 为 null 时返回 null（不影响主业务）。
     */
    private Scope openScope(io.opentelemetry.api.trace.Span span, ResolvedCtx rc) {
        if (span == null) return null;
        io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.current().with(span);
        if (rc != null && rc.baggage != null) {
            ctx = ctx.with(rc.baggage);
        }
        return ctx.makeCurrent();
    }

    // ==================== 上下文解析（ThreadLocal 优先，模型绑定兜底） ====================

    /** 解析后的业务上下文：层级 / 名称 / intent / sessionId / userId / 强引用 ctx */
    private static final class ResolvedCtx {
        String layer;
        String name;
        String intent;
        String sessionId;
        String userId;
        /** 保留进入 call() 时解析到的 AgentSpanContext 强引用，避免 call 期间 ThreadLocal
         *  因线程切换/提前清理而不可见，导致 held 误判为 false、span 被提前 end。 */
        AgentSpanContext spanCtx;
        /** startBusinessSpan 构造的 Baggage：不在 startBusinessSpan 内 makeCurrent（会泄漏未关闭 Scope，
         *  破坏线程 OTel 上下文栈 → traceId 跨请求泄漏），改由 call()/stream() 与 span 合并进同一个
         *  会被 finally 正常 close 的 scope。 */
        io.opentelemetry.api.baggage.Baggage baggage;
    }

    /**
     * 解析业务上下文：
     *   - 优先用 AgentSpanContext (ThreadLocal) 显式 set 的值（同步调用路径，含富属性）
     *   - 层级 / 名称为空时回退到模型构造时绑定的 defaultAgentLayer / defaultAgentName，
     *     彻底消除流式链路 / 跨线程场景下 ThreadLocal 丢失导致的 "llm:" 退化 span。
     */
    private ResolvedCtx resolve() {
        ResolvedCtx rc = new ResolvedCtx();
        AgentSpanContext ctx = AgentSpanContext.get();
        if (ctx != null) {
            rc.layer = ctx.getAgentLayer();
            rc.name = ctx.getAgentName();
            rc.intent = ctx.getIntent();
            rc.sessionId = ctx.getSessionId();
            rc.userId = ctx.getUserId();
            rc.spanCtx = ctx;
        }
        if (rc.layer == null || rc.layer.isBlank()) rc.layer = defaultAgentLayer;
        if (rc.name == null || rc.name.isBlank()) rc.name = defaultAgentName;
        return rc;
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
