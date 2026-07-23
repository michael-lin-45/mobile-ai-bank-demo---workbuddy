package com.observability.repository;

import com.observability.model.BusinessEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 业务埋点事件 Repository。
 */
@Repository
public interface BusinessEventRepository extends JpaRepository<BusinessEvent, Long> {

    /** 按 event_type 查询全部（聚合时由 Service 内存按时间/维度过滤） */
    List<BusinessEvent> findByEventType(String eventType);

    /** 按 event_type + 时间范围计数（getEventCount 核心方法） */
    long countByEventTypeAndCreatedAtBetween(String eventType, Instant from, Instant to);

    /** 按 event_type 总计数（seed 幂等判断） */
    long countByEventType(String eventType);
}
