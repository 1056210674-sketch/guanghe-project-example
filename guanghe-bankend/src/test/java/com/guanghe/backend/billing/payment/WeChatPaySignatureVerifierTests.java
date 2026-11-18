package com.guanghe.backend.billing.payment;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Callback authentication, exercised with locally generated keys.
 *
 * <p>These tests deliberately do not touch WeChat Pay: they generate a throwaway RSA key pair and
 * sign the same canonical string WeChat signs. What is being established is that the verifier cannot
 * be talked into accepting a message that was not signed by the holder of the private key — which is
 * exactly the property that makes the callback boundary safe to expose unauthenticated.
 */
class WeChatPaySignatureVerifierTests {

    private static final String TIMESTAMP = "1787000000";
    private static final String NONCE = "abcdefghijkl";
    private static final String BODY = "{\"id\":\"evt-1\",\"resource\":{\"ciphertext\":\"...\"}}";
    private static final String SERIAL = "4a3f1c2b9d";
    private static final BigInteger PLATFORM_SERIAL = new BigInteger(SERIAL, 16);

    private static KeyPair platformKeyPair;
    private static KeyPair attackerKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        platformKeyPair = generator.generateKeyPair();
        attackerKeyPair = generator.generateKeyPair();
    }

    /** The platform certificate this verifier holds is the one whose serial is {@link #SERIAL}. */
    private static WeChatPaySignatureVerifier verifier() {
        return new WeChatPaySignatureVerifier(platformKeyPair.getPublic(), PLATFORM_SERIAL);
    }

    @Test
    void aSignatureOverTheCanonicalStringIsAccepted() throws Exception {
        WeChatPaySignatureVerifier verifier = verifier();

        assertThat(verifier.available()).isTrue();
        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, sign(platformKeyPair, TIMESTAMP, NONCE, BODY),
                SERIAL)).isTrue();
    }

    @Test
    void aTamperedBodyIsRejected() throws Exception {
        WeChatPaySignatureVerifier verifier = verifier();
        String signature = sign(platformKeyPair, TIMESTAMP, NONCE, BODY);

        assertThat(verifier.verify(TIMESTAMP, NONCE,
                "{\"id\":\"evt-1\",\"resource\":{\"ciphertext\":\"...\"},\"amount\":{\"total\":1}}",
                signature, SERIAL)).isFalse();
    }

    @Test
    void aTamperedTimestampOrNonceIsRejected() throws Exception {
        WeChatPaySignatureVerifier verifier = verifier();
        String signature = sign(platformKeyPair, TIMESTAMP, NONCE, BODY);

        assertThat(verifier.verify("1787000001", NONCE, BODY, signature, SERIAL)).isFalse();
        assertThat(verifier.verify(TIMESTAMP, "abcdefghijkm", BODY, signature, SERIAL)).isFalse();
    }

    @Test
    void aSignatureFromAnotherKeyIsRejected() throws Exception {
        WeChatPaySignatureVerifier verifier = verifier();

        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY,
                sign(attackerKeyPair, TIMESTAMP, NONCE, BODY), SERIAL)).isFalse();
    }

    @Test
    void aMalformedSignatureIsAnAuthenticationFailureNotAServerFault() {
        WeChatPaySignatureVerifier verifier = verifier();

        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, "not-base64!!", SERIAL)).isFalse();
        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, "", SERIAL)).isFalse();
    }

    @Test
    void aMissingHeaderIsRefusedRatherThanTreatedAsUnsigned() {
        WeChatPaySignatureVerifier verifier = verifier();

        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, NONCE, BODY, null, SERIAL))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    // ------------------------------------------------------- platform certificate rotation

    /**
     * WeChat rotates its platform certificate, and the old one keeps signing for a while. An
     * otherwise perfect signature must still be refused when the message names a certificate this
     * deployment does not hold.
     */
    @Test
    void aSignatureIsRefusedWhenThePlatformSerialIsNotTheOneWeHold() throws Exception {
        WeChatPaySignatureVerifier verifier = verifier();
        String signature = sign(platformKeyPair, TIMESTAMP, NONCE, BODY);

        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, "DEADBEEF")).isFalse();
        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, SERIAL + "0")).isFalse();
    }

    @Test
    void aMissingOrUnparseablePlatformSerialIsRefused() throws Exception {
        WeChatPaySignatureVerifier verifier = verifier();
        String signature = sign(platformKeyPair, TIMESTAMP, NONCE, BODY);

        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, null)).isFalse();
        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, "")).isFalse();
        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, "   ")).isFalse();
        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, "not-hex")).isFalse();
    }

    @Test
    void thePlatformSerialIsMatchedCaseInsensitivelyAsHex() throws Exception {
        WeChatPaySignatureVerifier verifier = verifier();
        String signature = sign(platformKeyPair, TIMESTAMP, NONCE, BODY);

        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, SERIAL.toUpperCase()))
                .isTrue();
        assertThat(verifier.verify(TIMESTAMP, NONCE, BODY, signature, "004a3f1c2b9d")).isTrue();
    }

    @Test
    void withNoCertificateVerificationIsUnavailableAndFailsClosed() {
        WeChatPaySignatureVerifier verifier = new WeChatPaySignatureVerifier(new WeChatPayProperties());

        assertThat(verifier.available()).isFalse();
        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, NONCE, BODY, "anything", SERIAL))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }

    @Test
    void anUnreadableCertificateLeavesTheVerifierUnavailableRatherThanHalfConfigured() {
        WeChatPayProperties properties = new WeChatPayProperties();
        properties.setPlatformCertificate("-----BEGIN CERTIFICATE-----\nnot a certificate\n"
                + "-----END CERTIFICATE-----");

        WeChatPaySignatureVerifier verifier = new WeChatPaySignatureVerifier(properties);

        assertThat(verifier.available()).isFalse();
        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, NONCE, BODY, "anything", SERIAL))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }

    private static String sign(KeyPair keyPair, String timestamp, String nonce, String body)
            throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update((timestamp + '\n' + nonce + '\n' + body + '\n')
                .getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
