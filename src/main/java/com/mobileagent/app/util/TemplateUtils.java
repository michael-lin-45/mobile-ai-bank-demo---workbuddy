package com.mobileagent.app.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 模板加载工具 - 从classpath加载prompt模板,失败时使用fallback
 */
@Slf4j
public class TemplateUtils {

    /**
     * 从classpath加载模板文件,加载失败时返回fallback
     *
     * @param path classpath路径,如 "prompts/l0-domain.st"
     * @param fallback 加载失败时的兜底内容
     * @return 模板内容
     */
    public static String loadTemplate(String path, Supplier<String> fallback) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[TemplateUtils] Failed to load template: {}, using fallback", path);
            return fallback.get();
        }
    }
}
