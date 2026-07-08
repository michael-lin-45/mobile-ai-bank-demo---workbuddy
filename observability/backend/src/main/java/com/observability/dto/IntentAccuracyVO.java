package com.observability.dto;

import java.util.List;
import java.util.Map;

/**
 * 意图准确率趋势 + 混淆矩阵视图对象
 * 对应 GET /api/v1/ai/intent-accuracy-trend 和 GET /api/v1/ai/confusion-matrix 响应
 */
public class IntentAccuracyVO {

    /** 准确率趋势系列 */
    private List<TrendSeries> accuracyTrend;

    /** 混淆矩阵 */
    private ConfusionMatrix confusionMatrix;

    public IntentAccuracyVO() {}

    // ── Getters / Setters ──

    public List<TrendSeries> getAccuracyTrend() { return accuracyTrend; }
    public void setAccuracyTrend(List<TrendSeries> accuracyTrend) { this.accuracyTrend = accuracyTrend; }
    public ConfusionMatrix getConfusionMatrix() { return confusionMatrix; }
    public void setConfusionMatrix(ConfusionMatrix confusionMatrix) { this.confusionMatrix = confusionMatrix; }

    // ── TrendSeries ──

    public static class TrendSeries {
        private String name;
        private List<DataPoint> data;

        public TrendSeries() {}
        public TrendSeries(String name, List<DataPoint> data) { this.name = name; this.data = data; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<DataPoint> getData() { return data; }
        public void setData(List<DataPoint> data) { this.data = data; }
    }

    // ── DataPoint ──

    public static class DataPoint {
        private String time;
        private double value;

        public DataPoint() {}
        public DataPoint(String time, double value) { this.time = time; this.value = value; }

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
    }

    // ── ConfusionMatrix ──

    public static class ConfusionMatrix {
        private List<String> labels;
        private int[][] matrix;
        private int totalSamples;

        public ConfusionMatrix() {}

        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> labels) { this.labels = labels; }
        public int[][] getMatrix() { return matrix; }
        public void setMatrix(int[][] matrix) { this.matrix = matrix; }
        public int getTotalSamples() { return totalSamples; }
        public void setTotalSamples(int totalSamples) { this.totalSamples = totalSamples; }
    }
}
