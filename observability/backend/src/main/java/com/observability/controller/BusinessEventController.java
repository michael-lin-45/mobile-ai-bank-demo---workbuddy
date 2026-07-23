package com.observability.controller;

import com.observability.dto.ApiResponse;
import com.observability.dto.BusinessEventAggRow;
import com.observability.dto.BusinessEventIngestRequest;
import com.observability.service.BusinessEventQueryService;
import com.observability.service.BusinessEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 业务埋点控制器 — M1/M2 真实业务埋点入库与聚合。
 *
 * <ul>
 *   <li>POST /api/v1/business-events — 前端埋点入库</li>
 *   <li>GET  /api/v1/business-events/agg — 按维度 groupBy 聚合</li>
 * </ul>
 *
 * <p>错误码（§7.3）：40001=无效 event_type；40002=缺必传维度。
 */
@RestController
@RequestMapping("/api/v1/business-events")
public class BusinessEventController {

    private static final String CARD_CLICK = "mbank_card_click";
    private static final String HUMAN_CLICK = "mbank_human_click";

    private final BusinessEventService businessEventService;
    private final BusinessEventQueryService queryService;

    public BusinessEventController(BusinessEventService businessEventService,
                                   BusinessEventQueryService queryService) {
        this.businessEventService = businessEventService;
        this.queryService = queryService;
    }

    /** 前端埋点入库 */
    @PostMapping
    public ApiResponse<Void> ingest(@RequestBody BusinessEventIngestRequest req) {
        String eventType = req.getEventType();
        if (!CARD_CLICK.equals(eventType) && !HUMAN_CLICK.equals(eventType)) {
            return ApiResponse.error(40001, "invalid event_type: " + eventType);
        }
        if (CARD_CLICK.equals(eventType)
                && (req.getCardType() == null || req.getCardType().isBlank())) {
            return ApiResponse.error(40002, "mbank_card_click requires card_type");
        }
        if (HUMAN_CLICK.equals(eventType)
                && (req.getSource() == null || req.getSource().isBlank())) {
            return ApiResponse.error(40002, "mbank_human_click requires source");
        }
        try {
            businessEventService.ingest(req);
            return ApiResponse.ok(null);
        } catch (Exception e) {
            return ApiResponse.error(500, "ingest failed: " + e.getMessage());
        }
    }

    /** 聚合（按维度 groupBy） */
    @GetMapping("/agg")
    public ApiResponse<List<BusinessEventAggRow>> agg(
            @RequestParam String event,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        try {
            Instant fromI = parseInstant(from);
            Instant toI = parseInstant(to);
            return ApiResponse.ok(queryService.aggregate(event, groupBy, fromI, toI));
        } catch (Exception e) {
            return ApiResponse.error(500, "aggregate failed: " + e.getMessage());
        }
    }

    /** 事件计数（M1④ BusinessEventQueryService.getEventCount 直接透出） */
    @GetMapping("/count")
    public ApiResponse<Long> count(
            @RequestParam String event,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        try {
            Instant fromI = parseInstant(from);
            Instant toI = parseInstant(to);
            return ApiResponse.ok(queryService.getEventCount(event, fromI, toI));
        } catch (Exception e) {
            return ApiResponse.error(500, "count failed: " + e.getMessage());
        }
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
