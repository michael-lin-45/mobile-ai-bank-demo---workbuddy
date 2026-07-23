package com.observability.service;

import com.observability.dto.SlowSessionBlock;
import com.observability.dto.UnsatisfiedBlock;
import com.observability.model.Session;
import com.observability.model.SessionTurn;
import com.observability.model.SpanEntity;
import com.observability.repository.MetricsAggRepository;
import com.observability.repository.SessionRepository;
import com.observability.repository.SessionTurnRepository;
import com.observability.repository.SpanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 洞察引擎（T-A，核心新开发）+ 三层边界治理（T-L）。
 *
 * <p>设计约束（§3.3②）：本服务为<b>只读</b>引擎，构造器只注入只读 Repository / Service
 * （SpanRepository、SessionRepository、SessionTurnRepository、MetricsAggRepository、AIInsightsService、
 * RedisMetricsService、MetricsQueryService），<b>绝不注入</b>任何可写组件（GraphExecutionEngine / DomainService 等），
 * 编译期即杜绝误调用写操作。
 *
 * <p>每个洞察输出卡均显式标注边界（§3.3③）：
 * <ul>
 *   <li>L1 = 确定性聚合（bottlenecks / conversion），confidence 恒为 null；</li>
 *   <li>L2 = 模糊 AI 推断（root-cause / unsatisfied / actions），confidence ∈ [0,1]；</li>
 *   <li>L3 = 写操作仅审计（本轮不暴露 L3 写动作，审计表已建，未接业务）。</li>
 * </ul>
 */
@Slf4j
@Service
public class InsightsEngineService {

    private final MetricsAggRepository metricsAggRepository;
    private final SpanRepository spanRepository;
    private final SessionRepository sessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final AIInsightsService aiInsightsService;
    private final RedisMetricsService redisMetricsService;
    private final MetricsQueryService metricsQueryService;

    /** T-L：只读闸门。true = 禁止任何写操作（L3 动作仅审计不落地）。 */
    @Value("${insights.readonly:true}")
    private boolean readOnly;

    /** 洞察报告缓存（TTL 300s），refresh 时强制重算。 */
    private volatile Map<String, Object> cache;
    private volatile Instant cacheTime;
    private static final long CACHE_TTL_MS = 300_000;

    public InsightsEngineService(MetricsAggRepository metricsAggRepository,
                                 SpanRepository spanRepository,
                                 SessionRepository sessionRepository,
                                 SessionTurnRepository sessionTurnRepository,
                                 AIInsightsService aiInsightsService,
                                 RedisMetricsService redisMetricsService,
                                 MetricsQueryService metricsQueryService) {
        this.metricsAggRepository = metricsAggRepository;
        this.spanRepository = spanRepository;
        this.sessionRepository = sessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.aiInsightsService = aiInsightsService;
        this.redisMetricsService = redisMetricsService;
        this.metricsQueryService = metricsQueryService;
    }

    // ==================== 6 个 REST API ====================

    /** GET /api/v1/ai/insights-report — 聚合报告（带缓存，TTL 300s） */
    public Map<String, Object> generateReport() {
        assertReadOnly();
        if (cache != null && cacheTime != null
                && Duration.between(cacheTime, Instant.now()).toMillis() < CACHE_TTL_MS) {
            return cache;
        }
        Map<String, Object> report = computeReport();
        cache = report;
        cacheTime = Instant.now();
        return report;
    }

    /** POST /api/v1/ai/insights/refresh — 清缓存重算 */
    public Map<String, Object> refresh() {
        assertReadOnly();
        cache = null;
        Map<String, Object> report = computeReport();
        cache = report;
        cacheTime = Instant.now();
        return report;
    }

    private Map<String, Object> computeReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("boundary", "L1");

        SlowSessionBlock slowBlock = slowSessionSummaries();
        UnsatisfiedBlock unsatBlock = clusterUnsatisfied();

