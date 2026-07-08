package com.observability.controller;

import com.observability.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

/**
 * Skill 业务效果统计控制器 — P0 跳过，P1 实现
 *
 * ⚠️ 仅空壳，所有方法返回 501 Not Implemented。
 */
@RestController
@RequestMapping("/api/v1/ai/skill-stats")
@Deprecated
public class SkillStatsController {

    /**
     * P1 实现：获取 Skill 业务效果统计
     */
    @GetMapping
    public ApiResponse<String> getSkillStats() {
        return ApiResponse.error(501, "P1实现");
    }
}
