package com.mobileagent.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯静态方法单元测试：验证 {@link ModelConfig#deriveCompletionsPath(String)} 对 LLM endpoint
 * 路径（completionsPath）的推导逻辑。
 *
 * <p>不启动 Spring 上下文，仅覆盖路径推导，确保：
 * <ul>
 *   <li>base-url 不带版本段（如历史配置 https://token.sensenova.cn）→ 回退 /v1/chat/completions；</li>
 *   <li>base-url 已带 /vN（N 为数字，大小写不敏感）→ 返回 /chat/completions，避免 /v1/v1 重复；</li>
 *   <li>对 /v2、/v3 等不同版本形式同样适配。</li>
 * </ul>
 */
class ModelConfigPathTest {

    // ==================== 必覆盖用例 ====================

    @Test
    void testNoVersionSuffix_fallsBackToV1() {
        assertThat(ModelConfig.deriveCompletionsPath("https://token.sensenova.cn"))
                .as("历史配置不带版本段，应回退 OpenAI 约定 /v1/chat/completions")
                .isEqualTo("/v1/chat/completions");
    }

    @Test
    void testTrailingV1_returnsChatCompletionsOnly() {
        assertThat(ModelConfig.deriveCompletionsPath("https://token.sensenova.cn/v1"))
                .as("base-url 已带 /v1，不应再叠加 /v1 造成 /v1/v1 重复")
                .isEqualTo("/chat/completions");
    }

    @Test
    void testTrailingV2_returnsChatCompletionsOnly() {
        assertThat(ModelConfig.deriveCompletionsPath("https://api.xxx.com/v2"))
                .as("/v2 形式应返回 /chat/completions")
                .isEqualTo("/chat/completions");
    }

    @Test
    void testTrailingV3_returnsChatCompletionsOnly() {
        assertThat(ModelConfig.deriveCompletionsPath("https://api.xxx.com/v3"))
                .as("/v3 形式应返回 /chat/completions")
                .isEqualTo("/chat/completions");
    }

    // ==================== 额外健壮性用例 ====================

    @Test
    void testNullBaseUrl_fallsBackToV1() {
        assertThat(ModelConfig.deriveCompletionsPath(null))
                .as("null base-url 不应抛异常，回退 /v1/chat/completions")
                .isEqualTo("/v1/chat/completions");
    }

    @Test
    void testUppercaseV1_isCaseInsensitive() {
        assertThat(ModelConfig.deriveCompletionsPath("https://api.xxx.com/V1"))
                .as("版本段大小写不敏感，/V1 也应识别为已带版本")
                .isEqualTo("/chat/completions");
    }

    @Test
    void testTrailingWhitespace_isTrimmed() {
        assertThat(ModelConfig.deriveCompletionsPath("https://api.xxx.com/v2  "))
                .as("首尾空白应被 trim 后再判断版本段")
                .isEqualTo("/chat/completions");
    }
}
