package com.observability.service;

import com.observability.model.AgentPerformance;
import com.observability.repository.AgentPerformanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Agent 性能查询服务 — Agent/LLM 双维度分析
 */
@Slf4j
@Service
public class AgentPerformanceService {

    private final AgentPerformanceRepository agentPerformanceRepository;

    public AgentPerformanceService(AgentPerformanceRepository agentPerformanceRepository) {
        this.agentPerformanceRepository = agentPerformanceRepository;
    }

    /**
     * 获取 Agent 性能数据
     *
     * @param from      开始时间
     * @param to        结束时间
     * @param dimension agent / llm（默认 agent）
     */
    public Map<String, Object> getPerformance(Instant from, Instant to, String dimension) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();
        if (dimension == null || dimension.isBlank()) dimension = "agent";

        List<AgentPerformance> records = agentPerformanceRepository.findByTimeRange(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);

        if ("llm".equals(dimension)) {
            return buildLlmDimension(records);
        } else {
            return buildAgentDimension(records);
        }
    }

    /**
     * 获取箱线图数据
     */
    public Map<String, Object> getBoxplotData(Instant from, Instant to, String dimension) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        List<AgentPerformance> records = agentPerformanceRepository.findByTimeRange(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension != null ? dimension : "agent");

        // 按 agentName 或 model 分组
        Map<String, List<Double>> ttftGroups = new LinkedHashMap<>();
        Map<String, List<Double>> tpotGroups = new LinkedHashMap<>();
        Map<String, List<Double>> durationGroups = new LinkedHashMap<>();

        for (AgentPerformance ap : records) {
            String groupKey = "llm".equals(dimension) ? ap.getModel() : ap.getAgentName();
            if (groupKey == null) groupKey = "unknown";

            if (ap.getTtftP50Ms() != null) {
                ttftGroups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                        .add(ap.getTtftP50Ms().doubleValue());
            }
            if (ap.getTpotP50Ms() != null) {
                tpotGroups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                        .add(ap.getTpotP50Ms().doubleValue());
            }
            if (ap.getTotalDurationMs() != null && ap.getCallCount() != null && ap.getCallCount() > 0) {
                durationGroups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                        .add(ap.getTotalDurationMs().doubleValue() / ap.getCallCount());
            }
        }

        result.put("ttft", buildBoxplotSeries(ttftGroups));
        result.put("tpot", buildBoxplotSeries(tpotGroups));
        result.put("duration", buildBoxplotSeries(durationGroups));

        return result;
    }

    /**
     * 获取散点图数据
     */
    public Map<String, Object> getScatterData(Instant from, Instant to, String dimension) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        List<AgentPerformance> records = agentPerformanceRepository.findByTimeRange(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension != null ? dimension : "agent");

        // 按组别构建散点：[x=callCount, y=avgDurationMs]
        Map<String, List<double[]>> scatterGroups = new LinkedHashMap<>();
        for (AgentPerformance ap : records) {
            String groupKey = "llm".equals(dimension) ? ap.getModel() : ap.getAgentName();
            if (groupKey == null) groupKey = "unknown";

            double callCount = ap.getCallCount() != null ? ap.getCallCount().doubleValue() : 1.0;
            double avgDuration = (ap.getTotalDurationMs() != null && ap.getCallCount() != null && ap.getCallCount() > 0)
                    ? ap.getTotalDurationMs().doubleValue() / ap.getCallCount() : 0.0;

            scatterGroups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                    .add(new double[]{callCount, avgDuration});
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (var entry : scatterGroups.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", entry.getKey());
            List<List<Double>> data = new ArrayList<>();
            for (double[] pt : entry.getValue()) {
                data.add(List.of(pt[0], pt[1]));
            }
            s.put("data", data);
            series.add(s);
        }
        result.put("series", series);

        return result;
    }

    // ── Builders ──

    private Map<String, Object> buildAgentDimension(List<AgentPerformance> records) {
        // 按 agentName 聚合
        Map<String, List<AgentPerformance>> byAgent = new LinkedHashMap<>();
        for (AgentPerformance ap : records) {
            String key = ap.getAgentName() != null ? ap.getAgentName() : "unknown";
            byAgent.computeIfAbsent(key, k -> new ArrayList<>()).add(ap);
        }

        // 全局汇总变量
        long totalCalls = 0;
        long totalDurationMs = 0;
        long totalErrorCount = 0;
        long totalTokensIn = 0;
        long totalTokensOut = 0;

        List<Map<String, Object>> tableRows = new ArrayList<>();
        List<Map<String, Object>> boxplotEntries = new ArrayList<>();
        List<Map<String, Object>> scatterSeries = new ArrayList<>();
        List<String> categoryNames = new ArrayList<>();

        for (var entry : byAgent.entrySet()) {
            String agentName = entry.getKey();
            List<AgentPerformance> agentRecords = entry.getValue();

            long calls = agentRecords.stream().mapToLong(r -> r.getCallCount() != null ? r.getCallCount() : 0).sum();
            long duration = agentRecords.stream().mapToLong(r -> r.getTotalDurationMs() != null ? r.getTotalDurationMs() : 0).sum();
            long errors = agentRecords.stream().mapToLong(r -> r.getErrorCount() != null ? r.getErrorCount() : 0).sum();
            long tokensIn = agentRecords.stream().mapToLong(r -> r.getTotalTokensIn() != null ? r.getTotalTokensIn() : 0).sum();
            long tokensOut = agentRecords.stream().mapToLong(r -> r.getTotalTokensOut() != null ? r.getTotalTokensOut() : 0).sum();

            totalCalls += calls;
            totalDurationMs += duration;
            totalErrorCount += errors;
            totalTokensIn += tokensIn;
            totalTokensOut += tokensOut;

            String agentLevel = agentRecords.get(0).getAgentLevel();

            // tables.agent 行
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent", agentName);
            row.put("level", agentLevel);
            row.put("calls", calls);
            row.put("totalLatency", calls > 0 ? duration / calls : 0L);
            row.put("ttftP50", medianOfP50s(agentRecords, "ttft"));
            row.put("ttftP95", maxOfP95s(agentRecords, "ttft"));
            row.put("tpotP50", medianOfP50s(agentRecords, "tpot"));
            row.put("tpotP95", maxOfP95s(agentRecords, "tpot"));
            row.put("totalTokens", tokensIn + tokensOut);
            row.put("errorRate", calls > 0 ? errors * 100.0 / calls : 0.0);
            tableRows.add(row);

            // boxplots.agent — 从 ttft 分布构建
            List<Double> ttftVals = new ArrayList<>();
            for (AgentPerformance ap : agentRecords) {
                if (ap.getTtftP50Ms() != null) {
                    ttftVals.add(ap.getTtftP50Ms().doubleValue());
                }
            }
            Map<String, Object> boxEntry = new LinkedHashMap<>(computeBoxplot(ttftVals));
            boxEntry.put("name", agentName);
            boxplotEntries.add(boxEntry);

            // scatters.agent
            Map<String, Object> scatterEntry = new LinkedHashMap<>();
            scatterEntry.put("name", agentName);
            double avgDurationMs = calls > 0 ? (double) duration / calls : 0.0;
            List<Map<String, Object>> data = new ArrayList<>();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", calls);
            point.put("y", avgDurationMs);
            data.add(point);
            scatterEntry.put("data", data);
            scatterSeries.add(scatterEntry);

            categoryNames.add(agentName);
        }

        // kpis
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalCalls", totalCalls);
        kpis.put("avgLatency", totalCalls > 0 ? (double) totalDurationMs / totalCalls : 0.0);
        kpis.put("errorRate", totalCalls > 0 ? (double) totalErrorCount * 100.0 / totalCalls : 0.0);
        kpis.put("totalTokens", totalTokensIn + totalTokensOut);

        // tables
        Map<String, Object> tables = new LinkedHashMap<>();
        tables.put("agent", tableRows);

        // boxplots
        Map<String, Object> boxplots = new LinkedHashMap<>();
        boxplots.put("agent", boxplotEntries);

        // scatters
        Map<String, Object> scatters = new LinkedHashMap<>();
        scatters.put("agent", scatterSeries);

        // categories
        Map<String, Object> categories = new LinkedHashMap<>();
        categories.put("agent", categoryNames);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", "agent");
        result.put("kpis", kpis);
        result.put("tables", tables);
        result.put("boxplots", boxplots);
        result.put("scatters", scatters);
        result.put("categories", categories);

        return result;
    }

    private Map<String, Object> buildLlmDimension(List<AgentPerformance> records) {
        // 按 model 聚合
        Map<String, List<AgentPerformance>> byModel = new LinkedHashMap<>();
        for (AgentPerformance ap : records) {
            String key = ap.getModel() != null ? ap.getModel() : "unknown";
            byModel.computeIfAbsent(key, k -> new ArrayList<>()).add(ap);
        }

        // 全局汇总变量
        long totalCalls = 0;
        long totalDurationMs = 0;
        long totalErrorCount = 0;
        long totalTokensIn = 0;
        long totalTokensOut = 0;

        List<Map<String, Object>> tableRows = new ArrayList<>();
        List<Map<String, Object>> boxplotEntries = new ArrayList<>();
        List<Map<String, Object>> scatterSeries = new ArrayList<>();
        List<String> categoryNames = new ArrayList<>();

        for (var entry : byModel.entrySet()) {
            String modelName = entry.getKey();
            List<AgentPerformance> modelRecords = entry.getValue();

            long calls = modelRecords.stream().mapToLong(r -> r.getCallCount() != null ? r.getCallCount() : 0).sum();
            long duration = modelRecords.stream().mapToLong(r -> r.getTotalDurationMs() != null ? r.getTotalDurationMs() : 0).sum();
            long errors = modelRecords.stream().mapToLong(r -> r.getErrorCount() != null ? r.getErrorCount() : 0).sum();
            long tokensIn = modelRecords.stream().mapToLong(r -> r.getTotalTokensIn() != null ? r.getTotalTokensIn() : 0).sum();
            long tokensOut = modelRecords.stream().mapToLong(r -> r.getTotalTokensOut() != null ? r.getTotalTokensOut() : 0).sum();

            totalCalls += calls;
            totalDurationMs += duration;
            totalErrorCount += errors;
            totalTokensIn += tokensIn;
            totalTokensOut += tokensOut;

            // tables.llm 行
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", modelName);
            row.put("calls", calls);
            row.put("totalLatency", calls > 0 ? duration / calls : 0L);
            row.put("ttftP50", medianOfP50s(modelRecords, "ttft"));
            row.put("ttftP95", maxOfP95s(modelRecords, "ttft"));
            row.put("tpotP50", medianOfP50s(modelRecords, "tpot"));
            row.put("tpotP95", maxOfP95s(modelRecords, "tpot"));
            row.put("totalTokens", tokensIn + tokensOut);
            row.put("errorRate", calls > 0 ? errors * 100.0 / calls : 0.0);
            tableRows.add(row);

            // boxplots.llm — 从 ttft 分布构建
            List<Double> ttftVals = new ArrayList<>();
            for (AgentPerformance ap : modelRecords) {
                if (ap.getTtftP50Ms() != null) {
                    ttftVals.add(ap.getTtftP50Ms().doubleValue());
                }
            }
            Map<String, Object> boxEntry = new LinkedHashMap<>(computeBoxplot(ttftVals));
            boxEntry.put("name", modelName);
            boxplotEntries.add(boxEntry);

            // scatters.llm
            Map<String, Object> scatterEntry = new LinkedHashMap<>();
            scatterEntry.put("name", modelName);
            double avgDurationMs = calls > 0 ? (double) duration / calls : 0.0;
            List<Map<String, Object>> data = new ArrayList<>();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", calls);
            point.put("y", avgDurationMs);
            data.add(point);
            scatterEntry.put("data", data);
            scatterSeries.add(scatterEntry);

            categoryNames.add(modelName);
        }

        // kpis
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalCalls", totalCalls);
        kpis.put("avgLatency", totalCalls > 0 ? (double) totalDurationMs / totalCalls : 0.0);
        kpis.put("errorRate", totalCalls > 0 ? (double) totalErrorCount * 100.0 / totalCalls : 0.0);
        kpis.put("totalTokens", totalTokensIn + totalTokensOut);

        // tables
        Map<String, Object> tables = new LinkedHashMap<>();
        tables.put("llm", tableRows);

        // boxplots
        Map<String, Object> boxplots = new LinkedHashMap<>();
        boxplots.put("llm", boxplotEntries);

        // scatters
        Map<String, Object> scatters = new LinkedHashMap<>();
        scatters.put("llm", scatterSeries);

        // categories
        Map<String, Object> categories = new LinkedHashMap<>();
        categories.put("llm", categoryNames);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", "llm");
        result.put("kpis", kpis);
        result.put("tables", tables);
        result.put("boxplots", boxplots);
        result.put("scatters", scatters);
        result.put("categories", categories);

        return result;
    }

    private Map<String, Object> aggregateAgentRecords(String agentName, List<AgentPerformance> records) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("agentName", agentName);

        String agentLevel = records.get(0).getAgentLevel();
        vo.put("agentLevel", agentLevel);

        long totalCalls = records.stream().mapToLong(r -> r.getCallCount() != null ? r.getCallCount() : 0).sum();
        long totalDuration = records.stream().mapToLong(r -> r.getTotalDurationMs() != null ? r.getTotalDurationMs() : 0).sum();
        long totalError = records.stream().mapToLong(r -> r.getErrorCount() != null ? r.getErrorCount() : 0).sum();
        long totalTokensIn = records.stream().mapToLong(r -> r.getTotalTokensIn() != null ? r.getTotalTokensIn() : 0).sum();
        long totalTokensOut = records.stream().mapToLong(r -> r.getTotalTokensOut() != null ? r.getTotalTokensOut() : 0).sum();

        vo.put("callCount", totalCalls);
        vo.put("ttftP50Ms", medianOfP50s(records, "ttft"));
        vo.put("ttftP95Ms", maxOfP95s(records, "ttft"));
        vo.put("ttftP99Ms", maxOfP95s(records, "ttft")); // 用 P95 近似
        vo.put("tpotP50Ms", medianOfP50s(records, "tpot"));
        vo.put("tpotP95Ms", maxOfP95s(records, "tpot"));
        vo.put("durationP50Ms", totalDuration > 0 && totalCalls > 0 ? totalDuration / totalCalls : 0);
        vo.put("durationP95Ms", totalDuration > 0 && totalCalls > 0 ? (totalDuration / totalCalls) * 2 : 0);
        vo.put("tokenAvgInput", totalCalls > 0 ? totalTokensIn / totalCalls : 0);
        vo.put("tokenAvgOutput", totalCalls > 0 ? totalTokensOut / totalCalls : 0);
        vo.put("errorCount", totalError);
        vo.put("completionRate", totalCalls > 0 ? (totalCalls - totalError) * 100.0 / totalCalls : 100.0);

        return vo;
    }

    private Map<String, Object> aggregateModelRecords(String modelName, List<AgentPerformance> records) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("llmModel", modelName);

        long totalCalls = records.stream().mapToLong(r -> r.getCallCount() != null ? r.getCallCount() : 0).sum();
        long totalError = records.stream().mapToLong(r -> r.getErrorCount() != null ? r.getErrorCount() : 0).sum();
        long totalTokensIn = records.stream().mapToLong(r -> r.getTotalTokensIn() != null ? r.getTotalTokensIn() : 0).sum();
        long totalTokensOut = records.stream().mapToLong(r -> r.getTotalTokensOut() != null ? r.getTotalTokensOut() : 0).sum();

        vo.put("callCount", totalCalls);
        vo.put("ttftP50Ms", medianOfP50s(records, "ttft"));
        vo.put("ttftP95Ms", maxOfP95s(records, "ttft"));
        vo.put("tpotP50Ms", medianOfP50s(records, "tpot"));
        vo.put("tpotP95Ms", maxOfP95s(records, "tpot"));
        vo.put("tokenAvgInput", totalCalls > 0 ? totalTokensIn / totalCalls : 0);
        vo.put("tokenAvgOutput", totalCalls > 0 ? totalTokensOut / totalCalls : 0);
        vo.put("errorCount", totalError);

        return vo;
    }

    // ── Stats Helpers ──

    private double medianOfP50s(List<AgentPerformance> records, String field) {
        List<Double> values = new ArrayList<>();
        for (AgentPerformance r : records) {
            Long val = "ttft".equals(field) ? r.getTtftP50Ms() : r.getTpotP50Ms();
            if (val != null) values.add(val.doubleValue());
        }
        if (values.isEmpty()) return 0.0;
        Collections.sort(values);
        int mid = values.size() / 2;
        if (values.size() % 2 == 0) {
            return (values.get(mid - 1) + values.get(mid)) / 2.0;
        }
        return values.get(mid);
    }

    private double maxOfP95s(List<AgentPerformance> records, String field) {
        double max = 0.0;
        for (AgentPerformance r : records) {
            Long val = "ttft".equals(field) ? r.getTtftP95Ms() : r.getTpotP95Ms();
            if (val != null && val > max) max = val.doubleValue();
        }
        return max;
    }

    private double computeP95FromRecords(List<AgentPerformance> records, boolean isTTFT) {
        List<Double> values = new ArrayList<>();
        for (AgentPerformance r : records) {
            Long val = isTTFT ? r.getTtftP50Ms() : r.getTpotP50Ms();
            if (val != null) values.add(val.doubleValue());
        }
        if (values.isEmpty()) return 0.0;
        Collections.sort(values);
        int idx = (int) Math.ceil(0.95 * values.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= values.size()) idx = values.size() - 1;
        return values.get(idx);
    }

    // ── Boxplot ──

    private Map<String, Object> buildBoxplotSeries(Map<String, List<Double>> groups) {
        Map<String, Object> series = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            series.put(entry.getKey(), computeBoxplot(entry.getValue()));
        }
        return series;
    }

    private Map<String, Object> computeBoxplot(List<Double> values) {
        if (values.isEmpty()) {
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

    private double percentile(List<Double> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }
}
