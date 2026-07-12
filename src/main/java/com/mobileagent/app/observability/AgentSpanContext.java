package com.mobileagent.app.observability;

import io.opentelemetry.api.trace.Span;

import java.util.Map;

/**
 * ThreadLocal holder for passing agent business context to ObsChatModel.
 *
 * Usage: AgentSpanContext.set(layer, agentName, intent, sessionId, userId)
 *        before calling ChatModel.call()/stream().
 *        ObsChatModel reads it to create OTel Span with business attributes.
 *        AgentSpanContext.clear() after the call.
 *
 * ─────────────────────────────────────────────────────────────
 * Held-span 模式（延迟回填 intent，用于 L0/L1-LLM1/L1-LLM2）：
 * ─────────────────────────────────────────────────────────────
 * 业务识别结果（domain / routeType / intentName）是在 LLM 调用 *返回后* 才解析出来的，
 * 而 ObsChatModel 在 call() 的 finally 里会立即 span.end()，导致这些结果来不及写入 span。
 *
 * 解决方案：调用方改用 setWithHeldSpan(...) 告知 ObsChatModel「本 span 由调用方托管」：
 *   1. ObsChatModel 创建 span 后把引用 attach 到本上下文，且 finally 中 *不* 自动 end；
 *   2. 调用方在 LLM 返回、解析出真实识别结果后，调用 commitIntent(realValue)
 *      把结果写入 span.intent 并 end span（结束托管）；
 *   3. 异常路径下 ObsChatModel 仍会自行 end（避免泄漏），commitIntent 检测到 span 已
 *      结束(isRecording()==false)则为安全 no-op，仅清理 ThreadLocal。
 */
public class AgentSpanContext {

    private static final ThreadLocal<AgentSpanContext> HOLDER = new ThreadLocal<>();

    private String agentLayer;   // L0 / L1-LLM1 / L1-LLM2 / L2
    private String agentName;    // DomainRouter / ContextRouter / SubGraphRouter / TransferService etc.
    private String intent;       // TRANSFER / BILL_QUERY / WEALTH_CONSULT / CHAT etc.
    private String sessionId;
    private String userId;

    /** 托管模式：true 时 ObsChatModel 不自动 end span，交由调用方 commitIntent 结束 */
    private boolean holdSpan = false;
    /** 托管模式下 ObsChatModel 创建的业务 span 引用（解析出识别结果后回填 intent） */
    private Span liveSpan;

    private AgentSpanContext() {}

    public static AgentSpanContext set(String agentLayer, String agentName,
                                        String intent, String sessionId, String userId) {
        AgentSpanContext ctx = new AgentSpanContext();
        ctx.agentLayer = agentLayer;
        ctx.agentName = agentName;
        ctx.intent = intent;
        ctx.sessionId = sessionId;
        ctx.userId = userId;
        HOLDER.set(ctx);
        return ctx;
    }

    /**
     * 托管模式入口：与 set 相同，但标记 holdSpan=true，告知 ObsChatModel 由调用方托管 span。
     * 调用方必须在 LLM 调用后（无论成功/失败）调用 commitIntent(...) 结束并清理。
     */
    public static AgentSpanContext setWithHeldSpan(String agentLayer, String agentName,
                                                   String intent, String sessionId, String userId) {
        AgentSpanContext ctx = set(agentLayer, agentName, intent, sessionId, userId);
        ctx.holdSpan = true;
        return ctx;
    }

    public static AgentSpanContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        AgentSpanContext ctx = HOLDER.get();
        if (ctx != null && ctx.liveSpan != null && ctx.liveSpan.isRecording()) {
            // 兜底：托管模式调用方忘记 commit 时，仍结束 span 防止泄漏
            ctx.liveSpan.end();
        }
        HOLDER.remove();
    }

    /** 托管模式：ObsChatModel 在创建 span 后回调，把引用交回上下文 */
    public void attachSpan(Span span) {
        if (this.holdSpan && this.liveSpan == null) {
            this.liveSpan = span;
            System.out.println("[AgentSpanContext][DIAG] attachSpan layer=" + agentLayer
                    + " thread=" + Thread.currentThread().getName());
        } else if (this.liveSpan != null) {
            System.out.println("[AgentSpanContext][DIAG] attachSpan SKIP(already) layer=" + agentLayer);
        }
    }

    /**
     * 托管模式：调用方解析出真实业务识别结果后调用，回填 intent 并结束 span。
     * 若 span 已被 ObsChatModel 在异常路径中结束（isRecording()==false），则仅清理 ThreadLocal。
     *
     * @param intent 真实业务识别结果（L0=domain / L1-LLM1=routeType / L1-LLM2=intentName）
     * @param extra  额外需要写入 span 的属性（可为空 Map）
     */
    public void commitIntent(String intent, Map<String, String> extra) {
        this.intent = intent;
        boolean recording = this.liveSpan != null && this.liveSpan.isRecording();
        System.out.println("[AgentSpanContext][DIAG] commitIntent layer=" + agentLayer
                + " intent=" + intent + " liveSpanNull=" + (liveSpan == null)
                + " recording=" + recording + " thread=" + Thread.currentThread().getName());
        if (recording) {
            try {
                this.liveSpan.setAttribute("intent", intent);
                if (extra != null) {
                    extra.forEach((k, v) -> {
                        if (k != null && v != null) this.liveSpan.setAttribute(k, v);
                    });
                }
            } catch (Exception ignored) {
                // 属性写入失败不影响主业务
            } finally {
                this.liveSpan.end();
            }
        }
        HOLDER.remove();
    }

    public boolean isHoldSpan() { return holdSpan; }

    public String getAgentLayer() { return agentLayer; }
    public String getAgentName() { return agentName; }
    public String getIntent() { return intent; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
}

