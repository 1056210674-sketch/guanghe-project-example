package com.guanghe.backend.common.api;

import com.guanghe.backend.common.web.TraceIdHolder;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        return ResponseEntity.status(code.status()).body(error(code, exception.getMessage(), exception.details()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    ResponseEntity<ApiErrorResponse> handleBindingException(Exception exception) {
        var fieldErrors = exception instanceof MethodArgumentNotValidException methodException
                ? methodException.getBindingResult().getFieldErrors()
                : ((BindException) exception).getBindingResult().getFieldErrors();
        Map<String, Object> details = new LinkedHashMap<>();
        fieldErrors.forEach(fieldError -> details.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
        return ResponseEntity.unprocessableEntity()
                .body(error(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), details));
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class
    })
    ResponseEntity<ApiErrorResponse> handleMalformedRequest(Exception exception) {
        return ResponseEntity.unprocessableEntity()
                .body(error(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), Map.of()));
    }

    /**
     * A request that reached the dispatcher but matched no handler.
     *
     * <p>This is an absent route, not a server fault. The distinction matters to the browser: a
     * module whose endpoints are switched off must be recognisable as "not offered here", because a
     * 500 reads as "we are broken" and invites a retry that can never succeed.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleMissingRoute(NoResourceFoundException exception) {
        ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
        return ResponseEntity.status(code.status()).body(error(code, code.defaultMessage(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception, HttpServletResponse response) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            log.debug("request_client_disconnected");
            return null;
        }
        if (response.isCommitted()) {
            log.error("Unhandled failure after response was committed", exception);
            return null;
        }
        log.error("Unhandled request failure", exception);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status()).body(error(code, code.defaultMessage(), Map.of()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleUnusableAsyncResponse(AsyncRequestNotUsableException exception) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            log.debug("async_request_client_disconnected");
        } else {
            log.warn("async_response_not_writable type={}", exception.getClass().getSimpleName());
        }
    }

    private ApiErrorResponse error(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiErrorResponse(code.name(), message, details, TraceIdHolder.currentTraceId());
    }
}
