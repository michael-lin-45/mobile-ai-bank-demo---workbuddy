package com.mobileagent.app.mock;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 模拟银行服务 - 提供转账和账单查询的模拟数据
 */
@Service
public class MockBankingService {

    /**
     * 模拟执行转账
     */
    public TransferResult executeTransfer(String receiver, BigDecimal amount, String purpose) {
        // 模拟: 随机生成交易流水号
        String txnId = "TXN" + System.currentTimeMillis() % 1000000;
        return new TransferResult(true, receiver, amount, purpose, txnId,
                "转账成功！已向" + receiver + "转账" + amount + "元。"
                + (purpose != null ? "用途：" + purpose + "。" : "")
                + "交易流水号：" + txnId);
    }

    /**
     * 模拟查询账单
     */
    public BillQueryResult queryBill(String timePeriod, String expenseType) {
        List<BillItem> items = new ArrayList<>();

        // 根据时间范围和类型生成模拟账单
        LocalDate today = LocalDate.now();
        String period = timePeriod != null ? timePeriod : "近一个月";

        if (expenseType == null || expenseType.isEmpty()) {
            // 全部类型
            items.add(new BillItem("餐饮", new BigDecimal("356.50"), today.minusDays(1)));
            items.add(new BillItem("交通", new BigDecimal("128.00"), today.minusDays(2)));
            items.add(new BillItem("购物", new BigDecimal("899.00"), today.minusDays(3)));
            items.add(new BillItem("水电费", new BigDecimal("245.30"), today.minusDays(5)));
            items.add(new BillItem("工资收入", new BigDecimal("15000.00"), today.minusDays(10)));
        } else {
            // 按类型筛选
            switch (expenseType) {
                case "餐饮":
                    items.add(new BillItem("餐饮", new BigDecimal("356.50"), today.minusDays(1)));
                    break;
                case "交通":
                    items.add(new BillItem("交通", new BigDecimal("128.00"), today.minusDays(2)));
                    break;
                case "购物":
                    items.add(new BillItem("购物", new BigDecimal("899.00"), today.minusDays(3)));
                    break;
                case "支出":
                case "开销":
                    items.add(new BillItem("餐饮", new BigDecimal("356.50"), today.minusDays(1)));
                    items.add(new BillItem("交通", new BigDecimal("128.00"), today.minusDays(2)));
                    items.add(new BillItem("购物", new BigDecimal("899.00"), today.minusDays(3)));
                    items.add(new BillItem("水电费", new BigDecimal("245.30"), today.minusDays(5)));
                    break;
                case "收入":
                    items.add(new BillItem("工资收入", new BigDecimal("15000.00"), today.minusDays(10)));
                    break;
                default:
                    items.add(new BillItem(expenseType, new BigDecimal("200.00"), today.minusDays(1)));
                    break;
            }
        }

        BigDecimal total = items.stream()
                .map(BillItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder();
        sb.append(period).append("的账单明细：\n");
        for (BillItem item : items) {
            sb.append("- ").append(item.type()).append(": ")
              .append(item.amount()).append("元 (")
              .append(item.date().format(DateTimeFormatter.ofPattern("MM月dd日")))
              .append(")\n");
        }
        sb.append("合计: ").append(total).append("元");

        return new BillQueryResult(true, period, expenseType, items, total, sb.toString());
    }

    // --- 内部数据类 ---

    public record TransferResult(boolean success, String receiver, BigDecimal amount,
                                  String purpose, String txnId, String message) {}

    public record BillQueryResult(boolean success, String timePeriod, String expenseType,
                                   List<BillItem> items, BigDecimal total, String message) {}

    public record BillItem(String type, BigDecimal amount, LocalDate date) {}
}
