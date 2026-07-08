package com.observability.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应格式
 * <pre>{@code {"code": 0, "message": "ok", "data": ...}}</pre>
 *
 * @param <T> data 字段类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public ApiResponse() {}

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功响应 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /** 成功响应，带消息 */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(0, message, data);
    }

    /** 错误响应 */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    // ── Getters / Setters ──

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
