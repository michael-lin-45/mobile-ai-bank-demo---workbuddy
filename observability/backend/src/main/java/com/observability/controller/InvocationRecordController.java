package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.dto.InvocationRecord;
import com.observability.service.InvocationRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 外部调用台账控制器（V23 B4 / 任务分解 M10）。
 *
 * <p>GET /api/v1/invocations?category={all|rag|tool|skill|mcp} — 按类别透出调用台账。
 */
@RestController
@RequestMapping("/api/v1/invocations")
public class InvocationRecordController {

    private final InvocationRecordService service;

    public InvocationRecordController(InvocationRecordService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<InvocationRecord>> list(
            @RequestParam(required = false, defaultValue = "all") String category) {
        try {
            return ApiResponse.ok(service.getInvocations(category));
        } catch (Exception e) {
            return ApiResponse.error(500, "invocations failed: " + e.getMessage());
        }
    }
}
