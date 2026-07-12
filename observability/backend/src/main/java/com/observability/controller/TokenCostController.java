package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.TokenCostService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Token 成本控制器
 *
 * GET /api/v1/ai/token-cost — Token 成本趋势/饼图/明细
 */
@RestController
@RequestMapping("/api/v1/ai/token-cost")
public class TokenCostController {

    private final TokenCostService tokenCostService;

    public TokenCostController(TokenCostService tokenCostService) {
        this.tokenCostService = tokenCostService;
    }

    /**
     * Token 成本查询
     *
     * @param groupBy 分组维度: model / intent（默认 model）
     * @param from    开始时间
     * @param to      结束时间
     * @param type    响应类型: trend / breakdown / detail（默认返回 trend+breakdown）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getTokenCost(
            @RequestParam(required = false, defaultValue = "model") String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "trend") String type) {
        try {
            Map<String, Object> data;
            if ("detail".equals(type)) {
                // getDetailTable 已返回 {content: [...]} 结构，前端读取 value.content
                data = tokenCostService.getDetailTable(from, to);
            } else if ("breakdown".equals(type)) {
                data = tokenCostService.getBreakdown(from, to);
            } else {
                data = tokenCostService.getTrend(from, to, groupBy);
            }
            return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch token cost: " + e.getMessage());
        }
    }
}
