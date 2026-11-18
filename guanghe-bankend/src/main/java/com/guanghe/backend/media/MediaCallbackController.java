package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.media", name = "enabled", havingValue = "true")
final class MediaCallbackController {
    private static final Logger LOG = LoggerFactory.getLogger(MediaCallbackController.class);
    private static final int MAX_CALLBACK_BYTES = 2 * 1024 * 1024;
    private final MediaSubmissionService service;
    private final ObjectMapper json;
    MediaCallbackController(MediaSubmissionService service, ObjectMapper json) {
        this.service = service; this.json = json;
    }

    @PostMapping({
            "/api/v1/callbacks/media/order-progress",
            "/api/callback/mt-order-progress"
    })
    Map<String, Object> progress(HttpServletRequest request) throws Exception {
        return orderCallback("PROGRESS", request);
    }

    @PostMapping({
            "/api/v1/callbacks/media/order-receipt",
            "/api/callback/mt-order-receipt"
    })
    Map<String, Object> receipt(HttpServletRequest request) throws Exception {
        return orderCallback("RECEIPT", request);
    }

    @PostMapping({
            "/api/v1/callbacks/media/order-rejection",
            "/api/callback/mt-order-refund"
    })
    Map<String, Object> rejection(HttpServletRequest request) throws Exception {
        return orderCallback("REFUND", request);
    }

    @PostMapping({
            "/api/v1/callbacks/media/resource-update",
            "/api/callback/mt-resource-update"
    })
    Map<String, Object> resourceUpdate(HttpServletRequest request) throws Exception {
        JsonNode payload = decode(request);
        try {
            service.processResourceCallback(payload);
        } catch (MediaProviderException | BusinessException exception) {
            String code = exception instanceof MediaProviderException provider
                    ? provider.code() : ((BusinessException) exception).errorCode().name();
            LOG.warn("Media resource callback rejected code={} shape={}", code, resourceShape(payload));
            throw exception;
        }
        return success();
    }

    private Map<String, Object> orderCallback(String eventType, HttpServletRequest request) throws Exception {
        service.processCallback(eventType, decode(request));
        return success();
    }

    private JsonNode decode(HttpServletRequest request) throws Exception {
        String response = request.getParameter("response");
        String sign = request.getParameter("sign");
        String appKey = request.getParameter("app_key");
        if (response == null && request.getContentType() != null
                && request.getContentType().toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE)) {
            try {
                byte[] raw = request.getInputStream().readNBytes(MAX_CALLBACK_BYTES + 1);
                if (raw.length > MAX_CALLBACK_BYTES) {
                    throw new MediaProviderException("MEDIA_CALLBACK_PAYLOAD_INVALID", false);
                }
                JsonNode envelope = json.readTree(new String(raw, StandardCharsets.UTF_8));
                response = envelope.path("response").textValue();
                sign = envelope.path("sign").textValue();
                appKey = envelope.has("app_key") ? envelope.path("app_key").textValue()
                        : envelope.path("appKey").textValue();
            } catch (MediaProviderException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new MediaProviderException("MEDIA_CALLBACK_PAYLOAD_INVALID", false, exception);
            }
        }
        return service.decodeCallback(response, sign, appKey);
    }

    private Map<String, Object> success() {
        return Map.of("errorCode", "", "message", "", "t", Long.toString(System.currentTimeMillis() / 1000));
    }

    @ExceptionHandler(MediaProviderException.class)
    ResponseEntity<Map<String, Object>> providerError(MediaProviderException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "MEDIA_CALLBACK_SIGNATURE_INVALID" -> HttpStatus.UNAUTHORIZED;
            case "MEDIA_PROVIDER_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        LOG.warn("Media callback rejected path={} code={} status={}",
                request.getRequestURI(), exception.code(), status.value());
        Map<String, Object> body = Map.of(
                "errorCode", exception.code(),
                "message", "callback rejected",
                "t", Long.toString(System.currentTimeMillis() / 1000));
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String, Object>> businessError(BusinessException exception, HttpServletRequest request) {
        LOG.warn("Media callback rejected path={} code={} status={}",
                request.getRequestURI(), exception.errorCode().name(), exception.errorCode().status().value());
        Map<String, Object> body = Map.of(
                "errorCode", exception.errorCode().name(),
                "message", "callback rejected",
                "t", Long.toString(System.currentTimeMillis() / 1000));
        return ResponseEntity.status(exception.errorCode().status()).body(body);
    }

    private String resourceShape(JsonNode payload) {
        if (payload == null) return "container=NULL,items=0";
        JsonNode resources = payload;
        if (payload.isObject() && !payload.has("id") && !payload.has("resource_id") && payload.has("data")) {
            resources = payload.path("data");
        }
        Iterable<JsonNode> items = resources.isObject() && (resources.has("id") || resources.has("resource_id"))
                ? List.of(resources) : resources;
        int count = 0, nonObject = 0, modelMissing = 0, modelOther = 0, idMissing = 0;
        int primaryIdEmptyWithAlternate = 0, nameMissing = 0, statusMissing = 0, statusOther = 0;
        int priceMissing = 0, priceNonPositive = 0, priceMalformed = 0;
        for (JsonNode item : items) {
            if (++count > 500) break;
            if (!item.isObject()) { nonObject++; continue; }
            String model = item.path("model_id").asText("").strip();
            if (model.isEmpty()) modelMissing++;
            else if (!"1".equals(model)) modelOther++;
            String id = item.path("id").asText("").strip();
            String alternate = item.path("resource_id").asText("").strip();
            if (id.isEmpty() && alternate.isEmpty()) idMissing++;
            if (item.has("id") && id.isEmpty() && !alternate.isEmpty()) primaryIdEmptyWithAlternate++;
            if (item.path("name").asText("").isBlank()) nameMissing++;
            String status = item.path("status").asText("").strip();
            if (status.isEmpty()) statusMissing++;
            else if (!List.of("-1", "0", "1").contains(status)) statusOther++;
            JsonNode price = item.get("price_assign");
            if (price == null || price.isNull() || (price.isTextual() && price.textValue().isBlank())) {
                priceMissing++;
            } else if (!price.isNumber() && !price.isTextual()) {
                priceMalformed++;
            } else {
                try {
                    if (new BigDecimal(price.asText().strip()).signum() <= 0) priceNonPositive++;
                } catch (RuntimeException invalidPrice) {
                    priceMalformed++;
                }
            }
        }
        return "container=" + resources.getNodeType() + ",items=" + count + ",nonObject=" + nonObject
                + ",modelMissing=" + modelMissing + ",modelOther=" + modelOther + ",idMissing=" + idMissing
                + ",primaryIdEmptyWithAlternate=" + primaryIdEmptyWithAlternate + ",nameMissing=" + nameMissing
                + ",statusMissing=" + statusMissing + ",statusOther=" + statusOther
                + ",priceMissing=" + priceMissing + ",priceNonPositive=" + priceNonPositive
                + ",priceMalformed=" + priceMalformed;
    }
}
