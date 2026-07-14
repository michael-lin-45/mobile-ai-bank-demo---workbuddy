package com.mobileagent.app.domain;

import com.mobileagent.app.data.StreamChunk;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 领域处理者接口 - L1域服务的统一抽象
 *
 * AbstractDomainService 和 ChatService 都实现此接口，
 * 供 DomainServiceRegistry 统一管理和分发。
 */
public interface DomainHandler {

    /**
     * 处理用户消息 — 统一返回 Flux<StreamChunk>
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @return 处理结果流
     */
    Flux<StreamChunk> handle(String sessionId, String userInput);

    /**
     * 获取领域名称
     */
    String getDomainName();

    /**
     * 自报告 L1 上下文 — 编排接管时调用，让每个 DomainService 报告自己的活跃/挂起状态
     *
     * <p>设计原则：DomainService 自己知道自己的状态，由它自己报告，
     * OrchestrationAgent 不需要知道 GlobalSessionContext 的内部结构。
     *
     * <p>默认返回空快照。AbstractDomainService 覆盖实现。
     *
     * @param sessionId 会话ID
     * @return L1 上下文快照
     */
    default L1ContextSnapshot collectL1Context(String sessionId) {
        return L1ContextSnapshot.EMPTY;
    }

    /**
     * 释放 L1 所有权 — 编排领养后调用，清除 activeAgent/suspendedAgents 信息
     *
     * <p>领养流程的第三步（scan → enrich → release）。
     * DomainService 自行清理自己的状态，编排层不直接操作 GlobalSessionContext。
     * 只释放 inheritedIntents 中指定的意图，不在列表中的保留（用户编排结束后可resume）。
     *
     * <p>设计原则：编排领养 L1 后，不管 cancel 还是 answer，统一只清 agentInfo。
     * L2 checkpoint 不在此处 abort — 由 L2GraphTool 接管后通过 _cancelSignal 让 L2 子图
     * 自己走 cancelExecution 干净终止，或正常执行完毕后 GES 自动清理 checkpoint。
     *
     * <p>默认无操作。AbstractDomainService 覆盖实现。
     *
     * @param sessionId       会话ID
     * @param inheritedIntents 需要释放的意图列表（只有被编排继承的意图才释放）
     */
    default void releaseL1Resources(String sessionId, List<String> inheritedIntents) {
        // no-op
    }
}
