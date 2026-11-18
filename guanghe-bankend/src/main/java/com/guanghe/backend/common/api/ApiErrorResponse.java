package com.guanghe.backend.common.api;

import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId
) {
}

