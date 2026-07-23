package com.observability.service;

import com.observability.dto.InvocationRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部调用台账服务（V23 B4 / 任务分解 M10）。
 *
 * <p>独立服务于 ToolStatsService（不扩展之），统一聚合 RAG / 工具函数 / SKILL / MCP 四类外部调用。
 * 数据以确定性 seed 提供（演示环境无真实调用上报时仍可观测），B5 将接入 McpToolCallback 真实埋点。
 */
@Slf4j
@Service
public class InvocationRecordService {

    /** 演示用 seed 台账（首次构造后不变） */
    private final List<InvocationRecord> records = seed();

    /**
     * 按类别查询调用台账。
     *
     * @param category all | rag | tool | skill | mcp（null 或 all → 全部）
     * @return 调用记录列表
     */
    public List<InvocationRecord> getInvocations(String category) {
        if (category == null || category.equalsIgnoreCase("all")) {
            return new ArrayList<>(records);
        }
        String cat = category.toLowerCase();
        List<InvocationRecord> out = new ArrayList<>();
        for (InvocationRecord r : records) {
            if (cat.equals(r.getCategory().toLowerCase())) {
                out.add(r);
            }
        }
        return out;
    }

    private List<InvocationRecord> seed() {
        List<InvocationRecord> list = new ArrayList<>();

        // 工具函数（业务系统调用）
        list.add(rec("tool", "crm.getCustomer", 1820, 1790, 30, 120, 320, "下游超时"));
        list.add(rec("tool", "risk.evaluate", 940, 935, 5, 240, 610, null));
        list.add(rec("tool", "ots.sendCode", 760, 758, 2, 180, 430, null));
        list.add(rec("tool", "doc.generateReceipt", 310, 308, 2, 360, 720, null));

        // MCP 工具（V23 B5 真实埋点来源）
        list.add(rec("mcp", "MCP:queryBalance", 640, 638, 2, 210, 480, null));
        list.add(rec("mcp", "MCP:transfer", 220, 218, 2, 340, 700, "授权失败"));
        list.add(rec("mcp", "MCP:fundDetail", 150, 150, 0, 260, 540, null));

        // RAG 检索
        list.add(rec("rag", "rag.retrieve", 3120, 3100, 20, 85, 260, null));

        // SKILL
        list.add(rec("skill", "skill.transfer", 220, 218, 2, 340, 700, null));
        list.add(rec("skill", "skill.recommend", 180, 180, 0, 200, 460, null));

        return list;
    }

    private InvocationRecord rec(String category, String name, long calls, long success, long failed,
                                 double avg, double p95, String lastError) {
        double errorRate = calls > 0 ? Math.round(failed * 1000.0 / calls) / 10.0 : 0.0;
        return InvocationRecord.builder()
                .category(category)
                .name(name)
                .calls(calls)
                .success(success)
                .failed(failed)
                .avgLatencyMs(avg)
                .p95LatencyMs(p95)
                .errorRate(errorRate)
                .lastError(lastError)
                .build();
    }
}
