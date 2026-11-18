package com.guanghe.backend.integration.internal.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class InternalHmacSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private InternalHmacSigner() {
    }

    public static SignedContent sign(
            String timestamp,
            String nonce,
            String method,
            String rawPathWithQuery,
            byte[] body,
            byte[] secret
    ) {
        requireText(timestamp, "timestamp");
        requireText(nonce, "nonce");
        requireText(method, "method");
        requireText(rawPathWithQuery, "rawPathWithQuery");
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }

        byte[] requestBody = body == null ? new byte[0] : body;
        String bodySha256 = sha256Hex(requestBody);
        String canonical = canonicalText(timestamp, nonce, method, rawPathWithQuery, bodySha256);
        return new SignedContent(bodySha256, hmacBase64(canonical, secret));
    }

    public static boolean signatureMatches(String expectedBase64, String actualBase64) {
        if (expectedBase64 == null || actualBase64 == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedBase64.getBytes(StandardCharsets.US_ASCII),
                actualBase64.getBytes(StandardCharsets.US_ASCII)
        );
    }

    static String canonicalText(
            String timestamp,
            String nonce,
            String method,
            String rawPathWithQuery,
            String bodySha256
    ) {
        return String.join(
                "\n",
                timestamp,
                nonce,
                method.toUpperCase(Locale.ROOT),
                rawPathWithQuery,
                bodySha256
        );
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String hmacBase64(String canonical, byte[] secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record SignedContent(String bodySha256, String signature) {
    }
}
