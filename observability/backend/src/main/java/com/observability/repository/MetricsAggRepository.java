package com.observability.repository;

import com.observability.model.MetricsAgg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 指标聚合 Repository — 支持按名称+时间范围查询
 */
@Repository
public interface MetricsAggRepository extends JpaRepository<MetricsAgg, Long> {

    /**
     * 查询指定指标名称在时间范围内的所有记录
     */
    List<MetricsAgg> findByMetricNameAndTimestampBetweenOrderByTimestampAsc(
            String metricName, Instant from, Instant to);

    /**
     * 查询时间窗口内的所有指标（用于 AI 洞察聚合）
     */
    List<MetricsAgg> findByTimestampBetweenOrderByTimestampAsc(Instant from, Instant to);

    /**
     * 获取所有不重复的指标名称
     */
    @Query("SELECT DISTINCT m.metricName FROM MetricsAgg m")
    List<String> findDistinctMetricNames();

    /**
     * 按 agg_window 查询指定时间范围内的指标
     */
    @Query("SELECT m FROM MetricsAgg m WHERE m.aggWindow = :window AND m.timestamp BETWEEN :from AND :to ORDER BY m.timestamp")
    List<MetricsAgg> findByAggWindowAndTimestampBetween(
            @Param("window") String window,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * 删除指定时间之前的指标聚合记录（数据清理，保留最近 N 天）。
     * @Modifying 批量 DELETE，避免大表派生删除 OOM。
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM MetricsAgg m WHERE m.timestamp < :before")
    int deleteByTimestampBefore(@Param("before") Instant before);
}
