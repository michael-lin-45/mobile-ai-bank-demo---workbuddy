package com.observability.service;

import com.observability.dto.InvocationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InvocationRecordService 单测 — 锚定 V23 M5 / M10 验收：
 *  - 四类（all / tool / rag / skill / mcp）分类正确；
 *  - MCP seed 分支为 queryBalance / transfer / fundDetail（PRD M10③ + 任务分解）；
 *  - errorRate 以百分比口径计算且落在 [0,100]。
 *
 * <p>该 Service 为确定性 seed（无外部依赖），直接实例化即可测试。
 */
class InvocationRecordServiceTest {

    private final InvocationRecordService service = new InvocationRecordService();

    @Test
    void category_all_returnsAllSeedRecords() {
        // 4 tool + 3 mcp + 1 rag + 2 skill = 10
        assertEquals(10, service.getInvocations("all").size());
    }

    @Test
    void category_null_returnsAll() {
        assertEquals(10, service.getInvocations(null).size());
    }

    @Test
    void category_tool_returnsFourToolRecords() {
        List<InvocationRecord> tool = service.getInvocations("tool");
        assertEquals(4, tool.size());
        List<String> names = tool.stream().map(InvocationRecord::getName).collect(Collectors.toList());
        assertTrue(names.containsAll(List.of(
                "crm.getCustomer", "risk.evaluate", "ots.sendCode", "doc.generateReceipt")));
    }

    @Test
    void category_rag_returnsOneRecord() {
        List<InvocationRecord> rag = service.getInvocations("rag");
        assertEquals(1, rag.size());
        assertEquals("rag.retrieve", rag.get(0).getName());
    }

    @Test
    void category_skill_returnsTwoRecords() {
        List<InvocationRecord> skill = service.getInvocations("skill");
        assertEquals(2, skill.size());
        List<String> names = skill.stream().map(InvocationRecord::getName).collect(Collectors.toList());
        assertTrue(names.containsAll(List.of("skill.transfer", "skill.recommend")));
    }

    @Test
    void category_mcp_returnsQueryBalanceTransferFundDetail() {
        // PRD M10③ + 任务分解：MCP seed 分支为 queryBalance / transfer / fundDetail
        List<InvocationRecord> mcp = service.getInvocations("mcp");
        assertEquals(3, mcp.size());
        for (InvocationRecord r : mcp) {
            assertEquals("mcp", r.getCategory());
        }
        List<String> names = mcp.stream().map(InvocationRecord::getName).collect(Collectors.toList());
        assertTrue(names.containsAll(List.of(
                "MCP:queryBalance", "MCP:transfer", "MCP:fundDetail")));

        // MCP:transfer：220 调用 / 2 失败 → 错误率 ≈ 0.9%
        InvocationRecord transfer = mcp.stream()
                .filter(r -> "MCP:transfer".equals(r.getName())).findFirst().orElseThrow();
        assertEquals(220, transfer.getCalls());
        assertEquals(2, transfer.getFailed());
        assertTrue(transfer.getErrorRate() > 0.0);
        assertEquals("授权失败", transfer.getLastError());
    }

    @Test
    void errorRate_calculatedAsPercentageWithinRange() {
        for (InvocationRecord r : service.getInvocations("all")) {
            double expected = r.getCalls() > 0
                    ? Math.round(r.getFailed() * 1000.0 / r.getCalls()) / 10.0 : 0.0;
            assertEquals(expected, r.getErrorRate(), 0.001,
                    "errorRate 应等于 failed/calls*100（百分比）");
            assertTrue(r.getErrorRate() >= 0.0 && r.getErrorRate() <= 100.0,
                    "errorRate 应在 [0,100] 区间");
        }
    }

    @Test
    void mcp_queryBalance_seedValues() {
        // 对齐 InvocationRecordService.seed() 实际 seed：
        // MCP:queryBalance = calls 640 / success 638 / failed 2 / lastError null
        InvocationRecord qb = service.getInvocations("mcp").stream()
                .filter(r -> "MCP:queryBalance".equals(r.getName())).findFirst().orElseThrow();
        assertEquals(640, qb.getCalls());
        assertEquals(638, qb.getSuccess());
        assertEquals(2, qb.getFailed());
        assertNull(qb.getLastError());
    }
}
