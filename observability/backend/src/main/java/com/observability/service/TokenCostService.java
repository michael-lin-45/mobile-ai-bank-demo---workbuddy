package com.observability.service;

import com.observability.model.TokenCost;
import com.observability.repository.TokenCostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Token 成本分析服务 — 趋势 / 饼图 / 明细表
 */
@Slf4j
@Service
public class TokenCostService {

    private final TokenCostRepository tokenCostRepository;

    public TokenCostService(TokenCostRepository tokenCostRepository) {
        this.tokenCostRepository = tokenCostRepository;
    }

    /**
     * 获取 Token 成本趋势
     *
     * @param from    开始时间
     * @param to      结束时间
     * @param groupBy intent / model（默认 model）
     */
    public Map<String, Object> getTrend(Instant from, Instant to, String groupBy) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();
        if (groupBy == null || groupBy.isBlank()) groupBy = "model";

        List<TokenCost> records;
        if ("intent".equals(groupBy)) {
            records = tokenCostRepository.findByTimeRangeOrderByIntent(from, to);
        } else {
            records = tokenCostRepository.findByTimeRangeOrderByModel(from, to);
        }

        return buildTrendResult(records, groupBy);
    }

    /**
     * 获取 Token 成本饼图数据（Breakdown）
     */
    public Map<String, Object> getBreakdown(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        List<TokenCost> records = tokenCostRepository.findByTimeRangeOrderByModel(from, to);

        // 按 model 聚合
        Map<String, long[]> byModel = new LinkedHashMap<>();
        long totalInput = 0;
        long totalOutput = 0;

        for (TokenCost tc : records) {
            String key = tc.getModel() != null ? tc.getModel() : "unknown";
            long[] vals = byModel.computeIfAbsent(key, k -> new long[2]);
            long input = tc.getTokensIn() != null ? tc.getTokensIn() : 0;
            long output = tc.getTokensOut() != null ? tc.getTokensOut() : 0;
            vals[0] += input;
            vals[1] += output;
            totalInput += input;
            totalOutput += output;
        }

        // 成本估算: 输入 $0.002/1K tokens, 输出 $0.006/1K tokens
        double totalCost = totalInput * 0.002 / 1000.0 + totalOutput * 0.006 / 1000.0;

        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (var entry : byModel.entrySet()) {
            long[] vals = entry.getValue();
            double cost = vals[0] * 0.002 / 1000.0 + vals[1] * 0.006 / 1000.0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", entry.getKey());
            item.put("inputTokens", vals[0]);
            item.put("outputTokens", vals[1]);
            item.put("costEstimate", Math.round(cost * 100.0) / 100.0);
            breakdown.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalInput", totalInput);
        result.put("totalOutput", totalOutput);
        result.put("totalCost", Math.round(totalCost * 100.0) / 100.0);
        result.put("breakdown", breakdown);

        return result;
    }

    /**
     * 获取 Token 成本明细表
     */
    public List<Map<String, Object>> getDetailTable(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        List<TokenCost> records = tokenCostRepository.findByTimeRange(from, to);

        List<Map<String, Object>> table = new ArrayList<>();
        for (TokenCost tc : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tc.getId());
            row.put("intent", tc.getIntent());
            row.put("model", tc.getModel());
            row.put("callCount", tc.getCallCount());
            row.put("tokensIn", tc.getTokensIn());
            row.put("tokensOut", tc.getTokensOut());
            row.put("aggWindow", tc.getAggWindow());
            row.put("timestamp", tc.getTimestamp() != null ? tc.getTimestamp().toString() : null);
            double cost = (tc.getTokensIn() != null ? tc.getTokensIn() : 0) * 0.002 / 1000.0
                        + (tc.getTokensOut() != null ? tc.getTokensOut() : 0) * 0.006 / 1000.0;
            row.put("costEstimate", Math.round(cost * 10000.0) / 10000.0);
            table.add(row);
        }

        return table;
    }

    private Map<String, Object> buildTrendResult(List<TokenCost> records, String groupBy) {
        // 按 groupBy key 分组，每组按时间排序
        Map<String, List<TokenCost>> groups = new LinkedHashMap<>();
        for (TokenCost tc : records) {
            String key = "intent".equals(groupBy) ? tc.getIntent() : tc.getModel();
            if (key == null) key = "unknown";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tc);
        }

        long totalInput = 0;
        long totalOutput = 0;

        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            String key = entry.getKey();
            List<TokenCost> groupRecords = entry.getValue();

            long inputSum = groupRecords.stream().mapToLong(t -> t.getTokensIn() != null ? t.getTokensIn() : 0).sum();
            long outputSum = groupRecords.stream().mapToLong(t -> t.getTokensOut() != null ? t.getTokensOut() : 0).sum();

            totalInput += inputSum;
            totalOutput += outputSum;

            double cost = inputSum * 0.002 / 1000.0 + outputSum * 0.006 / 1000.0;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("inputTokens", inputSum);
            item.put("outputTokens", outputSum);
            item.put("costEstimate", Math.round(cost * 100.0) / 100.0);
            breakdown.add(item);
        }

        double totalCost = totalInput * 0.002 / 1000.0 + totalOutput * 0.006 / 1000.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalInput", totalInput);
        result.put("totalOutput", totalOutput);
        result.put("totalCost", Math.round(totalCost * 100.0) / 100.0);
        result.put("breakdown", breakdown);

        return result;
    }
}
