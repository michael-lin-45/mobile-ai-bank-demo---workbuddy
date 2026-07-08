package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.ConversionFunnelService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 转化漏斗控制器
 *
 * GET /api/v1/ai/conversion-funnel — 漏斗数据
 */
@RestController
@RequestMapping("/api/v1/ai/conversion-funnel")
public class ConversionFunnelController {

    private final ConversionFunnelService conversionFunnelService;

    public ConversionFunnelController(ConversionFunnelService conversionFunnelService) {
        this.conversionFunnelService = conversionFunnelService;
    }

    /**
     * 获取转化漏斗
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getFunnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        try {
            Map<String, Object> result = conversionFunnelService.getFunnel(from, to);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch conversion funnel: " + e.getMessage());
        }
    }
}
