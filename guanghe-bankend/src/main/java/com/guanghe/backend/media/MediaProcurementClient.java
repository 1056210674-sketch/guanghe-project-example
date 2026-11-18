package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.media", name = "enabled", havingValue = "true")
final class MediaProcurementClient implements MediaProviderAdapter {
    static final Map<String, String> PATHS = Map.of(
            "resources", "/api/companyResource/syncResource",
            "submit", "/api/companyOrder/receive",
            "status", "/api/companyOrder/getOrderStatusNew",
            "wallet", "/api/companyWallet/walletBalance");
    private final MediaProperties properties;
    private final ObjectMapper json;
    private final MediaProtocolCodec codec;
    private final HttpClient http;

    @Autowired
    MediaProcurementClient(MediaProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build());
    }

    MediaProcurementClient(MediaProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties; this.json = json; this.codec = new MediaProtocolCodec(json); this.http = http;
    }

    @Override
    public ResourcePage resources(String modelId, String resourceId, int page, int limit) {
        JsonNode data = post("resources", Map.of("resource_id", resourceId == null ? "" : resourceId,
                "model_id", modelId, "page", page, "limit", limit), false);
        List<JsonNode> items = new ArrayList<>();
        JsonNode source = data.path("data");
        JsonNode reportedTotal = data.path("total");
        if (!source.isArray() || !reportedTotal.canConvertToLong() || reportedTotal.longValue() < 0) {
            throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
        }
        source.forEach(items::add);
        long total = reportedTotal.longValue();
        return new ResourcePage(total, List.copyOf(items));
    }

    @Override
    public SubmitResult submit(SubmitCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_id", command.modelId());
        payload.put("resource_id", command.resourceId());
        payload.put("customer_order_id", command.customerOrderId());
        payload.put("customer_batch_id", command.customerOrderId());
        payload.put("out_model_id", command.modelId());
        payload.put("out_resource_id", command.resourceId());
        payload.put("resource_price", command.upstreamPrice());
        payload.put("sale_id", "0");
        payload.put("sale_name", "光禾AI智能体框架");
        try {
            payload.put("prop", json.writeValueAsString(Map.of("biaoti", command.title(),
                    "neirong", command.bodyHtml(), "laiyuan", "", "miaoshu", command.remark(), "leixing", 1)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        JsonNode result = post("submit", payload, true);
        String orderId = text(result, "resource_order_id");
        if (orderId == null) throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", true);
        return new SubmitResult(orderId, text(result, "resource_order_num"), result);
    }

    @Override
    public OrderResult order(String modelId, String upstreamOrderId) {
        if (upstreamOrderId == null || upstreamOrderId.isBlank()) {
            throw new IllegalArgumentException("An upstream order id is required for status lookup");
        }
        JsonNode result = post("status", Map.of("model_id", modelId, "order_id", upstreamOrderId), false);
        String status = text(result, "status");
        if (status == null) throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
        return new OrderResult(status, result);
    }

    @Override
    public JsonNode walletBalance() {
        return post("wallet", Map.of(), false);
    }

    @Override
    public JsonNode decodeCallback(String response, String sign, String appKey) {
        if (!MessageDigestSupport.constant(properties.getAppKey(), appKey)) {
            throw new MediaProviderException("MEDIA_CALLBACK_SIGNATURE_INVALID", false);
        }
        return codec.decrypt(response, sign, properties.getAppKey());
    }

    private JsonNode post(String path, Map<String, ?> payload, boolean uncertainOnTransport) {
        if (!properties.isEnabled() || !properties.providerConfigured()) {
            throw new MediaProviderException("MEDIA_PROVIDER_NOT_CONFIGURED", false);
        }
        try {
            Map<String, String> envelope = codec.encrypt(payload, properties.getAppKey());
            StringBuilder form = new StringBuilder();
            envelope.forEach((name, value) -> {
                if (!form.isEmpty()) form.append('&');
                form.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            });
            URI uri = URI.create(properties.getBaseUrl()).resolve(PATHS.get(path));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new MediaProviderException("MEDIA_PROVIDER_AUTHENTICATION_FAILED", false);
            }
            if (response.statusCode() == 429) throw new MediaProviderException("MEDIA_PROVIDER_RATE_LIMITED", false);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MediaProviderException(response.statusCode() >= 500
                        ? "MEDIA_PROVIDER_UNAVAILABLE" : "MEDIA_PROVIDER_REJECTED",
                        uncertainOnTransport && ambiguousSubmissionHttpStatus(response.statusCode()));
            }
            JsonNode body = json.readTree(response.body());
            if (body.hasNonNull("response")) {
                body = codec.decrypt(body.path("response").textValue(), body.path("sign").textValue(), properties.getAppKey());
            }
            if (!body.isObject()) throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", uncertainOnTransport);
            if (body.hasNonNull("errorCode") && !body.path("errorCode").asText().isBlank()) {
                String providerError = body.path("errorCode").asText().strip();
                String stableCode = "MEDIA_PROVIDER_REJECTED";
                if ("ERROR00015".equals(providerError)) {
                    stableCode = switch (path) {
                        case "resources" -> "MEDIA_PROVIDER_SCHEDULE_RESTRICTED";
                        case "status" -> "MEDIA_UPSTREAM_ORDER_PENDING";
                        default -> "MEDIA_PROVIDER_REJECTED";
                    };
                }
                throw new MediaProviderException(stableCode, false);
            }
            JsonNode data = body.path("data");
            return data.isMissingNode() || data.isNull() ? json.createObjectNode() : data;
        } catch (MediaProviderException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new MediaProviderException(uncertainOnTransport ? "MEDIA_SUBMISSION_UNKNOWN" : "MEDIA_PROVIDER_TIMEOUT",
                    uncertainOnTransport, exception);
        } catch (java.io.IOException exception) {
            throw new MediaProviderException(uncertainOnTransport ? "MEDIA_SUBMISSION_UNKNOWN" : "MEDIA_PROVIDER_UNAVAILABLE",
                    uncertainOnTransport, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaProviderException("MEDIA_PROVIDER_UNAVAILABLE", uncertainOnTransport, exception);
        } catch (Exception exception) {
            throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", uncertainOnTransport, exception);
        }
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    static boolean ambiguousSubmissionHttpStatus(int status) {
        // A validation/client rejection is a definite no-order result. Timeout, conflict (a
        // duplicate customer reference may already exist), and server errors do not prove that
        // the provider failed before accepting the order, so they must never trigger a retry.
        return status == 408 || status == 409 || status >= 500;
    }

    private static final class MessageDigestSupport {
        private static boolean constant(String left, String right) {
            byte[] a = String.valueOf(left).getBytes(StandardCharsets.UTF_8);
            byte[] b = String.valueOf(right).getBytes(StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(a, b);
        }
    }
}
