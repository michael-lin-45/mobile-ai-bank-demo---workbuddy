package com.observability.repository;

import com.observability.model.RedisMetricsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     */
    void deleteByTimestampBefore(Instant before);

    /**
     * Get the latest snapshot value for a metric key - returns 0 if not found.
     */
    @Query("SELECT s.metricValue FROM RedisMetricsSnapshot s WHERE s.metricKey = :key ORDER BY s.timestamp DESC LIMIT 1")
    Optional<Double> findLatestValueByKey(@Param("key") String key);
}
