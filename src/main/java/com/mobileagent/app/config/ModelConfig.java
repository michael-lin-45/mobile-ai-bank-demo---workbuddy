package com.mobileagent.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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
 *   domain        - L0 领域路由 (DomainRouter)
 *   context       - L1 上下文路由判断 (ContextRouter, FOLLOW/SWITCH/RESUME)
 *   intent        - L1 意图识别+上下文改写 (IntentRouter)
 *   paramExtract  - L2 子Graph参数提取+取消意图判断
 *   chat          - L1 闲聊 (ChatService)
 *   wealthInterpret - L2 理财产品解读(流式)
 *
 * 对话历史统一使用 GlobalSessionContext.messages,
 * 不再使用 Spring AI ChatMemory。
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
        return ChatClient.builder(chatModel).build();
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
        return ChatClient.builder(chatModel).build();
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
        return ChatClient.builder(chatModel).build();
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

    // ==================== wealthInterpret: L2 理财产品解读(流式) ====================

    @Bean("wealthInterpretChatModel")
    public ChatModel wealthInterpretChatModel(
            @Value("${models.wealth-interpret.base-url}") String baseUrl,
            @Value("${models.wealth-interpret.api-key}") String apiKey,
            @Value("${models.wealth-interpret.model}") String model) {
        log.info("[ModelConfig] wealthInterpretChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("wealthInterpretChatClient")
    public ChatClient wealthInterpretChatClient(@org.springframework.beans.factory.annotation.Qualifier("wealthInterpretChatModel") ChatModel chatModel) {
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
    public ChatClient chatChatClient(@org.springframework.beans.factory.annotation.Qualifier("chatChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
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
