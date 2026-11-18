package com.guanghe.backend.common.api;

import com.guanghe.backend.common.web.TraceIdHolder;

public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data, TraceIdHolder.currentTraceId());
    }
}

