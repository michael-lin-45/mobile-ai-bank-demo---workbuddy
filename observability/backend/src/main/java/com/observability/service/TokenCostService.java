package com.observability.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.model.MetricsAgg;
import com.observability.model.SpanEntity;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.SpanRepository;
import com.observability.repository.TokenCostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Token 成本分析服务 — 实时从 metrics_agg + Span 聚合。
 *
 * 此前实现读取 token_cost 静态表，而该表全代码无任何写入方，导致页面永远为空。
 * 现改为实时聚合:
 *  - metrics_agg: llm.token.input / llm.token.output（cumulative，取窗口内 MAX）按 model / intent 聚合
 *  - SpanEntity: 调用次数（按 intent × model 交叉统计）
 *  - 成本 = 输入 × 单价 + 输出 × 单价（每 1K tokens）
 */
@Slf4j
@Service
public class TokenCostService {

    // 保留构造参数以兼容 Controller（token_cost 表已弃用，改为实时聚合）
    private final TokenCostRepository tokenCostRepository;
    private final MetricsAggRepository metricsAggRepository;
    private final SpanRepository spanRepository;
    private final ObjectMapper objectMapper;

    /** 每 1K tokens 单价（输入 / 输出），单位：元（默认通用价，可按模型覆盖） */
    private static final double PRICE_INPUT_PER_1K = 0.002;
    private static final double PRICE_OUTPUT_PER_1K = 0.006;

    public TokenCostService(TokenCostRepository tokenCostRepository,
                             MetricsAggRepository metricsAggRepository,
                             SpanRepository spanRepository,
                             ObjectMapper objectMapper) {
        this.tokenCostRepository = tokenCostRepository;
        this.metricsAggRepository = metricsAggRepository;
        this.spanRepository = spanRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 对外接口 ====================

    public Map<String, Object> getTrend(Instant from, Instant to, String groupBy) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();
        if (groupBy == null || groupBy.isBlank()) groupBy = "model";

        String tagKey = "intent".equals(groupBy) ? "intent" : "model";
        Map<String, long[]> byKey = tokenTotalsByTag(tagKey, from, to);
        return buildTrendResult(byKey);
    }

    public Map<String, Object> getBreakdown(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        // 优先按意图拆解；若意图维度基本无数据，则退化为按模型
        Map<String, long[]> byIntent = tokenTotalsByTag("intent", from, to);
        boolean intentHasData = byIntent.size() > 1
                || (byIntent.size() == 1 && !byIntent.containsKey("unknown"));
        Map<String, long[]> target = intentHasData ? byIntent : tokenTotalsByTag("model", from, to);
        return buildTrendResult(target);
    }

