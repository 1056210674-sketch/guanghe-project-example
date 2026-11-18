package com.guanghe.backend.integration.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalHmacSignerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void matchesSharedV1TestVector() throws Exception {
        JsonNode vector;
        try (InputStream input = getClass().getResourceAsStream("/contracts/hmac/hmac-v1-test-vector.json")) {
            assertThat(input).isNotNull();
            vector = objectMapper.readTree(input);
        }

        InternalHmacSigner.SignedContent signed = InternalHmacSigner.sign(
                vector.required("timestamp").textValue(),
                vector.required("nonce").textValue(),
                vector.required("method").textValue(),
                vector.required("rawPathWithQuery").textValue(),
                vector.required("bodyUtf8").textValue().getBytes(StandardCharsets.UTF_8),
                vector.required("secret").textValue().getBytes(StandardCharsets.UTF_8)
        );

        assertThat(signed.bodySha256()).isEqualTo(vector.required("bodySha256").textValue());
        assertThat(signed.signature()).isEqualTo(vector.required("signatureBase64").textValue());
        assertThat(InternalHmacSigner.signatureMatches(signed.signature(), signed.signature())).isTrue();
        assertThat(InternalHmacSigner.signatureMatches(signed.signature(), signed.signature() + "x")).isFalse();
    }

    @Test
    void hashesAnAbsentBodyAsEmptyBytes() {
        InternalHmacSigner.SignedContent signed = InternalHmacSigner.sign(
                "1787625000",
                "nonce",
                "GET",
                "/internal/v1/capabilities",
                null,
                "test-secret".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(signed.bodySha256())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
