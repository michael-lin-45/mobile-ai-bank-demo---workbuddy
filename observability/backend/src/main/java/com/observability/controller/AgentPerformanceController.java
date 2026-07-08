package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.AgentPerformanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 性能控制器
 *
 * GET /api/v1/ai/agent-performance          — Agent/LLM 性能
 * GET /api/v1/ai/agent-performance/boxplot  — 箱线图数据
 * GET /api/v1/ai/agent-performance/scatter  — 散点图数据
 */
@RestController
@RequestMapping("/api/v1/ai/agent-performance")
public class AgentPerformanceController {

    private final AgentPerformanceService agentPerformanceService;

    public AgentPerformanceController(AgentPerformanceService agentPerformanceService) {
        this.agentPerformanceService = agentPerformanceService;
    }

    /**
     * Agent/LLM 性能
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "agent") String dimension) {
        try {
            Map<String, Object> result = agentPerformanceService.getPerformance(from, to, dimension);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch agent performance: " + e.getMessage());
        }
    }

    /**
     * 箱线图数据
     */
    @GetMapping("/boxplot")
    public ApiResponse<Map<String, Object>> getBoxplotData(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "agent") String dimension) {
        try {
            Map<String, Object> result = agentPerformanceService.getBoxplotData(from, to, dimension);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch boxplot data: " + e.getMessage());
        }
    }

    /**
     * 散点图数据
     */
    @GetMapping("/scatter")
    public ApiResponse<Map<String, Object>> getScatterData(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "agent") String dimension) {
        try {
            Map<String, Object> result = agentPerformanceService.getScatterData(from, to, dimension);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch scatter data: " + e.getMessage());
        }
    }
}
