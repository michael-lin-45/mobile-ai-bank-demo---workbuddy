package com.observability.service;

import com.observability.dto.BusinessEventIngestRequest;
import com.observability.model.BusinessEvent;
import com.observability.repository.BusinessEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 业务埋点入库服务 — 将前端埋点映射为 {@link BusinessEvent} 实体并落库。
 *
 * <p>复用既有 JPA 管线，不引入新摄入链路；Core 侧无需改动。
 */
@Slf4j
@Service
public class BusinessEventService {

    private final BusinessEventRepository repository;

    public BusinessEventService(BusinessEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 入库一条业务埋点事件。
     *
     * @param req 前端上报的埋点请求（已通过 Controller 层必填维度校验）
     */
    public void ingest(BusinessEventIngestRequest req) {
        BusinessEvent entity = new BusinessEvent();
        entity.setSessionId(req.getSessionId());
        entity.setTraceId(req.getTraceId());
        entity.setUserId(req.getUserId());
        entity.setAgent(req.getAgent());
        entity.setEventType(req.getEventType());
        entity.setCardType(req.getCardType());
        entity.setSource(req.getSource());
        entity.setChannel(req.getChannel());
        entity.setCreatedAt(parseTs(req.getTs()));
        repository.save(entity);
        log.debug("[BusinessEvent] ingested {} session={} trace={}",
                req.getEventType(), req.getSessionId(), req.getTraceId());
    }

    /** 解析 ISO-8601 时间戳；为空或非法时回退到当前时间（北京时间） */
    private Instant parseTs(String ts) {
        if (ts == null || ts.isBlank()) return Instant.now();
        try {
            return Instant.parse(ts);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
