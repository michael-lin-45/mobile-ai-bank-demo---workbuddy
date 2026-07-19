package com.observability.service;

import com.observability.model.AlertEvent;
import com.observability.model.AlertRule;
import com.observability.repository.AlertEventRepository;
import com.observability.repository.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 告警引擎（T-B，必做·新开发）。
 *
 * <p>每 30s 对全部启用规则求值一次（@Scheduled(fixedRate=30_000)）：
 * <ul>
 *   <li>从数据源采集指标当前值，回写规则 {@code currentValue}/{@code lastEvaluatedAt}；</li>
 *   <li>命中阈值 → 触发 FIRING 事件并经 LogNotifier 记录分发（不真实外发）；</li>
 *   <li>抑制：同一规则 5 分钟内已通知过 → 记 SUPPRESSED 事件（suppressionCount+1），不重复通知；</li>
 *   <li>未命中且存在未关闭事件 → 将该事件置 RESOLVED（状态机 FIRING/ACKED → RESOLVED）；</li>
 *   <li>人工确认：通过 POST /api/v1/alerts/events/{id}/ack 将事件置 ACKED。</li>
 * </ul>
 *
 * <p>通知严格遵循主理人决策 3：仅 LogNotifier 落地，绝不发生真实钉钉/飞书 HTTP 请求。
 */
@Slf4j
@Service
public class AlertEngineService {

    private final AlertRuleRepository ruleRepo;
    private final AlertEventRepository eventRepo;
    private final AlertNotifyService notifyService;
    private final StringRedisTemplate redis;
    private final RedisMetricsService metrics;

    /** 抑制窗口（秒），与详细设计 §3.2② 一致 */
    private static final long SUPPRESS_TTL_SECONDS = 300;

    public AlertEngineService(AlertRuleRepository ruleRepo,
                              AlertEventRepository eventRepo,
                              AlertNotifyService notifyService,
                              StringRedisTemplate redis,
                              RedisMetricsService metrics) {
        this.ruleRepo = ruleRepo;
        this.eventRepo = eventRepo;
        this.notifyService = notifyService;
        this.redis = redis;
        this.metrics = metrics;
    }

    @Scheduled(fixedRate = 30_000)
    public void evaluateRules() {
        List<AlertRule> rules = ruleRepo.findByEnabledTrue();
        Instant now = Instant.now();
        for (AlertRule rule : rules) {
            try {
                double value = collectMetricValue(rule);
                rule.setCurrentValue(value);
                rule.setLastEvaluatedAt(now);
                ruleRepo.save(rule);

                if (isBreached(rule, value)) {
                    handleBreach(rule, value, now);
                } else {
                    handleRecovery(rule, now);
                }
            } catch (Exception e) {
                log.warn("[Alert] Failed to evaluate rule {}: {}", rule.getRuleName(), e.getMessage());
            }
        }
    }

    /** 命中阈值：FIRING（首次）/ SUPPRESSED（抑制窗内重复） */
    private void handleBreach(AlertRule rule, double value, Instant now) {
        String suppressKey = "obs:alerts:suppress:" + rule.getId();
        if (Boolean.TRUE.equals(redis.hasKey(suppressKey))) {
            long count = eventRepo.countByRuleIdAndStatus(rule.getId(), "SUPPRESSED");
            AlertEvent ev = AlertEvent.builder()
                    .ruleId(rule.getId())
                    .ruleName(rule.getRuleName())
                    .severity(rule.getSeverity())
                    .status("SUPPRESSED")
                    .triggeredAt(now)
                    .message(buildMessage(rule, value))
                    .notifiedChannels(rule.getNotifyChannels())
                    .notifyResult("SUPPRESSED:within " + SUPPRESS_TTL_SECONDS + "s window")
                    .suppressionCount((int) count + 1)
                    .build();
            eventRepo.save(ev);
            log.info("[Alert] Rule {} suppressed (window active), suppressionCount={}",
                    rule.getRuleName(), ev.getSuppressionCount());
            return;
        }

        AlertEvent ev = AlertEvent.builder()
                .ruleId(rule.getId())
                .ruleName(rule.getRuleName())
                .severity(rule.getSeverity())
                .status("FIRING")
                .triggeredAt(now)
                .message(buildMessage(rule, value))
                .build();
        notifyService.notify(rule, ev);
        eventRepo.save(ev);
        redis.opsForValue().set(suppressKey, "1", SUPPRESS_TTL_SECONDS, TimeUnit.SECONDS);
        log.info("[Alert] Rule {} FIRING (value={})", rule.getRuleName(), value);
    }

    /** 未命中且存在未关闭事件 → 置 RESOLVED */
    private void handleRecovery(AlertRule rule, Instant now) {
        List<AlertEvent> open = eventRepo.findByRuleIdAndStatusInOrderByTriggeredAtDesc(
                rule.getId(), List.of("FIRING", "ACKED"));
        if (!open.isEmpty()) {
            AlertEvent ev = open.get(0);
            ev.setStatus("RESOLVED");
            ev.setResolvedAt(now);
            eventRepo.save(ev);
            log.info("[Alert] Rule {} recovered, event {} RESOLVED", rule.getRuleName(), ev.getId());
        }
    }

    /**
     * 采集规则对应指标的当前值。映射约定（与 QA 测试契约对齐）：
     * metricName 含 request_count → Redis request_count:6h；
     * error_rate → error_count:6h / request_count:6h × 100；
     * latency_p95 → Redis latency:stats:6h.p95；ttft_p95 → Redis ttft:stats:1m.p95。
     */
    private double collectMetricValue(AlertRule rule) {
        String key = rule.getMetricName() != null ? rule.getMetricName().toLowerCase() : "";
        try {
            if (key.contains("request_count") || key.contains("request.count")) {
                return metrics.getRequestCount("6h");
            } else if (key.contains("error_rate") || key.contains("error.count")) {
                long errs = metrics.getErrorCount("6h");
                long reqs = metrics.getRequestCount("6h");
                return reqs > 0 ? (errs * 100.0 / reqs) : 0.0;
            } else if (key.contains("latency") && key.contains("p95")) {
                return metrics.getLatencyStats("6h").getOrDefault("p95", 0.0);
            } else if (key.contains("ttft") && key.contains("p95")) {
                return metrics.getTTFTStats("1m").getOrDefault("p95", 0L).doubleValue();
            } else if (key.contains("unsatisfied")) {
                return 0.0;
            }
        } catch (Exception e) {
            log.debug("[Alert] collectMetricValue failed for {}: {}", key, e.getMessage());
        }
        return 0.0;
    }

    private boolean isBreached(AlertRule rule, double value) {
        Double threshold = rule.getThreshold();
        if (threshold == null) return false;
        String op = rule.getOperator() != null ? rule.getOperator() : ">";
        return switch (op) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            default -> value > threshold;
        };
    }

    private String buildMessage(AlertRule rule, double value) {
        return String.format("[%s] %s %s %.2f (current=%.2f)",
                rule.getSeverity(), rule.getRuleName(), rule.getOperator(), rule.getThreshold(), value);
    }
}
