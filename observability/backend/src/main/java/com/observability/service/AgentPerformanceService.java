package com.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.model.MetricsAgg;
import com.observability.model.SpanEntity;
import com.observability.repository.AgentPerformanceRepository;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Agent 性能查询服务 — 实时从 Span + metrics_agg 聚合。
 *
 * 此前实现读取 agent_performance 静态表，而该表全代码无任何写入方，导致页面永远为空。
 * 现改为实时聚合:
 *  - SpanEntity（每个 LLM 调用产生一条业务 span）: 精确调用次数、真实 operation 耗时分位、
 *    ERROR 状态→错误率，以及 model/agent.name/agent.level 维度。
 *  - metrics_agg（H2）:
 *      llm.first_token.latency → TTFT 样本（修复 publishPercentileHistogram 后到达）
 *      llm.token.per.output.time → TPOT 样本
 *      llm.token.input / llm.token.output → Token 总量（cumulative，取窗口内 MAX）
 */
@Slf4j
@Service
public class AgentPerformanceService {

    // 保留构造参数以兼容 Controller（agent_performance 表已弃用，改为实时聚合）
    private final AgentPerformanceRepository agentPerformanceRepository;
    private final SpanRepository spanRepository;
    private final MetricsAggRepository metricsAggRepository;
    private final ObjectMapper objectMapper;

    public AgentPerformanceService(AgentPerformanceRepository agentPerformanceRepository,
                                   SpanRepository spanRepository,
                                   MetricsAggRepository metricsAggRepository,
                                   ObjectMapper objectMapper) {
        this.agentPerformanceRepository = agentPerformanceRepository;
        this.spanRepository = spanRepository;
        this.metricsAggRepository = metricsAggRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 对外接口 ====================

    public Map<String, Object> getPerformance(Instant from, Instant to, String dimension) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();
        if (dimension == null || dimension.isBlank()) dimension = "agent";

        Aggregates agg = computeAggregates(from, to);
        Map<String, Agg> groups = "llm".equals(dimension) ? agg.byModel : agg.byAgent;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("kpis", buildKpis(groups));
        result.put("tables", Map.of(dimension, buildTableRows(groups, "llm".equals(dimension))));
        result.put("boxplots", Map.of(dimension, buildBoxplotEntries(groups)));
        result.put("scatters", Map.of(dimension, buildScatterEntries(groups)));
        result.put("categories", Map.of(dimension, new ArrayList<>(groups.keySet())));
        return result;
    }

    public Map<String, Object> getBoxplotData(Instant from, Instant to, String dimension) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();
        if (dimension == null || dimension.isBlank()) dimension = "agent";

