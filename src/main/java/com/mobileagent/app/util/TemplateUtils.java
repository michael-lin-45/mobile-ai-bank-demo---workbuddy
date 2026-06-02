package com.mobileagent.app.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板加载工具 - 从classpath加载prompt模板,失败时使用fallback
 *
 * 缓存策略: 按路径缓存,首次加载后永不再读磁盘。
 * L1 Service在构造时可通过warmUp()预热,确保运行时零IO。
 */
@Slf4j
public class TemplateUtils {

    /** 模板缓存: path → template content */
    private static final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 从classpath加载模板文件
     *
     * 首次调用从磁盘加载并缓存,后续调用直接返回缓存内容(零IO)
     * 模板文件不存在时抛出 IllegalStateException, 因为模板是系统必需资源
     *
     * @param path classpath路径,如 "prompts/l0-domain.st"
     * @return 模板内容
     */
    public static String loadTemplate(String path) {
        return templateCache.computeIfAbsent(path, TemplateUtils::doLoadTemplate);
    }

    /**
     * 预热模板 - 在构造时调用,将模板内容加载到缓存
     *
     * 使用场景: L1 Service在build()时调用,确保handle()运行时零IO
     *
     * @param path classpath路径
     * @return 加载的模板内容(方便链式调用)
     */
    public static String warmUp(String path) {
        String content = loadTemplate(path);
        log.info("[TemplateUtils] Warmed up template: path={}, length={}", path, content.length());
        return content;
    }

    /**
     * 清除缓存(仅用于测试)
     */
    public static void clearCache() {
        templateCache.clear();
    }

    private static String doLoadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            log.info("[TemplateUtils] Loaded template from disk: path={}, length={}", path, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load required template: " + path, e);
        }
    }
}
