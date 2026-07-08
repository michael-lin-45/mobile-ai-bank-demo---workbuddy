package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.ToolStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 工具调用统计控制器
 *
 * GET /api/v1/ai/tool-stats — 工具调用统计
 */
@RestController
@RequestMapping("/api/v1/ai/tool-stats")
public class ToolStatsController {

    private final ToolStatsService toolStatsService;

    public ToolStatsController(ToolStatsService toolStatsService) {
        this.toolStatsService = toolStatsService;
    }

    /**
     * 获取工具调用统计
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getToolStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            Map<String, Object> result = toolStatsService.getToolStats(from, to);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch tool stats: " + e.getMessage());
        }
    }
}
