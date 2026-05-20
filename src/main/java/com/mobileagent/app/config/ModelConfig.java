package com.mobileagent.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多模型配置 - L0/L1/L2各层独立ChatClient
 *
 * Bean命名规范:
 *   {用途}ChatModel   - ChatModel实例(供需要ChatModel的代码注入)
 *   {用途}ChatClient  - ChatClient实例(供需要ChatClient的代码注入)
 *
 * 用途:
 *   domain        - L0 领域路由 (DomainRouter, qwen-turbo)
 *   context       - L1 上下文路由判断 (ContextRouter, FOLLOW_UP/SWITCH_NEW/RESUME)
 *   intent        - L1 意图识别+上下文改写 (IntentionRouter, 理财L1)
 *   paramExtract  - L2 子Graph参数提取+取消意图判断
 *   chat          - L1 闲聊 (ChatService, 32B+大模型)
 *
 * ChatMemory架构:
 *   chatMemory (全局)          - 全局对话记录，L0 DomainRouter手动读取，ChatService通过Advisor读取
 *   wealthChatMemory (理财)    - WealthService使用,只记录理财领域消息,传入ContextRouter/IntentionRouter
 *   transferChatMemory (转账)  - TransferService使用,只记录转账领域消息,传入ContextRouter
 *   billChatMemory (账单)      - BillService使用,只记录账单领域消息,传入ContextRouter
 *   (闲聊无独立ChatMemory,使用全局ChatMemory)
 *
 * 历史注入方式:
 *   L0 DomainRouter   → 手动读取全局ChatMemory,格式化到{chat_history}(区分历史/当前消息)
 *   L1 ContextRouter   → 调用方传入领域ChatMemory,手动格式化到{chat_history}
 *   L1 IntentionRouter → 调用方传入领域ChatMemory,手动格式化到{chat_history}
 *   L1 ChatService     → 通过ReadOnlyMemoryAdvisor注入全局ChatMemory(标准对话,无需区分)
 */
@Slf4j
@Configuration
public class ModelConfig {

    // ==================== domain: L0 领域路由 ====================

    @Bean("domainChatModel")
    public ChatModel domainChatModel(
            @Value("${models.domain.base-url}") String baseUrl,
            @Value("${models.domain.api-key}") String apiKey,
            @Value("${models.domain.model}") String model) {
        log.info("[ModelConfig] domainChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("domainChatClient")
    public ChatClient domainChatClient(@org.springframework.beans.factory.annotation.Qualifier("domainChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }

    // ==================== context: L1 上下文路由 ====================

    @Bean("contextChatModel")
    public ChatModel contextChatModel(
            @Value("${models.context.base-url}") String baseUrl,
            @Value("${models.context.api-key}") String apiKey,
            @Value("${models.context.model}") String model) {
        log.info("[ModelConfig] contextChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("contextChatClient")
    public ChatClient contextChatClient(@org.springframework.beans.factory.annotation.Qualifier("contextChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }

    // ==================== intent: L1 意图识别+改写 ====================

    @Bean("intentChatModel")
    public ChatModel intentChatModel(
            @Value("${models.intent.base-url}") String baseUrl,
            @Value("${models.intent.api-key}") String apiKey,
            @Value("${models.intent.model}") String model) {
        log.info("[ModelConfig] intentChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("intentChatClient")
    public ChatClient intentChatClient(@org.springframework.beans.factory.annotation.Qualifier("intentChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }

    // ==================== paramExtract: L2 子Graph提参 ====================

    @Bean("paramExtractChatModel")
    public ChatModel paramExtractChatModel(
            @Value("${models.param-extract.base-url}") String baseUrl,
            @Value("${models.param-extract.api-key}") String apiKey,
            @Value("${models.param-extract.model}") String model) {
        log.info("[ModelConfig] paramExtractChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("paramExtractChatClient")
    public ChatClient paramExtractChatClient(@org.springframework.beans.factory.annotation.Qualifier("paramExtractChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    // ==================== chat: L1 闲聊 ====================

    @Bean("chatChatModel")
    public ChatModel chatChatModel(
            @Value("${models.chat.base-url}") String baseUrl,
            @Value("${models.chat.api-key}") String apiKey,
            @Value("${models.chat.model}") String model) {
        log.info("[ModelConfig] chatChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("chatChatClient")
    public ChatClient chatChatClient(@org.springframework.beans.factory.annotation.Qualifier("chatChatModel") ChatModel chatModel,
                                     ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(new ReadOnlyMemoryAdvisor(chatMemory))
                .build();
    }

    // ==================== ChatMemory 实例 ====================

    /** 全局ChatMemory - L0 DomainRouter使用,记录所有对话 */
    @Bean
    public ChatMemory chatMemory(@Value("${routing.history.max-pairs:4}") int maxPairs) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxPairs * 2)
                .build();
    }

    /** 理财领域ChatMemory - WealthService使用,只记录理财相关消息 */
    @Bean("wealthChatMemory")
    public ChatMemory wealthChatMemory(@Value("${routing.history.max-pairs:4}") int maxPairs) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxPairs * 2)
                .build();
    }

    /** 转账领域ChatMemory - TransferService使用,只记录转账相关消息 */
    @Bean("transferChatMemory")
    public ChatMemory transferChatMemory(@Value("${routing.history.max-pairs:4}") int maxPairs) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxPairs * 2)
                .build();
    }

    /** 账单领域ChatMemory - BillService使用,只记录账单相关消息 */
    @Bean("billChatMemory")
    public ChatMemory billChatMemory(@Value("${routing.history.max-pairs:4}") int maxPairs) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxPairs * 2)
                .build();
    }

    // ==================== 工具方法 ====================

    private ChatModel buildChatModel(String baseUrl, String apiKey, String model) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
    }
}
