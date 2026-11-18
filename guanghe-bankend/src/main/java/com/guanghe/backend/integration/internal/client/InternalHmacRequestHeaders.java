package com.guanghe.backend.integration.internal.client;

public record InternalHmacRequestHeaders(
        String keyId,
        String timestamp,
        String nonce,
        String contentSha256,
        String signature,
        String traceparent
) {
}
