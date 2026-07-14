package com.mobileagent.app.mock;

import com.mobileagent.app.observability.ObservabilityMetrics;
import io.micrometer.core.instrument.Timer;
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

    private final ObservabilityMetrics obsMetrics;

    public MockBankingService(ObservabilityMetrics obsMetrics) {
        this.obsMetrics = obsMetrics;
    }

    /**
     * 模拟执行转账
     */
    public TransferResult executeTransfer(String receiver, BigDecimal amount, String purpose) {
        Timer.Sample sample = obsMetrics.startToolTimer();
        try {
            // 模拟: 随机生成交易流水号
            String txnId = "TXN" + System.currentTimeMillis() % 1000000;
            TransferResult result = new TransferResult(true, receiver, amount, purpose, txnId,
                    "转账成功！已向" + receiver + "转账" + amount + "元。"
                    + (purpose != null ? "用途：" + purpose + "。" : "")
                    + "交易流水号：" + txnId);
            // 埋点：工具调用成功
            obsMetrics.stopToolTimer(sample, "transfer", "success");
            return result;
        } catch (Exception e) {
            obsMetrics.stopToolTimer(sample, "transfer", "error");
            throw e;
        }
    }

    /**
     * 模拟查询账单
     */
    public BillQueryResult queryBill(String timePeriod, String expenseType) {
        Timer.Sample sample = obsMetrics.startToolTimer();
        try {
            List<BillItem> items = new ArrayList<>();

            // 根据时间范围和类型生成模拟账单
            LocalDate today = LocalDate.now();
            String period = timePeriod != null ? timePeriod : "近一个月";

            if (expenseType == null || expenseType.isEmpty()) {
                // 未指定类型 → 默认返回支出
                items.add(new BillItem("餐饮", new BigDecimal("356.50"), today.minusDays(1)));
                items.add(new BillItem("交通", new BigDecimal("128.00"), today.minusDays(2)));
                items.add(new BillItem("购物", new BigDecimal("899.00"), today.minusDays(3)));
                items.add(new BillItem("水电费", new BigDecimal("245.30"), today.minusDays(5)));
            } else {
                // 按收支类型筛选
                switch (expenseType) {
                    case "支出":
                    case "开销":
                        items.add(new BillItem("餐饮", new BigDecimal("356.50"), today.minusDays(1)));
                        items.add(new BillItem("交通", new BigDecimal("128.00"), today.minusDays(2)));
                        items.add(new BillItem("购物", new BigDecimal("899.00"), today.minusDays(3)));
                        items.add(new BillItem("水电费", new BigDecimal("245.30"), today.minusDays(5)));
                        break;
                    case "收入":
                        items.add(new BillItem("工资收入", new BigDecimal("15000.00"), today.minusDays(10)));
                        items.add(new BillItem("理财收益", new BigDecimal("86.50"), today.minusDays(15)));
                        break;
                    case "收支":
                        items.add(new BillItem("餐饮", new BigDecimal("356.50"), today.minusDays(1)));
                        items.add(new BillItem("交通", new BigDecimal("128.00"), today.minusDays(2)));
                        items.add(new BillItem("购物", new BigDecimal("899.00"), today.minusDays(3)));
                        items.add(new BillItem("水电费", new BigDecimal("245.30"), today.minusDays(5)));
                        items.add(new BillItem("工资收入", new BigDecimal("15000.00"), today.minusDays(10)));
                        items.add(new BillItem("理财收益", new BigDecimal("86.50"), today.minusDays(15)));
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
            sb.append(period).append("的账单明细(").append(expenseType != null ? expenseType : "支出").append(")：\n");
            for (BillItem item : items) {
                sb.append("- ").append(item.type()).append(": ")
                  .append(item.amount()).append("元 (")
                  .append(item.date().format(DateTimeFormatter.ofPattern("MM月dd日")))
                  .append(")\n");
            }
            sb.append("合计: ").append(total).append("元");

            BillQueryResult result = new BillQueryResult(true, period, expenseType, items, total, sb.toString());
            // 埋点：工具调用成功
            obsMetrics.stopToolTimer(sample, "bill_query", "success");
            return result;
        } catch (Exception e) {
            obsMetrics.stopToolTimer(sample, "bill_query", "error");
            throw e;
        }
    }

    // --- 内部数据类 ---

    /**
     * 模拟理财咨询
     */
    public WealthConsultResult wealthConsult(String riskLevel, String focusArea) {
        Timer.Sample sample = obsMetrics.startToolTimer();
        try {
            String normalizedRisk = normalizeRiskLevel(riskLevel);
            String normalizedArea = (focusArea == null || focusArea.isEmpty()) ? "全部" : focusArea;
            StringBuilder sb = new StringBuilder();
            sb.append("根据您的风险偏好（").append(normalizedRisk).append("）");
            if (!"全部".equals(normalizedArea)) {
                sb.append("、关注领域（").append(normalizedArea).append("）");
            }
            sb.append("，为您推荐以下理财产品：\n");

            // 按关注领域筛选推荐
            switch (normalizedRisk) {
                case "激进" -> {
                    if (matchesArea(normalizedArea, "科技")) sb.append("- 科技成长基金: 年化收益6.8%, 高风险, 科技赛道, 锁定1年\n");
                    if (matchesArea(normalizedArea, "能源")) sb.append("- 新能源进取基金: 年化收益6.2%, 高风险, 能源赛道, 锁定1年\n");
                    if (matchesArea(normalizedArea, "汽车")) sb.append("- 智能汽车基金: 年化收益5.9%, 高风险, 汽车赛道, 锁定6个月\n");
                    if (matchesArea(normalizedArea, "银行")) sb.append("- 金融科技混合: 年化收益5.5%, 高风险, 银行+科技, 锁定6个月\n");
                    if (matchesArea(normalizedArea, "工业")) sb.append("- 先进制造基金: 年化收益5.7%, 高风险, 工业赛道, 锁定1年\n");
                    if (matchesArea(normalizedArea, "饮食")) sb.append("- 消费升级进取版: 年化收益5.3%, 高风险, 饮食消费, 锁定6个月\n");
                    if (matchesArea(normalizedArea, "娱乐")) sb.append("- 文娱传媒基金: 年化收益5.6%, 高风险, 娱乐赛道, 锁定1年\n");
                    if (matchesArea(normalizedArea, "全部")) {
                        sb.append("- 天天利进取版: 年化收益4.2%, 中高风险, 灵活申赎\n");
                        sb.append("- 汇添富精选混合: 年化收益5.8%, 高风险, 锁定6个月\n");
                        sb.append("- 成长优选基金: 年化收益6.5%, 高风险, 锁定1年\n");
                    }
                }
                case "保守" -> {
                    if (matchesArea(normalizedArea, "科技")) sb.append("- 科技蓝筹稳健债: 年化收益3.0%, 低风险, 科技板块, 灵活申赎\n");
                    if (matchesArea(normalizedArea, "能源")) sb.append("- 能源稳定收益: 年化收益2.9%, 低风险, 能源板块, 锁定3个月\n");
                    if (matchesArea(normalizedArea, "汽车")) sb.append("- 汽车行业安心宝: 年化收益2.8%, 低风险, 汽车板块, 锁定3个月\n");
                    if (matchesArea(normalizedArea, "银行")) sb.append("- 银行股息优选: 年化收益3.2%, 低风险, 银行板块, 灵活申赎\n");
                    if (matchesArea(normalizedArea, "工业")) sb.append("- 工业稳定理财: 年化收益2.9%, 低风险, 工业板块, 锁定3个月\n");
                    if (matchesArea(normalizedArea, "饮食")) sb.append("- 消费稳健理财: 年化收益3.0%, 低风险, 饮食消费, 灵活申赎\n");
                    if (matchesArea(normalizedArea, "娱乐")) sb.append("- 文娱稳健收益: 年化收益2.8%, 低风险, 娱乐板块, 锁定3个月\n");
                    if (matchesArea(normalizedArea, "全部")) {
                        sb.append("- 安心宝定期: 年化收益2.8%, 低风险, 锁定3个月\n");
                        sb.append("- 稳利宝保本版: 年化收益3.0%, 低风险, 灵活申赎\n");
                        sb.append("- 国债逆回购: 年化收益2.5%, 极低风险, 隔夜\n");
                    }
                }
                default -> { // 稳健
                    if (matchesArea(normalizedArea, "科技")) sb.append("- 科技稳健混合: 年化收益4.2%, 中低风险, 科技板块, 锁定6个月\n");
                    if (matchesArea(normalizedArea, "能源")) sb.append("- 新能源稳健债: 年化收益3.9%, 中低风险, 能源板块, 锁定3个月\n");
                    if (matchesArea(normalizedArea, "汽车")) sb.append("- 智驾稳健理财: 年化收益3.8%, 中低风险, 汽车板块, 锁定3个月\n");
                    if (matchesArea(normalizedArea, "银行")) sb.append("- 银行优选理财: 年化收益3.6%, 中低风险, 银行板块, 灵活申赎\n");
                    if (matchesArea(normalizedArea, "工业")) sb.append("- 制造稳健收益: 年化收益3.8%, 中低风险, 工业板块, 锁定6个月\n");
                    if (matchesArea(normalizedArea, "饮食")) sb.append("- 消费稳健组合: 年化收益3.7%, 中低风险, 饮食消费, 灵活申赎\n");
                    if (matchesArea(normalizedArea, "娱乐")) sb.append("- 文娱均衡理财: 年化收益3.9%, 中低风险, 娱乐板块, 锁定3个月\n");
                    if (matchesArea(normalizedArea, "全部")) {
                        sb.append("- 稳利宝稳健版: 年化收益3.5%, 中低风险, 灵活申赎\n");
                        sb.append("- 汇添富稳健债券: 年化收益3.8%, 中低风险, 锁定3个月\n");
                        sb.append("- 优选理财组合A: 年化收益4.0%, 中风险, 锁定6个月\n");
                    }
                }
            }

            sb.append("\n温馨提示: 理财有风险,投资需谨慎。过往收益不代表未来表现。");

            WealthConsultResult result = new WealthConsultResult(true, normalizedRisk, normalizedArea, sb.toString());
            obsMetrics.stopToolTimer(sample, "wealth_consult", "success");
            return result;
        } catch (Exception e) {
            obsMetrics.stopToolTimer(sample, "wealth_consult", "error");
            throw e;
        }
    }

    private boolean matchesArea(String normalizedArea, String target) {
        // 用户选了"全部"→ 只匹配"全部"分支
        // 用户选了具体领域→ 匹配该领域 + "全部"分支中的通用推荐
        if ("全部".equals(normalizedArea)) {
            return "全部".equals(target);
        }
        return normalizedArea.equals(target);
    }

    /**
     * 模拟理财产品解读
     */
    public WealthInterpretResult wealthInterpret(String productName) {
        Timer.Sample sample = obsMetrics.startToolTimer();
        try {
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

            WealthInterpretResult result = new WealthInterpretResult(true, productName, sb.toString());
            obsMetrics.stopToolTimer(sample, "wealth_interpret", "success");
            return result;
        } catch (Exception e) {
            obsMetrics.stopToolTimer(sample, "wealth_interpret", "error");
            throw e;
        }
    }

    private String normalizeRiskLevel(String riskLevel) {
        if (riskLevel == null) return "稳健";
        String lower = riskLevel.toLowerCase();
        if (lower.contains("激进") || lower.contains("高风险") || lower.contains("进取")) return "激进";
        if (lower.contains("保守") || lower.contains("低风险")) return "保守";
        return "稳健";
    }

    /**
     * 模拟理财产品购买 — 供 WEALTH_PURCHASE 意图的 WealthPurchaseGraphConfig 调用
     */
    public WealthPurchaseResult wealthPurchase(String productName, BigDecimal shareCount) {
        String orderId = "WP" + System.currentTimeMillis() % 1000000;
        BigDecimal unitPrice = getUnitPrice(productName);
        BigDecimal totalAmount = unitPrice.multiply(shareCount);

        String message = "购买成功！已购买" + productName + " " + shareCount + "份，"
                + "单价" + unitPrice + "元/份，"
                + "总金额" + totalAmount + "元。"
                + "订单号：" + orderId;

        return new WealthPurchaseResult(true, productName, shareCount, unitPrice, totalAmount, orderId, message);
    }

    private BigDecimal getUnitPrice(String productName) {
        if (productName == null) return new BigDecimal("1.00");
        return switch (productName) {
            case "稳利宝" -> new BigDecimal("1.0000");
            case "汇添富" -> new BigDecimal("1.2500");
            case "天天利" -> new BigDecimal("1.0000");
            case "安心宝" -> new BigDecimal("1.0000");
            default -> new BigDecimal("1.0000");
        };
    }

    public record TransferResult(boolean success, String receiver, BigDecimal amount,
                                  String purpose, String txnId, String message) {}

    public record BillQueryResult(boolean success, String timePeriod, String expenseType,
                                   List<BillItem> items, BigDecimal total, String message) {}

    public record BillItem(String type, BigDecimal amount, LocalDate date) {}

    public record WealthConsultResult(boolean success, String riskLevel, String focusArea, String message) {}

    public record WealthInterpretResult(boolean success, String productName, String message) {}

    public record WealthPurchaseResult(boolean success, String productName, BigDecimal shareCount,
                                        BigDecimal unitPrice, BigDecimal totalAmount,
                                        String orderId, String message) {}
}
