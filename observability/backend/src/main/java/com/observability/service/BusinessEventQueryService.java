package com.observability.service;

import com.observability.dto.BusinessEventAggRow;
import com.observability.model.BusinessEvent;
import com.observability.repository.BusinessEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业务埋点查询服务 — 计数与多维聚合（M1/M2 验收核心）。
 *
 * <p>聚合口径：先按 event_type 取全量，内存按时间窗过滤后按 groupBy 维度分组计数，
 * 覆盖 event / session_id / trace_id / agent / card_type / source 全部维度。
 */
@Slf4j
@Service
public class BusinessEventQueryService {

    private static final long DEFAULT_WINDOW_SECONDS = 7L * 86400;

    private final BusinessEventRepository repository;

    public BusinessEventQueryService(BusinessEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 统计指定事件类型在时间窗内的发生次数。
     *
     * @param eventType 事件类型（mbank_card_click / mbank_human_click）
     * @param from      起始时间（null → 近 7 天）
     * @param to        结束时间（null → 当前）
     * @return 事件计数
     */
    public long getEventCount(String eventType, Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(DEFAULT_WINDOW_SECONDS);
        if (to == null) to = Instant.now();
        return repository.countByEventTypeAndCreatedAtBetween(eventType, from, to);
    }

    /**
     * 按 groupBy 维度聚合指定事件类型。
     *
     * @param eventType 事件类型
     * @param groupBy   维度：event / session_id / trace_id / agent / card_type / source（null → 按 event_type）
     * @param from      起始时间
     * @param to        结束时间
     * @return 聚合行列表
     */
    public List<BusinessEventAggRow> aggregate(String eventType, String groupBy, Instant from, Instant to) {
        final Instant fromResolved = (from != null) ? from : Instant.now().minusSeconds(DEFAULT_WINDOW_SECONDS);
        final Instant toResolved = (to != null) ? to : Instant.now();

        List<BusinessEvent> all = repository.findByEventType(eventType);
        List<BusinessEvent> filtered = all.stream()
                .filter(e -> e.getCreatedAt() != null
                        && !e.getCreatedAt().isBefore(fromResolved)
                        && !e.getCreatedAt().isAfter(toResolved))
                .collect(Collectors.toList());

        Map<String, Long> counts = new LinkedHashMap<>();
        for (BusinessEvent e : filtered) {
            String key = resolveGroupKey(e, groupBy);
            if (key == null) continue;
            counts.merge(key, 1L, Long::sum);
        }

        List<BusinessEventAggRow> rows = new ArrayList<>(counts.size());
        for (Map.Entry<String, Long> en : counts.entrySet()) {
            rows.add(new BusinessEventAggRow(en.getKey(), en.getValue()));
        }
        return rows;
    }

    private String resolveGroupKey(BusinessEvent e, String groupBy) {
        if (groupBy == null) return e.getEventType();
        switch (groupBy) {
            case "session_id":   return e.getSessionId();
            case "trace_id":     return e.getTraceId();
            case "agent":        return e.getAgent();
            case "card_type":    return e.getCardType();
            case "source":       return e.getSource();
            case "event":
            case "event_type":   return e.getEventType();
            default:             return e.getEventType();
        }
    }
}
