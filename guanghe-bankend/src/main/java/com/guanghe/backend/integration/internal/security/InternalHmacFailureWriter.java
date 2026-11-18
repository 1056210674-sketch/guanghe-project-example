package com.guanghe.backend.integration.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.ApiErrorResponse;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.web.TraceIdHolder;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class InternalHmacFailureWriter {

    private final ObjectMapper objectMapper;

    public InternalHmacFailureWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(
                        errorCode.name(),
                        errorCode.defaultMessage(),
                        Map.of(),
                        TraceIdHolder.currentTraceId()
                )
        );
    }
}
