package com.fridayclass.common;

/**
 * 统一响应体。约定见 docs/api-contract.md：
 * code = 0 表示成功，非 0 表示失败；data 为业务数据。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
