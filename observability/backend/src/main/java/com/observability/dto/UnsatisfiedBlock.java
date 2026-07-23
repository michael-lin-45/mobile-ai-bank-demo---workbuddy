package com.observability.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 不满意会话诊断块（V23 B4 / 任务分解 M4②）。
 *
 * <p>聚合报告 {@code /ai/insights-report} 的 {@code unsatisfied} 字段由裸列表升级为带触发标志的块，
 * 前端「智能诊断」TAB 据此决定是否高亮告警。
 *
 * <ul>
 *   <li>triggered — 是否触发告警（不满意率超过阈值）</li>
 *   <li>rate — 不满意率（%）</li>
 *   <li>total / unsatisfied — 窗口内总会话数 / 不满意会话数</li>
 *   <li>clusters — 不满意会话共性聚类</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnsatisfiedBlock {

    /** 是否触发告警 */
    private boolean triggered;

    /** 不满意率（%） */
    private double rate;

    /** 窗口内总会话数 */
    private long total;

    /** 窗口内不满意会话数 */
    private long unsatisfied;

    /** 聚类 */
    private List<UnsatisfiedCluster> clusters;

    /** 单条聚类 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnsatisfiedCluster {
        private String dimension;
        private int count;
        private List<String> examples;
        private String commonPattern;
    }
}
