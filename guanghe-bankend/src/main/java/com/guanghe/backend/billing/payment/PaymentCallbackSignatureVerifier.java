package com.guanghe.backend.billing.payment;

/**
 * Verifies the authenticity of an inbound provider callback.
 *
 * <p>Split out from {@link PaymentProvider} so the callback boundary's timestamp, nonce, ownership,
 * amount and replay logic can be exercised without merchant credentials, while signature
 * verification itself stays fail-closed.
 */
public interface PaymentCallbackSignatureVerifier {

    /**
     * @return {@code true} only when the signature is cryptographically valid for this payload
     */
    boolean verify(String timestamp, String nonce, String rawBody, String signature, String serialNumber);

    /** Whether verification is possible at all right now. */
    boolean available();
}
