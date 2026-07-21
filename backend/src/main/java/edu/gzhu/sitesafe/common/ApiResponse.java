package edu.gzhu.sitesafe.common;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "操作成功", data, TraceContext.currentId(), Instant.now());
    }

    public static ApiResponse<Void> okMessage(String message) {
        return new ApiResponse<>(true, "OK", message, null, TraceContext.currentId(), Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null, TraceContext.currentId(), Instant.now());
    }
}
