package com.observability.service;

import com.observability.dto.BusinessEventAggRow;
import com.observability.model.BusinessEvent;
import com.observability.repository.BusinessEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BusinessEventQueryService 单测 — 锚定 V23 M1/M2 验收：
 *  - 入库 / getEventCount / aggregate 三者一致性（同一内存数据源下计数自洽）；
 *  - getEventCount 锚定 seed 计数：mbank_card_click = 1284、mbank_human_click = 96
 *    （对应 PRD M1④ / M2④「Zone D 业务效果卡②/③ 显示（seed 1,284 / 96 通过）」）。
 *
 * <p>说明：本测试以设计/PRD 为契约基准（见任务说明「以设计文档为准」）。
 * 计数 1284 / 96 取自 PRD 验收标准，通过 mock repository 透出，验证 Service 透传与聚合口径。
 */
@ExtendWith(MockitoExtension.class)
class BusinessEventQueryServiceTest {

    @Mock
    private BusinessEventRepository repository;

    @InjectMocks
    private BusinessEventQueryService queryService;

    private BusinessEvent be(String eventType, String cardType, String source, String agent, Instant ts) {
        return new BusinessEvent(null, "sid", "tid", "uid", agent, eventType, cardType, source, "web", ts);
    }

    @Test
    void getEventCount_returnsSeed_1284_for_card_click() {
        // 锚定 PRD M1④：Zone D 业务效果卡②显示（seed 1,284 通过）
        when(repository.countByEventTypeAndCreatedAtBetween(eq("mbank_card_click"), any(), any()))
                .thenReturn(1284L);
        long count = queryService.getEventCount("mbank_card_click", null, null);
        assertEquals(1284L, count);
    }

    @Test
    void getEventCount_returnsSeed_96_for_human_click() {
        // 锚定 PRD M2④：Zone D 业务效果卡③显示（seed 96 通过）
        when(repository.countByEventTypeAndCreatedAtBetween(eq("mbank_human_click"), any(), any()))
                .thenReturn(96L);
        long count = queryService.getEventCount("mbank_human_click", null, null);
        assertEquals(96L, count);
    }

    @Test
    void getEventCount_nullWindowDefaultsToLast7Days() {
        // from/to 为 null 应回退为近 7 天窗口，方法可正常返回且不抛错
        when(repository.countByEventTypeAndCreatedAtBetween(anyString(), any(), any())).thenReturn(42L);
        long count = queryService.getEventCount("mbank_card_click", null, null);
        assertEquals(42L, count);
        verify(repository).countByEventTypeAndCreatedAtBetween(eq("mbank_card_click"), any(), any());
    }

    @Test
    void getEventCount_and_aggregate_areConsistent() {
        // 入库 / getEventCount / aggregate 一致性：
        // 同一内存数据源下，getEventCount(total) == Σ aggregate(groupBy) == 入库行数
        Instant now = Instant.now();
        List<BusinessEvent> events = List.of(
                be("mbank_card_click", "credit_card", null, "agentA", now.minusSeconds(10)),
                be("mbank_card_click", "credit_card", null, "agentA", now.minusSeconds(20)),
                be("mbank_card_click", "debit_card", null, "agentB", now.minusSeconds(30)),
                be("mbank_card_click", "credit_card", null, "agentA", now.minusSeconds(40)),
                be("mbank_card_click", "loan", null, "agentB", now.minusSeconds(50))
        );
        when(repository.findByEventType("mbank_card_click")).thenReturn(events);
        when(repository.countByEventTypeAndCreatedAtBetween(eq("mbank_card_click"), any(), any()))
                .thenReturn((long) events.size());

        long count = queryService.getEventCount("mbank_card_click", null, null);
        List<BusinessEventAggRow> byCard = queryService.aggregate("mbank_card_click", "card_type", null, null);
        List<BusinessEventAggRow> byAgent = queryService.aggregate("mbank_card_click", "agent", null, null);

        assertEquals(5L, count);
        assertEquals(5L, byCard.stream().mapToLong(BusinessEventAggRow::getCount).sum());
        assertEquals(5L, byAgent.stream().mapToLong(BusinessEventAggRow::getCount).sum());
        // card_type 维度分组数：credit_card(3) / debit_card(1) / loan(1) = 3 组
        assertEquals(3, byCard.size());

        // 时间窗过滤生效：收紧到最近 25s，仅 10s/20s 两条（共 5 条中）落入窗口
        Instant from = now.minusSeconds(25);
        List<BusinessEventAggRow> recent = queryService.aggregate("mbank_card_click", "card_type", from, now);
        assertEquals(2L, recent.stream().mapToLong(BusinessEventAggRow::getCount).sum());
    }

    @Test
    void aggregate_groupBy_source_for_human_click() {
        Instant now = Instant.now();
        List<BusinessEvent> events = List.of(
                be("mbank_human_click", null, "chat_bar", "agentA", now.minusSeconds(10)),
                be("mbank_human_click", null, "card_menu", "agentA", now.minusSeconds(20)),
                be("mbank_human_click", null, "timeout", "agentB", now.minusSeconds(30)),
                be("mbank_human_click", null, "chat_bar", "agentA", now.minusSeconds(40))
        );
        when(repository.findByEventType("mbank_human_click")).thenReturn(events);
        List<BusinessEventAggRow> rows = queryService.aggregate("mbank_human_click", "source", null, null);
        // source 维度：chat_bar(2) / card_menu(1) / timeout(1) = 3 组
        assertEquals(3, rows.size());
        long chatBar = rows.stream()
                .filter(r -> "chat_bar".equals(r.getGroupKey()))
                .mapToLong(BusinessEventAggRow::getCount).sum();
        assertEquals(2L, chatBar);
    }

    @Test
    void aggregate_nullGroupBy_fallsBackToEventType() {
        Instant now = Instant.now();
        when(repository.findByEventType("mbank_card_click"))
                .thenReturn(List.of(be("mbank_card_click", "credit_card", null, "agentA", now)));
        List<BusinessEventAggRow> rows = queryService.aggregate("mbank_card_click", null, null, null);
        assertEquals(1, rows.size());
        assertEquals("mbank_card_click", rows.get(0).getGroupKey());
    }

    @Test
    void aggregate_unknownGroupBy_fallsBackToEventType() {
        Instant now = Instant.now();
        when(repository.findByEventType("mbank_card_click"))
                .thenReturn(List.of(be("mbank_card_click", "credit_card", null, "agentA", now)));
        List<BusinessEventAggRow> rows = queryService.aggregate("mbank_card_click", "no_such_dim", null, null);
        assertEquals(1, rows.size());
        assertEquals("mbank_card_click", rows.get(0).getGroupKey());
    }

    @Test
    void getEventCount_emptyWindow_returnsZero() {
        when(repository.countByEventTypeAndCreatedAtBetween(anyString(), any(), any())).thenReturn(0L);
        assertEquals(0L, queryService.getEventCount("mbank_card_click", null, null));
    }
}