    public Map<String, Object> getDetailTable(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        // 按 (intent, model) 交叉聚合 token 量
        Map<String, long[]> byPair = tokenTotalsByIntentModel(from, to);
        // 调用次数（按 intent × model）
        Map<String, Long> callCounts = callCountsByIntentModel(from, to);

        long grandTotal = 0;
        for (long[] v : byPair.values()) grandTotal += v[0] + v[1];

        List<Map<String, Object>> table = new ArrayList<>();
        for (var entry : byPair.entrySet()) {
            String[] parts = entry.getKey().split("\\|\\|", 2);
            String intent = parts.length > 0 ? parts[0] : "unknown";
            String model = parts.length > 1 ? parts[1] : "unknown";
            long in = entry.getValue()[0];
            long out = entry.getValue()[1];
            long total = in + out;
            long calls = callCounts.getOrDefault(entry.getKey(), 0L);
            double pct = grandTotal > 0 ? Math.round(total * 10000.0 / grandTotal) / 100.0 : 0.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intent", intent);
            row.put("model", model);
            row.put("calls", calls);
            row.put("inputTokens", in);
            row.put("outputTokens", out);
            row.put("totalTokens", total);
            row.put("percentage", pct + "%");
            table.add(row);
        }
        // 按总 token 降序
        table.sort((a, b) -> Long.compare(
                ((Number) b.get("totalTokens")).longValue(),
                ((Number) a.get("totalTokens")).longValue()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", table);
        return result;
    }

    // ==================== 聚合核心 ====================

    private Map<String, long[]> tokenTotalsByTag(String tagKey, Instant from, Instant to) {
        Map<String, long[]> map = new LinkedHashMap<>();
        accumulateByTag(map, "llm.token.input", tagKey, from, to, true);
        accumulateByTag(map, "llm.token.output", tagKey, from, to, false);
        return map;
    }

    private void accumulateByTag(Map<String, long[]> map, String metric, String tagKey,
                                 Instant from, Instant to, boolean isInput) {
        try {
            List<MetricsAgg> list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metric, from, to);
            for (MetricsAgg m : list) {
                if (m.getValue() == null) continue;
                String key = extractTagValue(m.getTags(), tagKey);
                if (key == null || key.isBlank()) key = "unknown";
                long[] v = map.computeIfAbsent(key, k -> new long[2]);
                long val = m.getValue().longValue();
                if (isInput) v[0] = Math.max(v[0], val);
                else v[1] = Math.max(v[1], val);
            }
        } catch (Exception e) {
            log.debug("[TokenCost] aggregate failed for {} by {}: {}", metric, tagKey, e.getMessage());
        }
    }

    private Map<String, long[]> tokenTotalsByIntentModel(Instant from, Instant to) {
        Map<String, long[]> map = new LinkedHashMap<>();
        accumulateByIntentModel(map, "llm.token.input", from, to, true);
        accumulateByIntentModel(map, "llm.token.output", from, to, false);
        return map;
    }

    private void accumulateByIntentModel(Map<String, long[]> map, String metric,
                                          Instant from, Instant to, boolean isInput) {
        try {
            List<MetricsAgg> list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metric, from, to);
            for (MetricsAgg m : list) {
                if (m.getValue() == null) continue;
                String model = extractTagValue(m.getTags(), "model");
                String intent = extractTagValue(m.getTags(), "intent");
                String key = (intent != null ? intent : "unknown") + "||" + (model != null ? model : "unknown");
                long[] v = map.computeIfAbsent(key, k -> new long[2]);
                long val = m.getValue().longValue();
                if (isInput) v[0] = Math.max(v[0], val);
                else v[1] = Math.max(v[1], val);
            }
        } catch (Exception e) {
            log.debug("[TokenCost] pair aggregate failed for {}: {}", metric, e.getMessage());
        }
    }

    private Map<String, Long> callCountsByIntentModel(Instant from, Instant to) {
        Map<String, Long> map = new LinkedHashMap<>();
        try {
            List<SpanEntity> spans = spanRepository.findByStartTimeBetweenOrderByStartTimeDesc(from, to);
            for (SpanEntity s : spans) {
                String model = extractModel(s);
                String intent = extractFromAttrs(s.getAttributes(), "intent");
                String key = (intent != null ? intent : "unknown") + "||" + (model != null ? model : "unknown");
                map.merge(key, 1L, Long::sum);
            }
        } catch (Exception e) {
            log.debug("[TokenCost] call count aggregate failed: {}", e.getMessage());
        }
        return map;
    }

    // ==================== 结果构建 ====================

    private Map<String, Object> buildTrendResult(Map<String, long[]> byKey) {
        long totalInput = 0;
        long totalOutput = 0;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (var entry : byKey.entrySet()) {
            long in = entry.getValue()[0];
            long out = entry.getValue()[1];
            totalInput += in;
            totalOutput += out;
            double cost = in * PRICE_INPUT_PER_1K / 1000.0 + out * PRICE_OUTPUT_PER_1K / 1000.0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", entry.getKey());
            item.put("inputTokens", in);
            item.put("outputTokens", out);
            item.put("costEstimate", Math.round(cost * 100.0) / 100.0);
            breakdown.add(item);
        }
        double totalCost = totalInput * PRICE_INPUT_PER_1K / 1000.0 + totalOutput * PRICE_OUTPUT_PER_1K / 1000.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalInput", totalInput);
        result.put("totalOutput", totalOutput);
        result.put("totalCost", Math.round(totalCost * 100.0) / 100.0);
        result.put("breakdown", breakdown);
        return result;
    }

    // ==================== 维度提取 ====================

    private String extractModel(SpanEntity s) {
        String m = extractFromAttrs(s.getAttributes(), "model.name");
        if (m == null && s.getOperationName() != null) {
            int i = s.getOperationName().indexOf(':');
            if (i >= 0 && i < s.getOperationName().length() - 1) m = s.getOperationName().substring(i + 1);
        }
        return m != null ? m : "unknown";
    }

    private String extractFromAttrs(String attrsJson, String key) {
        if (attrsJson == null || attrsJson.isBlank()) return null;
        try {
            Map<String, String> tags = objectMapper.readValue(attrsJson,
                    new TypeReference<Map<String, String>>() {});
            return tags.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTagValue(String tagsJson, String key) {
        return extractFromAttrs(tagsJson, key);
    }
}
