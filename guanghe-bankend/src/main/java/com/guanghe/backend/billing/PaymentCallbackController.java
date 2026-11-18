package com.guanghe.backend.billing;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one unauthenticated commercial endpoint.
 *
 * <p>Answers in the provider's own vocabulary rather than this service's {@code ApiResponse}
 * envelope, because the caller is a payment platform that decides whether to retry based on the
 * status code.
 *
 * <p>The response contract is deliberately narrow:
 * <ul>
 *   <li>acknowledge when the message was authentic and its effect — including "nothing to do",
 *       "already applied" and "stale" — has been durably recorded, so the provider stops retrying;
 *   <li>refuse when the message could not be authenticated or could not be accounted for, so the
 *       provider keeps retrying while the recorded audit row tells an operator what to fix.
 * </ul>
 *
 * <p>An unauthenticated payload is never parsed for business meaning and never logged: only the
 * exception type and a stable code leave this class.
 */
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PaymentCallbackController {

    /** Callbacks are a few kilobytes; anything larger is not one of ours. */
    private static final int MAX_CALLBACK_BYTES = 64 * 1024;

    private static final Map<String, Object> ACK = Map.of("code", "SUCCESS", "message", "成功");

    private final PaymentCallbackService callbacks;

    public PaymentCallbackController(PaymentCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping("/api/v1/callbacks/payment/notify")
    ResponseEntity<Map<String, Object>> notify(HttpServletRequest request) throws IOException {
        String rawBody = readBody(request);
        RechargeOrderService.Settlement settlement =
                callbacks.accept(headers(request), rawBody);
        return switch (settlement) {
            // Everything we could act on has been recorded; retrying would change nothing.
            case APPLIED, DUPLICATE, STALE, NO_CHANGE -> ResponseEntity.ok(ACK);
            // The message may be legitimate but we cannot account for it. Refusing keeps the
            // provider retrying while an operator looks at the audit row this recorded.
            case AMOUNT_MISMATCH -> failure(HttpStatus.CONFLICT, "PAYMENT_AMOUNT_MISMATCH");
            case UNMATCHED -> failure(HttpStatus.UNPROCESSABLE_ENTITY, "RECHARGE_ORDER_NOT_FOUND");
        };
    }

    private String readBody(HttpServletRequest request) throws IOException {
        byte[] raw = request.getInputStream().readNBytes(MAX_CALLBACK_BYTES + 1);
        if (raw.length > MAX_CALLBACK_BYTES) {
            throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            values.put(name.toLowerCase(java.util.Locale.ROOT), request.getHeader(name));
        }
        return values;
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String, Object>> businessError(BusinessException exception) {
        return failure(exception.errorCode().status(), exception.errorCode().name());
    }

    private static ResponseEntity<Map<String, Object>> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .body(Map.of("code", "FAIL", "message", code));
    }
}
