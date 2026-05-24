package com.mobileagent.app.data;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 工作流执行状态枚举
 *
 * 替代之前在WorkflowOutput中硬编码的字符串比较。
 * JSON序列化时仍输出字符串值（"COMPLETED"等），保持API兼容。
 */
public enum WorkflowStatus {

    /** 操作完成 */
    COMPLETED("COMPLETED"),

    /** 子智能体需要用户补充参数 */
    INTERRUPTED("INTERRUPTED"),

    /** 意图消歧,需要用户明确意图 */
    DISAMBIGUATION("DISAMBIGUATION"),

    /** 错误 */
    ERROR("ERROR");

    private final String value;

    WorkflowStatus(String value) {
        this.value = value;
    }

    /** JSON序列化值，保持与前端API的兼容性 */
    @JsonValue
    public String getValue() {
        return value;
    }

    /** 从字符串解析为枚举，兼容旧的字符串值 */
    public static WorkflowStatus fromString(String value) {
        if (value == null) return null;
        for (WorkflowStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown WorkflowStatus: " + value);
    }
}
