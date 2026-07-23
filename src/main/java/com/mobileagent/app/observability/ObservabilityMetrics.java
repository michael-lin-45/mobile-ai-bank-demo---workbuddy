package com.mobileagent.app.observability;

import io.micrometer.core.instrument.Counter;
import com.mobileagent.app.observability.MetricsRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 可观测指标注册中心 — 15 项 MVP 指标 Micrometer Meter
 *
 * 指标命名遵循 OTel SemConv 风格: agent.{domain}.{metric} / llm.{metric}
 *
 * 指标清单 (15项 MVP):
 *   ── llm.* (5项, ObsChatModel 自动采集) ──
 *   [C] llm.token.input             — LLM 输入 token 数
 *   [C] llm.token.output            — LLM 输出 token 数
 *   [H] llm.first_token.latency     — 首 token 延迟
 *   [H] llm.operation.duration      — LLM 总操作耗时
 *   [C] llm.error.count             — LLM 错误计数
 *
 *   ── agent.* (10项, 业务埋点) ──
 *   [C] agent.router.decision.outcome — 路由决策结果 (layer/decision/domain tag)
 *   [C] agent.intent.accuracy        — 意图准确率四态标签
 *   [H] agent.rewrite.accuracy       — 改写准确率
 *   [T] agent.workflow.execution.duration — 子图执行耗时 (intent/graph tag)
 *   [C] agent.workflow.interrupt     — 子图中断次数 (intent/interrupt_node tag)
 *   [H] agent.slot.askback.total     — 追问槽位总数
 *   [C] agent.tool.call.count        — 工具调用计数 (tool_name/result tag)
 *   [T] agent.tool.call.duration     — 工具调用耗时 (tool_name tag)
 *   [C] agent.skill.outcome          — 技能执行结果 (skill_name/result tag)
 *   [C] agent.session.completed      — 会话正常完成 (intent/result tag)
 *   [C] agent.business.outcome       — 业务结果 (intent/result tag)
 *
 * Tag 基数预算规则 (§2.4):
 *   禁止 user.id / session.id / trace.id / 原始 prompt / 原始金额 进入 Metric Tag。
 */
@Slf4j
@Component
public class ObservabilityMetrics {

    private final MeterRegistry meterRegistry;

    /** 集中式指标注册表（T-K P6）：新指标走 deepflux.* 前缀；存量 22 个调用保持不变 */
    private final MetricsRegistry metricsRegistry;

    // ── Atomic 状态（供 Gauge 使用）──
    private final AtomicLong suspendDepth = new AtomicLong(0);
    private final AtomicLong activeSessionCount = new AtomicLong(0);

    // ── 动态 Counter/Timer 缓存（支持任意 tag 组合）──
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> summaryCache = new ConcurrentHashMap<>();

    // ── Meter 声明 ──
    private Counter intentRecognized;
    private DistributionSummary intentConfidence;
    private Counter routerDecisionHit;
    private Counter routerDecisionLlm;
    private Counter routerDecisionFail;
    private Counter stateTransitionFollow;
    private Counter stateTransitionSwitch;
    private Counter stateTransitionResume;
    private DistributionSummary slotAskbackTotal;
    private Timer workflowExecutionDuration;
    private Counter workflowInterrupt;
    private Counter sessionCompleted;
    private Counter sessionAbandoned;
    private Counter businessOutcomeSuccess;
    private Counter businessOutcomeFail;
    private DistributionSummary extractionCompleteness;
    private Counter rewriteTotal;
    private DistributionSummary rewriteAccuracy;
    private Counter rewritePass;
    private Timer llmCallDuration;
    private Timer toolCallDuration;

