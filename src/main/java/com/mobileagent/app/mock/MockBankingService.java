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

    /**
     * 模拟理财咨询
     */
    public WealthConsultResult wealthConsult(String riskLevel) {
        String normalizedRisk = normalizeRiskLevel(riskLevel);
        StringBuilder sb = new StringBuilder();
        sb.append("根据您的风险偏好（").append(normalizedRisk).append("），为您推荐以下理财产品：\n");

        switch (normalizedRisk) {
            case "激进" -> {
                sb.append("- 天天利进取版: 年化收益4.2%, 中高风险, 灵活申赎\n");
                sb.append("- 汇添富精选混合: 年化收益5.8%, 高风险, 锁定6个月\n");
                sb.append("- 成长优选基金: 年化收益6.5%, 高风险, 锁定1年\n");
            }
            case "保守" -> {
                sb.append("- 安心宝定期: 年化收益2.8%, 低风险, 锁定3个月\n");
                sb.append("- 稳利宝保本版: 年化收益3.0%, 低风险, 灵活申赎\n");
                sb.append("- 国债逆回购: 年化收益2.5%, 极低风险, 隔夜\n");
            }
            default -> { // 稳健
                sb.append("- 稳利宝稳健版: 年化收益3.5%, 中低风险, 灵活申赎\n");
                sb.append("- 汇添富稳健债券: 年化收益3.8%, 中低风险, 锁定3个月\n");
                sb.append("- 优选理财组合A: 年化收益4.0%, 中风险, 锁定6个月\n");
            }
        }

        sb.append("\n温馨提示: 理财有风险,投资需谨慎。过往收益不代表未来表现。");

        return new WealthConsultResult(true, normalizedRisk, sb.toString());
    }

    /**
     * 模拟理财产品解读
     */
    public WealthInterpretResult wealthInterpret(String productName) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(productName).append(" 产品解读】\n");

        switch (productName) {
            case "稳利宝" -> {
                sb.append("- 产品类型: 固定收益类理财\n");
                sb.append("- 风险等级: R2(中低风险)\n");
                sb.append("- 年化收益率: 3.0%~3.8%(根据版本)\n");
                sb.append("- 投资期限: 灵活申赎\n");
                sb.append("- 起购金额: 1万元\n");
                sb.append("- 底层资产: 国债、政策性金融债、银行存款\n");
                sb.append("- 适合人群: 追求稳健收益、资金灵活性要求高的投资者\n");
            }
            case "汇添富" -> {
                sb.append("- 产品类型: 混合型基金理财\n");
                sb.append("- 风险等级: R3(中风险)\n");
                sb.append("- 年化收益率: 3.8%~5.8%(根据版本)\n");
                sb.append("- 投资期限: 锁定3~6个月\n");
                sb.append("- 起购金额: 1000元\n");
                sb.append("- 底层资产: 债券80%+股票20%\n");
                sb.append("- 适合人群: 追求收益高于存款、能承受一定波动的投资者\n");
            }
            case "天天利" -> {
                sb.append("- 产品类型: 现金管理类理财\n");
                sb.append("- 风险等级: R1(低风险)\n");
                sb.append("- 年化收益率: 2.8%~4.2%(根据版本)\n");
                sb.append("- 投资期限: 灵活申赎(T+1到账)\n");
                sb.append("- 起购金额: 1元\n");
                sb.append("- 底层资产: 银行间同业存单、央行票据\n");
                sb.append("- 适合人群: 闲置资金短期理财,替代活期存款\n");
            }
            case "安心宝" -> {
                sb.append("- 产品类型: 保本型理财\n");
                sb.append("- 风险等级: R1(低风险)\n");
                sb.append("- 年化收益率: 2.8%~3.0%\n");
                sb.append("- 投资期限: 锁定3个月\n");
                sb.append("- 起购金额: 5万元\n");
                sb.append("- 底层资产: 银行存款、国债\n");
                sb.append("- 保本机制: 银行提供本金保障\n");
                sb.append("- 适合人群: 追求本金安全、稳健收益的保守型投资者\n");
            }
            default -> {
                sb.append("- 产品类型: 综合理财\n");
                sb.append("- 风险等级: R2(中低风险)\n");
                sb.append("- 年化收益率: 3.0%~4.5%\n");
                sb.append("- 投资期限: 灵活申赎\n");
                sb.append("- 起购金额: 1万元\n");
                sb.append("- 底层资产: 债券、货币市场工具\n");
                sb.append("- 如需了解具体产品详情,请提供准确的产品名称\n");
            }
        }

        sb.append("\n温馨提示: 以上信息仅供参考,具体以产品说明书为准。");

        return new WealthInterpretResult(true, productName, sb.toString());
    }

    private String normalizeRiskLevel(String riskLevel) {
        if (riskLevel == null) return "稳健";
        String lower = riskLevel.toLowerCase();
        if (lower.contains("激进") || lower.contains("高风险") || lower.contains("进取")) return "激进";
        if (lower.contains("保守") || lower.contains("低风险")) return "保守";
        return "稳健";
    }

    public record TransferResult(boolean success, String receiver, BigDecimal amount,
                                  String purpose, String txnId, String message) {}

    public record BillQueryResult(boolean success, String timePeriod, String expenseType,
                                   List<BillItem> items, BigDecimal total, String message) {}

    public record BillItem(String type, BigDecimal amount, LocalDate date) {}

    public record WealthConsultResult(boolean success, String riskLevel, String message) {}

    public record WealthInterpretResult(boolean success, String productName, String message) {}
}
