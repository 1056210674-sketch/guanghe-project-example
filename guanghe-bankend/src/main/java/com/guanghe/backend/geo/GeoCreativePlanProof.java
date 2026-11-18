package com.guanghe.backend.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.task.PaidTaskProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Server-authenticated provenance for a model-produced, product-specific GEO visual plan. */
public final class GeoCreativePlanProof {
    private static final byte[] DOMAIN = "geo-visual-plan-v1\u0000".getBytes(StandardCharsets.UTF_8);
    private final PaidTaskProperties properties;
    private final ObjectMapper json;

    public GeoCreativePlanProof(PaidTaskProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public void sign(ObjectNode plan, long userId, long spaceId, long productId,
                     InternalRunContextDtos.Product product) {
        if (plan.has("proof")) throw invalid();
        plan.put("proof", "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                digest(plan, userId, spaceId, productId, product)));
    }

    public void verify(ObjectNode plan, long userId, long spaceId, long productId,
                       InternalRunContextDtos.Product product) {
        JsonNode supplied = plan.get("proof");
        if (supplied == null || !supplied.isTextual()
                || !supplied.textValue().matches("^v1\\.[A-Za-z0-9_-]{43}$")) throw invalid();
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(supplied.textValue().substring(3));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        ObjectNode unsigned = plan.deepCopy();
        unsigned.remove("proof");
        byte[] expected = digest(unsigned, userId, spaceId, productId, product);
        if (!MessageDigest.isEqual(expected, actual)) throw invalid();
    }

    private byte[] digest(ObjectNode plan, long userId, long spaceId, long productId,
                          InternalRunContextDtos.Product product) {
        String secret = properties.getTokenSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32 || product == null) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("userId", Long.toString(userId));
        payload.put("spaceId", Long.toString(spaceId));
        payload.put("productId", Long.toString(productId));
        payload.put("reportId", plan.path("reportId").asText());
        // The complete current product, including every prohibited claim and identity reference,
        // invalidates a previously issued plan whenever the authoritative catalog changes.
        payload.set("product", json.valueToTree(product));
        payload.set("plan", plan);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(DOMAIN);
            return mac.doFinal(json.writeValueAsBytes(sort(payload)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to sign GEO plan", exception);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            ArrayList<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) sorted.set(name, sort(node.get(name)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = json.createArrayNode();
            node.forEach(value -> sorted.add(sort(value)));
            return sorted;
        }
        return node;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "GEO 创作方案无效或商品信息已更新，请重新生成", java.util.Map.of());
    }
}
