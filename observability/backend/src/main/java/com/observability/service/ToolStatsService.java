package com.observability.service;

import com.observability.model.ToolCall;
import com.observability.repository.ToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 工具调用统计服务
 */
@Slf4j
@Service
public class ToolStatsService {

    private final ToolCallRepository toolCallRepository;

    public ToolStatsService(ToolCallRepository toolCallRepository) {
        this.toolCallRepository = toolCallRepository;
    }

    /**
     * 获取工具调用统计
     *
     * @param from 开始时间
     * @param to   结束时间
     * @return tools 列表，含 count/success/fail/avg/p95/errorRate
     */
    public Map<String, Object> getToolStats(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(3600);
        if (to == null) to = Instant.now();

        List<ToolCall> records = toolCallRepository.findByTimeRange(from, to);

        // 按 toolName 聚合
        Map<String, List<ToolCall>> byTool = new LinkedHashMap<>();
        for (ToolCall tc : records) {
            String key = tc.getToolName() != null ? tc.getToolName() : "unknown";
            byTool.computeIfAbsent(key, k -> new ArrayList<>()).add(tc);
        }

        List<Map<String, Object>> tools = new ArrayList<>();
        for (var entry : byTool.entrySet()) {
            String toolName = entry.getKey();
            List<ToolCall> toolRecords = entry.getValue();

            long totalCalls = toolRecords.stream().mapToLong(t -> t.getCallCount() != null ? t.getCallCount() : 0).sum();
            long successCalls = toolRecords.stream().mapToLong(t -> t.getSuccessCount() != null ? t.getSuccessCount() : 0).sum();
            long failCalls = toolRecords.stream().mapToLong(t -> t.getFailCount() != null ? t.getFailCount() : 0).sum();
            double avgDuration = toolRecords.stream()
                    .mapToLong(t -> t.getAvgDurationMs() != null ? t.getAvgDurationMs() : 0)
                    .average().orElse(0.0);
            double p95Duration = toolRecords.stream()
                    .mapToLong(t -> t.getP95DurationMs() != null ? t.getP95DurationMs() : 0)
                    .max().orElse(0);

            Map<String, Object> toolVO = new LinkedHashMap<>();
            toolVO.put("toolName", toolName);
            toolVO.put("toolProvider", toolRecords.get(0).getProvider());
            toolVO.put("totalCalls", totalCalls);
            toolVO.put("successCalls", successCalls);
            toolVO.put("errorCalls", failCalls);
            toolVO.put("avgDurationMs", Math.round(avgDuration * 10.0) / 10.0);
            toolVO.put("p95DurationMs", p95Duration);
            toolVO.put("successRate", totalCalls > 0 ? Math.round(successCalls * 1000.0 / totalCalls) / 10.0 : 100.0);

            // 典型错误
            List<String> errors = toolRecords.stream()
                    .map(ToolCall::getTypicalErrors)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            toolVO.put("typicalErrors", errors);

            tools.add(toolVO);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return result;
    }
}
