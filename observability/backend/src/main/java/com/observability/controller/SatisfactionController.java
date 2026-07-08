package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.SatisfactionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 满意度控制器
 *
 * GET  /api/v1/ai/satisfaction — 满意度聚合查询
 * POST /api/v1/ai/satisfaction — 提交满意度反馈
 */
@RestController
@RequestMapping("/api/v1/ai/satisfaction")
public class SatisfactionController {

    private final SatisfactionService satisfactionService;

    public SatisfactionController(SatisfactionService satisfactionService) {
        this.satisfactionService = satisfactionService;
    }

    /**
     * 获取满意度聚合数据
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getSatisfaction(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            Map<String, Object> result = satisfactionService.getSatisfaction(from, to);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch satisfaction data: " + e.getMessage());
        }
    }

    /**
     * 提交满意度反馈
     *
     * Request Body: { "sessionId": "sess_xxx", "rating": "satisfied", "reason": "可选" }
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> submitSatisfaction(@RequestBody Map<String, String> body) {
        try {
            String sessionId = body.get("sessionId");
            String rating = body.get("rating");
            String reason = body.get("reason");

            if (sessionId == null || sessionId.isBlank()) {
                return ApiResponse.error(400, "sessionId is required");
            }
            if (rating == null || rating.isBlank()) {
                return ApiResponse.error(400, "rating is required (satisfied|neutral|unsatisfied)");
            }
            if (!List.of("satisfied", "neutral", "unsatisfied").contains(rating)) {
                return ApiResponse.error(400, "rating must be one of: satisfied, neutral, unsatisfied");
            }

            boolean success = satisfactionService.submitSatisfaction(sessionId, rating, reason);
            if (success) {
                return ApiResponse.ok(Map.of("sessionId", sessionId, "rating", rating),
                        "Feedback submitted successfully");
            } else {
                return ApiResponse.error(404, "Session not found: " + sessionId);
            }
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to submit satisfaction: " + e.getMessage());
        }
    }
}
