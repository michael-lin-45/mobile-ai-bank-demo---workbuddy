package com.observability.controller;

import com.observability.dto.AIInsightVO;
import com.observability.dto.ApiResponse;
import com.observability.service.AIInsightsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * AI 洞察查询控制器
 *
 * GET /api/v1/ai/insights — AI 洞察聚合数据
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AIInsightsController {

    private final AIInsightsService aiInsightsService;

    public AIInsightsController(AIInsightsService aiInsightsService) {
        this.aiInsightsService = aiInsightsService;
    }

    /**
     * 获取 AI 洞察聚合数据
     *
     * @param type 洞察类型：confidence / extraction / rewrite（不指定返回全部）
     * @param from 开始时间 ISO 8601（可选，默认最近 24 小时）
     * @param to   结束时间 ISO 8601（可选，默认现在）
     */
    @GetMapping("/insights")
    public ApiResponse<AIInsightVO> getInsights(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            AIInsightVO vo = aiInsightsService.getInsights(from, to);
            return ApiResponse.ok(vo);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch AI insights: " + e.getMessage());
        }
    }

    /**
     * 获取意图分布
     */
    @GetMapping("/intent-distribution")
    public ApiResponse<Map<String, Long>> getIntentDistribution(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            Map<String, Long> distribution = aiInsightsService.getIntentDistribution(from, to);
            return ApiResponse.ok(distribution);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch intent distribution: " + e.getMessage());
        }
    }

    /**
     * 意图准确率趋势
     */
    @GetMapping("/intent-accuracy-trend")
    public ApiResponse<Map<String, Object>> getIntentAccuracyTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            Map<String, Object> result = aiInsightsService.getIntentAccuracyTrend(from, to);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch intent accuracy trend: " + e.getMessage());
        }
    }

    /**
     * 统一准确率分析报告（趋势 + 改写表 + 根因 TOP3 + 混淆矩阵）
     * 供前端 AccuracyTab 单端点消费，修复前后端契约错配。
     */
    @GetMapping("/accuracy-report")
    public ApiResponse<Map<String, Object>> getAccuracyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            return ApiResponse.ok(aiInsightsService.getAccuracyReport(from, to));
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch accuracy report: " + e.getMessage());
        }
    }

    /**
     * 混淆矩阵
     */
    @GetMapping("/debug/metric-names")
    public Map<String, Object> debugMetricNames() {
        return aiInsightsService.debugMetricNames();
    }

    @GetMapping("/debug/metric-samples")
    public Map<String, Object> debugMetricSamples(
            @RequestParam(defaultValue = "agent.rewrite.accuracy") String metricName,
            @RequestParam(defaultValue = "5") int limit) {
        return aiInsightsService.debugMetricSamples(metricName, limit);
    }

    @GetMapping("/confusion-matrix")
    public ApiResponse<Map<String, Object>> getConfusionMatrix(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            Map<String, Object> result = aiInsightsService.getConfusionMatrix(from, to);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch confusion matrix: " + e.getMessage());
        }
    }
}
