package com.observability.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 外部调用台账记录（V23 B4 / 任务分解 M10）。
 *
 * <p>统一描述 RAG / 工具函数 / SKILL / MCP 四类外部调用的聚合指标，
 * 供前端「外部调用」TAB（ExternalCallTab）按 category 切换展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvocationRecord {

    /** 调用类别：rag | tool | skill | mcp */
    private String category;

    /** 调用名（如 crm.getCustomer / MCP:queryBalance / rag.retrieve） */
    private String name;

    /** 调用次数 */
    private long calls;

    /** 成功次数 */
    private long success;

    /** 失败次数 */
    private long failed;

    /** 平均耗时（ms） */
    private double avgLatencyMs;

    /** P95 耗时（ms） */
    private double p95LatencyMs;

    /** 错误率（%） */
    private double errorRate;

    /** 典型错误（无则 null） */
    private String lastError;
}
