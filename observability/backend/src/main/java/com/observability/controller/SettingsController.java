package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统设置控制器
 *
 * GET /api/v1/settings/collection — 采集配置
 * GET /api/v1/settings/storage    — 存储配置
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * 获取采集配置
     */
    @GetMapping("/collection")
    public ApiResponse<Map<String, Object>> getCollectionConfig() {
        try {
            Map<String, Object> config = settingsService.getCollectionConfig();
            return ApiResponse.ok(config);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch collection settings: " + e.getMessage());
        }
    }

    /**
     * 获取存储配置
     */
    @GetMapping("/storage")
    public ApiResponse<Map<String, Object>> getStorageConfig() {
        try {
            Map<String, Object> config = settingsService.getStorageConfig();
            return ApiResponse.ok(config);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to fetch storage settings: " + e.getMessage());
        }
    }
}
