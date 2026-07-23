package com.observability.service;

import com.observability.dto.SlowSessionBlock;
import com.observability.dto.UnsatisfiedBlock;
import com.observability.dto.UnsatisfiedBlock.UnsatisfiedCluster;
import com.observability.model.Session;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.SessionRepository;
import com.observability.repository.SessionTurnRepository;
import com.observability.repository.SpanRepository;
import com.observability.service.AIInsightsService;
import com.observability.service.InsightsEngineService;
import com.observability.service.MetricsQueryService;
import com.observability.service.RedisMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * InsightsEngineService 条件触发区验证 — 锚定 PRD M4③ / 设计 §8.5.3：
 *  - 慢会话根因：P90 > 3.0s 触发（设计/PRD 明确要求 3s）；
 *  - 不满意共性：不满意率 > 10% 触发（设计/PRD 明确要求 10%）。
 *
 * <p>契约判定原则（任务说明「以设计文档为准」）：slowSessionSummaries() / clusterUnsatisfied()
 * 为 private 方法，通过反射直测条件逻辑；断言以设计/PRD 验收为基准。
 *
 * <p>【V23 阈值修复已落地】
 *  - InsightsEngineService.java:314 thresholdSeconds = 3.0（设计/PRD 要求 3s）；
 *  - InsightsEngineService.java:207 用 rate > 10.0（设计/PRD 要求 10%）。
 *  本测试按 3s / 10% 契约编写，下列边界用例验证修复后行为：
 *  P90>3s 触发、rate>10% 触发；P90<3s / rate≤10% 不触发。
 */
@ExtendWith(MockitoExtension.class)
class InsightsEngineServiceConditionTest {

    @Mock private MetricsAggRepository metricsAggRepository;
    @Mock private SpanRepository spanRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private SessionTurnRepository sessionTurnRepository;
    @Mock private AIInsightsService aiInsightsService;
    @Mock private RedisMetricsService redisMetricsService;
    @Mock private MetricsQueryService metricsQueryService;

    private InsightsEngineService service;

    @BeforeEach
    void setUp() {
        service = new InsightsEngineService(
                metricsAggRepository, spanRepository, sessionRepository,
                sessionTurnRepository, aiInsightsService, redisMetricsService, metricsQueryService);
    }

    private SlowSessionBlock invokeSlow() throws Exception {
        Method m = InsightsEngineService.class.getDeclaredMethod("slowSessionSummaries");
        m.setAccessible(true);
        return (SlowSessionBlock) m.invoke(service);
    }

    private UnsatisfiedBlock invokeUnsat() throws Exception {
        Method m = InsightsEngineService.class.getDeclaredMethod("clusterUnsatisfied");
        m.setAccessible(true);
        return (UnsatisfiedBlock) m.invoke(service);
    }

    /**
     * 构造一个可控的「不满意」分页（total 取自 getTotalElements，与源码口径一致）。
     * 用 mock(Page.class) 显式桩定 getTotalElements()/getContent()，以便独立控制
     * total 与 content（二者可不等，如 total=120 而本页 content 仅 1 条）。
     * 调用方须用 doReturn(unsatPage(...)).when(repo).findBySatisfactionRating(...)，
     * 先求值 unsatPage 再注册桩，避免 when(...).thenReturn(unsatPage(...)) 的嵌套桩冲突
     * （Mockito UnfinishedStubbingException）。
     */
    @SuppressWarnings("unchecked")
    private Page<Session> unsatPage(long total, List<Session> content) {
        Page<Session> p = mock(Page.class);
        when(p.getTotalElements()).thenReturn(total);
        when(p.getContent()).thenReturn(content);
        return p;
    }

    private Session session(String id, long dur, int turns, String flow) {
        return Session.builder()
                .sessionId(id).durationSeconds(dur).turnCount(turns).intentFlow(flow)
                .startTime(Instant.now()).build();
    }

    // ============ 慢会话：P90 > 3.0s 触发 ============

