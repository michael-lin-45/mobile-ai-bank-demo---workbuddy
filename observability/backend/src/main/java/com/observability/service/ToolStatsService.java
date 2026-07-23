package com.observability.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.model.MetricsAgg;
import com.observability.repository.MetricsAggRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 工具调用统计服务
 *
 * <p>数据来源：{@code metrics_agg} 表（经 OTLP 从 Core 的 Micrometer 指标落库）。
 * Core 的工具调用只产生指标、不产生 span，因此工具统计必须从指标派生，而非从 Trace/Span 派生。
 *
 * <p>两条相关指标：
 * <ul>
 *   <li>{@code agent.tool.call.count}（Counter）— tags JSON {@code {"tool_name":"<name>","result":"success|failed"}}，
 *        value = 该 1 分钟窗口内的调用次数。</li>
 *   <li>{@code agent.tool.call.duration}（Timer）— tags JSON {@code {"tool_name":"<name>"}}，
 *       value = 秒。OtlpParserService 每个窗口会落「avg」和「sum」两条记录（同名同 tags 同 timestamp），
 *       因为 avg ≤ sum，按 (tool_name, timestamp) 取 MIN 即可还原该窗口的平均耗时（秒）。</li>
 * </ul>
 *
 * <p>单位：duration 在 H2 中以「秒」存储（isSecondsUnitMetric 对 *.duration / 非 llm.* 指标返回 true），
 * 聚合后 ×1000 转回毫秒，与 UI 其余耗时保持一致。
 *
 * <p>返回结构严格对齐前端 {@code ToolCallTab.jsx} 契约：{@code {chart, kpis, details}}。
 * P95 仅存在于 Redis 热层、不在 H2，故 details 的 p95Latency 用 "—"、kpis 的 p95Latency 用 null。
 */
@Slf4j
@Service
public class ToolStatsService {

    private static final String COUNT_METRIC = "agent.tool.call.count";
    private static final String DURATION_METRIC = "agent.tool.call.duration";
    private static final String DEFAULT_WINDOW = "1m";

    private final MetricsAggRepository metricsAggRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolStatsService(MetricsAggRepository metricsAggRepository) {
        this.metricsAggRepository = metricsAggRepository;
    }

    /**
     * 获取工具调用统计（chart / kpis / details）。
     *
     * @param from 开始时间（null 则默认最近 7 天）
     * @param to   结束时间（null 则默认 now）
     */
    public Map<String, Object> getToolStats(Instant from, Instant to) {
        if (from == null) from = Instant.now().minus(7, ChronoUnit.DAYS);
        if (to == null) to = Instant.now();

        List<MetricsAgg> countRows = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(COUNT_METRIC, from, to);
        List<MetricsAgg> durationRows = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(DURATION_METRIC, from, to);

        // ── 每工具计数聚合 ──
        Map<String, ToolAgg> byTool = new LinkedHashMap<>();
        for (MetricsAgg m : countRows) {
            String tool = toolNameFromTags(m.getTags());
            ToolAgg agg = byTool.computeIfAbsent(tool, ToolAgg::new);
            double v = m.getValue() != null ? m.getValue() : 0.0;
            String result = resultFromTags(m.getTags());
            if ("success".equals(result)) {
                agg.success += v;
            } else if ("failed".equals(result) || "error".equals(result)) {
                agg.failed += v;
            }
            agg.total += v;
        }

        // ── 每工具平均耗时（毫秒）──
        // 按 (tool_name, timestamp) 分组取 MIN（即 avg 秒），再对多窗口取均值 ×1000。
        Map<String, Map<Instant, Double>> durWindowMin = new LinkedHashMap<>();
        for (MetricsAgg m : durationRows) {
            String tool = toolNameFromTags(m.getTags());
            double v = m.getValue() != null ? m.getValue() : 0.0;
            durWindowMin
                    .computeIfAbsent(tool, k -> new LinkedHashMap<>())
                    .merge(m.getTimestamp(), v, Math::min);
        }
        Map<String, Double> avgLatencyMs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Instant, Double>> e : durWindowMin.entrySet()) {
            double meanSec = e.getValue().values().stream()
                    .mapToDouble(Double::doubleValue)
                    .average().orElse(0.0);
            avgLatencyMs.put(e.getKey(), meanSec * 1000.0);
        }

