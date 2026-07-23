package com.observability.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 慢会话诊断块（V23 B4 / 任务分解 M4①）。
 *
 * <p>聚合报告 {@code /ai/insights-report} 的 {@code slowSessions} 字段由裸列表升级为带触发标志的块，
 * 前端「智能诊断」TAB 据此决定是否高亮告警。
 *
 * <ul>
 *   <li>triggered — 是否触发告警（p90 时延超过阈值）</li>
 *   <li>p90Seconds — 近 7 天会话时延 P90（秒）</li>
 *   <li>thresholdSeconds — 触发阈值（秒），默认 60</li>
 *   <li>rows — Top5 最慢会话明细</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowSessionBlock {

    /** 是否触发告警 */
    private boolean triggered;

    /** 近 7 天会话时延 P90（秒） */
    private double p90Seconds;

    /** 触发阈值（秒） */
    private double thresholdSeconds;

    /** Top5 最慢会话明细 */
    private List<SlowSessionRow> rows;

    /** 单条慢会话明细 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlowSessionRow {
        private String sessionId;
        private Long durationSeconds;
        private Integer turnCount;
        private String intentFlow;
    }
}
