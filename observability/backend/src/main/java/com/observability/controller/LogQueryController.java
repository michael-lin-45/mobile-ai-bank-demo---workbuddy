package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.LogQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 日志查询控制器
 *
 * GET /api/v1/logs — 多条件日志查询
 */
@RestController
@RequestMapping("/api/v1/logs")
public class LogQueryController {

    private final LogQueryService logQueryService;

    public LogQueryController(LogQueryService logQueryService) {
        this.logQueryService = logQueryService;
    }

    /**
     * 多条件日志查询
     *
     * @param from      开始时间（可选）
     * @param to        结束时间（可选）
     * @param level     日志级别（可选，INFO/WARN/ERROR）
     * @param traceId   traceId 搜索（可选，模糊匹配）
     * @param userId    用户ID筛选（可选）
     * @param sessionId 会话ID筛选（可选）
     * @param q         关键词搜索（可选）
     * @param page      页码（0-based，默认 0）
     * @param size      每页大小（默认 50，最大 200）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> queryLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        try {
            Map<String, Object> result = logQueryService.queryLogs(
                    from, to, level, traceId, userId, sessionId, q, page, size);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to query logs: " + e.getMessage());
        }
    }
}