        // ── 明细行（按调用次数降序）──
        List<Map<String, Object>> details = new ArrayList<>();
        for (ToolAgg agg : byTool.values()) {
            double errorRate = agg.total > 0 ? agg.failed * 100.0 / agg.total : 0.0;
            double avgLat = avgLatencyMs.getOrDefault(agg.toolName, 0.0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool", agg.toolName);
            row.put("toolName", agg.toolName);
            row.put("calls", (long) Math.round(agg.total));
            row.put("success", (long) Math.round(agg.success));
            row.put("failed", (long) Math.round(agg.failed));
            row.put("avgLatency", round1(avgLat));
            row.put("p95Latency", "—");
            row.put("errorRate", round1(errorRate));
            row.put("typicalError", "—");
            details.add(row);
        }
        details.sort((a, b) -> Long.compare((Long) b.get("calls"), (Long) a.get("calls")));

        // ── KPI 摘要 ──
        long totalCalls = 0, totalSuccess = 0, totalFailed = 0;
        for (ToolAgg agg : byTool.values()) {
            totalCalls += Math.round(agg.total);
            totalSuccess += Math.round(agg.success);
            totalFailed += Math.round(agg.failed);
        }
        double overallErrorRate = totalCalls > 0 ? totalFailed * 100.0 / totalCalls : 0.0;
        double successRate = totalCalls > 0 ? totalSuccess * 100.0 / totalCalls : 0.0;

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalCalls", totalCalls);
        kpis.put("totalSuccess", totalSuccess);
        kpis.put("totalFailed", totalFailed);
        kpis.put("successRate", round1(successRate));
        kpis.put("errorRate", round1(overallErrorRate));
        kpis.put("overallErrorRate", round1(overallErrorRate));
        kpis.put("p95Latency", null);

        // ── 堆叠柱状图 ──
        List<String> categories = new ArrayList<>();
        List<Long> successData = new ArrayList<>();
        List<Long> failedData = new ArrayList<>();
        for (Map<String, Object> row : details) {
            categories.add((String) row.get("tool"));
            successData.add((Long) row.get("success"));
            failedData.add((Long) row.get("failed"));
        }
        Map<String, Object> successSeries = new LinkedHashMap<>();
        successSeries.put("name", "成功");
        successSeries.put("data", successData);
        successSeries.put("color", "#52c41a");
        Map<String, Object> failedSeries = new LinkedHashMap<>();
        failedSeries.put("name", "失败");
        failedSeries.put("data", failedData);
        failedSeries.put("color", "#ff4d4f");

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("categories", categories);
        chart.put("series", List.of(successSeries, failedSeries));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chart", chart);
        result.put("kpis", kpis);
        result.put("details", details);
        return result;
    }

    /** 从 tags JSON 解析 tool_name（缺省 "unknown"）。 */
    private String toolNameFromTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return "unknown";
        try {
            JsonNode node = objectMapper.readTree(tagsJson);
            if (node.has("tool_name")) return node.get("tool_name").asText();
        } catch (Exception ignored) {
            // 解析失败时退化为 unknown
        }
        return "unknown";
    }

    /** 从 tags JSON 解析 result（success/failed/error），无则 null。 */
    private String resultFromTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(tagsJson);
            if (node.has("result")) return node.get("result").asText();
        } catch (Exception ignored) {
            // 解析失败时退化为 null
        }
        return null;
    }

    /** 保留 1 位小数（用于百分比 / 毫秒）。 */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 单工具聚合中间态。 */
    private static final class ToolAgg {
        final String toolName;
        double total = 0.0;
        double success = 0.0;
        double failed = 0.0;

        ToolAgg(String toolName) {
            this.toolName = toolName;
        }
    }
}