    public ObservabilityMetrics(MeterRegistry meterRegistry, MetricsRegistry metricsRegistry) {
        this.meterRegistry = meterRegistry;
        this.metricsRegistry = metricsRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("[ObservabilityMetrics] Registering AI observability meters...");

        // ── Batch 1: L0 意图识别 ──
        intentRecognized = Counter.builder("agent.intent.recognized")
                .description("L0 intent recognition count")
                .register(meterRegistry);

        intentConfidence = DistributionSummary.builder("agent.intent.confidence")
                .description("L0 intent confidence score distribution")
                .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        // ── Batch 1: 路由决策结果 ──
        routerDecisionHit = Counter.builder("agent.router.decision.outcome")
                .description("Router decisions that hit deterministic/keyword match")
                .tag("outcome", "hit")
                .register(meterRegistry);

        routerDecisionLlm = Counter.builder("agent.router.decision.outcome")
                .description("Router decisions that used LLM fallback")
                .tag("outcome", "llm_fallback")
                .register(meterRegistry);

        routerDecisionFail = Counter.builder("agent.router.decision.outcome")
                .description("Router decisions that failed and defaulted to CHAT")
                .tag("outcome", "fail")
                .register(meterRegistry);

        // ── Batch 2: 状态机转换 ──
        stateTransitionFollow = Counter.builder("agent.state.transition")
                .description("State machine FOLLOW transitions")
                .tag("transition", "FOLLOW")
                .register(meterRegistry);

        stateTransitionSwitch = Counter.builder("agent.state.transition")
                .description("State machine SWITCH transitions")
                .tag("transition", "SWITCH")
                .register(meterRegistry);

        stateTransitionResume = Counter.builder("agent.state.transition")
                .description("State machine RESUME transitions")
                .tag("transition", "RESUME")
                .register(meterRegistry);

        // ── Batch 3: 追问槽位 ──
        slotAskbackTotal = DistributionSummary.builder("agent.slot.askback.total")
                .description("Number of askback slots per interaction")
                .register(meterRegistry);

        // ── Batch 4: 子图执行 ──
        workflowExecutionDuration = Timer.builder("agent.workflow.execution.duration")
                .description("SubGraph execution duration")
                .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        workflowInterrupt = Counter.builder("agent.workflow.interrupt")
                .description("SubGraph interruptBefore count")
                .register(meterRegistry);

        // ── Gauge: 挂起 Agent 深度 ──
        Gauge.builder("agent.state.suspend.depth", suspendDepth, AtomicLong::get)
                .description("Current suspended agent queue depth")
                .register(meterRegistry);

        // ── Batch 3: Session 生命周期 ──
        sessionCompleted = Counter.builder("agent.session.completed")
                .description("Sessions completed normally")
                .register(meterRegistry);

        sessionAbandoned = Counter.builder("agent.session.abandoned")
                .description("Sessions abandoned abnormally")
                .register(meterRegistry);

        // ── Gauge: 活跃会话数 ──
        Gauge.builder("agent.session.active", activeSessionCount, AtomicLong::get)
                .description("Current active session count")
                .register(meterRegistry);

        // ── Batch 4: 业务结果 ──
        businessOutcomeSuccess = Counter.builder("agent.business.outcome")
                .description("Business operation outcomes")
                .tag("outcome", "success")
                .register(meterRegistry);

        businessOutcomeFail = Counter.builder("agent.business.outcome")
                .description("Business operation outcomes")
                .tag("outcome", "fail")
                .register(meterRegistry);

        // ── Batch 4: 参数提取 ──
        extractionCompleteness = DistributionSummary.builder("agent.extraction.completeness")
                .description("Parameter extraction completeness rate")
                .publishPercentiles(0.5, 0.9, 0.95)
                .register(meterRegistry);

        // ── L1 改写准确率 ──
        rewriteTotal = Counter.builder("agent.rewrite.total")
                .description("Total rewrite attempts")
                .register(meterRegistry);

        rewriteAccuracy = DistributionSummary.builder("agent.rewrite.accuracy")
                .description("Rewrite accuracy rate")
                .register(meterRegistry);

        // ── LLM 调用计时 ──
        llmCallDuration = Timer.builder("gen_ai.client.operation.duration")
                .description("LLM call duration")
                .tag("gen_ai.operation.name", "chat")
                .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        // ── 工具调用耗时 ──
        toolCallDuration = Timer.builder("agent.tool.call.duration")
                .description("Tool call duration")
                .tag("tool_name", "default")
                .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        log.info("[ObservabilityMetrics] All meters registered: {} meters",
                meterRegistry.getMeters().size());
    }

    // ==================== 统一动态 Counter/Timer 方法 ====================

    /**
     * 记录带动态 tag 的 Counter。
     * 使用缓存避免重复创建相同 tag 组合的 Counter。
     *
     * @param name 指标名称 (如 "agent.router.decision.outcome")
     * @param tags 动态 tag (key1, value1, key2, value2, ...)
     */
    public void recordCounter(String name, String... tags) {
        safeRecord(() -> {
            String cacheKey = buildCacheKey(name, tags);
            Counter counter = counterCache.computeIfAbsent(cacheKey, k ->
                    Counter.builder(name)
                            .tags(tags)
                            .register(meterRegistry));
            counter.increment();
        });
    }

