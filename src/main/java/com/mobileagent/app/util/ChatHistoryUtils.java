package com.mobileagent.app.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 字符串工具 - 日志截断等通用方法
 */
@Slf4j
public class ChatHistoryUtils {

    /** 截断字符串,用于日志输出 */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
