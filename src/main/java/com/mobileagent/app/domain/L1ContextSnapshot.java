package com.mobileagent.app.domain;

import java.util.List;

/**
 * L1 上下文快照 — 编排接管时，各 DomainService 自报告的活跃/挂起状态
 *
 * <p>设计原则：
 * <ul>
 *   <li>DomainService 自己知道自己的状态，由它自己报告</li>
 *   <li>OrchestrationAgent 不需要知道 GlobalSessionContext 的内部结构</li>
 *   <li>每个域独立报告，编排层只负责汇总和注入</li>
 * </ul>
 *
 * <p>领养流程：
 * <ol>
 *   <li>scan: 遍历 DomainServiceRegistry → handler.collectL1Context()</li>
 *   <li>enrich: 汇总所有快照 → 拼入 PlannerAgent 的 ORIGINAL_REQUEST</li>
 *   <li>release: 遍历有上下文的 handler → handler.releaseL1Resources()</li>
 * </ol>
 */
public record L1ContextSnapshot(
    String domain,
    List<ActiveContext> activeContexts,
    List<SuspendedContext> suspendedContexts
) {
    public static final L1ContextSnapshot EMPTY = new L1ContextSnapshot("", List.of(), List.of());

    public boolean isEmpty() {
        return (activeContexts == null || activeContexts.isEmpty())
            && (suspendedContexts == null || suspendedContexts.isEmpty());
    }

    /**
     * 活跃子图上下文 — 当前正在执行的L2子图信息
     *
     * @param domain       域名 (TRANSFER, WEALTH, ...)
     * @param intent       子图意图 (TRANSFER, WEALTH_CONSULT, ...)
     * @param threadId     L2子图线程ID
     * @param lastQuestion 中断时的提问内容（可能为null）
     * @param userMessages  该L1子图交互过程中的所有用户输入记录，第一个元素为原始输入；可能为空列表
     */
    public record ActiveContext(String domain, String intent, String threadId, String lastQuestion,
                                java.util.List<String> userMessages) {}

    /**
     * 挂起子图上下文 — 被其他子图中断挂起的L2子图信息（仅MultiSubAgentDomainService）
     *
     * @param domain   域名
     * @param intent   子图意图
     * @param threadId L2子图线程ID
     */
    public record SuspendedContext(String domain, String intent, String threadId) {}
}
