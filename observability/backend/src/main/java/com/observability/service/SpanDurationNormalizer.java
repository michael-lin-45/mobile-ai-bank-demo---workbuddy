package com.observability.service;

import com.observability.model.SpanEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Span 时长归一化工具。
 *
 * <h2>背景（根因）</h2>
 * 上游主应用 {@code mobile-ai-bank-demo} 的 OTel 埋点中，<b>手动创建</b>的
 * {@code SERVER}（HTTP 入口 {@code POST /api/bank/chat}）与 {@code INTERNAL}
 * （L0/L1/L2 业务层）span 偶发上报畸大时长：实测 root=2373098ms、L0=1185064ms、
 * L1=1188028ms，约为真实耗时的 1000 倍；而 OTel <b>自动</b>埋点的 {@code CLIENT}
 * （真实 HTTP 调用 LLM）span 时长正常（2~5s）。
 *
 * 后端 {@link com.observability.service.OtlpParserService#parseTraces} 严格按 OTLP 规范解析
 * （{@code nanoToMillis = ns/1e6}，{@code durationMs = endTime - startTime}），无单位换算错误，
 * 因此 H2 中存储的值即上游上报值——属于「值错」而非「单位换算错」。
 *
 * <h2>本工具的兜底策略</h2>
 * 对时长超过 {@link #SANE_SPAN_CEILING_MS} 的「畸大」span，用其<b>子树中正常叶子 span
 * （{@code CLIENT}）的首末墙钟时间包络</b>（{@code min(start) / max(end)}）重算
 * {@code durationMs} 与 {@code end_time}。该包络基于真实 {@code CLIENT} span 的墙钟时间，
 * 因此重算结果落在真实耗时量级，可正确指导 E2E 总耗时条、业务层瀑布条与 TTFT 计算。
 *
 * 用法：在「落库」（{@code OtlpParserService}）与「展示」（{@code TraceQueryService}）两处调用
 * {@link #normalize(List)}，保证未来写入与既有数据展示均被校正。{@link #normalize(List)} 对
 * 正常 span 为幂等无副作用。
 */
public final class SpanDurationNormalizer {

    /**
     * 单条 span 的合理时长上限：10 分钟。
     * 本应用真实 agent / HTTP 调用均为秒级；超过即视为畸大（实测 19.75min / 39.5min）。
     * 若未来出现合法超过 10 分钟的长耗时操作，请上调此阈值。
     */
    public static final long SANE_SPAN_CEILING_MS = 10L * 60 * 1000;

    private SpanDurationNormalizer() {
    }

    /**
     * 就地归一化给定 trace 的 span 列表：仅修改「畸大」span 的时间窗，正常 span 不变。
     *
     * @param spans 同一 trace 的 {@link SpanEntity} 列表（调用方需保证属于同一 traceId）
     */
    public static void normalize(List<SpanEntity> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }

        Map<String, SpanEntity> byId = new HashMap<>();
        Map<String, List<SpanEntity>> childrenMap = new HashMap<>();
        for (SpanEntity s : spans) {
            if (s.getSpanId() != null) {
                byId.put(s.getSpanId(), s);
            }
            String pid = s.getParentSpanId();
            if (pid != null && !pid.isEmpty()) {
                childrenMap.computeIfAbsent(pid, k -> new ArrayList<>()).add(s);
            }
        }

        // spanId -> 归一化后的有效时间窗 {startMs, endMs}（memo）
        Map<String, long[]> resolved = new HashMap<>();
        Set<String> visiting = new HashSet<>();

        for (SpanEntity s : spans) {
            resolve(s.getSpanId(), byId, childrenMap, resolved, visiting);
        }

        for (SpanEntity s : spans) {
            long[] env = resolved.get(s.getSpanId());
            if (env == null || env[0] == Long.MIN_VALUE || env[1] == Long.MIN_VALUE) {
                continue; // 无法解析，保持原值，避免用 0 覆盖
            }
            long ownStart = s.getStartTime() != null ? s.getStartTime().toEpochMilli() : Long.MIN_VALUE;
            long ownEnd = s.getEndTime() != null ? s.getEndTime().toEpochMilli() : Long.MIN_VALUE;
            boolean ownSane = isSane(s);
            if (!ownSane && (env[0] != ownStart || env[1] != ownEnd)) {
                long dur = env[1] - env[0];
                if (dur >= 0) {
                    s.setStartTime(Instant.ofEpochMilli(env[0]));
                    s.setEndTime(Instant.ofEpochMilli(env[1]));
                    s.setDurationMs(dur);
                }
            }
        }
    }

    /** 该 span 时长是否处于合理区间（用于判定是否需要兜底重算）。 */
    private static boolean isSane(SpanEntity s) {
        if (s.getDurationMs() == null || s.getDurationMs() <= 0) {
            return false;
        }
        if (s.getStartTime() == null || s.getEndTime() == null) {
            return false;
        }
        return s.getDurationMs() <= SANE_SPAN_CEILING_MS;
    }

    /**
     * 递归解析某 span 的有效时间窗（memo + visiting 防环）。
     * 正常 span 直接返回自身窗；畸大 span 递归使用子节点包络；无正常子节点时返回自身（标记不可解析）。
     */
    private static long[] resolve(String spanId,
                                  Map<String, SpanEntity> byId,
                                  Map<String, List<SpanEntity>> childrenMap,
                                  Map<String, long[]> resolved,
                                  Set<String> visiting) {
        long[] cached = resolved.get(spanId);
        if (cached != null) {
            return cached;
        }
        if (visiting.contains(spanId)) {
            return null; // 环，放弃
        }
        SpanEntity s = byId.get(spanId);
        if (s == null) {
            return null;
        }

        long ownStart = s.getStartTime() != null ? s.getStartTime().toEpochMilli() : Long.MIN_VALUE;
        long ownEnd = s.getEndTime() != null ? s.getEndTime().toEpochMilli() : Long.MIN_VALUE;

        if (isSane(s)) {
            long[] r = new long[]{ownStart, ownEnd};
            resolved.put(spanId, r);
            return r;
        }

        // 畸大：尝试用子节点包络（自底向上，子节点先被归一化为真实窗）
        visiting.add(spanId);
        List<SpanEntity> children = childrenMap.get(spanId);
        long minStart = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;
        if (children != null) {
            for (SpanEntity c : children) {
                long[] cr = resolve(c.getSpanId(), byId, childrenMap, resolved, visiting);
                if (cr != null) {
                    if (cr[0] < minStart) minStart = cr[0];
                    if (cr[1] > maxEnd) maxEnd = cr[1];
                }
            }
        }
        visiting.remove(spanId);

        if (minStart != Long.MAX_VALUE && maxEnd != Long.MIN_VALUE && maxEnd >= minStart) {
            long[] r = new long[]{minStart, maxEnd};
            resolved.put(spanId, r);
            return r;
        }

        // 无法从子节点解析：保留原值（memo 以便父节点使用），调用方按 isSane 判定
        if (ownStart != Long.MIN_VALUE && ownEnd != Long.MIN_VALUE) {
            long[] r = new long[]{ownStart, ownEnd};
            resolved.put(spanId, r);
            return r;
        }
        long[] sentinel = new long[]{Long.MIN_VALUE, Long.MIN_VALUE};
        resolved.put(spanId, sentinel);
        return null;
    }
}
