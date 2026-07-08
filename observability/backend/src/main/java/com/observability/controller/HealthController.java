package com.observability.controller;

import com.observability.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 健康检查控制器
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "service", "observability-backend",
                "timestamp", Instant.now().toString(),
                "version", "0.1.0"
        ));
    }
}
