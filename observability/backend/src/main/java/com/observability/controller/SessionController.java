package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.service.SessionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 会话控制器
 *
 * GET  /api/v1/sessions              — 会话列表（分页+筛选）
 * GET  /api/v1/sessions/{sessionId}  — 会话详情（含 turns）
 * POST /api/v1/sessions              — 接收主应用桥接的会话数据
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 会话列表
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listSessions(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) String agentLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        try {
            Instant fromInstant = parseInstantOrNull(from);
            Instant toInstant = parseInstantOrNull(to);
            Map<String, Object> result = sessionService.listSessions(
                    fromInstant, toInstant, intent, agentLevel, status,
                    sessionId, userId, channel, page, size);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to list sessions: " + e.getMessage());
        }
    }

    /**
     * 会话详情
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<Map<String, Object>> getSessionDetail(@PathVariable String sessionId) {
        try {
            Map<String, Object> detail = sessionService.getSessionDetail(sessionId != null ? sessionId.trim() : sessionId);
            if (detail == null) {
                return ApiResponse.error(404, "Session not found: " + sessionId);
            }
            return ApiResponse.ok(detail);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get session detail: " + e.getMessage());
        }
    }

    /**
     * 接收主应用桥接的会话数据（upsert）
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> upsertSession(@RequestBody Map<String, Object> body) {
        try {
            sessionService.upsertSession(body);
            return ApiResponse.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to upsert session: " + e.getMessage());
        }
    }

    private Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
