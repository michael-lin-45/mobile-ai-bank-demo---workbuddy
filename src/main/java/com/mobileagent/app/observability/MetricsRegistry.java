package com.mobileagent.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集中式指标注册表（T-K P6，设计 §3.1①）。
 *
 * <p>所有新增指标统一走 {@code deepflux.<domain>.<measure>} 前缀，与存量
 * {@code agent.* / llm.* / gen_ai.*} 指标隔离，避免命名发散；存量 22 个 ObservabilityMetrics
 * 调用保持不变（不重命名，向后兼容）。
 *
 * <p>关键约定：
 * <ul>
 *   <li>Timer 强制 {@code publishPercentileHistogram(true)}（OTLP Histogram，后端只解析 Histogram，支持分位查询）；</li>
 *   <li>UpDownCounter 以 {@code AtomicLong + Gauge} 实现（Micrometer 无原生 UpDownCounter）；</li>
 *   <li>Counter/Timer/Summary 按 name+tags 缓存，避免重复注册。</li>
 * </ul>
 */
@Component
public class MetricsRegistry {

    private final MeterRegistry meterRegistry;

    /** 指标前缀（设计 §3.1①：deepflux.<domain>.<measure>） */
    private static final String PREFIX = "deepflux.";

    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> summaryCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> upDownState = new ConcurrentHashMap<>();

    public MetricsRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 启动即注册 {@code deepflux.workflow.pending_approval} Gauge（初始 0）。
     * <p>否则该 UpDownCounter 仅在真实 interrupt→approve 流量到达时才创建，
     * 导致 /actuator 在空闲期观测不到 P7 指标（符合 QA 实测结论）。
     * 注册后 interrupt(+1)/approve(-1) 仍按原语义叠加，不改动业务行为。
     */
    @PostConstruct
    public void init() {
        addUpDown("workflow.pending_approval", 0L);
    }

    /** 规整指标名：自动补齐 deepflux. 前缀（避免重复前缀） */
    private String name(String measure) {
        return measure.startsWith(PREFIX) ? measure : PREFIX + measure;
    }

    /**
     * 记录 Counter（单调+1）。
     */
    public void recordCounter(String measure, String... tags) {
        String n = name(measure);
        String key = cacheKey(n, tags);
        Counter c = counterCache.computeIfAbsent(key, k ->
                Counter.builder(n).tags(tags).register(meterRegistry));
        c.increment();
    }

    /**
     * 记录 Timer（耗时）。<b>强制直方图</b>（§3.1①）。
     */
    public void recordTimer(String measure, long durationMs, String... tags) {
        String n = name(measure);
        String key = cacheKey(n, tags);
        Timer t = timerCache.computeIfAbsent(key, k ->
                Timer.builder(n).tags(tags)
                        .publishPercentileHistogram(true)
                        .register(meterRegistry));
        t.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录 DistributionSummary。
     */
    public void recordSummary(String measure, double value, String... tags) {
        String n = name(measure);
        String key = cacheKey(n, tags);
        DistributionSummary s = summaryCache.computeIfAbsent(key, k ->
                DistributionSummary.builder(n).tags(tags).register(meterRegistry));
        s.record(value);
    }

    /**
     * UpDownCounter：基于 AtomicLong + Gauge。delta 为正/负均可。
     * 同一 (measure + tags) 维护一个共享状态，Gauge 实时暴露当前值。
     */
    public void addUpDown(String measure, long delta, String... tags) {
        String n = name(measure);
        String key = cacheKey(n, tags);
        AtomicLong state = upDownState.computeIfAbsent(key, k -> {
            AtomicLong v = new AtomicLong(0);
            Gauge.builder(n, v, AtomicLong::get)
                    .tags(tags)
                    .register(meterRegistry);
            return v;
        });
        state.addAndGet(delta);
    }

    /** 读取某 UpDownCounter 当前值（默认 0） */
    public long getUpDown(String measure, String... tags) {
        AtomicLong v = upDownState.get(cacheKey(name(measure), tags));
        return v != null ? v.get() : 0L;
    }

    private String cacheKey(String n, String... tags) {
        StringBuilder sb = new StringBuilder(n);
        if (tags != null) {
            for (String t : tags) {
                sb.append('|').append(t);
            }
        }
        return sb.toString();
    }
}