        Aggregates agg = computeAggregates(from, to);
        Map<String, Agg> groups = "llm".equals(dimension) ? agg.byModel : agg.byAgent;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("ttft", buildBoxplotSeries(groups, "ttft"));
        result.put("tpot", buildBoxplotSeries(groups, "tpot"));
        result.put("duration", buildBoxplotSeries(groups, "duration"));
        return result;
    }

    public Map<String, Object> getScatterData(Instant from, Instant to, String dimension) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();
        if (dimension == null || dimension.isBlank()) dimension = "agent";

        Aggregates agg = computeAggregates(from, to);
        Map<String, Agg> groups = "llm".equals(dimension) ? agg.byModel : agg.byAgent;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);

        List<Map<String, Object>> series = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            Agg g = entry.getValue();
            double avgDuration = g.durations.isEmpty() ? 0.0
                    : g.durations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", entry.getKey());
            List<Map<String, Object>> data = new ArrayList<>();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", g.calls);
            point.put("y", Math.round(avgDuration * 100.0) / 100.0);
            data.add(point);
            s.put("data", data);
            series.add(s);
        }
        result.put("series", series);
        return result;
    }

    // ==================== 聚合核心 ====================

    private Aggregates computeAggregates(Instant from, Instant to) {
        Aggregates agg = new Aggregates();

        // ── 1. 从 Span 构建调用次数 / 真实耗时分位 / 错误率（按 model 与 agent 双维度）──
        try {
            List<SpanEntity> spans = spanRepository.findByStartTimeBetweenOrderByStartTimeDesc(from, to);
            for (SpanEntity s : spans) {
                String model = extractModel(s);
                String agent = extractAgent(s);
                String level = extractLevel(s);
                if (model == null) model = "unknown";

                Agg modelAgg = agg.byModel.computeIfAbsent(model, k -> new Agg());
                modelAgg.calls++;
                if (s.getDurationMs() != null) modelAgg.durations.add(s.getDurationMs().doubleValue());
                if ("ERROR".equalsIgnoreCase(s.getStatusCode())) modelAgg.errors++;

                if (agent != null) {
                    Agg agentAgg = agg.byAgent.computeIfAbsent(agent, k -> new Agg());
                    agentAgg.level = level;
                    agentAgg.calls++;
                    if (s.getDurationMs() != null) agentAgg.durations.add(s.getDurationMs().doubleValue());
                    if ("ERROR".equalsIgnoreCase(s.getStatusCode())) agentAgg.errors++;
                }
            }
        } catch (Exception e) {
            log.warn("[AgentPerformance] Span aggregation failed: {}", e.getMessage());
        }

        // ── 2. 从 metrics_agg 补充 TTFT / TPOT / Token 总量 ──
        fillMetricSamples(agg, from, to, "llm.first_token.latency", true, false);
        fillMetricSamples(agg, from, to, "llm.token.per.output.time", false, true);
        fillTokenTotals(agg, from, to, "llm.token.input", true);
        fillTokenTotals(agg, from, to, "llm.token.output", false);

        return agg;
    }

    /** 将 histogram 指标的 avg 样本按 model/agent 收集到对应 Agg 的 ttft/tpot 列表 */
    private void fillMetricSamples(Aggregates agg, Instant from, Instant to,
                                    String metricName, boolean isTtft, boolean isTpot) {
        try {
            List<MetricsAgg> list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
            for (MetricsAgg m : list) {
                if (m.getValue() == null) continue;
                String model = extractTagValue(m.getTags(), "model");
                String agent = extractTagValue(m.getTags(), "agent.name");
                Agg g = agg.byModel.computeIfAbsent(model != null ? model : "unknown", k -> new Agg());
                if (isTtft) g.ttft.add(m.getValue());
                if (isTpot) g.tpot.add(m.getValue());
                if (agent != null) {
                    Agg ga = agg.byAgent.computeIfAbsent(agent, k -> new Agg());
                    if (isTtft) ga.ttft.add(m.getValue());
                    if (isTpot) ga.tpot.add(m.getValue());
                }
            }
        } catch (Exception e) {
            log.debug("[AgentPerformance] metric sample fill failed for {}: {}", metricName, e.getMessage());
        }
    }

    /** Token 总量（cumulative 计数器，取窗口内 MAX） */
    private void fillTokenTotals(Aggregates agg, Instant from, Instant to,
                                 String metricName, boolean isInput) {
        try {
            List<MetricsAgg> list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
            for (MetricsAgg m : list) {
                if (m.getValue() == null) continue;
                long v = m.getValue().longValue();
                String model = extractTagValue(m.getTags(), "model");
                String agent = extractTagValue(m.getTags(), "agent.name");
                Agg g = agg.byModel.computeIfAbsent(model != null ? model : "unknown", k -> new Agg());
                if (isInput) g.tokensIn = Math.max(g.tokensIn, v);
                else g.tokensOut = Math.max(g.tokensOut, v);
                if (agent != null) {
                    Agg ga = agg.byAgent.computeIfAbsent(agent, k -> new Agg());
                    if (isInput) ga.tokensIn = Math.max(ga.tokensIn, v);
                    else ga.tokensOut = Math.max(ga.tokensOut, v);
                }
            }
        } catch (Exception e) {
            log.debug("[AgentPerformance] token total fill failed for {}: {}", metricName, e.getMessage());
        }
    }

    // ==================== 结果构建 ====================

    private Map<String, Object> buildKpis(Map<String, Agg> groups) {
        long totalCalls = 0, totalErrors = 0;
        double totalDuration = 0;
        long totalTokens = 0;
        for (Agg g : groups.values()) {
            totalCalls += g.calls;
            totalErrors += g.errors;
            totalDuration += g.durations.stream().mapToDouble(Double::doubleValue).sum();
            totalTokens += g.tokensIn + g.tokensOut;
        }
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalCalls", totalCalls);
        kpis.put("avgLatency", totalCalls > 0 ? Math.round(totalDuration / totalCalls * 100.0) / 100.0 : 0.0);
        kpis.put("errorRate", totalCalls > 0 ? Math.round(totalErrors * 10000.0 / totalCalls) / 100.0 : 0.0);
        kpis.put("totalTokens", totalTokens);
        return kpis;
    }

    private List<Map<String, Object>> buildTableRows(Map<String, Agg> groups, boolean isLlm) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            String key = entry.getKey();
            Agg g = entry.getValue();
            double avgDur = g.avgDuration();
            Map<String, Object> row = new LinkedHashMap<>();
            if (isLlm) {
                row.put("agent", key);
                row.put("model", key);
            } else {
                row.put("agent", key);
                row.put("level", g.level != null ? g.level : "unknown");
            }
            row.put("calls", g.calls);
            row.put("totalLatency", Math.round(avgDur));
            row.put("ttftP50", Math.round(percentile(g.ttft, 0.50)));
            row.put("ttftP95", Math.round(percentile(g.ttft, 0.95)));
            row.put("tpotP50", Math.round(percentile(g.tpot, 0.50)));
            row.put("tpotP95", Math.round(percentile(g.tpot, 0.95)));
            long totalTok = g.tokensIn + g.tokensOut;
            row.put("totalTokens", totalTok);
            row.put("tokenDesc", g.tokensIn + "/" + g.tokensOut);
            row.put("errorRate", g.calls > 0 ? Math.round(g.errors * 10000.0 / g.calls) / 100.0 : 0.0);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildBoxplotEntries(Map<String, Agg> groups) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            Map<String, Object> box = computeBoxplot(entry.getValue().ttft);
            Map<String, Object> e = new LinkedHashMap<>(box);
            e.put("name", entry.getKey());
            entries.add(e);
        }
        return entries;
    }

    private List<Map<String, Object>> buildScatterEntries(Map<String, Agg> groups) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            Agg g = entry.getValue();
            double avgDuration = g.avgDuration();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", entry.getKey());
            List<Map<String, Object>> data = new ArrayList<>();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", g.calls);
            point.put("y", Math.round(avgDuration * 100.0) / 100.0);
            data.add(point);
            e.put("data", data);
            entries.add(e);
        }
        return entries;
    }

    private Map<String, Object> buildBoxplotSeries(Map<String, Agg> groups, String field) {
        Map<String, Object> series = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            List<Double> vals;
            if ("tpot".equals(field)) vals = entry.getValue().tpot;
            else if ("duration".equals(field)) vals = entry.getValue().durations;
            else vals = entry.getValue().ttft;
            series.put(entry.getKey(), computeBoxplot(vals));
        }
        return series;
    }

    // ==================== 维度提取 ====================

    private String extractModel(SpanEntity s) {
        String m = extractFromAttrs(s.getAttributes(), "model.name");
        if (m == null && s.getOperationName() != null) {
            int i = s.getOperationName().indexOf(':');
            if (i >= 0 && i < s.getOperationName().length() - 1) {
                m = s.getOperationName().substring(i + 1);
            }
        }
        return m;
    }

    private String extractAgent(SpanEntity s) {
        String a = extractFromAttrs(s.getAttributes(), "agent.name");
        if (a == null && s.getOperationName() != null) {
            int i = s.getOperationName().indexOf(':');
            if (i > 0) a = s.getOperationName().substring(0, i);
        }
        return a;
    }

    private String extractLevel(SpanEntity s) {
        String l = extractFromAttrs(s.getAttributes(), "agent.level");
        if (l == null) {
            String a = extractAgent(s);
            if (a != null && (a.startsWith("L0") || a.startsWith("L1") || a.startsWith("L2"))) l = a;
        }
        return l;
    }

    private String extractFromAttrs(String attrsJson, String key) {
        if (attrsJson == null || attrsJson.isBlank()) return null;
        try {
            Map<String, String> tags = objectMapper.readValue(attrsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            return tags.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTagValue(String tagsJson, String key) {
        return extractFromAttrs(tagsJson, key);
    }

    // ==================== 统计工具 ====================

    private double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    private Map<String, Object> computeBoxplot(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of("min", 0.0, "q1", 0.0, "median", 0.0, "q3", 0.0, "max", 0.0);
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return Map.of(
                "min", sorted.get(0),
                "q1", percentile(sorted, 0.25),
                "median", percentile(sorted, 0.50),
                "q3", percentile(sorted, 0.75),
                "max", sorted.get(n - 1)
        );
    }

    // ==================== 内部数据结构 ====================

    private static class Agg {
        long calls = 0;
        long errors = 0;
        String level;
        List<Double> durations = new ArrayList<>();
        List<Double> ttft = new ArrayList<>();
        List<Double> tpot = new ArrayList<>();
        long tokensIn = 0;
        long tokensOut = 0;

        double avgDuration() {
            return durations.isEmpty() ? 0.0
                    : durations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }

    private static class Aggregates {
        Map<String, Agg> byModel = new LinkedHashMap<>();
        Map<String, Agg> byAgent = new LinkedHashMap<>();
    }
}
