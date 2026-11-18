package com.guanghe.backend.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TaskConfirmationTokenCodec {
    private final byte[] secret;

    public TaskConfirmationTokenCodec(PaidTaskProperties properties) {
        secret = properties.getTokenSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String token(TaskRecords.Draft draft) {
        String value = "confirm|" + draft.id() + "|" + draft.userId() + "|" + draft.inputHash()
                + "|" + draft.pricingVersionId() + "|" + draft.expiresAt().toEpochSecond();
        return "v1." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(value));
    }

    public String workflowToken(long planId, long userId, int version, int maxPointCost,
                                OffsetDateTime expiresAt) {
        String value = "workflow-confirm|" + planId + "|" + userId + "|" + version
                + "|" + maxPointCost + "|" + expiresAt.toEpochSecond();
        return "v1." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(value));
    }

    public String workflowExecutionToken(long executionId, long planId, long userId,
                                         int executionNo, String workerId) {
        String value = "workflow-execution|" + executionId + "|" + planId + "|" + userId
                + "|" + executionNo + "|" + workerId;
        return "v1." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(value));
    }

    public String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