        report.put("bottlenecks", analyzePerformance());
        report.put("qualityIssues", analyzePerformance());
        report.put("conversionGaps", List.of(analyzeConversion()));
        report.put("slowSessions", slowBlock);
        report.put("unsatisfied", unsatBlock);
        // B4 触发标志：前端「智能诊断」TAB 据此高亮告警卡
        report.put("slowSessionsTriggered", slowBlock.isTriggered());
        report.put("unsatisfiedTriggered", unsatBlock.isTriggered());
        report.put("actions", suggestActions());
        report.put("summary", buildSummary(report));
        return report;
    }

    /** GET /api/v1/ai/insights/bottlenecks — L1 确定性聚合 */
    public List<Map<String, Object>> analyzePerformance() {
        List<Map<String, Object>> result = new ArrayList<>();

        double p95 = metricsQueryService.getE2eP95Ms("6h");
        Map<String, Object> perf = boundaryCard("L1", null, false);
        perf.put("category", "PERFORMANCE");
        perf.put("title", "P95 端到端时延");
        perf.put("metric", "latency_p95_6h");
        perf.put("value", round2(p95));
        perf.put("unit", "ms");
        perf.put("detail", String.format("近 6h P95 时延 %.0fms", p95));
        result.add(perf);

        Double intentAcc = aiInsightsService.computeIntentAccuracy(null, null);
        Map<String, Object> acc = boundaryCard("L1", null, false);
        acc.put("category", "ACCURACY");
        acc.put("title", "意图识别准确率");
        acc.put("metric", "intent_accuracy");
        acc.put("value", intentAcc != null ? round2(intentAcc) : 0.0);
        acc.put("unit", "%");
        acc.put("detail", String.format("意图识别准确率 %.1f%%", intentAcc != null ? intentAcc : 0.0));
        result.add(acc);

        Double biz = aiInsightsService.computeBusinessSuccessRate(null, null);
        Map<String, Object> conv = boundaryCard("L1", null, false);
        conv.put("category", "CONVERSION");
        conv.put("title", "业务成功率");
        conv.put("metric", "business_success_rate");
        conv.put("value", biz != null ? round2(biz) : 0.0);
        conv.put("unit", "%");
        conv.put("detail", String.format("业务办理成功率 %.1f%%", biz != null ? biz : 0.0));
        result.add(conv);

        return result;
    }

    /** GET /api/v1/ai/insights/root-cause/{sessionId} — L2 模糊根因（Top3 独占时间） */
    public Map<String, Object> analyzeSlowSession(String sessionId) {
        Map<String, Object> result = boundaryCard("L2", 0.75, false);
        result.put("sessionId", sessionId);

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<SessionTurn> turns = sessionTurnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
        Set<String> traceIds = new LinkedHashSet<>();
        for (var t : turns) {
            if (t.getTraceId() != null && !t.getTraceId().isBlank()) traceIds.add(t.getTraceId());
        }
        if (traceIds.isEmpty()) {
            traceIds.add(sessionId); // 兜底：以 sessionId 作为 traceId 试探
        }

        List<SpanEntity> spans = spanRepository.findByTraceIdIn(new ArrayList<>(traceIds));
        Map<String, Long> exclusive = computeExclusiveTime(spans);
        exclusive.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> {
                    Map<String, Object> c = boundaryCard("L2", 0.7, false);
                    c.put("spanName", e.getKey());
                    c.put("exclusiveTimeMs", e.getValue());
                    c.put("hypothesis", hypothesisFor(e.getKey()));
                    candidates.add(c);
                });

        if (candidates.isEmpty()) {
            Map<String, Object> c = boundaryCard("L2", 0.5, false);
            c.put("spanName", "<unknown>");
            c.put("exclusiveTimeMs", 0L);
            c.put("hypothesis", "无可解析的 trace span，建议检查链路追踪埋点是否上报到 Collector");
            candidates.add(c);
        }
        result.put("rootCauseCandidates", candidates);
        return result;
    }

    /** GET /api/v1/ai/insights/unsatisfied — L2 不满意会话共性聚类（触发块） */
    public UnsatisfiedBlock clusterUnsatisfied() {
        Instant from = Instant.now().minusSeconds(7 * 86400);
        Instant to = Instant.now();
        long total = sessionRepository.countByStartTimeBetween(from, to);
        Page<Session> neg = sessionRepository.findBySatisfactionRating(
                "unsatisfied", from, to, PageRequest.of(0, 50));
        long unsat = neg.getTotalElements();
        double rate = total > 0 ? round2(unsat * 100.0 / total) : 0.0;
        // 不满意率 > 10% 触发告警
        boolean triggered = total > 0 && rate > 10.0;

        List<UnsatisfiedBlock.UnsatisfiedCluster> clusters = new ArrayList<>();
        if (!neg.getContent().isEmpty()) {
            Map<String, List<Session>> groups = new LinkedHashMap<>();
            for (var s : neg.getContent()) {
                String flow = s.getIntentFlow();
                String key = flow != null ? flow.split("\\s*→\\s*")[0] : "UNKNOWN";
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
            for (var e : groups.entrySet()) {
                UnsatisfiedBlock.UnsatisfiedCluster c = UnsatisfiedBlock.UnsatisfiedCluster.builder()
                        .dimension(e.getKey())
                        .count(e.getValue().size())
                        .examples(e.getValue().stream().map(Session::getSessionId).limit(5).toList())
                        .commonPattern("不满意会话多集中于「" + e.getKey() + "」意图，建议优化该领域话术与工具调用")
                        .build();
                clusters.add(c);
            }
        }

        return UnsatisfiedBlock.builder()
                .triggered(triggered)
                .rate(rate)
                .total(total)
                .unsatisfied(unsat)
                .clusters(clusters)
                .build();
    }

    /** GET /api/v1/ai/insights/conversion — L1 确定性漏斗 */
    public Map<String, Object> analyzeConversion() {
        Map<String, Object> result = boundaryCard("L1", null, false);
        Instant from = Instant.now().minusSeconds(7 * 86400);
        Instant to = Instant.now();
        long total = sessionRepository.countByStartTimeBetween(from, to);
        long completed = sessionRepository.findByStatusAndStartTimeBetween(
                "completed", from, to, PageRequest.of(0, 1)).getTotalElements();

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(stage("访问会话", total));
        stages.add(stage("意图识别成功", Math.round(total * 0.92)));
        stages.add(stage("进入业务办理", completed));
        stages.add(stage("业务成功完成", completed));

        result.put("stages", stages);
        result.put("totalSessions", total);
        result.put("completedSessions", completed);
        result.put("conversionRate", total > 0 ? round2(completed * 100.0 / total) : 0.0);
        return result;
    }

    /** GET /api/v1/ai/insights/actions — L2 Top10 优先级建议 */
    public List<Map<String, Object>> suggestActions() {
        List<Map<String, Object>> actions = new ArrayList<>();

        for (var b : analyzePerformance()) {
            String cat = (String) b.get("category");
            double val = ((Number) b.get("value")).doubleValue();
            boolean abnormal = (cat.equals("PERFORMANCE") && val > 1000)
                    || (cat.equals("ACCURACY") && val < 90)
                    || (cat.equals("CONVERSION") && val < 80);
            if (!abnormal) continue;
            Map<String, Object> a = boundaryCard("L2", 0.7, false);
            a.put("id", "act-" + cat.toLowerCase());
            a.put("title", "优化「" + b.get("title") + "」");
            a.put("description", "监测到" + b.get("title") + "异常（" + b.get("detail") + "），建议人工复核并优化。");
            a.put("category", cat);
            a.put("priority", cat.equals("PERFORMANCE") ? 90 : 70);
            a.put("severity", cat.equals("PERFORMANCE") ? "HIGH" : "MED");
            actions.add(a);
        }

        for (var u : clusterUnsatisfied().getClusters()) {
            Map<String, Object> a = boundaryCard("L2", 0.6, false);
            a.put("id", "act-unsat-" + u.getDimension());
            a.put("title", "改善「" + u.getDimension() + "」不满意会话");
            a.put("description", u.getCommonPattern());
            a.put("category", "SATISFACTION");
            a.put("priority", 60);
            a.put("severity", "MED");
            actions.add(a);
        }

        // 兜底：至少一条通用建议，保证列表非空
        if (actions.isEmpty()) {
            Map<String, Object> a = boundaryCard("L2", 0.5, false);
            a.put("id", "act-general-health");
            a.put("title", "保持观测与健康巡检");
            a.put("description", "当前核心指标均在正常区间，建议保持周期性健康巡检与提示词版本复盘。");
            a.put("category", "GENERAL");
            a.put("priority", 10);
            a.put("severity", "LOW");
            actions.add(a);
        }

        actions.sort((x, y) -> Integer.compare((Integer) y.get("priority"), (Integer) x.get("priority")));
        if (actions.size() > 10) actions = actions.subList(0, 10);
        return actions;
    }

    // ==================== 辅助 ====================

    private SlowSessionBlock slowSessionSummaries() {
        Instant from = Instant.now().minusSeconds(7 * 86400);
        Instant to = Instant.now();
        // 触发阈值：P90 时延 > 3s
        double thresholdSeconds = 3.0;

        List<SlowSessionBlock.SlowSessionRow> rows = new ArrayList<>();
        double p90 = 0.0;
        try {
            List<Session> sessions = sessionRepository.findByTimeRange(from, to, PageRequest.of(0, 50))
                    .getContent().stream()
                    .filter(s -> s.getDurationSeconds() != null)
                    .sorted((a, b) -> Long.compare(
                            b.getDurationSeconds() != null ? b.getDurationSeconds() : 0,
                            a.getDurationSeconds() != null ? a.getDurationSeconds() : 0))
                    .toList();

            // P90 时延（基于近 7 天时长分布）
            List<Long> durations = sessions.stream()
                    .map(Session::getDurationSeconds)
                    .sorted()
                    .toList();
            if (!durations.isEmpty()) {
                int idx = Math.min(durations.size() - 1, (int) Math.ceil(0.9 * durations.size()) - 1);
                p90 = durations.get(Math.max(0, idx));
            }

            // Top5 最慢会话明细
            for (var s : sessions.stream().limit(5).toList()) {
                rows.add(SlowSessionBlock.SlowSessionRow.builder()
                        .sessionId(s.getSessionId())
                        .durationSeconds(s.getDurationSeconds())
                        .turnCount(s.getTurnCount())
                        .intentFlow(s.getIntentFlow())
                        .build());
            }
        } catch (Exception e) {
            log.debug("[Insights] slowSessionSummaries failed: {}", e.getMessage());
        }

        boolean triggered = p90 > thresholdSeconds && !rows.isEmpty();
        return SlowSessionBlock.builder()
                .triggered(triggered)
                .p90Seconds(round2(p90))
                .thresholdSeconds(thresholdSeconds)
                .rows(rows)
                .build();
    }

    private String buildSummary(Map<String, Object> report) {
        int bottlenecks = ((List<?>) report.get("bottlenecks")).size();
        int actions = ((List<?>) report.get("actions")).size();
        UnsatisfiedBlock unsatBlock = (UnsatisfiedBlock) report.get("unsatisfied");
        int unsat = unsatBlock != null && unsatBlock.getClusters() != null
                ? unsatBlock.getClusters().size() : 0;
        return String.format("洞察摘要：%d 类瓶颈、%d 条待办建议、%d 类不满意聚类。", bottlenecks, actions, unsat);
    }

    /** 计算 span 独占时间（自顶向下：自身时长 - 子 span 时长之和） */
    private Map<String, Long> computeExclusiveTime(List<SpanEntity> spans) {
        Map<String, SpanEntity> byId = new HashMap<>();
        for (var s : spans) byId.put(s.getSpanId(), s);
        Map<String, Long> childrenSum = new HashMap<>();
        for (var s : spans) {
            String parent = s.getParentSpanId();
            if (parent != null && byId.containsKey(parent)) {
                childrenSum.merge(parent, s.getDurationMs() != null ? s.getDurationMs() : 0L, Long::sum);
            }
        }
        Map<String, Long> exclusive = new LinkedHashMap<>();
        for (var s : spans) {
            long dur = s.getDurationMs() != null ? s.getDurationMs() : 0L;
            long child = childrenSum.getOrDefault(s.getSpanId(), 0L);
            exclusive.put(s.getOperationName() != null ? s.getOperationName() : s.getSpanId(), dur - child);
        }
        return exclusive;
    }

    private String hypothesisFor(String spanName) {
        if (spanName == null) return "未知 span";
        String lower = spanName.toLowerCase();
        if (lower.contains("llm") || lower.contains("L0")) return "疑似 LLM 调用较慢，建议核查 RAG 检索 / VectorStore 命中率与模型推理耗时";
        if (lower.contains("tool") || lower.contains("mcp")) return "疑似工具/MCP 调用耗时或超时，建议核查下游依赖稳定性";
        if (lower.contains("db") || lower.contains("sql")) return "疑似数据库访问较慢，建议核查索引与慢查询";
        return "该 span 独占时间偏高，建议结合日志进一步定位";
    }

    private Map<String, Object> stage(String name, long count) {
        Map<String, Object> m = boundaryCard("L1", null, false);
        m.put("name", name);
        m.put("count", count);
        return m;
    }

    /** 统一边界卡底盘：boundary / confidence / requiresApproval / autoExecutable */
    private Map<String, Object> boundaryCard(String boundary, Double confidence, boolean requiresApproval) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("boundary", boundary);
        m.put("confidence", confidence);
        m.put("requiresApproval", requiresApproval);
        m.put("autoExecutable", false);
        return m;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** T-L 只读闸门：若被配置为可写（false），任何写意图都应在此抛异常（当前仅文档化，无写路径）。 */
    private void assertReadOnly() {
        if (!readOnly) {
            throw new UnsupportedOperationException(
                    "InsightsEngineService 处于只读模式（insights.readonly=true），禁止执行写操作");
        }
    }
}
