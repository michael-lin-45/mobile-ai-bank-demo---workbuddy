package com.observability.service;

import com.observability.dto.AIInsightVO;
import com.observability.model.MetricsAgg;
import com.observability.model.SpanEntity;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 洞察聚合查询服务
 * - 置信度分布（按意图分组）
 * - 参数提取完整率
 * - 改写准确率
 */
@Slf4j
@Service
public class AIInsightsService {

    private final MetricsAggRepository metricsAggRepository;
    private final SpanRepository spanRepository;

    public AIInsightsService(MetricsAggRepository metricsAggRepository,
                             SpanRepository spanRepository) {
        this.metricsAggRepository = metricsAggRepository;
        this.spanRepository = spanRepository;
    }

    /**
     * 获取完整 AI 洞察数据
     */
    public AIInsightVO getInsights(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        AIInsightVO vo = new AIInsightVO();
        vo.setConfidenceDistribution(getConfidenceDistribution(from, to));
        vo.setExtractionCompleteness(getExtractionCompleteness(from, to));
        vo.setRewriteAccuracy(getRewriteAccuracy(from, to));
        return vo;
    }

    /**
     * 置信度分布 — 按意图分组
     */
    public List<AIInsightVO.IntentConfidence> getConfidenceDistribution(Instant from, Instant to) {
        // Use agent.intent.accuracy (Counter with state tag: correct/fuzzy/error/disambiguated)
        List<MetricsAgg> accuracyMetrics = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(
                        "agent.intent.accuracy", from, to);

        // Group by intent_predicted tag
        Map<String, List<Double>> byIntent = new LinkedHashMap<>();
        for (var m : accuracyMetrics) {
            String intent = extractTag(m.getTags(), "intent_predicted");
            if (intent == null) intent = extractTag(m.getTags(), "intent");
            if (intent == null) intent = extractTag(m.getTags(), "domain");
            if (intent == null) intent = "UNKNOWN";
            byIntent.computeIfAbsent(intent, k -> new ArrayList<>()).add(m.getValue());
        }

        List<AIInsightVO.IntentConfidence> result = new ArrayList<>();
        for (var entry : byIntent.entrySet()) {
            String intent = entry.getKey();
            List<Double> values = entry.getValue();
            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            List<AIInsightVO.Bucket> distribution = buildConfidenceBuckets(values);

            result.add(new AIInsightVO.IntentConfidence(
                    intent, avg, values.size(), distribution));
        }

        return result;
    }

