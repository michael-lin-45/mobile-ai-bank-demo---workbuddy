package com.observability.dto;

import java.util.List;
import java.util.Map;

/**
 * AI 洞察聚合视图对象 — 对应 GET /api/v1/ai/insights 响应
 */
public class AIInsightVO {
    private List<IntentConfidence> confidenceDistribution;
    private ExtractionCompleteness extractionCompleteness;
    private RewriteAccuracy rewriteAccuracy;

    public AIInsightVO() {}

    public List<IntentConfidence> getConfidenceDistribution() { return confidenceDistribution; }
    public void setConfidenceDistribution(List<IntentConfidence> confidenceDistribution) { this.confidenceDistribution = confidenceDistribution; }
    public ExtractionCompleteness getExtractionCompleteness() { return extractionCompleteness; }
    public void setExtractionCompleteness(ExtractionCompleteness extractionCompleteness) { this.extractionCompleteness = extractionCompleteness; }
    public RewriteAccuracy getRewriteAccuracy() { return rewriteAccuracy; }
    public void setRewriteAccuracy(RewriteAccuracy rewriteAccuracy) { this.rewriteAccuracy = rewriteAccuracy; }

    // ── IntentConfidence ──

    public static class IntentConfidence {
        private String intent;
        private double avgConfidence;
        private long count;
        private List<Bucket> distribution;

        public IntentConfidence() {}
        public IntentConfidence(String intent, double avgConfidence, long count, List<Bucket> distribution) {
            this.intent = intent;
            this.avgConfidence = avgConfidence;
            this.count = count;
            this.distribution = distribution;
        }

        public String getIntent() { return intent; }
        public void setIntent(String intent) { this.intent = intent; }
        public double getAvgConfidence() { return avgConfidence; }
        public void setAvgConfidence(double avgConfidence) { this.avgConfidence = avgConfidence; }
        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }
        public List<Bucket> getDistribution() { return distribution; }
        public void setDistribution(List<Bucket> distribution) { this.distribution = distribution; }
    }

    // ── Bucket ──

    public static class Bucket {
        private String range;
        private long count;

        public Bucket() {}
        public Bucket(String range, long count) { this.range = range; this.count = count; }

        public String getRange() { return range; }
        public void setRange(String range) { this.range = range; }
        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }
    }

    // ── ExtractionCompleteness ──

    public static class ExtractionCompleteness {
        private double overallRate;
        private Map<String, Double> byField;
        private long totalExtractions;

        public double getOverallRate() { return overallRate; }
        public void setOverallRate(double overallRate) { this.overallRate = overallRate; }
        public Map<String, Double> getByField() { return byField; }
        public void setByField(Map<String, Double> byField) { this.byField = byField; }
        public long getTotalExtractions() { return totalExtractions; }
        public void setTotalExtractions(long totalExtractions) { this.totalExtractions = totalExtractions; }
    }

    // ── RewriteAccuracy ──

    public static class RewriteAccuracy {
        private double accuracyRate;
        private long totalRewrites;
        private Map<String, Long> failureReasons;

        public double getAccuracyRate() { return accuracyRate; }
        public void setAccuracyRate(double accuracyRate) { this.accuracyRate = accuracyRate; }
        public long getTotalRewrites() { return totalRewrites; }
        public void setTotalRewrites(long totalRewrites) { this.totalRewrites = totalRewrites; }
        public Map<String, Long> getFailureReasons() { return failureReasons; }
        public void setFailureReasons(Map<String, Long> failureReasons) { this.failureReasons = failureReasons; }
    }
}
