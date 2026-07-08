package com.observability.dto;

import java.util.List;

/**
 * 转化漏斗视图对象 — 对应 GET /api/v1/ai/conversion-funnel 响应
 */
public class FunnelVO {

    /** 漏斗各阶段 */
    private List<Stage> stages;

    /** 流失节点 */
    private List<DropOff> dropOffs;

    public FunnelVO() {}

    // ── Getters / Setters ──

    public List<Stage> getStages() { return stages; }
    public void setStages(List<Stage> stages) { this.stages = stages; }
    public List<DropOff> getDropOffs() { return dropOffs; }
    public void setDropOffs(List<DropOff> dropOffs) { this.dropOffs = dropOffs; }

    // ── Stage ──

    public static class Stage {
        private String stage;
        private long count;
        private double rate;

        public Stage() {}
        public Stage(String stage, long count, double rate) { this.stage = stage; this.count = count; this.rate = rate; }

        public String getStage() { return stage; }
        public void setStage(String stage) { this.stage = stage; }
        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }
        public double getRate() { return rate; }
        public void setRate(double rate) { this.rate = rate; }
    }

    // ── DropOff ──

    public static class DropOff {
        private String stage;
        private long count;
        private double rate;

        public DropOff() {}
        public DropOff(String stage, long count, double rate) { this.stage = stage; this.count = count; this.rate = rate; }

        public String getStage() { return stage; }
        public void setStage(String stage) { this.stage = stage; }
        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }
        public double getRate() { return rate; }
        public void setRate(double rate) { this.rate = rate; }
    }
}
