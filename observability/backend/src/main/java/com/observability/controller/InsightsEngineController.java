package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.InsightsEngineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 洞察引擎控制器（T-A，核心新开发）。
 *
 * <p>暴露 6 个 REST 端点（设计 §4.1 / 主文档 §9.6），统一返回 {@code ApiResponse{code,message,data}}：
 * <ul>
 *   <li>GET  /api/v1/ai/insights-report              — 聚合报告（带缓存）</li>
 *   <li>GET  /api/v1/ai/insights/bottlenecks         — 瓶颈（L1 确定性）</li>
 *   <li>GET  /api/v1/ai/insights/root-cause/{sid}    — 慢会话根因（L2 模糊）</li>
 *   <li>GET  /api/v1/ai/insights/unsatisfied         — 不满意聚类（L2 模糊）</li>
 *   <li>GET  /api/v1/ai/insights/conversion          — 转化漏斗（L1 确定性）</li>
 *   <li>GET  /api/v1/ai/insights/actions             — 优化建议 Top10（L2 模糊）</li>
 *   <li>POST /api/v1/ai/insights/refresh             — 清缓存重算</li>
 * </ul>
 *
 * <p>每个洞察 VO 均带 boundary / confidence / requiresApproval 字段（T-L 三层边界，见 InsightsEngineService）。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class InsightsEngineController {

    private final InsightsEngineService insightsEngineService;

    public InsightsEngineController(InsightsEngineService insightsEngineService) {
        this.insightsEngineService = insightsEngineService;
    }

    /** 聚合报告（带缓存） */
    @GetMapping("/insights-report")
    public ApiResponse<Map<String, Object>> getInsightsReport() {
        try {
            return ApiResponse.ok(insightsEngineService.generateReport());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to generate insights report: " + e.getMessage());
        }
    }

    /** 瓶颈（L1 确定性聚合） */
    @GetMapping("/insights/bottlenecks")
    public ApiResponse<List<Map<String, Object>>> getBottlenecks() {
        try {
            return ApiResponse.ok(insightsEngineService.analyzePerformance());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to analyze bottlenecks: " + e.getMessage());
        }
    }

    /** 慢会话根因（L2 模糊推断） */
    @GetMapping("/insights/root-cause/{sessionId}")
    public ApiResponse<Map<String, Object>> getRootCause(@PathVariable String sessionId) {
        try {
            return ApiResponse.ok(insightsEngineService.analyzeSlowSession(sessionId));
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to analyze root cause: " + e.getMessage());
        }
    }

    /** 不满意会话共性聚类（L2 模糊推断） */
    @GetMapping("/insights/unsatisfied")
    public ApiResponse<List<Map<String, Object>>> getUnsatisfied() {
        try {
            return ApiResponse.ok(insightsEngineService.clusterUnsatisfied());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to cluster unsatisfied: " + e.getMessage());
        }
    }

    /** 转化漏斗（L1 确定性计数） */
    @GetMapping("/insights/conversion")
    public ApiResponse<Map<String, Object>> getConversion() {
        try {
            return ApiResponse.ok(insightsEngineService.analyzeConversion());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to analyze conversion: " + e.getMessage());
        }
    }

    /** 优化建议 Top10（L2 模糊建议） */
    @GetMapping("/insights/actions")
    public ApiResponse<List<Map<String, Object>>> getActions() {
        try {
            return ApiResponse.ok(insightsEngineService.suggestActions());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to suggest actions: " + e.getMessage());
        }
    }

    /** 清缓存重算 */
    @PostMapping("/insights/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        try {
            return ApiResponse.ok(insightsEngineService.refresh());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to refresh insights: " + e.getMessage());
        }
    }
}
