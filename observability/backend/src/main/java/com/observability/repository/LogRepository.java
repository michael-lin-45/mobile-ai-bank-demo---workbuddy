package com.observability.repository;

import com.observability.model.LogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 日志 Repository — 支持多条件筛选和分页
 */
@Repository
public interface LogRepository extends JpaRepository<LogEntity, Long> {

    /**
     * 多条件日志查询（含关键词搜索）
     */
    @Query("SELECT l FROM LogEntity l WHERE " +
           "(l.timestamp >= :from OR :from IS NULL) " +
           "AND (l.timestamp <= :to OR :to IS NULL) " +
           "AND (l.level = :level OR :level IS NULL) " +
           "AND (l.traceId LIKE CONCAT('%', :traceId, '%') OR :traceId IS NULL) " +
           "AND (LOWER(l.message) LIKE LOWER(CONCAT('%', :q, '%')) OR :q IS NULL) " +
           "ORDER BY l.timestamp DESC")
    Page<LogEntity> queryLogs(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("level") String level,
            @Param("traceId") String traceId,
            @Param("q") String q,
            Pageable pageable);

    /**
     * 按时间范围和级别查询
     */
    Page<LogEntity> findByTimestampBetweenAndLevelOrderByTimestampDesc(
            Instant from, Instant to, String level, Pageable pageable);

    /**
     * 删除过期日志。@Modifying 批量 DELETE，避免大表派生删除 OOM。
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM LogEntity l WHERE l.timestamp < :before")
    int deleteByTimestampBefore(@Param("before") Instant before);
}
