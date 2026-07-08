package com.observability.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA Entity — 指标聚合记录
 * 对应 H2 表 metrics_agg
 */
@Entity
@Table(name = "metrics_agg", indexes = {
    @Index(name = "idx_metrics_name_ts", columnList = "metricName, timestamp"),
    @Index(name = "idx_metrics_window", columnList = "aggWindow")
})
public class MetricsAgg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_name", nullable = false, length = 128)
    private String metricName;

    /** JSON 标签，如 {"intent":"TRANSFER","model":"qwen-plus"} */
    @Column(length = 512)
    private String tags;

    @Column(nullable = false, name = "\"value\"")
    private Double value;

    @Column(name = "agg_window", nullable = false, length = 16)
    private String aggWindow;

    @Column(nullable = false, name = "\"timestamp\"")
    private Instant timestamp;

    public MetricsAgg() {}

    public MetricsAgg(String metricName, String tags, Double value, String aggWindow, Instant timestamp) {
        this.metricName = metricName;
        this.tags = tags;
        this.value = value;
        this.aggWindow = aggWindow;
        this.timestamp = timestamp;
    }

    // ── Getters / Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }

    public String getAggWindow() { return aggWindow; }
    public void setAggWindow(String aggWindow) { this.aggWindow = aggWindow; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