    @Test
    void slowSession_p90Above3s_triggers() throws Exception {
        // 时长 [10,8,6,4,2] → P90 = 10s > 3s → 应触发
        List<Session> sessions = List.of(
                session("s1", 10L, 5, "转账 → 失败"),
                session("s2", 8L, 4, "转账 → 失败"),
                session("s3", 6L, 3, "查询 → 成功"),
                session("s4", 4L, 2, "查询 → 成功"),
                session("s5", 2L, 1, "查询 → 成功"));
        when(sessionRepository.findByTimeRange(any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(sessions));

        SlowSessionBlock block = invokeSlow();
        assertEquals(10.0, block.getP90Seconds(), 0.001);
        assertTrue(block.isTriggered(), "P90=10s 应超过 3s 阈值并触发（当前源码 60s 会误判为未触发 → 源码偏差）");
        assertEquals(5, block.getRows().size());
        assertEquals("s1", block.getRows().get(0).getSessionId());
        assertNotNull(block.getRows().get(0).getIntentFlow());
    }

    @Test
    void slowSession_p90Below3s_notTriggered() throws Exception {
        // 时长全 1s → P90 = 1s < 3s → 不触发
        List<Session> sessions = List.of(
                session("s1", 1L, 1, "A → B"),
                session("s2", 1L, 1, "A → B"));
        when(sessionRepository.findByTimeRange(any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(sessions));

        SlowSessionBlock block = invokeSlow();
        assertEquals(1.0, block.getP90Seconds(), 0.001);
        assertFalse(block.isTriggered(), "P90=1s 未达到 3s 阈值，不应触发");
        assertEquals(2, block.getRows().size());
    }

    @Test
    void slowSession_empty_noTrigger() throws Exception {
        when(sessionRepository.findByTimeRange(any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        SlowSessionBlock block = invokeSlow();
        assertFalse(block.isTriggered());
        assertTrue(block.getRows().isEmpty());
    }

    // ============ 不满意：不满意率 > 10% 触发 ============

    @Test
    void unsatisfied_rateAbove10pct_triggers() throws Exception {
        // rate = 120/1000 = 12% > 10% → 应触发
        when(sessionRepository.countByStartTimeBetween(any(), any())).thenReturn(1000L);
        Session neg = Session.builder().sessionId("u1")
                .intentFlow("转账 → 失败").satisfactionRating("unsatisfied").build();
        doReturn(unsatPage(120L, List.of(neg))).when(sessionRepository)
                .findBySatisfactionRating(eq("unsatisfied"), any(), any(), any(PageRequest.class));

        UnsatisfiedBlock block = invokeUnsat();
        assertEquals(12.0, block.getRate(), 0.001);
        assertEquals(1000L, block.getTotal());
        assertEquals(120L, block.getUnsatisfied());
        assertTrue(block.isTriggered(), "不满意率 12% 应超过 10% 阈值并触发");
        assertEquals(1, block.getClusters().size());
        UnsatisfiedCluster c = block.getClusters().get(0);
        assertEquals("转账", c.getDimension());
        assertTrue(c.getCount() > 0);
        assertNotNull(c.getCommonPattern());
    }

    @Test
    void unsatisfied_rateBetween5and10pct_notTriggered_perSpec() throws Exception {
        // rate = 80/1000 = 8% —— 设计/PRD 要求 >10% 才触发（8% 不触发）。
        // V23 修复后源码阈值为 10%，8% 正确判定为不触发。
        when(sessionRepository.countByStartTimeBetween(any(), any())).thenReturn(1000L);
        Session neg = Session.builder().sessionId("u1")
                .intentFlow("查询 → 失败").satisfactionRating("unsatisfied").build();
        doReturn(unsatPage(80L, List.of(neg))).when(sessionRepository)
                .findBySatisfactionRating(eq("unsatisfied"), any(), any(), any(PageRequest.class));

        UnsatisfiedBlock block = invokeUnsat();
        assertEquals(8.0, block.getRate(), 0.001);
        assertFalse(block.isTriggered(), "不满意率 8% 未达到 10% 阈值，不应触发（V23 修复后源码阈值已为 10%）");
        assertEquals(1, block.getClusters().size()); // 源码按 getContent() 始终构建聚类（与触发阈值无关）
    }

    @Test
    void unsatisfied_rateBelow5pct_notTriggered() throws Exception {
        // rate = 30/1000 = 3% < 10%（也 < 5%）→ 不触发，前后端口径一致
        when(sessionRepository.countByStartTimeBetween(any(), any())).thenReturn(1000L);
        Session neg = Session.builder().sessionId("u1")
                .intentFlow("X → 失败").satisfactionRating("unsatisfied").build();
        doReturn(unsatPage(30L, List.of(neg))).when(sessionRepository)
                .findBySatisfactionRating(eq("unsatisfied"), any(), any(), any(PageRequest.class));

        UnsatisfiedBlock block = invokeUnsat();
        assertEquals(3.0, block.getRate(), 0.001);
        assertFalse(block.isTriggered());
    }

    @Test
    void unsatisfied_zeroTotal_noTrigger() throws Exception {
        when(sessionRepository.countByStartTimeBetween(any(), any())).thenReturn(0L);
        doReturn(unsatPage(0L, List.of())).when(sessionRepository)
                .findBySatisfactionRating(eq("unsatisfied"), any(), any(), any(PageRequest.class));

        UnsatisfiedBlock block = invokeUnsat();
        assertEquals(0.0, block.getRate(), 0.001);
        assertEquals(0L, block.getTotal());
        assertFalse(block.isTriggered());
        assertTrue(block.getClusters().isEmpty());
    }
}
