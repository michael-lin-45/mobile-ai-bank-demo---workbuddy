package com.observability.service;

import com.observability.repository.MetricsAggRepository;
import com.observability.repository.SessionRepository;
import com.observability.repository.SessionTurnRepository;
import com.observability.repository.SpanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * P13 只读闸门单测（T-C，设计 §5.4）。
 *
 * <p>InsightsEngineService 仅注入只读 Repo/Service；通过 {@code @Value("${insights.readonly:true}")}
 * 的 readOnly 字段在 generateReport()/refresh() 入口经 assertReadOnly() 拦截写操作。
 *
 * <p>本测试用 Mockito mock 7 个只读依赖，构造纯单元测试（不启动 Spring 上下文）：
 * <ul>
 *   <li>readOnly=false → 调用 generateReport()/refresh() 必须抛 UnsupportedOperationException；</li>
 *   <li>readOnly=true（默认）→ 不抛，正常返回报告 Map。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InsightsEngineReadonlyTest {

    @Mock
    private MetricsAggRepository metricsAggRepository;
    @Mock
    private SpanRepository spanRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SessionTurnRepository sessionTurnRepository;
    @Mock
    private AIInsightsService aiInsightsService;
    @Mock
    private RedisMetricsService redisMetricsService;
    @Mock
    private MetricsQueryService metricsQueryService;

    /** 构造引擎（纯 new，readOnly 为 Java 原始默认值 false，需经反射显式设置） */
    private InsightsEngineService newService() {
        return new InsightsEngineService(metricsAggRepository, spanRepository,
                sessionRepository, sessionTurnRepository, aiInsightsService, redisMetricsService,
                metricsQueryService);
    }

    /** 经反射设置 private readOnly 字段（绕过 @Value 注入，模拟配置切换） */
    private void setReadOnly(InsightsEngineService svc, boolean value) throws Exception {
        Field f = InsightsEngineService.class.getDeclaredField("readOnly");
        f.setAccessible(true);
        f.set(svc, value);
    }

    // ---- 正向：readOnly=false 必须抛 UnsupportedOperationException ----

    @Test
    void readOnlyFalse_generateReport_throws() throws Exception {
        InsightsEngineService svc = newService();
        setReadOnly(svc, false);
        assertThrows(UnsupportedOperationException.class, svc::generateReport);
    }

    @Test
    void readOnlyFalse_refresh_throws() throws Exception {
        InsightsEngineService svc = newService();
        setReadOnly(svc, false);
        assertThrows(UnsupportedOperationException.class, svc::refresh);
    }

    // ---- 反向：readOnly=true（默认）不抛，正常返回 ----

    @Test
    void readOnlyTrue_generateReport_ok() throws Exception {
        InsightsEngineService svc = newService();
        setReadOnly(svc, true);
        stubReadOnlyRepos();
        Map<String, Object> report = assertDoesNotThrow(() -> svc.generateReport());
        assertNotNull(report, "readOnly=true 时应正常返回报告，不应抛 UnsupportedOperationException");
    }

    @Test
    void readOnlyTrue_refresh_ok() throws Exception {
        InsightsEngineService svc = newService();
        setReadOnly(svc, true);
        stubReadOnlyRepos();
        Map<String, Object> report = assertDoesNotThrow(() -> svc.refresh());
        assertNotNull(report, "readOnly=true 时应正常刷新并返回报告，不应抛 UnsupportedOperationException");
    }

    /** 为 readOnly=true 路径提供最小只读桩数据，避免 computeReport 内 NPE */
    private void stubReadOnlyRepos() {
        lenient().when(redisMetricsService.getLatencyStats(any())).thenReturn(java.util.Collections.emptyMap());
        lenient().when(aiInsightsService.computeIntentAccuracy(any(), any())).thenReturn(0.0);
        lenient().when(aiInsightsService.computeBusinessSuccessRate(any(), any())).thenReturn(0.0);
        lenient().when(sessionRepository.findBySatisfactionRating(any(), any(), any(), any()))
                .thenReturn(Page.empty());
        lenient().when(sessionRepository.countByStartTimeBetween(any(), any())).thenReturn(0L);
        lenient().when(sessionRepository.findByStatusAndStartTimeBetween(any(), any(), any(), any()))
                .thenReturn(Page.empty());
    }
}
