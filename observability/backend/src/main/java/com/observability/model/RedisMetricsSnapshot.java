package com.observability.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA Entity - Redis metrics snapshot
 * Periodic snapshots of Redis counter/gauge metrics for H2 persistence fallback.
 */
@Entity
@Table(name = "redis_metrics_snapshot", indexes = {
    @Index(name = "idx_rms_key_ts", columnList = "metricKey, timestamp"),
    @Index(name = "idx_rms_ts", columnList = "timestamp")
})
public class RedisMetricsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_key", nullable = false, length = 256)
    private String metricKey;

    @Column(name = "metric_type", nullable = false, length = 16)
    private String metricType;

    @Column(name = "metric_value")
    private Double metricValue;

    @Column(length = 512)
    private String tags;

    @Column(nullable = false, name = "\"timestamp\"")
    private Instant timestamp;

    public RedisMetricsSnapshot() {}

    public RedisMetricsSnapshot(String metricKey, String metricType, Double metricValue, String tags, Instant timestamp) {
        this.metricKey = metricKey;
        this.metricType = metricType;
        this.metricValue = metricValue;
        this.tags = tags;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }

    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }

    public Double getMetricValue() { return metricValue; }
    public void setMetricValue(Double metricValue) { this.metricValue = metricValue; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
