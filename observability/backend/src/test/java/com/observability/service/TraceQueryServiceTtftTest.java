package com.observability.service;

import com.observability.model.SpanEntity;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.RedisMetricsSnapshotRepository;
import com.observability.repository.SessionRepository;
import com.observability.repository.SessionTurnRepository;
import com.observability.repository.SpanRepository;
import com.observability.service.AIInsightsService;
import com.observability.service.RedisMetricsService;
import com.observability.service.TraceQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归验证 TraceQueryService.computeTTFT 的新实现（按「用户权威定义」计算 TTFT）。
 *
 * 核心规则：TTFT = 最深层 Agent（L2>L1>L0）「首次完整回复 span」的首 TOKEN 时间 − 请求起点
 * （root SERVER span 的 startTime）。旧实现取「最晚开始的最深层 span 起点」，会抓到 trace 尾部的
 * 收尾/泄漏 span（~2865ms），把 TTFT 夸大成 ~2873ms。新实现应只选「产出 TOKEN 的 answer span」。
 *
 * 另含护栏验证（B）：MetricsQueryService 的 P95 快照护栏（>SANE_SPAN_CEILING_MS 当 0）与
 * computeE2eDurationMs 的泄漏 trace 护栏（返回 null）。
 *
 * SpanEntity 构造沿用 SpanDurationNormalizerTest 的 span(...) 风格，额外设置 operationName / attributes。
 */
class TraceQueryServiceTtftTest {

    private static final long T0 = Instant.parse("2026-07-20T02:31:16.665Z").toEpochMilli();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceQueryService traceSvc = new TraceQueryService(
            mock(SpanRepository.class), mock(SessionRepository.class), mock(SessionTurnRepository.class),
            mock(RedisMetricsService.class), objectMapper);

    // ───────────────────────── 构造辅助 ─────────────────────────

    /** 类似 SpanDurationNormalizerTest.span(...)，但额外设置 operationName 与 attributes(JSON)。 */
    private SpanEntity span(String id, String parent, String kind, String op,
                            long startMs, long durMs, String attrsJson) {
        SpanEntity e = new SpanEntity();
        e.setSpanId(id);
        e.setParentSpanId(parent);
        e.setKind(kind);
        e.setOperationName(op);
        e.setStartTime(Instant.ofEpochMilli(startMs));
        e.setEndTime(Instant.ofEpochMilli(startMs + durMs));
        e.setDurationMs(durMs);
        if (attrsJson != null && !attrsJson.isEmpty()) {
            e.setAttributes(attrsJson);
        }
        return e;
    }

