package com.observability.service;

import com.observability.model.SpanEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SpanDurationNormalizer：对「畸大」SERVER/INTERNAL span 用子树中正常
 * CLIENT span 的墙钟包络兜底重算 durationMs，使其回落到真实耗时量级。
 *
 * 复现线上异常 trace（root=2373098ms / L0=1185064ms，CLIENT 正常 3~5s）。
 */
class SpanDurationNormalizerTest {

    private static final long T0 = Instant.parse("2026-07-20T02:31:16.665Z").toEpochMilli();

    private SpanEntity span(String id, String parent, String kind, long startMs, long durMs) {
        SpanEntity e = new SpanEntity();
        e.setSpanId(id);
        e.setParentSpanId(parent);
        e.setKind(kind);
        e.setStartTime(Instant.ofEpochMilli(startMs));
        e.setEndTime(Instant.ofEpochMilli(startMs + durMs));
        e.setDurationMs(durMs);
        return e;
    }

    @Test
    void degenerateServerAndInternalSpansAreRecomputedFromClientEnvelope() {
        // 根 SERVER（畸大 2373098ms）+ L0 INTERNAL（畸大 1185064ms），二者均被正常 CLIENT 子 span 包裹
        SpanEntity root = span("root", null, "SERVER", T0, 2_373_098L);
        SpanEntity l0 = span("l0", "root", "INTERNAL", T0 + 1000, 1_185_064L);
        SpanEntity c1 = span("c1", "l0", "CLIENT", T0 + 2000, 3000);   // 正常
        SpanEntity c2 = span("c2", "l0", "CLIENT", T0 + 3000, 5000);   // 正常

        List<SpanEntity> spans = new ArrayList<>(List.of(root, l0, c1, c2));

        // 前置断言：归一化前确实是畸大值
        assertEquals(2_373_098L, root.getDurationMs());
        assertEquals(1_185_064L, l0.getDurationMs());

        SpanDurationNormalizer.normalize(spans);

        // CLIENT（正常）保持不变
        assertEquals(3000, c1.getDurationMs());
        assertEquals(5000, c2.getDurationMs());

        // L0 = 其 CLIENT 子树的墙钟包络 = (c2 end) - (c1 start) = (T0+8000) - (T0+2000) = 6000ms
        assertEquals(6000, l0.getDurationMs());
        assertTrue(l0.getDurationMs() < SpanDurationNormalizer.SANE_SPAN_CEILING_MS);

        // root = 整棵 trace 子类树（经 CLIENT 叶子）的墙钟包络 = (c2 end) - (c1 start) = (T0+8000) - (T0+2000) = 6000ms
        assertEquals(6000, root.getDurationMs());
        assertTrue(root.getDurationMs() < SpanDurationNormalizer.SANE_SPAN_CEILING_MS);
    }

    @Test
    void saneSpansAreLeftUntouched() {
        SpanEntity a = span("a", null, "SERVER", T0, 1500);
        SpanEntity b = span("b", "a", "INTERNAL", T0 + 100, 2000);
        List<SpanEntity> spans = new ArrayList<>(List.of(a, b));

        SpanDurationNormalizer.normalize(spans);

        assertEquals(1500, a.getDurationMs());
        assertEquals(2000, b.getDurationMs());
    }
}
