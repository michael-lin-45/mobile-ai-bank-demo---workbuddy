package com.observability.service;

import com.observability.model.MetricsAgg;
import com.observability.repository.MetricsAggRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolStatsService 单元测试 —— 校验「从 metrics_agg 派生工具统计」的逻辑，
 * 并锁定构造函数签名（MetricsAggRepository 单参），防止 8f2267d 类回归。
 */
class ToolStatsServiceTest {

    private final MetricsAggRepository repo = mock(MetricsAggRepository.class);
    private final ToolStatsService service = new ToolStatsService(repo);

    /** 构造一条 metrics_agg 记录。 */
    private MetricsAgg agg(String name, String tags, double value, Instant ts) {
        return new MetricsAgg(name, tags, value, "1m", ts);
    }

    @Test
    void aggregatesToolStatsFromMetricsAgg() {
        Instant now = Instant.now();

        // ── agent.tool.call.count ──
        // transfer: success=10, failed=2 → 共 12 次
        // query:    success=5,  failed=0 → 共 5 次
        List<MetricsAgg> counts = List.of(
                agg("agent.tool.call.count", "{\"tool_name\":\"transfer\",\"result\":\"success\"}", 10.0, now),
                agg("agent.tool.call.count", "{\"tool_name\":\"transfer\",\"result\":\"failed\"}", 2.0, now),
                agg("agent.tool.call.count", "{\"tool_name\":\"query\",\"result\":\"success\"}", 5.0, now)
        );

        // ── agent.tool.call.duration（秒）──
        // 每个窗口落 avg(0.5) + sum(6.0)；按 (tool,timestamp) 取 MIN = avg。
        // transfer 两窗口 avg 均 0.5s → 均值 0.5s → 500ms
        // query    两窗口 avg 均 0.3s → 均值 0.3s → 300ms
        List<MetricsAgg> durations = List.of(
                agg("agent.tool.call.duration", "{\"tool_name\":\"transfer\"}", 0.5, now),
                agg("agent.tool.call.duration", "{\"tool_name\":\"transfer\"}", 6.0, now),
                agg("agent.tool.call.duration", "{\"tool_name\":\"transfer\"}", 0.5, now.minusSeconds(60)),
                agg("agent.tool.call.duration", "{\"tool_name\":\"transfer\"}", 6.0, now.minusSeconds(60)),
                agg("agent.tool.call.duration", "{\"tool_name\":\"query\"}", 0.3, now),
                agg("agent.tool.call.duration", "{\"tool_name\":\"query\"}", 1.5, now),
                agg("agent.tool.call.duration", "{\"tool_name\":\"query\"}", 0.3, now.minusSeconds(120)),
                agg("agent.tool.call.duration", "{\"tool_name\":\"query\"}", 1.5, now.minusSeconds(120))
        );

        when(repo.findByMetricNameAndTimestampBetweenOrderByTimestampAsc(
                eq("agent.tool.call.count"), any(), any())).thenReturn(counts);
        when(repo.findByMetricNameAndTimestampBetweenOrderByTimestampAsc(
                eq("agent.tool.call.duration"), any(), any())).thenReturn(durations);

        Map<String, Object> result = service.getToolStats(null, null);
        assertNotNull(result);

        // ── details ──
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("details");
        assertNotNull(details);
        assertEquals(2, details.size());

        // 按调用次数降序：transfer(12) 在前，query(5) 在后
        Map<String, Object> transfer = details.get(0);
        Map<String, Object> query = details.get(1);

        assertEquals("transfer", transfer.get("tool"));
        assertEquals(12L, transfer.get("calls"));
        assertEquals(10L, transfer.get("success"));
        assertEquals(2L, transfer.get("failed"));
        assertEquals(16.7, (Double) transfer.get("errorRate"), 0.1);
        assertEquals(500.0, (Double) transfer.get("avgLatency"), 1.0);
        assertEquals("—", transfer.get("p95Latency"));
        assertEquals("—", transfer.get("typicalError"));

        assertEquals("query", query.get("tool"));
        assertEquals(5L, query.get("calls"));
        assertEquals(5L, query.get("success"));
        assertEquals(0L, query.get("failed"));
        assertEquals(0.0, (Double) query.get("errorRate"), 0.001);
        assertEquals(300.0, (Double) query.get("avgLatency"), 1.0);

        // ── kpis ──
        @SuppressWarnings("unchecked")
        Map<String, Object> kpis = (Map<String, Object>) result.get("kpis");
        assertEquals(17L, kpis.get("totalCalls"));
        assertEquals(15L, kpis.get("totalSuccess"));
        assertEquals(2L, kpis.get("totalFailed"));
        assertNull(kpis.get("p95Latency"));

        // ── chart ──
        @SuppressWarnings("unchecked")
        Map<String, Object> chart = (Map<String, Object>) result.get("chart");
        @SuppressWarnings("unchecked")
        List<String> categories = (List<String>) chart.get("categories");
        assertTrue(categories.contains("transfer"));
        assertTrue(categories.contains("query"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) chart.get("series");
        assertEquals(2, series.size());
        assertEquals("成功", series.get(0).get("name"));
        assertEquals("失败", series.get(1).get("name"));
    }
}
