package com.mobileagent.app.observability;

/**
 * ThreadLocal holder for passing agent business context to ObsChatModel.
 * 
 * Usage: AgentSpanContext.set(layer, agentName, intent, sessionId, userId)
 *        before calling ChatModel.call()/stream().
 *        ObsChatModel reads it to create OTel Span with business attributes.
 *        AgentSpanContext.clear() after the call.
 */
public class AgentSpanContext {

    private static final ThreadLocal<AgentSpanContext> HOLDER = new ThreadLocal<>();

    private String agentLayer;   // L0 / L1-LLM1 / L1-LLM2 / L2
    private String agentName;    // DomainRouter / ContextRouter / SubGraphRouter / TransferService etc.
    private String intent;       // TRANSFER / BILL_QUERY / WEALTH_CONSULT / CHAT etc.
    private String sessionId;
    private String userId;

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

    public static AgentSpanContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public String getAgentLayer() { return agentLayer; }
    public String getAgentName() { return agentName; }
    public String getIntent() { return intent; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
}
