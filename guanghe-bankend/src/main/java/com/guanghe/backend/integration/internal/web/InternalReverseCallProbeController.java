package com.guanghe.backend.integration.internal.web;

import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.integration.internal.security.InternalCallerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/test")
@Profile({"dev", "test", "mock"})
@ConditionalOnProperty(name = "zhixiaohe.internal.reverse-probe-enabled", havingValue = "true")
public class InternalReverseCallProbeController {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalReverseCallProbeController.class);
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^[0-9a-f]{2}-[0-9a-f]{32}-([0-9a-f]{16})-[0-9a-f]{2}$"
    );

    @PostMapping("/reverse-call")
    Map<String, Object> reverseCall(
            @RequestParam(name = "mode") String mode,
            HttpServletRequest request
    ) {
        if (!"python-m1".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        InternalCallerIdentity caller = (InternalCallerIdentity) request.getAttribute(
                InternalCallerIdentity.REQUEST_ATTRIBUTE
        );
        LOGGER.info(
                "internal_reverse_probe_completed traceId={} parentSpanId={} queryMode=python-m1",
                TraceIdHolder.currentTraceId(),
                parentSpanId(request.getHeader("traceparent"))
        );
        return Map.of(
                "accepted", true,
                "callerKeyId", caller.keyId(),
                "traceId", TraceIdHolder.currentTraceId()
        );
    }

    private String parentSpanId(String traceparent) {
        if (traceparent == null) {
            return "unavailable";
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent.toLowerCase(java.util.Locale.ROOT));
        return matcher.matches() ? matcher.group(1) : "unavailable";
    }
}
