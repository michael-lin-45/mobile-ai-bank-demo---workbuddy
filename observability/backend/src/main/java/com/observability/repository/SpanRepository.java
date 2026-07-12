package com.observability.repository;

import com.observability.model.SpanEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
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
     * 删除过期 span（超过保留天数）。
     * 使用 @Modifying 批量 DELETE：避免 Spring Data JPA 派生删除先 SELECT 全表加载实体再逐条删除，
     * 在 2.2GB 大表上会 OOM 导致 HTTP 500。
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM SpanEntity s WHERE s.startTime < :before")
    int deleteByStartTimeBefore(@Param("before") Instant before);

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

    /**
     * 获取时间范围内所有根 span 的 start_time（仅投影时间列，用于趋势图请求量分桶统计，
     * 避免加载完整 SpanEntity 实体导致 attributes 大字段内存膨胀）
     */
    @Query(value = "SELECT start_time FROM spans WHERE (parent_span_id IS NULL OR parent_span_id = '') AND start_time >= :from", nativeQuery = true)
    List<Instant> findRootSpanStartTimesSince(@Param("from") Instant from);

    /**
     * 统计时间范围内的 span 总数（错误率分母：请求/操作数）。命中 idx_spans_time 索引。
     */
    @Query("SELECT COUNT(*) FROM SpanEntity s WHERE s.startTime BETWEEN :from AND :to")
    long countByTimeRange(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * 统计时间范围内 statusCode = 'ERROR' 的 span 数（错误率分子）。
     */
    @Query("SELECT COUNT(*) FROM SpanEntity s WHERE s.statusCode = 'ERROR' AND s.startTime BETWEEN :from AND :to")
    long countErrorsByTimeRange(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * 统计时间范围内 operation_name 以指定前缀开头的 span 数（Agent 分层调用数，真实计数）。
     * prefix 形如 'L0:%' / 'L1:%' / 'L2:%'（LIKE 前缀匹配，'L1:' 天然排除 L1-LLM1/L1-LLM2 子调用）。
     * 取代旧的「按 OTLP 指标导出次数 +1」Redis 计数器（第十五轮诊断：累积计数器反复导出导致放大失真且 L1 反超 L0）。
     */
    @Query(value = "SELECT COUNT(*) FROM spans WHERE operation_name LIKE :prefix AND start_time >= :from", nativeQuery = true)
    long countByOperationNamePrefixSince(@Param("prefix") String prefix, @Param("from") Instant from);

    /**
     * 统计时间范围内 operation_name 以指定前缀开头的「去重 trace 数」（Agent 分层调用数，按 agent 视角计 1 次/请求）。
     * 关键：用 COUNT(DISTINCT trace_id) 而非 COUNT(*)，使每个请求（每 trace）在 L1/L2 只计 1 次，
     * 即使该层内部展开多个 LLM 子 span（如 L1-LLM1/L1-LLM2、L2 多 step），也只算 1 个 agent。
     * prefix 形如 'L0:%' / 'L1%' / 'L2:%'（'L1%' 覆盖 L1: / L1-LLM1: / L1-LLM2: 全部 L1 变体）。
     * 取代原始 span 条数统计（第十八轮决策：用户确认 agent 统计应为 1，不数子 span）。
     */
    @Query(value = "SELECT COUNT(DISTINCT trace_id) FROM spans WHERE operation_name LIKE :prefix AND start_time >= :from", nativeQuery = true)
    long countDistinctTraceByOpNamePrefixSince(@Param("prefix") String prefix, @Param("from") Instant from);

}
