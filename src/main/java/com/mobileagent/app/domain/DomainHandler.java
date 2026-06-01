package com.mobileagent.app.domain;

import com.mobileagent.app.data.StreamChunk;
import reactor.core.publisher.Flux;

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
     * @param globalChatHistory 全局跨域对话历史
     * @return 处理结果流
     */
    Flux<StreamChunk> handle(String sessionId, String userInput, String globalChatHistory);

    /**
     * 获取领域名称
     */
    String getDomainName();
}
