package com.mobileagent.app.util;

/**
 * JSON解析工具 - 统一LLM返回值的JSON提取逻辑
 *
 * LLM返回内容常见格式:
 * - 纯JSON: {"domain": "WEALTH", ...}
 * - Markdown代码块: ```json\n{...}\n```
 * - 混合文本: Here is the result:\n```json\n{...}\n```
 *
 * 提取策略: 剥离代码块标记 → 取第一个{到最后一个}之间的内容
 */
public class JsonParseUtils {

    /**
     * 从LLM返回内容中提取JSON字符串
     *
     * @param content LLM返回的原始内容
     * @return 提取的JSON字符串，如果无法提取则返回原始内容trim后的结果
     */
    public static String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7);
        else if (trimmed.startsWith("```")) trimmed = trimmed.substring(3);
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        trimmed = trimmed.trim();

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }
}
