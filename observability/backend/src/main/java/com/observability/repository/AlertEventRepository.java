package com.observability.repository;

import com.observability.model.AlertEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * AlertEvent Repository — 告警事件查询
 */
@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    /**
     * 按时间范围查询告警事件
     */
    @Query("SELECT e FROM AlertEvent e WHERE " +
           "(e.triggeredAt >= :from OR :from IS NULL) " +
           "AND (e.triggeredAt <= :to OR :to IS NULL) " +
           "ORDER BY e.triggeredAt DESC")
    Page<AlertEvent> findByTimeRange(@Param("from") Instant from,
                                       @Param("to") Instant to,
                                       Pageable pageable);

    /**
     * 按规则 ID 和时间范围查询
     */
    @Query("SELECT e FROM AlertEvent e WHERE " +
           "e.ruleId = :ruleId " +
           "AND (e.triggeredAt >= :from OR :from IS NULL) " +
           "AND (e.triggeredAt <= :to OR :to IS NULL) " +
           "ORDER BY e.triggeredAt DESC")
    Page<AlertEvent> findByRuleIdAndTimeRange(@Param("ruleId") Long ruleId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to,
                                                Pageable pageable);

    /**
     * 按状态查询
     */
    List<AlertEvent> findByStatusOrderByTriggeredAtDesc(String status);

    /**
     * 按规则 ID 查询全部事件
     */
    List<AlertEvent> findByRuleIdOrderByTriggeredAtDesc(Long ruleId);
}
