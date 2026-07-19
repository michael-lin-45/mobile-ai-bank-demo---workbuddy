package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.model.AlertEvent;
import com.observability.model.AlertRule;
import com.observability.service.AlertService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 告警控制器
 *
 * GET    /api/v1/alerts/rules       — 规则列表
 * POST   /api/v1/alerts/rules       — 创建规则
 * PUT    /api/v1/alerts/rules/{id}  — 更新规则
 * DELETE /api/v1/alerts/rules/{id}  — 删除规则
 * GET    /api/v1/alerts/events      — 事件列表
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    // ==================== 规则 CRUD ====================

    /** 规则列表 */
    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> listRules() {
        try {
            return ApiResponse.ok(alertService.listRules());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to list alert rules: " + e.getMessage());
        }
    }

    /** 获取单条规则 */
    @GetMapping("/rules/{id}")
    public ApiResponse<AlertRule> getRule(@PathVariable Long id) {
        try {
            AlertRule rule = alertService.getRule(id);
            if (rule == null) {
                return ApiResponse.error(404, "Alert rule not found: " + id);
            }
            return ApiResponse.ok(rule);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get alert rule: " + e.getMessage());
        }
    }

    /** 创建规则 */
    @PostMapping("/rules")
    public ApiResponse<AlertRule> createRule(@RequestBody AlertRule rule) {
        try {
            AlertRule created = alertService.createRule(rule);
            return ApiResponse.ok(created, "Alert rule created");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to create alert rule: " + e.getMessage());
        }
    }

    /** 更新规则 */
    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRule rule) {
        try {
            AlertRule updated = alertService.updateRule(id, rule);
            if (updated == null) {
                return ApiResponse.error(404, "Alert rule not found: " + id);
            }
            return ApiResponse.ok(updated, "Alert rule updated");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to update alert rule: " + e.getMessage());
        }
    }

    /** 删除规则 */
    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        try {
            boolean deleted = alertService.deleteRule(id);
            if (!deleted) {
                return ApiResponse.error(404, "Alert rule not found: " + id);
            }
            return ApiResponse.ok(null, "Alert rule deleted");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to delete alert rule: " + e.getMessage());
        }
    }

    // ==================== 事件 ====================

    /** 告警事件列表 */
    @GetMapping("/events")
    public ApiResponse<Map<String, Object>> listEvents(
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        try {
            Map<String, Object> result = alertService.listEvents(ruleId, status, from, to, page, size);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to list alert events: " + e.getMessage());
        }
    }

    /** 告警事件详情 */
    @GetMapping("/events/{id}")
    public ApiResponse<AlertEvent> getEvent(@PathVariable Long id) {
        try {
            AlertEvent event = alertService.getEvent(id);
            if (event == null) {
                return ApiResponse.error(404, "Alert event not found: " + id);
            }
            return ApiResponse.ok(event);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get alert event: " + e.getMessage());
        }
    }

    /** T-B 状态机：人工确认事件（FIRING/ACKED → ACKED） */
    @PostMapping("/events/{id}/ack")
    public ApiResponse<AlertEvent> ackEvent(@PathVariable Long id) {
        try {
            AlertEvent event = alertService.ackEvent(id);
            if (event == null) {
                return ApiResponse.error(404, "Alert event not found: " + id);
            }
            return ApiResponse.ok(event, "Alert event acknowledged");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to ack alert event: " + e.getMessage());
        }
    }
}
