package com.observability.repository;

import com.observability.model.SpanEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Span Repository - Trace 查询核心
 */
@Repository
public interface SpanRepository extends JpaRepository<SpanEntity, Long> {

    /**
     * 按 traceId 查询所有 span
     */
    List<SpanEntity> findByTraceIdOrderByStartTimeAsc(String traceId);

    /**
     * 按时间范围查询根 span（parentSpanId IS NULL 或空字符串），分页
     *
     * 使用原生 SQL 绕开 Hibernate 6 JPQL 空字符串字面量解析问题。
     */
    @Query(value = "SELECT * FROM spans WHERE (parent_span_id IS NULL OR parent_span_id = '') AND start_time >= :from ORDER BY start_time DESC",
           countQuery = "SELECT COUNT(*) FROM spans WHERE (parent_span_id IS NULL OR parent_span_id = '') AND start_time >= :from",
           nativeQuery = true)
    Page<SpanEntity> findRootSpansByTimeRange(@Param("from") Instant from, Pageable pageable);

    /**
     * Find business root spans (SERVER kind with HTTP-like operation name).
     * Used as fallback when no true root spans (parentSpanId IS NULL) exist,
     * because OTel auto-instrumentation sometimes assigns parentSpanId to the
     * HTTP SERVER span that should be the trace root.
     */
    @Query(value = "SELECT * FROM spans WHERE kind = 'SERVER' " +
           "AND operation_name LIKE 'GET %' OR operation_name LIKE 'POST %' OR operation_name LIKE 'PUT %' OR operation_name LIKE 'DELETE %' " +
           "AND start_time >= :from ORDER BY start_time DESC",
           countQuery = "SELECT COUNT(*) FROM spans WHERE kind = 'SERVER' " +
           "AND (operation_name LIKE 'GET %' OR operation_name LIKE 'POST %' OR operation_name LIKE 'PUT %' OR operation_name LIKE 'DELETE %') " +
           "AND start_time >= :from",
           nativeQuery = true)
    Page<SpanEntity> findBusinessRootSpansByTimeRange(@Param("from") Instant from, Pageable pageable);

    /**
     * 获取时间范围内所有 span
     */
    List<SpanEntity> findByStartTimeBetweenOrderByStartTimeDesc(Instant from, Instant to);

    /**
     * 按 traceId 列表批量查询（用于组装 TraceListVO）
     */
    List<SpanEntity> findByTraceIdIn(List<String> traceIds);

    /**
     * 获取时间范围内所有不重复的 traceId
     */
    @Query("SELECT DISTINCT s.traceId FROM SpanEntity s WHERE s.startTime >= :from")
    List<String> findDistinctTraceIdsSince(@Param("from") Instant from);

    /**
     * 统计 trace 下的 span 数量
     */
    long countByTraceId(String traceId);

    /**
     * 删除过期 span（超过保留天数）
     */
    void deleteByStartTimeBefore(Instant before);

    /**
     * 按 operationName 模糊匹配统计去重 traceId 数量（用于 Agent 分层调用数回退）
     * 匹配任意一个 pattern 即计入，同一 trace 只计一次。
     */
    @Query("SELECT COUNT(DISTINCT s.traceId) FROM SpanEntity s WHERE s.startTime >= :from "
            + "AND (s.operationName LIKE %:pattern1% OR s.operationName LIKE %:pattern2% OR s.operationName LIKE %:pattern3%)")
    long countDistinctTracesByOpNameLike(@Param("from") Instant from,
                                          @Param("pattern1") String pattern1,
                                          @Param("pattern2") String pattern2,
                                          @Param("pattern3") String pattern3);

    /**
     * 获取所有不重复的 operationName（用于 H2 回退时按名称匹配分层）
     */
    @Query("SELECT DISTINCT s.operationName FROM SpanEntity s")
    List<String> findDistinctOperationNames();

    /**
     * DEBUG: operation_name distribution with root/non-root breakdown
     */
    @Query(value = "SELECT operation_name, COUNT(*) as cnt, " +
           "SUM(CASE WHEN parent_span_id IS NULL OR parent_span_id = '' THEN 1 ELSE 0 END) as root_cnt " +
           "FROM spans GROUP BY operation_name ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> findOperationNameDistribution();

}