    /** 构建 attributes JSON：键值对 ("k1","v1","k2","v2")。 */
    private static String attrs(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(kv[i]).append("\":\"").append(kv[i + 1]).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    // ───────────────────────── A. computeTTFT 单元测试 ─────────────────────────

    /**
     * A1（验收主用例）：标准 L0→L1→L2，每层多次 LLM。
     * root SERVER 覆盖全程；最深层 L2 的最终 answer span（L2-LLM2）首 TOKEN 在 T0+1500。
     * 期望 TTFT = (T0+1500) − T0 = 1500ms（精确数值）。
     */
    @Test
    void standardL0L1L2MultipleLlmPerLayerTtftIs1500() {
        List<SpanEntity> spans = new ArrayList<>(List.of(
                span("root", null, "SERVER", "POST /api/bank/chat", T0, 2000, null),
                span("l0", "root", "INTERNAL", "L0:Agent", T0 + 50, 250, attrs("agent.layer", "L0")),
                span("l1", "root", "INTERNAL", "L1:Agent", T0 + 300, 500, attrs("agent.layer", "L1")),
                span("l2", "root", "INTERNAL", "L2:Agent", T0 + 800, 1200, attrs("agent.layer", "L2")),
                // L2 内 2 次 LLM；最终 answer span 带 llm.first_token_time = 绝对 epoch (T0+1500)
                span("l2llm1", "l2", "INTERNAL", "L2-LLM1", T0 + 850, 300, attrs("agent.layer", "L2")),
                span("l2llm2", "l2", "INTERNAL", "L2-LLM2", T0 + 1200, 600,
                        attrs("agent.layer", "L2", "llm.first_token_time", String.valueOf(T0 + 1500)))
        ));
        Long ttft = traceSvc.computeTTFT(spans);
        assertEquals(1500L, ttft,
                "TTFT must equal deepest answer span first-token (T0+1500) − request start (T0)");
    }

    /**
     * A1 变体：L2-LLM2 不设 llm.first_token_time，startTime=T0+1500 → 走 startTime 兜底，TTFT 仍为 1500。
     */
    @Test
    void standardL0L1L2TtftUsesStartTimeFallbackWhenNoFirstTokenAttr() {
        List<SpanEntity> spans = new ArrayList<>(List.of(
                span("root", null, "SERVER", "POST /api/bank/chat", T0, 2000, null),
                span("l0", "root", "INTERNAL", "L0:Agent", T0 + 50, 250, attrs("agent.layer", "L0")),
                span("l1", "root", "INTERNAL", "L1:Agent", T0 + 300, 500, attrs("agent.layer", "L1")),
                span("l2", "root", "INTERNAL", "L2:Agent", T0 + 800, 1200, attrs("agent.layer", "L2")),
                span("l2llm1", "l2", "INTERNAL", "L2-LLM1", T0 + 850, 300, attrs("agent.layer", "L2")),
                // 无 llm.first_token_time，首 TOKEN 兜底为自身 startTime = T0+1500
                span("l2llm2", "l2", "INTERNAL", "L2-LLM2", T0 + 1500, 600, attrs("agent.layer", "L2"))
        ));
        Long ttft = traceSvc.computeTTFT(spans);
        assertEquals(1500L, ttft,
                "Without llm.first_token_time, TTFT uses answer span startTime (T0+1500) − request start");
    }

    /**
     * A2（2873ms 旧 bug 回归守卫，任务字面示例 op="L2:Cleanup"）：
     * trace 尾部有一个很晚才开始(T0+2865)、但不产出 TOKEN 的收尾/泄漏 span
     * （op=L2:Cleanup，agent.layer=L2，无 llm.first_token_time）。真正的 L2 answer span 首 TOKEN 在 T0+1500。
     * 新实现必须排除 Cleanup span，取 1500 而非 ~2865。
     *
     * 注：此用例用以验证「最深层非 TOKEN 的 L2* 收尾 span 不应被抓取」。若本用例失败（返回 ~2865），
     * 说明 isAnswerProducing() 把任何 L2* 前缀 op 都当成 answer-producing（与其 Javadoc 自述矛盾），
     * 属于修复不完整——详见运行后向 software-engineer 的反馈。
     */
    @Test
    void regression2873BugNotGrabbingLatestStartedL2CleanupSpan() {
        List<SpanEntity> spans = new ArrayList<>(List.of(
                span("root", null, "SERVER", "POST /api/bank/chat", T0, 3000, null), // 覆盖到 T0+3000，使 2865 落在 trace 内（护栏不触发）
                span("l0", "root", "INTERNAL", "L0:Agent", T0 + 50, 250, attrs("agent.layer", "L0")),
                span("l1", "root", "INTERNAL", "L1:Agent", T0 + 300, 500, attrs("agent.layer", "L1")),
                span("l2", "root", "INTERNAL", "L2:Agent", T0 + 800, 1200, attrs("agent.layer", "L2")),
                span("l2llm1", "l2", "INTERNAL", "L2-LLM1", T0 + 850, 300, attrs("agent.layer", "L2")),
                span("l2llm2", "l2", "INTERNAL", "L2-LLM2", T0 + 1200, 600,
                        attrs("agent.layer", "L2", "llm.first_token_time", String.valueOf(T0 + 1500))),
                // 泄漏/收尾 span：很晚才开始、不产出 TOKEN；通过 agent.layer=L2 处于最深层
                span("cleanup", "l2", "INTERNAL", "L2:Cleanup", T0 + 2865, 50, attrs("agent.layer", "L2"))
        ));
        Long ttft = traceSvc.computeTTFT(spans);
        assertEquals(1500L, ttft,
                "Must pick deepest answer span (T0+1500), NOT the late cleanup span (T0+2865)");
    }

    /**
     * A2 变体（任务另一合法构造「某 INTERNAL span」）：收尾 span 用非 LLM op
     * （op=Cleanup，不以后缀 L0/L1/L2/llm: 开头、不含 -LLM），仅通过 agent.layer=L2 处于最深层。
     * 该 span 不产出 TOKEN → 必须被排除，TTFT=1500。此用例用以确认核心排除机制本身工作正常。
     */
    @Test
    void regression2873BugExcludesNonLlmLeakSpan() {
        List<SpanEntity> spans = new ArrayList<>(List.of(
                span("root", null, "SERVER", "POST /api/bank/chat", T0, 3000, null),
                span("l0", "root", "INTERNAL", "L0:Agent", T0 + 50, 250, attrs("agent.layer", "L0")),
                span("l1", "root", "INTERNAL", "L1:Agent", T0 + 300, 500, attrs("agent.layer", "L1")),
                span("l2", "root", "INTERNAL", "L2:Agent", T0 + 800, 1200, attrs("agent.layer", "L2")),
                span("l2llm2", "l2", "INTERNAL", "L2-LLM2", T0 + 1200, 600,
                        attrs("agent.layer", "L2", "llm.first_token_time", String.valueOf(T0 + 1500))),
                span("leak", "l2", "INTERNAL", "Cleanup", T0 + 2865, 50, attrs("agent.layer", "L2"))
        ));
        Long ttft = traceSvc.computeTTFT(spans);
        assertEquals(1500L, ttft,
                "Non-LLM leak span (op=Cleanup) must be excluded; TTFT=1500");
    }

    /**
     * A3（无 L2）：最深层为 L1，answer span 在 L1。
     * 期望 TTFT = L1 answer span 首 TOKEN(T0+800) − 请求起点(T0) = 800ms。
     */
    @Test
    void noL2DeepestIsL1AnswerSpan() {
        List<SpanEntity> spans = new ArrayList<>(List.of(
                span("root", null, "SERVER", "POST /api/bank/chat", T0, 1000, null),
                span("l0", "root", "INTERNAL", "L0:Agent", T0 + 50, 250, attrs("agent.layer", "L0")),
                span("l1", "root", "INTERNAL", "L1:Agent", T0 + 300, 600, attrs("agent.layer", "L1")),
                span("l1llm1", "l1", "INTERNAL", "L1-LLM1", T0 + 350, 200, attrs("agent.layer", "L1")),
                span("l1llm2", "l1", "INTERNAL", "L1-LLM2", T0 + 600, 200,
                        attrs("agent.layer", "L1", "llm.first_token_time", String.valueOf(T0 + 800)))
        ));
        Long ttft = traceSvc.computeTTFT(spans);
        assertEquals(800L, ttft,
                "With no L2, TTFT uses L1 answer span first-token (T0+800) − request start");
    }

    /**
     * A4（异常中断）：最深层 L2 只有一个「不产出 TOKEN、非 LLM op」的包装 span
     * （op 不以 L0/L1/L2/llm: 开头、不含 -LLM）。不应崩溃，返回兜底值（非负、且 ≤ trace 总时长）。
     */
    @Test
    void deepestLayerWithoutAnswerSpanFallsBackGracefully() {
        List<SpanEntity> spans = new ArrayList<>(List.of(
                span("root", null, "SERVER", "POST /api/bank/chat", T0, 2000, null),
                span("l0", "root", "INTERNAL", "L0:Agent", T0 + 50, 250, attrs("agent.layer", "L0")),
                span("l1", "root", "INTERNAL", "L1:Agent", T0 + 300, 500, attrs("agent.layer", "L1")),
                // 非 LLM op（"AgentL2Internal"），无 token 属性 → 最深层无 answer-producing span
                span("l2wrap", "l1", "INTERNAL", "AgentL2Internal", T0 + 800, 400, attrs("agent.layer", "L2"))
        ));
        Long ttft = traceSvc.computeTTFT(spans);
        assertNotNull(ttft, "Should not return null for a well-formed deepest layer");
        assertTrue(ttft >= 0, "TTFT must not be negative");
        assertTrue(ttft <= 2000L, "TTFT must not exceed total trace duration");
    }

    /**
     * A5（护栏越界）：把 answer span 的 llm.first_token_time 设成离谱大值（>=1e12 视为绝对 epoch），
     * 使 computed ttft 远超 trace 总时长。护栏必须回退到 answer span 起点，结果 ≤ 总时长（绝不 > 总时长）。
     */
    @Test
    void guardrailPreventsTtftExceedingTotalDuration() {
        List<SpanEntity> spans = new ArrayList<>(List.of(
                span("root", null, "SERVER", "POST /api/bank/chat", T0, 2000, null), // 总时长 = 2000ms
                span("l0", "root", "INTERNAL", "L0:Agent", T0 + 50, 250, attrs("agent.layer", "L0")),
                span("l1", "root", "INTERNAL", "L1:Agent", T0 + 300, 500, attrs("agent.layer", "L1")),
                span("l2", "root", "INTERNAL", "L2:Agent", T0 + 800, 1200, attrs("agent.layer", "L2")),
                // 离谱的 llm.first_token_time：T0 + 10_000_000（远超总时长），但 startTime 仍合理 (T0+1200)
                span("l2llm2", "l2", "INTERNAL", "L2-LLM2", T0 + 1200, 600,
                        attrs("agent.layer", "L2", "llm.first_token_time", String.valueOf(T0 + 10_000_000L)))
        ));
        Long ttft = traceSvc.computeTTFT(spans);
        assertNotNull(ttft, "Guardrail must return a bounded value, not null for sane answer span start");
        assertTrue(ttft >= 0, "TTFT must not be negative");
        assertTrue(ttft <= 2000L, "TTFT must never exceed total trace duration (guardrail)");
    }

    // ───────────────────────── B. 护栏验证 ─────────────────────────

    /** 构造 MetricsQueryService，注入指定 snapshotRepository mock。 */
    private MetricsQueryService metricsSvcWith(RedisMetricsSnapshotRepository snap) {
        return new MetricsQueryService(
                mock(RedisMetricsService.class), mock(MetricsAggRepository.class), snap,
                mock(SpanRepository.class), new ObjectMapper(),
                mock(AIInsightsService.class), mock(TraceQueryService.class));
    }

    /** 反射调用 private 方法。 */
    private static Object invokePrivate(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /**
     * B1：computeE2eDurationMs（private）对含 >600_000ms 墙钟跨度的泄漏 trace 返回 null。
     * 构造 root SERVER 无 durationMs、墙钟跨度 700_000ms（> 上限），走墙钟分支触发护栏。
     */
    @Test
    void e2eGuardrailReturnsNullForLeakedWallClock() throws Exception {
        RedisMetricsSnapshotRepository snap = mock(RedisMetricsSnapshotRepository.class);
        MetricsQueryService svc = metricsSvcWith(snap);

        SpanEntity root = new SpanEntity();
        root.setSpanId("root");
        root.setParentSpanId(null);
        root.setKind("SERVER");
        root.setOperationName("POST /api/bank/chat");
        root.setStartTime(Instant.ofEpochMilli(T0));
        root.setEndTime(Instant.ofEpochMilli(T0 + 700_000L)); // 700s 墙钟 > 600s 上限
        root.setDurationMs(null); // 强制走墙钟分支
        List<SpanEntity> spans = List.of(root);

        Long e2e = (Long) invokePrivate(svc, "computeE2eDurationMs", new Class[]{List.class}, spans);
        assertNull(e2e, "Leaked span wall clock (>600_000ms) must yield null, not a garbage E2E value");
    }

    /**
     * B2（必须有）：P95 快照护栏。用 Mockito 桩 SnapshotRepository，使 latency_p95:1m 返回旧 bug 的夸张值
     * 2_370_295.0（> SANE_SPAN_CEILING_MS=600_000）。断言 getLatencyStatsWithFallback 把该垃圾快照当 0 回退。
     * 这直接证明快照护栏生效——getRealtime 不会把 2_370_295 这类值透出为大屏 P95。
     */
    @Test
    void p95SnapshotGuardrailConvertsGarbageToZero() throws Exception {
        RedisMetricsSnapshotRepository snap = mock(RedisMetricsSnapshotRepository.class);
        when(snap.findLatestValueByKey(anyString())).thenReturn(Optional.empty());
        when(snap.findLatestValueByKey("latency_p95:1m")).thenReturn(Optional.of(2_370_295.0));
        MetricsQueryService svc = metricsSvcWith(snap);

        @SuppressWarnings("unchecked")
        Map<String, Double> result = (Map<String, Double>) invokePrivate(
                svc, "getLatencyStatsWithFallback", new Class[]{String.class, Map.class}, "1m", null);

        assertEquals(0.0, result.get("p95"), 1e-9,
                "Garbage P95 snapshot (>ceiling) must fall back to 0");
        assertEquals(0.0, result.get("avg"), 1e-9);
        assertEquals(0.0, result.get("p50"), 1e-9);
    }

    /**
     * B2 补充（代码审查断言）：SANE_SPAN_CEILING_MS 已提升为 public static final 且 == 600_000，
     * 供 MetricsQueryService 护栏复用（避免重复魔法数字）。
     */
    @Test
    void saneSpanCeilingIsPublicAndEquals600000() {
        assertEquals(600_000L, SpanDurationNormalizer.SANE_SPAN_CEILING_MS,
                "SANE_SPAN_CEILING_MS must be public and equal to 600_000ms (10 minutes)");
    }
}
