package com.observability.dto;

/**
 * 业务埋点聚合行 — 按 groupBy 维度聚合后的单行结果。
 *
 * <pre>{@code { "groupKey": "credit_card", "count": 642 }}</pre>
 */
public class BusinessEventAggRow {

    /** 聚合维度值（card_type / source / agent / session_id / trace_id / event_type） */
    private String groupKey;

    /** 该维度下的事件计数 */
    private long count;

    public BusinessEventAggRow() {}

    public BusinessEventAggRow(String groupKey, long count) {
        this.groupKey = groupKey;
        this.count = count;
    }

    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
}