    private List<AIInsightVO.Bucket> buildConfidenceBuckets(List<Double> values) {
        int[] bucketCounts = new int[5]; // 0-0.2, 0.2-0.4, ..., 0.8-1.0
        for (double v : values) {
            int idx = (int) (v / 0.2);
            if (idx >= 5) idx = 4;
            if (idx < 0) idx = 0;
            bucketCounts[idx]++;
        }
        String[] ranges = {"0.0-0.2", "0.2-0.4", "0.4-0.6", "0.6-0.8", "0.8-1.0"};
        List<AIInsightVO.Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            buckets.add(new AIInsightVO.Bucket(ranges[i], bucketCounts[i]));
        }
        return buckets;
    }

    /**
     * 参数提取完整率
     */
    public AIInsightVO.ExtractionCompleteness getExtractionCompleteness(Instant from, Instant to) {
        List<MetricsAgg> completenessMetrics = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(
                        "agent.slot.askback.total", from, to);

        AIInsightVO.ExtractionCompleteness result = new AIInsightVO.ExtractionCompleteness();
        if (completenessMetrics.isEmpty()) {
            result.setOverallRate(0.0);
            result.setByField(Map.of());
            result.setTotalExtractions(0);
            return result;
        }

        double overallRate = completenessMetrics.stream()
                .mapToDouble(MetricsAgg::getValue)
                .average().orElse(0.0);
        result.setOverallRate(overallRate);
        result.setTotalExtractions(completenessMetrics.size());

        // 按字段分组
        Map<String, Double> byField = new LinkedHashMap<>();
        Map<String, List<Double>> fieldGroups = completenessMetrics.stream()
                .collect(Collectors.groupingBy(
                        m -> {
                            String field = extractTag(m.getTags(), "field");
                            return field != null ? field : "unknown";
                        },
                        Collectors.mapping(MetricsAgg::getValue, Collectors.toList())
                ));
        for (var entry : fieldGroups.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0.0);
            byField.put(entry.getKey(), avg);
        }
        result.setByField(byField);

        return result;
    }

    /**
     * 改写准确率
     */
    public AIInsightVO.RewriteAccuracy getRewriteAccuracy(Instant from, Instant to) {
        List<MetricsAgg> rewriteMetrics = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(
                        "agent.rewrite.accuracy", from, to);

        AIInsightVO.RewriteAccuracy result = new AIInsightVO.RewriteAccuracy();
        if (rewriteMetrics.isEmpty()) {
            result.setAccuracyRate(0.0);
            result.setTotalRewrites(0);
            result.setFailureReasons(Map.of());
            return result;
        }

        double avgRate = rewriteMetrics.stream()
                .mapToDouble(MetricsAgg::getValue)
                .average().orElse(0.0);
        result.setAccuracyRate(avgRate);
        result.setTotalRewrites(rewriteMetrics.size());

        // 失败根因分类
        Map<String, Long> failureReasons = new LinkedHashMap<>();
        for (var m : rewriteMetrics) {
            String reason = extractTag(m.getTags(), "rule_check");
            if (reason != null && m.getValue() < 0.8) { // 低于80%视为失败
                failureReasons.merge(reason, 1L, Long::sum);
            }
        }
        result.setFailureReasons(failureReasons);

        return result;
    }

    // ==================== Helpers ====================

    private String extractTag(String tagsJson, String key) {
        if (tagsJson == null || tagsJson.isBlank()) return null;
        try {
            // 简单 JSON 解析避免引入 ObjectMapper 依赖
            String searchKey = "\"" + key + "\":\"";
            int idx = tagsJson.indexOf(searchKey);
            if (idx < 0) return null;
            idx += searchKey.length();
            int end = tagsJson.indexOf("\"", idx);
            if (end < 0) return tagsJson.substring(idx);
            return tagsJson.substring(idx, end);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取混淆矩阵
     * 数据源：H2 的 agent.intent.accuracy（Micrometer Counter，tag:
     * intent_predicted / intent_actual / state）。
     * 每个 (predicted, actual) 组合的计数 = 该组合各 state 最新累计值之和。
     * 修复 GAP-I2/P0-6：原实现从 Span attributes 读 intent_predicted/intent_actual，
     * 但 Core span 从未携带这两个属性 → 矩阵永远空。改用已验证的 Counter 派生。
     * 标签集在标准 6 类基础上，并入数据中实际出现的意图，避免丢数据。
     */
    public Map<String, Object> getConfusionMatrix(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        // (predicted, actual) -> state -> 窗口内最新累计值
        Map<String, Map<String, Map<String, Double>>> agg = new LinkedHashMap<>();
        List<MetricsAgg> list = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc("agent.intent.accuracy", from, to);
        for (MetricsAgg m : list) {
            String predicted = extractTag(m.getTags(), "intent_predicted");
            String actual = extractTag(m.getTags(), "intent_actual");
            String state = extractTag(m.getTags(), "state");
            if (predicted == null || actual == null || state == null) continue;
            agg.computeIfAbsent(predicted, k -> new LinkedHashMap<>())
                    .computeIfAbsent(actual, k -> new LinkedHashMap<>())
                    .put(state, m.getValue());
        }

        if (agg.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("labels", new ArrayList<>());
            result.put("matrix", new int[0][0]);
            result.put("totalSamples", 0);
            return result;
        }

        // 标签集：标准 6 类 + 数据中实际出现的意图
        List<String> stdLabels = List.of(
                "TRANSFER", "BILL_QUERY", "WEALTH_CONSULT",
                "WEALTH_INTERPRET", "CHAT", "UNSUPPORTED");
        LinkedHashSet<String> labelSet = new LinkedHashSet<>(stdLabels);
        for (var e : agg.entrySet()) {
            labelSet.add(e.getKey());
            labelSet.addAll(e.getValue().keySet());
        }
        List<String> labels = new ArrayList<>(labelSet);

        int n = labels.size();
        int[][] matrix = new int[n][n];
        int totalSamples = 0;

        for (var predEntry : agg.entrySet()) {
            int rowIdx = labels.indexOf(predEntry.getKey());
            if (rowIdx < 0) continue;
            for (var actEntry : predEntry.getValue().entrySet()) {
                int colIdx = labels.indexOf(actEntry.getKey());
                if (colIdx < 0) continue;
                double cell = actEntry.getValue().values().stream()
                        .mapToDouble(Double::doubleValue).sum();
                int cellInt = (int) Math.round(cell);
                matrix[rowIdx][colIdx] += cellInt;
                totalSamples += cellInt;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labels", labels);
        result.put("matrix", matrix);
        result.put("totalSamples", totalSamples);
        return result;
    }

    /**
     * 计算意图识别总体准确率（百分比 0-100）。
     * 数据源：H2 的 agent.intent.accuracy（Micrometer Counter 累计值，
     * tag: intent_predicted / intent_actual / state）。
     * 对每个 (intent_predicted, state) 组合取窗口内最新累计值，按意图聚合：
     *   accuracy = Σ(state=correct 最新值) / Σ(所有 state 最新值) × 100
     * 修复原 avg(累计值) 导致的 >100% 失真（即 P0-3 的 572% 问题）。
     * 供 Dashboard 实时卡片（/api/v1/metrics/realtime 的 intentAccuracy 字段）与
     * AccuracyTab 总体统计复用。
     */
    public Double computeIntentAccuracy(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(6 * 3600);
        if (to == null) to = Instant.now();
        List<MetricsAgg> list = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc("agent.intent.accuracy", from, to);
        if (list.isEmpty()) return null;

        // intent_predicted -> state -> 窗口内最新累计值
        // 注：list 已按 timestamp asc 排序，同一组合后出现的记录 timestamp 更新，覆盖即保留最新值
        Map<String, Map<String, Double>> intentStateLatest = new LinkedHashMap<>();
        for (MetricsAgg m : list) {
            String intent = extractTag(m.getTags(), "intent_predicted");
            if (intent == null) intent = extractTag(m.getTags(), "intent");
            if (intent == null) intent = "UNKNOWN";
            String state = extractTag(m.getTags(), "state");
            if (state == null) continue;
            intentStateLatest
                    .computeIfAbsent(intent, k -> new LinkedHashMap<>())
                    .put(state, m.getValue());
        }

        double totalCorrect = 0.0, totalAll = 0.0;
        for (Map<String, Double> stateMap : intentStateLatest.values()) {
            totalCorrect += stateMap.getOrDefault("correct", 0.0);
            totalAll += stateMap.values().stream().mapToDouble(Double::doubleValue).sum();
        }
        if (totalAll <= 0) return null;
        return Math.round(totalCorrect / totalAll * 10000.0) / 100.0;
    }

    /**
     * 计算改写准确率（百分比 0-100）。
     * 数据源：H2 的 agent.rewrite.pass（Micrometer Counter，每次改写 pass 时 +1）与
     * agent.rewrite.total（改写总次数 Counter）。
     * 准确率 = pass / total × 100。
     * 修复 GAP-C2 真值：原读 agent.rewrite.accuracy — 该指标是 DistributionSummary，
     * Micrometer OTLP 导出成 histogram 时不携带每次记录的 0/1 比率（sum=0），
     * 后端存的永远是 0 → 显示 0%。Core 侧已新增 agent.rewrite.pass 真实 Counter，
     * 此处改用 pass/total 得到真实改写准确率。
     */
    public Double computeRewriteAccuracy(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(6 * 3600);
        if (to == null) to = Instant.now();
        // 改写准确率真实口径：从 agent.rewrite.accuracy 的 rule_check tag 计数
        // (pass 记录数 / 总记录数 × 100)。
        // 原方案读 agent.rewrite.pass / agent.rewrite.total 两个 Counter，但：
        //  ① 预注册的 rewriteTotal Counter 经 Micrometer OTLP 导出后累计值恒为 0.0
        //     （与动态创建的 agent.l1.call / agent.reroute.count 导出正常形成反差，
        //      属 Micrometer OTLP 对预注册 Counter 的导出异常）；
        //  ② rewritePass 仅在 ruleCheck="pass" 时 increment，无 pass 记录时
        //     agent.rewrite.pass 这个 meter 根本不存在 → 原公式恒返回 null。
        // 改为直接对稳定进 H2、tag 真实的 agent.rewrite.accuracy 按 rule_check 计数，
        // 与 buildRewriteSection 的根因统计同源且鲁棒（无改写数据时返回 null，
        // 有数据时必返回 0~100 的真实百分比，不再为 null）。
        List<MetricsAgg> list = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc("agent.rewrite.accuracy", from, to);
        if (list.isEmpty()) return null;
        long total = list.size();
        long pass = list.stream()
                .filter(m -> "pass".equals(extractTag(m.getTags(), "rule_check")))
                .count();
        return Math.round(pass * 10000.0 / total) / 100.0;
    }

    /**
     * 计算 REROUTE 次数（最新累计值）。
     * 数据源：H2 的 agent.reroute.count（Micrometer Counter，tag: domain）。
     * 解锁 GAP-C3：原 rerouteRate 读 Redis reroute_rate（从无写入方 → null）。
     * Counter 单调累计，返回窗口内最新累计值（无数据返回 0）。
     */
    public Double computeRerouteCount(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(6 * 3600);
        if (to == null) to = Instant.now();
        double total = latestCounterTotal("agent.reroute.count", from, to);
        return (double) Math.round(total);
    }

    /**
     * 计算业务成功率（完成率 / 转化率，百分比 0-100）。
     * 数据源：H2 的 agent.business.outcome（Micrometer Counter，
     *   tag: outcome=success / outcome=fail，来自 GraphExecutionEngine 子图完成判定）。
     * 成功率 = success / (success + fail) × 100。
     * 解锁 GAP-C4（businessCompletionRate）/ D1（conversionRate）：
     * 原两者读 Redis business_completion / conversion（全库无写入方 → null）。
     * 注意：Core 另发 agent.business.outcome{intent,result}（来自 AbstractDomainService，
     *   不带 outcome 键），不参与本聚合，避免污染 success/fail 计数。
     * Counter 单调累计，按 tag 组合取窗口内最新累计值求和。无数据返回 null。
     */
    public Double computeBusinessSuccessRate(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(6 * 3600);
        if (to == null) to = Instant.now();
        List<MetricsAgg> list = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc("agent.business.outcome", from, to);
        if (list.isEmpty()) return null;

        // 业务成功/失败判定：兼容 Core 两套埋点
        //  - GraphExecutionEngine: agent.business.outcome{outcome=success|fail}
        //    （注意：streaming 成功路径当前未发射 outcome=success，已知限制）
        //  - AbstractDomainService: agent.business.outcome{intent=*,result=success}
        //    （每次 L2 子图 COMPLETE 都发射，是真实高频的成功信号；无 result=fail 分支）
        // 为避免"假 0%"，success 以 result=success 为主源，outcome=success 为辅源。
        Map<String, Double> successLatest = new LinkedHashMap<>();
        Map<String, Double> failLatest = new LinkedHashMap<>();
        for (MetricsAgg m : list) {
            String outcome = extractTag(m.getTags(), "outcome");
            String result = extractTag(m.getTags(), "result");
            boolean isSuccess = "success".equals(outcome) || "success".equals(result);
            boolean isFail = "fail".equals(outcome) || "fail".equals(result);
            if (isSuccess) {
                successLatest.put(m.getTags(), m.getValue());
            } else if (isFail) {
                failLatest.put(m.getTags(), m.getValue());
            }
        }
        double success = successLatest.values().stream().mapToDouble(Double::doubleValue).sum();
        double fail = failLatest.values().stream().mapToDouble(Double::doubleValue).sum();
        double total = success + fail;
        // 无任何业务结果数据 → 返回 null（前端显示"暂无"），严禁把"无数据"误显示成 0%
        if (total <= 0) return null;
        return Math.round(success / total * 10000.0) / 100.0;
    }

    /**
     * 取窗口内某 Counter 的最新累计值（所有 tag 组合求和）。
     * Counter 单调累计，list 按 timestamp asc 排序，同 tag 组合后出现的记录值更新 → 覆盖保留最新。
     */
    private double latestCounterTotal(String metricName, Instant from, Instant to) {
        List<MetricsAgg> list = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
        if (list.isEmpty()) return 0.0;
        Map<String, Double> latestByTags = new LinkedHashMap<>();
        for (MetricsAgg m : list) {
            latestByTags.put(m.getTags(), m.getValue());
        }
        return latestByTags.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    // ==================== 统一准确率报告（修复 GAP-I3/I4 契约错配） ====================

    /**
     * 统一准确率分析报告，供前端 AccuracyTab 单端点消费。
     * 聚合：意图准确率趋势 + 改写准确率表 + 改写失败根因 TOP3 + 混淆矩阵。
     *
     * 修复 P0-2：原 AccuracyTab 读取 accuracyData.trend/.rewrite/.rootCauses 等字段，
     * 但后端 /ai/intent-accuracy-trend 仅返回 {series}、/ai/insights 返回结构不一致，
     * 导致趋势图/改写表/根因卡片整页渲染为空。这里统一返回前端所需结构。
     */
    public Map<String, Object> getAccuracyReport(Instant from, Instant to) {
        if (from == null) from = Instant.now().minusSeconds(86400);
        if (to == null) to = Instant.now();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("trend", buildTrend(from, to));

        Map<String, Object> rewriteSection = buildRewriteSection(from, to);
        report.put("rewrite", rewriteSection.get("rows"));
        report.put("rewriteSummary", rewriteSection.get("summary"));
        report.put("rootCauses", rewriteSection.get("rootCauses"));
        report.put("rootCauseSummary", rewriteSection.get("rootCauseSummary"));

        report.put("confusion", getConfusionMatrix(from, to));
        report.put("overallStats", buildOverallStats(from, to));
        return report;
    }

    /**
     * 意图准确率趋势：按 intent_predicted 分组，每天一个点，值为 0-100 真实准确率。
     * 修复 P0-3：原算法对 Counter 累计值直接 avg*100，导致出现 572% 等失真值。
     * 正确口径：对每个 (intent_predicted, state) 取窗口内最新累计值，按意图聚合
     *   dailyAccuracy = Σ(state=correct) / Σ(所有 state) × 100
     */
    private Map<String, Object> buildTrend(Instant from, Instant to) {
        List<MetricsAgg> accuracyMetrics = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc("agent.intent.accuracy", from, to);

        // 空数据兜底
        if (accuracyMetrics.isEmpty()) {
            Map<String, Object> trend = new LinkedHashMap<>();
            trend.put("categories", new ArrayList<>());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", "总体准确率");
            s.put("data", new ArrayList<>());
            trend.put("series", List.of(s));
            return trend;
        }

        // intent -> day -> state -> 该天最新累计值
        // 注：accuracyMetrics 已按 timestamp asc 排序，同组合后出现的记录 timestamp 更新，覆盖即保留最新值
        Map<String, TreeMap<String, Map<String, Double>>> intentDayState = new LinkedHashMap<>();
        for (MetricsAgg m : accuracyMetrics) {
            String intent = extractTag(m.getTags(), "intent_predicted");
            if (intent == null) intent = extractTag(m.getTags(), "intent");
            if (intent == null) intent = "UNKNOWN";
            String state = extractTag(m.getTags(), "state");
            if (state == null) continue;
            String day = m.getTimestamp().toString().substring(0, 10);
            intentDayState
                    .computeIfAbsent(intent, k -> new TreeMap<>())
                    .computeIfAbsent(day, k -> new LinkedHashMap<>())
                    .put(state, m.getValue());
        }

        TreeSet<String> days = new TreeSet<>();
        intentDayState.values().forEach(d -> days.addAll(d.keySet()));
        List<String> categories = new ArrayList<>(days);

        List<Map<String, Object>> series = new ArrayList<>();
        for (var intentEntry : intentDayState.entrySet()) {
            Map<String, Map<String, Double>> dayState = intentEntry.getValue();
            List<Double> data = new ArrayList<>();
            for (String day : categories) {
                Map<String, Double> stateMap = dayState.get(day);
                if (stateMap == null || stateMap.isEmpty()) {
                    data.add(null);
                } else {
                    double correct = stateMap.getOrDefault("correct", 0.0);
                    double total = stateMap.values().stream().mapToDouble(Double::doubleValue).sum();
                    data.add(total > 0 ? Math.round(correct / total * 10000.0) / 100.0 : null);
                }
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", intentEntry.getKey() + "准确率");
            s.put("data", data);
            series.add(s);
        }

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("categories", categories);
        trend.put("series", series);
        return trend;
    }

    /** 改写准确率表 + 根因 TOP3：真实准确率从 pass/total Counter 派生，根因从 rule_check 计数派生 */
    private Map<String, Object> buildRewriteSection(Instant from, Instant to) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 改写准确率与根因均从 agent.rewrite.accuracy 的 rule_check tag 计数派生
        // （pass 数 / 总数 × 100），与 computeRewriteAccuracy 同构且鲁棒。
        // 不再依赖 agent.rewrite.total / agent.rewrite.pass（OTLP 导出累计值异常）。
        List<MetricsAgg> rewriteMetrics = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc("agent.rewrite.accuracy", from, to);
        if (rewriteMetrics.isEmpty()) {
            result.put("rows", new ArrayList<>());
            result.put("summary", "暂无改写数据");
            result.put("rootCauses", new ArrayList<>());
            result.put("rootCauseSummary", "暂无根因数据");
            return result;
        }
        long totalL = rewriteMetrics.size();
        long passL = rewriteMetrics.stream()
                .filter(m -> "pass".equals(extractTag(m.getTags(), "rule_check")))
                .count();
        double rate = Math.round(passL * 10000.0 / totalL) / 100.0;

        // 失败根因：从 rule_check 计数（过滤掉 pass）
        Map<String, Long> failureReasons = new LinkedHashMap<>();
        for (var m : rewriteMetrics) {
            String reason = extractTag(m.getTags(), "rule_check");
            if (reason != null && !"pass".equals(reason)) {
                failureReasons.merge(reason, 1L, Long::sum);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("scene", "总体改写");
        overall.put("total", totalL);
        overall.put("correct", passL);
        overall.put("rate", rate);
        overall.put("trend", 0);
        overall.put("errorExample", failureReasons.isEmpty() ? "—" : "主要失败规则: " + String.join(", ", failureReasons.keySet()));
        rows.add(overall);

        List<Map<String, Object>> rootCauses = new ArrayList<>();
        List<Map.Entry<String, Long>> sorted = failureReasons.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3).toList();
        long maxCount = sorted.isEmpty() ? 1 : sorted.get(0).getValue();
        int rank = 1;
        for (var e : sorted) {
            Map<String, Object> rc = new LinkedHashMap<>();
            rc.put("rank", rank++);
            rc.put("title", e.getKey());
            rc.put("count", e.getValue());
            rc.put("desc", "改写规则校验失败: " + e.getKey());
            rc.put("pct", (int) Math.round(e.getValue() * 100.0 / maxCount) + "%");
            rc.put("color", "#ff4d4f");
            rc.put("bg", "#fff2f0");
            rootCauses.add(rc);
        }

        result.put("rows", rows);
        result.put("summary", String.format("共 %d 次改写 · 改写准确率 %.1f%%", totalL, rate));
        result.put("rootCauses", rootCauses);
        result.put("rootCauseSummary", rootCauses.isEmpty() ? "无失败根因" : String.format("TOP%d 失败规则", rootCauses.size()));
        return result;
    }

    private String buildOverallStats(Instant from, Instant to) {
        Double acc = computeIntentAccuracy(from, to);
        if (acc == null) return "暂无准确率样本";
        return String.format("总体意图准确率 %.1f%%", acc);
    }

    private Map<String, String> parseSpanAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return null;
        try {
            // 简单解析，避免 ObjectMapper 依赖
            Map<String, String> result = new LinkedHashMap<>();
            // 格式: {"key1":"val1","key2":"val2"}
            String content = attributesJson.trim();
            if (content.startsWith("{") && content.endsWith("}")) {
                content = content.substring(1, content.length() - 1);
                String[] pairs = content.split(",");
                for (String pair : pairs) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replaceAll("\"", "");
                        String val = kv[1].trim().replaceAll("\"", "");
                        result.put(key, val);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public java.util.Map<String, Object> debugMetricNames() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            // Get distinct metric names
            var names = metricsAggRepository.findDistinctMetricNames();
            result.put("distinctMetricNames", names);
            result.put("count", names.size());
            
            // For each name, count records and get latest value
            Instant from = Instant.now().minusSeconds(7 * 86400);
            Instant to = Instant.now();
            java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
            java.util.Map<String, Double> latestValues = new java.util.LinkedHashMap<>();
            for (String name : names) {
                var list = metricsAggRepository.findByMetricNameAndTimestampBetweenOrderByTimestampAsc(name, from, to);
                counts.put(name, (long) list.size());
                if (!list.isEmpty()) {
                    latestValues.put(name, list.get(list.size() - 1).getValue());
                }
            }
            result.put("recordCounts", counts);
            result.put("latestValues", latestValues);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }


    public Map<String, Object> debugMetricSamples(String metricName, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        Instant from = Instant.now().minusSeconds(7 * 86400);
        Instant to = Instant.now();
        List<MetricsAgg> records = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
        result.put("totalRecords", records.size());
        List<Map<String, Object>> samples = new ArrayList<>();
        int start = Math.max(0, records.size() - limit);
        for (int i = start; i < records.size(); i++) {
            MetricsAgg m = records.get(i);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("metricName", m.getMetricName());
            sample.put("tags", m.getTags());
            sample.put("value", m.getValue());
            sample.put("aggWindow", m.getAggWindow());
            sample.put("timestamp", m.getTimestamp() != null ? m.getTimestamp().toString() : null);
            samples.add(sample);
        }
        result.put("samples", samples);
        return result;
    }


    public Map<String, Object> debugTagsWithField(String metricName, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        Instant from = Instant.now().minusSeconds(7 * 86400);
        Instant to = Instant.now();
        List<MetricsAgg> records = metricsAggRepository
                .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
        result.put("totalRecords", records.size());
        int withField = 0;
        Instant firstWithField = null;
        Instant lastWithField = null;
        for (var m : records) {
            String val = extractTag(m.getTags(), field);
            if (val != null) {
                withField++;
                if (firstWithField == null) firstWithField = m.getTimestamp();
                lastWithField = m.getTimestamp();
            }
        }
        result.put("withField_" + field, withField);
        result.put("firstWithField", firstWithField != null ? firstWithField.toString() : "null");
        result.put("lastWithField", lastWithField != null ? lastWithField.toString() : "null");
        // Also return first 3 records that have the field
        List<Map<String, Object>> samples = new ArrayList<>();
        int count = 0;
        for (var m : records) {
            String val = extractTag(m.getTags(), field);
            if (val != null && count < 3) {
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("tags", m.getTags());
                sample.put("value", m.getValue());
                sample.put("timestamp", m.getTimestamp() != null ? m.getTimestamp().toString() : null);
                samples.add(sample);
                count++;
            }
        }
        result.put("samples", samples);
        return result;
    }

}
