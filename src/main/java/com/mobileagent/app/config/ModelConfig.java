package com.mobileagent.app.config;

import com.mobileagent.app.observability.ObsChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;
import java.util.regex.Pattern;

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
 *   intent        - L1 意图识别+上下文改写 (SubGraphRouter)
 *   paramExtract  - L2 子Graph参数提取+取消意图判断
 *   chat          - L1 闲聊 (ChatService)
 *   wealthInterpret - L2 理财产品解读(流式)
 *   orch-planner  - 编排规划模型 (OrchestrationAgent 规划)
 *   orch          - 编排Agent模型 (OrchestrationAgent 执行)
 *
 * 对话历史统一使用 GlobalSessionContext.messages,
 * 不再使用 Spring AI ChatMemory。
 *
 * OTel 可观测增强:
 *   所有 ChatModel 均通过 ObsChatModel 包装，自动采集 llm.* 5 项指标:
 *   llm.token.input, llm.token.output, llm.first_token.latency,
 *   llm.operation.duration, llm.error.count
 *   注: 编排专用模型(orch-planner/orch)不强制 enable_thinking=false，以保留思维链能力。
 */
@Slf4j
@Configuration
public class ModelConfig {

    private final MeterRegistry meterRegistry;

    /**
     * 匹配 base-url 结尾的版本段，如 /v1、/v2、/v3（大小写不敏感）。
     * 用于推导 completionsPath，避免版本已包含在 base-url 时再叠加 /v1 造成 /v1/v1 重复。
     */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("/v\\d+$", Pattern.CASE_INSENSITIVE);

    public ModelConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ==================== domain: L0 领域路由 ====================

    @Bean("domainChatModel")
    public ChatModel domainChatModel(
            @Value("${models.domain.base-url}") String baseUrl,
            @Value("${models.domain.api-key}") String apiKey,
            @Value("${models.domain.model}") String model) {
        log.info("[ModelConfig] domainChatModel: baseUrl={}, model={}", baseUrl, model);
        return wrapWithObsChatModel(baseUrl, apiKey, model, "L0", "DomainRouter");
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
        return wrapWithObsChatModel(baseUrl, apiKey, model, "L1-LLM1", "ContextRouter");
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
        return wrapWithObsChatModel(baseUrl, apiKey, model, "L1-LLM2", "SubGraphRouter");
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
        return wrapWithObsChatModel(baseUrl, apiKey, model, "L2", "ParamExtract");
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
        return wrapWithObsChatModel(baseUrl, apiKey, model, "L2", "WealthInterpret");
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
        return wrapWithObsChatModel(baseUrl, apiKey, model, "L1", "ChatService");
    }

    @Bean("chatChatClient")
    public ChatClient chatChatClient(@org.springframework.beans.factory.annotation.Qualifier("chatChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    // ==================== orch-planner: 编排规划模型 ====================

    @Bean("orchPlannerModel")
    public ChatModel orchPlannerModel(
            @Value("${models.orch-planner.base-url}") String baseUrl,
            @Value("${models.orch-planner.api-key}") String apiKey,
            @Value("${models.orch-planner.model}") String model) {
        log.info("[ModelConfig] orchPlannerModel: baseUrl={}, model={}", baseUrl, model);
        return wrapOrchModel(baseUrl, apiKey, model, "ORCH", "OrchPlanner");
    }

    // ==================== orch: 编排Agent模型 ====================

    @Bean("orchModel")
    public ChatModel orchModel(
            @Value("${models.orch.base-url}") String baseUrl,
            @Value("${models.orch.api-key}") String apiKey,
            @Value("${models.orch.model}") String model) {
        log.info("[ModelConfig] orchModel: baseUrl={}, model={}", baseUrl, model);
        return wrapOrchModel(baseUrl, apiKey, model, "ORCH", "OrchAgent");
    }

    // ==================== 工具方法 ====================

    /**
     * 根据 base-url 推导 OpenAI 兼容接口的 completionsPath。
     *
     * <p>Spring AI 1.1.2 的 OpenAiApi.Builder 默认把 completionsPath 硬编码为
     * {@code /v1/chat/completions}。当 base-url 自身已经以 {@code /vN}（N 为数字，
     * 大小写不敏感，且为结尾段，如 /v1、/v2、/v3）结尾时，再叠加默认 /v1 会变成
     * {@code .../vN/v1/chat/completions}（重复/错误）。因此：
     *
     * <ul>
     *   <li>base-url 以 {@code /vN} 结尾 → 返回 {@code /chat/completions}（版本已含在 base-url）；</li>
     *   <li>否则 → 返回 {@code /v1/chat/completions}（回退 OpenAI 约定，对历史不带版本段的配置兼容）。</li>
     * </ul>
     *
     * <p>可见性设为包级，便于同包下的单元测试直接调用（无需反射）。
     *
     * @param baseUrl 模型 base-url
     * @return 推导出的 completionsPath
     */
    static String deriveCompletionsPath(String baseUrl) {
        if (baseUrl != null && VERSION_SUFFIX.matcher(baseUrl.trim()).find()) {
            return "/chat/completions";
        }
        return "/v1/chat/completions";
    }

    /**
     * 构建 OpenAiChatModel 并用 ObsChatModel 包装以采集 llm.* 指标。
     * 业务模型强制 enable_thinking=false（关闭思维链，降低延迟）。
     *
     * streamUsage=true 使 OpenAI 在流式响应的最后一个 chunk 中返回 usage 信息，
     * ObsChatModel 优先使用精确 usage，拿不到则走 content.length()/1.5 估算兜底。
     *
     * @param defaultAgentLayer 该模型所属业务层级 (L0/L1-LLM1/L1-LLM2/L1/L2)，ThreadLocal 缺失时兜底
     * @param defaultAgentName  该模型业务名称 (DomainRouter/ContextRouter/...)，ThreadLocal 缺失时兜底
     */
    private ChatModel wrapWithObsChatModel(String baseUrl, String apiKey, String model,
                                           String defaultAgentLayer, String defaultAgentName) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath(deriveCompletionsPath(baseUrl))
                .apiKey(apiKey)
                .build();
        OpenAiChatModel originalModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .extraBody(Map.of("enable_thinking", false))
                        .build())
                .build();
        return new ObsChatModel(originalModel, meterRegistry, model, defaultAgentLayer, defaultAgentName);
    }

    /**
     * 编排专用模型包装：不加 enable_thinking=false（编排需要思维链），
     * 同样经 ObsChatModel 包装以采集 llm.* 指标。
     */
    private ChatModel wrapOrchModel(String baseUrl, String apiKey, String model,
                                    String defaultAgentLayer, String defaultAgentName) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath(deriveCompletionsPath(baseUrl))
                .apiKey(apiKey)
                .build();
        OpenAiChatModel originalModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
        return new ObsChatModel(originalModel, meterRegistry, model, defaultAgentLayer, defaultAgentName);
    }
}
