package com.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * OTLP Metrics JSON Payload
 *
 * 标准 OTLP JSON 格式:
 * <pre>{@code
 * {
 *   "resourceMetrics": [{
 *     "resource": { "attributes": [...] },
 *     "scopeMetrics": [{
 *       "scope": { "name": "...", "version": "..." },
 *       "metrics": [{
 *         "name": "agent.intent.recognized",
 *         "description": "...",
 *         "unit": "1",
 *         "histogram": { "dataPoints": [{ "attributes": [...], "count": "10", ... }] }
 *         // 或 "sum": {...}, "gauge": {...}
 *       }]
 *     }]
 *   }]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OtlpMetricPayload {

    @JsonProperty("resourceMetrics")
    private List<ResourceMetric> resourceMetrics;

    public List<ResourceMetric> getResourceMetrics() { return resourceMetrics; }
    public void setResourceMetrics(List<ResourceMetric> resourceMetrics) { this.resourceMetrics = resourceMetrics; }

    // ── ResourceMetric ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceMetric {
        private Resource resource;
        @JsonProperty("scopeMetrics")
        private List<ScopeMetric> scopeMetrics;

        public Resource getResource() { return resource; }
        public void setResource(Resource resource) { this.resource = resource; }
        public List<ScopeMetric> getScopeMetrics() { return scopeMetrics; }
        public void setScopeMetrics(List<ScopeMetric> scopeMetrics) { this.scopeMetrics = scopeMetrics; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Resource {
        private List<Attribute> attributes;
        public List<Attribute> getAttributes() { return attributes; }
        public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }

        public String getServiceName() {
            if (attributes == null) return "unknown";
            return attributes.stream()
                    .filter(a -> "service.name".equals(a.key))
                    .map(a -> a.value != null ? a.value.getStringValue() : null)
                    .findFirst().orElse("unknown");
        }
    }

    // ── ScopeMetric ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeMetric {
        private Scope scope;
        private List<Metric> metrics;

        public Scope getScope() { return scope; }
        public void setScope(Scope scope) { this.scope = scope; }
        public List<Metric> getMetrics() { return metrics; }
        public void setMetrics(List<Metric> metrics) { this.metrics = metrics; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scope {
        private String name;
        private String version;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }

    // ── Metric ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metric {
        private String name;
        private String description;
        private String unit;
        private Histogram histogram;
        private Sum sum;
        private Gauge gauge;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public Histogram getHistogram() { return histogram; }
        public void setHistogram(Histogram histogram) { this.histogram = histogram; }
        public Sum getSum() { return sum; }
        public void setSum(Sum sum) { this.sum = sum; }
        public Gauge getGauge() { return gauge; }
        public void setGauge(Gauge gauge) { this.gauge = gauge; }

        /** 返回第一个 dataPoint 的 attributes 解析为 tag-string */
        public String extractTags() {
            List<Attribute> attrs = null;
            if (histogram != null && histogram.dataPoints != null && !histogram.dataPoints.isEmpty()) {
                attrs = histogram.dataPoints.get(0).attributes;
            } else if (sum != null && sum.dataPoints != null && !sum.dataPoints.isEmpty()) {
                attrs = sum.dataPoints.get(0).attributes;
            } else if (gauge != null && gauge.dataPoints != null && !gauge.dataPoints.isEmpty()) {
                attrs = gauge.dataPoints.get(0).attributes;
            }
            if (attrs == null) return "{}";
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < attrs.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(attrs.get(i).key).append("\":\"")
                  .append(attrs.get(i).value != null ? attrs.get(i).value.getStringValue() : "").append("\"");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    // ── Histogram ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Histogram {
        @JsonProperty("dataPoints")
        private List<HistogramDataPoint> dataPoints;
        private String aggregationTemporality;

        public List<HistogramDataPoint> getDataPoints() { return dataPoints; }
        public void setDataPoints(List<HistogramDataPoint> dataPoints) { this.dataPoints = dataPoints; }
        public String getAggregationTemporality() { return aggregationTemporality; }
        public void setAggregationTemporality(String aggregationTemporality) { this.aggregationTemporality = aggregationTemporality; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HistogramDataPoint {
        private List<Attribute> attributes;
        private String startTimeUnixNano;
        private String timeUnixNano;
        private String count;
        private double sum;
        @JsonProperty("bucketCounts")
        private List<Integer> bucketCounts;
        @JsonProperty("explicitBounds")
        private List<Double> explicitBounds;
        private Double min;
        private Double max;

        public List<Attribute> getAttributes() { return attributes; }
        public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }
        public String getStartTimeUnixNano() { return startTimeUnixNano; }
        public void setStartTimeUnixNano(String startTimeUnixNano) { this.startTimeUnixNano = startTimeUnixNano; }
        public String getTimeUnixNano() { return timeUnixNano; }
        public void setTimeUnixNano(String timeUnixNano) { this.timeUnixNano = timeUnixNano; }
        public String getCount() { return count; }
        public void setCount(String count) { this.count = count; }
        public double getSum() { return sum; }
        public void setSum(double sum) { this.sum = sum; }
        public List<Integer> getBucketCounts() { return bucketCounts; }
        public void setBucketCounts(List<Integer> bucketCounts) { this.bucketCounts = bucketCounts; }
        public List<Double> getExplicitBounds() { return explicitBounds; }
        public void setExplicitBounds(List<Double> explicitBounds) { this.explicitBounds = explicitBounds; }
        public Double getMin() { return min; }
        public void setMin(Double min) { this.min = min; }
        public Double getMax() { return max; }
        public void setMax(Double max) { this.max = max; }
    }

    // ── Sum ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sum {
        @JsonProperty("dataPoints")
        private List<SumDataPoint> dataPoints;
        private String aggregationTemporality;
        private boolean isMonotonic;

        public List<SumDataPoint> getDataPoints() { return dataPoints; }
        public void setDataPoints(List<SumDataPoint> dataPoints) { this.dataPoints = dataPoints; }
        public String getAggregationTemporality() { return aggregationTemporality; }
        public void setAggregationTemporality(String aggregationTemporality) { this.aggregationTemporality = aggregationTemporality; }
        public boolean isMonotonic() { return isMonotonic; }
        public void setMonotonic(boolean monotonic) { isMonotonic = monotonic; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SumDataPoint {
        private List<Attribute> attributes;
        private String startTimeUnixNano;
        private String timeUnixNano;
        @JsonProperty("asDouble")
        private Double asDouble;       // OTel JSON: "asDouble": 123.45
        @JsonProperty("asInt")
        private Object asInt;          // OTel JSON: "asInt": "42" or 42

        public List<Attribute> getAttributes() { return attributes; }
        public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }
        public String getStartTimeUnixNano() { return startTimeUnixNano; }
        public void setStartTimeUnixNano(String startTimeUnixNano) { this.startTimeUnixNano = startTimeUnixNano; }
        public String getTimeUnixNano() { return timeUnixNano; }
        public void setTimeUnixNano(String timeUnixNano) { this.timeUnixNano = timeUnixNano; }

        public double getValueAsDouble() {
            if (asDouble != null) return asDouble;
            if (asInt instanceof Number n) return n.doubleValue();
            if (asInt instanceof String s) {
                try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
            }
            return 0;
        }
    }

    // ── Gauge ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Gauge {
        @JsonProperty("dataPoints")
        private List<GaugeDataPoint> dataPoints;

        public List<GaugeDataPoint> getDataPoints() { return dataPoints; }
        public void setDataPoints(List<GaugeDataPoint> dataPoints) { this.dataPoints = dataPoints; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GaugeDataPoint {
        private List<Attribute> attributes;
        private String startTimeUnixNano;
        private String timeUnixNano;
        @JsonProperty("asDouble")
        private Double asDouble;       // OTel JSON: "asDouble": 123.45
        @JsonProperty("asInt")
        private Object asInt;          // OTel JSON: "asInt": "42" or 42

        public List<Attribute> getAttributes() { return attributes; }
        public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }
        public String getStartTimeUnixNano() { return startTimeUnixNano; }
        public void setStartTimeUnixNano(String startTimeUnixNano) { this.startTimeUnixNano = startTimeUnixNano; }
        public String getTimeUnixNano() { return timeUnixNano; }
        public void setTimeUnixNano(String timeUnixNano) { this.timeUnixNano = timeUnixNano; }

        public double getValueAsDouble() {
            if (asDouble != null) return asDouble;
            if (asInt instanceof Number n) return n.doubleValue();
            if (asInt instanceof String s) {
                try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
            }
            return 0;
        }
    }

    // ── Attribute ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attribute {
        private String key;
        private Value value;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Value getValue() { return value; }
        public void setValue(Value value) { this.value = value; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        private String stringValue;
        private Long intValue;
        private Double doubleValue;
        private Boolean boolValue;

        public String getStringValue() {
            if (stringValue != null) return stringValue;
            if (intValue != null) return String.valueOf(intValue);
            if (doubleValue != null) return String.valueOf(doubleValue);
            if (boolValue != null) return String.valueOf(boolValue);
            return null;
        }

        public void setStringValue(String stringValue) { this.stringValue = stringValue; }
        public Long getIntValue() { return intValue; }
        public void setIntValue(Long intValue) { this.intValue = intValue; }
        public Double getDoubleValue() { return doubleValue; }
        public void setDoubleValue(Double doubleValue) { this.doubleValue = doubleValue; }
        public Boolean getBoolValue() { return boolValue; }
        public void setBoolValue(Boolean boolValue) { this.boolValue = boolValue; }
    }

    // ── Helpers ──

    /** 获取此 payload 中所有 metric 的服务名 */
    public String extractServiceName() {
        if (resourceMetrics != null && !resourceMetrics.isEmpty()) {
            return resourceMetrics.get(0).getResource() != null
                    ? resourceMetrics.get(0).getResource().getServiceName() : "unknown";
        }
        return "unknown";
    }
}
