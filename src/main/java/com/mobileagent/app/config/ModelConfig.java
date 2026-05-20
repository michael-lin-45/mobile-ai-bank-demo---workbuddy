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
 * 多模型配置 - 3个独立ChatClient，各自可配不同的base-url/api-key/model
 *
 * Bean命名规范:
 *   {用途}ChatModel   - ChatModel实例(供需要ChatModel的代码注入)
 *   {用途}ChatClient  - ChatClient实例(供需要ChatClient的代码注入)
 *
 * 3个用途:
 *   context       - Phase1 上下文路由判断 (FOLLOW_UP/SWITCH_NEW/RESUME)
 *   intent        - Phase2 意图识别+上下文改写
 *   paramExtract  - 子Graph参数提取+取消意图判断
 *
 * 配置方式:
 *   application.yml 中 models.context / models.intent / models.param-extract 各自:
 *     base-url: OpenAI兼容端点URL
 *     api-key:  API密钥
 *     model:    模型名称
 *
 *   也可通过环境变量覆盖(如部署时):
 *     CONTEXT_BASE_URL, CONTEXT_API_KEY, CONTEXT_MODEL
 *     INTENT_BASE_URL,  INTENT_API_KEY,  INTENT_MODEL
 *     PARAM_EXTRACT_BASE_URL, PARAM_EXTRACT_API_KEY, PARAM_EXTRACT_MODEL
 */
@Slf4j
@Configuration
public class ModelConfig {

    // ==================== context: Phase1 上下文路由 ====================

    @Bean("contextChatModel")
    public ChatModel contextChatModel(
            @Value("${models.context.base-url}") String baseUrl,
            @Value("${models.context.api-key}") String apiKey,
            @Value("${models.context.model}") String model) {
        log.info("[ModelConfig] contextChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("contextChatClient")
    public ChatClient contextChatClient(@org.springframework.beans.factory.annotation.Qualifier("contextChatModel") ChatModel chatModel,
                                        ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(new ReadOnlyMemoryAdvisor(chatMemory))
                .build();
    }

    // ==================== intent: Phase2 意图识别+改写 ====================

    @Bean("intentChatModel")
    public ChatModel intentChatModel(
            @Value("${models.intent.base-url}") String baseUrl,
            @Value("${models.intent.api-key}") String apiKey,
            @Value("${models.intent.model}") String model) {
        log.info("[ModelConfig] intentChatModel: baseUrl={}, model={}", baseUrl, model);
        return buildChatModel(baseUrl, apiKey, model);
    }

    @Bean("intentChatClient")
    public ChatClient intentChatClient(@org.springframework.beans.factory.annotation.Qualifier("intentChatModel") ChatModel chatModel,
                                       ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(new ReadOnlyMemoryAdvisor(chatMemory))
                .build();
    }

    // ==================== paramExtract: 子Graph提参 ====================

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

    // ==================== ChatMemory (共享) ====================

    @Bean
    public ChatMemory chatMemory(@Value("${routing.history.max-pairs:4}") int maxPairs) {
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