    /**
     * 记录带动态 tag 的 Timer。
     *
     * @param name       指标名称
     * @param durationMs 耗时(毫秒)
     * @param tags       动态 tag (key1, value1, key2, value2, ...)
     */
    public void recordTimer(String name, long durationMs, String... tags) {
        safeRecord(() -> {
            String cacheKey = buildCacheKey(name, tags);
            Timer timer = timerCache.computeIfAbsent(cacheKey, k ->
                    Timer.builder(name)
                            .tags(tags)
                            .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                            .register(meterRegistry));
            timer.record(durationMs, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * 记录带动态 tag 的 DistributionSummary。
     *
     * @param name  指标名称
     * @param value 记录值
     * @param tags  动态 tag (key1, value1, key2, value2, ...)
     */
    public void recordSummary(String name, double value, String... tags) {
        safeRecord(() -> {
            String cacheKey = buildCacheKey(name, tags);
            DistributionSummary summary = summaryCache.computeIfAbsent(cacheKey, k ->
                    DistributionSummary.builder(name)
                            .tags(tags)
                            .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                            .register(meterRegistry));
            summary.record(value);
        });
    }

    /**
     * 启动一个带 tag 的 Timer.Sample（用于测量耗时）。
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 停止 Timer.Sample 并记录到带 tag 的 Timer。
     */
    public void stopTimer(Timer.Sample sample, String name, String... tags) {
        safeRecord(() -> {
            String cacheKey = buildCacheKey(name, tags);
            Timer timer = timerCache.computeIfAbsent(cacheKey, k ->
                    Timer.builder(name)
                            .tags(tags)
                            .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                            .register(meterRegistry));
            sample.stop(timer);
        });
    }

    /** 构建缓存 key：name + tags 拼接 */
    private String buildCacheKey(String name, String... tags) {
        StringBuilder sb = new StringBuilder(name);
        if (tags != null) {
            for (String tag : tags) {
                sb.append('|').append(tag);
            }
        }
        return sb.toString();
    }

    // ==================== 便捷记录方法（现有） ====================

    /** L0 意图识别成功 */
    public void recordIntentRecognized(String intent, double confidence) {
        safeRecord(() -> {
            intentRecognized.increment();
            intentConfidence.record(confidence);
        });
    }

    /** 路由决策：确定性命中 */
    public void recordRouterHit() {
        safeRecord(() -> routerDecisionHit.increment());
    }

    /** 路由决策：LLM 兜底 */
    public void recordRouterLlmFallback() {
        safeRecord(() -> routerDecisionLlm.increment());
    }

    /** 路由决策：失败回退 CHAT */
    public void recordRouterFail() {
        safeRecord(() -> routerDecisionFail.increment());
    }

    /**
     * 路由决策：带 layer/decision/domain tag 的完整记录。
     * 用于 OTel 观测后端按维度聚合。
     *
     * @param layer    路由层级 (L0/L1)
     * @param decision 决策类型 (hit/llm_fallback/fail)
     * @param domain   路由到的领域 (TRANSFER/BILL/WEALTH/CHAT/UNSUPPORTED)
     */
    public void recordRouterDecision(String layer, String decision, String domain) {
        recordCounter("agent.router.decision.outcome",
                "layer", layer, "decision", decision, "domain", domain);
    }

    /** 状态机转换 FOLLOW */
    public void recordStateTransitionFollow() {
        safeRecord(() -> stateTransitionFollow.increment());
    }

    /** 状态机转换 SWITCH */
    public void recordStateTransitionSwitch() {
        safeRecord(() -> stateTransitionSwitch.increment());
    }

    /** 状态机转换 RESUME */
    public void recordStateTransitionResume() {
        safeRecord(() -> stateTransitionResume.increment());
    }

    /** 通用状态转换记录 */
    public void recordStateTransition(String transition) {
        safeRecord(() -> {
            switch (transition.toUpperCase()) {
                case "FOLLOW":
                    stateTransitionFollow.increment();
                    break;
                case "SWITCH":
                    stateTransitionSwitch.increment();
                    break;
                case "RESUME":
                    stateTransitionResume.increment();
                    break;
                default:
                    break;
            }
        });
    }

    /** 追问槽位数 */
    public void recordSlotAskbackTotal(int count) {
        safeRecord(() -> slotAskbackTotal.record(count));
    }

    /**
     * 追问槽位埋点 — 每次追问时递增，可在会话结束时汇总。
     */
    public void incrementAskbackSlot() {
        safeRecord(() -> slotAskbackTotal.record(1));
    }

    /** 子图执行计时 */
    public Timer.Sample startWorkflowTimer() {
        return Timer.start(meterRegistry);
    }

    /** 子图执行计时结束 */
    public void stopWorkflowTimer(Timer.Sample sample) {
        safeRecord(() -> sample.stop(workflowExecutionDuration));
    }

    /**
     * 子图执行计时 — 带 intent/graph tag。
     */
    public void recordWorkflowDuration(String intent, String graphName, long durationMs) {
        recordTimer("agent.workflow.execution.duration", durationMs,
                "intent", intent, "graph", graphName);
    }

    /** 子图中断 */
    public void recordWorkflowInterrupt() {
        safeRecord(() -> workflowInterrupt.increment());
    }

    /**
     * 子图中断 — 带 intent/interrupt_node tag。
     */
    public void recordWorkflowInterrupt(String intent, String interruptNode) {
        recordCounter("agent.workflow.interrupt",
                "intent", intent, "interrupt_node", interruptNode);
    }

    /** 设置挂起深度 */
    public void setSuspendDepth(long depth) {
        suspendDepth.set(depth);
    }

    /** 会话正常完成 */
    public void recordSessionCompleted() {
        safeRecord(() -> {
            sessionCompleted.increment();
            activeSessionCount.decrementAndGet();
        });
    }

    /**
     * 会话完成 — 带 intent/result tag。
     */
    public void recordSessionCompleted(String intent, String result) {
        recordCounter("agent.session.completed",
                "intent", intent, "result", result);
        activeSessionCount.decrementAndGet();
    }

    /** 会话异常放弃 */
    public void recordSessionAbandoned() {
        safeRecord(() -> {
            sessionAbandoned.increment();
            activeSessionCount.decrementAndGet();
        });
    }

    /** 会话创建 */
    public void recordSessionCreated() {
        activeSessionCount.incrementAndGet();
    }

    /** 业务操作成功 */
    public void recordBusinessSuccess() {
        safeRecord(() -> businessOutcomeSuccess.increment());
    }

    /** 业务操作失败 */
    public void recordBusinessFail() {
        safeRecord(() -> businessOutcomeFail.increment());
    }

    /**
     * 业务结果 — 带 intent/result tag。
     */
    public void recordBusinessOutcome(String intent, String result) {
        recordCounter("agent.business.outcome",
                "intent", intent, "result", result);
    }

    /** 参数提取完整率 */
    public void recordExtractionCompleteness(double rate) {
        safeRecord(() -> extractionCompleteness.record(rate));
    }

    /** 改写总数 */
    public void recordRewriteTotal() {
        safeRecord(() -> rewriteTotal.increment());
    }

    /** 改写准确率 */
    public void recordRewriteAccuracy(double rate) {
        safeRecord(() -> rewriteAccuracy.record(rate));
    }

    /**
     * 改写准确率 — 带规则检查 tag (轻量信号)。
     * rule_check: pass / entity_lost / amount_not_normalized / pronoun_unresolved
     */
    public void recordRewriteAccuracy(String ruleCheck, String domain) {
        recordSummary("agent.rewrite.accuracy", "pass".equals(ruleCheck) ? 1.0 : 0.0,
                "rule_check", ruleCheck, "domain", domain);
        if ("pass".equals(ruleCheck)) {
            safeRecord(() -> rewritePass.increment());
        }
    }

    /** LLM 调用计时 */
    public Timer.Sample startLlmTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopLlmTimer(Timer.Sample sample) {
        safeRecord(() -> sample.stop(llmCallDuration));
    }

    // ==================== 新增指标方法 ====================

    /**
     * 待审批任务计数（UpDownCounter，T-J P7 §4.5）。
     * 人工中断 +1，审批完成 -1；经集中式 MetricsRegistry 暴露为 deepflux.workflow.pending_approval。
     *
     * @param delta 变化量（+1 中断 / -1 审批完成）
     * @param intent 触发意图（tag）
     */
    public void recordPendingApproval(long delta, String intent) {
        metricsRegistry.addUpDown("workflow.pending_approval", delta,
                "intent", (intent != null && !intent.isBlank()) ? intent : "UNKNOWN");
    }

    /**
     * 意图准确率 — 四态标签 (§5.7)。
     * state: correct / fuzzy / error / disambiguated
     */
    public void recordIntentAccuracy(String state, String intentPredicted, String intentActual) {
        recordCounter("agent.intent.accuracy",
                "state", state, "intent_predicted", intentPredicted,
                "intent_actual", intentActual);
    }

    /**
     * L1 层调用计数（FOLLOW/SWITCH/RESUME 上下文路由 + intent_identify 意图识别）。
     * 用于解锁 P0-5/P1-2：后端 OtlpParserService 对 metric 名含 "l1.call" 累加 agent_call:L1。
     */
    public void recordL1Call(String type, String domain) {
        recordCounter("agent.l1.call", "type", type, "domain", domain);
    }

    /**
     * REROUTE 计数（L1→L0 重新路由）。
     * 用于解锁 C3：后端从 H2 agent.reroute.count 聚合 rerouteRate。
     */
    public void recordReroute(String domain) {
        recordCounter("agent.reroute.count", "domain", domain);
    }

    /**
     * 技能执行结果。
     */
    public void recordSkillOutcome(String skillName, String result) {
        recordCounter("agent.skill.outcome",
                "skill_name", skillName, "result", result);
    }

    /**
     * 工具调用计数。
     */
    public void recordToolCallCount(String toolName, String result) {
        recordCounter("agent.tool.call.count",
                "tool_name", toolName, "result", result);
    }

    /**
     * 工具调用耗时。
     */
    public void recordToolCallDuration(String toolName, long durationMs) {
        recordTimer("agent.tool.call.duration", durationMs,
                "tool_name", toolName);
    }

    /**
     * 工具调用 — 启动计时。
     */
    public Timer.Sample startToolTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 工具调用 — 记录计数+停止计时。
     */
    public void stopToolTimer(Timer.Sample sample, String toolName, String result) {
        safeRecord(() -> {
            recordToolCallCount(toolName, result);
            sample.stop(timerCache.computeIfAbsent(
                    buildCacheKey("agent.tool.call.duration", "tool_name", toolName),
                    k -> Timer.builder("agent.tool.call.duration")
                            .tag("tool_name", toolName)
                            .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
                            .register(meterRegistry)));
        });
    }

    /**
     * 工具调用计数（带扩展 tag，如 category=mcp）。
     * 用于 MCP / 第三方工具调用统一埋点，extraTags 合并进 Counter tag。
     *
     * @param toolName   工具名（MCP 场景为 "MCP:<tool>"）
     * @param result     调用结果 (start/success/fail)
     * @param extraTags  扩展 tag（如 category=mcp），可为空
     */
    public void recordToolCallCount(String toolName, String result, Map<String, String> extraTags) {
        recordCounter("agent.tool.call.count", appendExtraTags(new String[]{"tool_name", toolName, "result", result}, extraTags));
    }

    /**
     * 工具调用耗时（带扩展 tag，如 category=mcp）。
     *
     * @param toolName   工具名（MCP 场景为 "MCP:<tool>"）
     * @param durationMs 耗时(毫秒)
     * @param extraTags  扩展 tag（如 category=mcp），可为空
     */
    public void recordToolCallDuration(String toolName, long durationMs, Map<String, String> extraTags) {
        recordTimer("agent.tool.call.duration", durationMs, appendExtraTags(new String[]{"tool_name", toolName}, extraTags));
    }

    /** 将扩展 tag Map 追加到基础 tag 数组（奇数 key/value 交替） */
    private String[] appendExtraTags(String[] base, Map<String, String> extraTags) {
        if (extraTags == null || extraTags.isEmpty()) return base;
        String[] out = new String[base.length + extraTags.size() * 2];
        System.arraycopy(base, 0, out, 0, base.length);
        int i = base.length;
        for (Map.Entry<String, String> e : extraTags.entrySet()) {
            out[i++] = e.getKey();
            out[i++] = e.getValue();
        }
        return out;
    }

    // ==================== Helpers ====================

    /** 安全记录 —— try-catch 包裹，不影响主业务 */
    private void safeRecord(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[ObservabilityMetrics] Failed to record metric: {}", e.getMessage());
        }
    }
}
