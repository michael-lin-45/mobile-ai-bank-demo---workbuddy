package com.observability.repository;

import com.observability.model.RedisMetricsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Redis metrics snapshots - supports H2 fallback reads.
 */
@Repository
public interface RedisMetricsSnapshotRepository extends JpaRepository<RedisMetricsSnapshot, Long> {

    /**
     * Get the latest snapshot for a given metric key.
     */
    Optional<RedisMetricsSnapshot> findFirstByMetricKeyOrderByTimestampDesc(String metricKey);

    /**
     * Get the latest snapshots for a given metric key prefix.
     */
    List<RedisMetricsSnapshot> findByMetricKeyStartingWithOrderByTimestampDesc(String metricKeyPrefix);

    /**
     * Get all snapshots within a time range, ordered by timestamp.
     */
    List<RedisMetricsSnapshot> findByTimestampBetweenOrderByTimestampAsc(Instant from, Instant to);

    /**
     * Delete snapshots older than the given timestamp (used by data cleanup).
     * @Modifying 批量 DELETE，避免大表派生删除 OOM。
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM RedisMetricsSnapshot s WHERE s.timestamp < :before")
    int deleteByTimestampBefore(@Param("before") Instant before);

    /**
     * Get the latest snapshot value for a metric key - returns 0 if not found.
     */
    @Query("SELECT s.metricValue FROM RedisMetricsSnapshot s WHERE s.metricKey = :key ORDER BY s.timestamp DESC LIMIT 1")
    Optional<Double> findLatestValueByKey(@Param("key") String key);

    /**
     * 统计给定 key 列表在 redis_metrics_snapshot 表中的命中总行数（死 key 诊断，Q1/T-A）。
     *
     * @param keys 待诊断的 metricKey 集合（如 5 个死 key）
     * @return 命中总行数；0 表示无任何死 key 行
     */
    @Query("SELECT COUNT(s) FROM RedisMetricsSnapshot s WHERE s.metricKey IN :keys")
    long countDeadKeys(@Param("keys") List<String> keys);

    /**
     * 按 key 分组统计命中行数（死 key 诊断 DTO 组装，Q1/T-A）。
     *
     * @param keys 待诊断的 metricKey 集合
     * @return 每行 [metricKey(String), count(Long)]；未被命中的 key 不会出现在结果中
     */
    @Query("SELECT s.metricKey, COUNT(s) FROM RedisMetricsSnapshot s WHERE s.metricKey IN :keys GROUP BY s.metricKey")
    List<Object[]> countDeadKeysGrouped(@Param("keys") List<String> keys);
}
