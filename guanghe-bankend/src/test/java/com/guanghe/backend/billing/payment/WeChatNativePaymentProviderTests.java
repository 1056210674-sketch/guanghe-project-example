package com.guanghe.backend.billing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The WeChat Pay provider boundary.
 *
 * <p>No merchant credential and no network call is involved anywhere in this class: the RSA key pair
 * is generated in-process and the AES key is a test constant. What is being established is that the
 * provider fails closed at every step, and that a fully configured provider still refuses to
 * fabricate a QR code, a provider order number or a payment success.
 */
class WeChatNativePaymentProviderTests {

    private static final long EPOCH_SECONDS = 1787000000L;
    private static final String TIMESTAMP = Long.toString(EPOCH_SECONDS);
    private static final String CALLBACK_NONCE = "callbacknonce";
    private static final String RESOURCE_NONCE = "resourcenonce";
    private static final String ASSOCIATED_DATA = "transaction";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    /** The serial of the platform certificate the verifier under test holds. */
    private static final String PLATFORM_SERIAL = "4a3f1c2b9d";
    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(EPOCH_SECONDS), ZoneOffset.UTC);

    private static KeyPair platformKeyPair;

    private WeChatPayProperties properties;
    private WeChatNativePaymentProvider provider;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        platformKeyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        properties = new WeChatPayProperties();
        provider = new WeChatNativePaymentProvider(properties, platformVerifier(), new ObjectMapper(),
                CLOCK);
    }

    private static WeChatPaySignatureVerifier platformVerifier() {
        return new WeChatPaySignatureVerifier(platformKeyPair.getPublic(),
                new BigInteger(PLATFORM_SERIAL, 16));
    }

    // ---------------------------------------------------------------- fail closed

    @Test
    void anUnconfiguredProviderNamesEveryMissingPrerequisiteAndNoValue() {
        PaymentCapability capability = provider.capability();

        assertThat(capability.provider()).isEqualTo(WeChatNativePaymentProvider.CODE);
        assertThat(capability.configured()).isFalse();
        assertThat(capability.payable()).isFalse();
        assertThat(capability.status()).isEqualTo(PaymentCapability.NOT_CONFIGURED);
        assertThat(capability.missingRequirements()).containsExactly("enabled", "merchantId", "appId",
                "apiV3Key", "merchantSerialNumber", "privateKey", "platformCertificate", "notifyUrl");
    }

    @Test
    void aPartlyConfiguredProviderStillReportsNotConfiguredAndLeaksNoValue() {
        properties.setMerchantId("1900000109");
        properties.setApiV3Key(API_V3_KEY);

        PaymentCapability capability = provider.capability();

        // Half-configured is not configured: the provider must not become partly usable.
        assertThat(capability.configured()).isFalse();
        assertThat(capability.payable()).isFalse();
        assertThat(capability.status()).isEqualTo(PaymentCapability.NOT_CONFIGURED);
        assertThat(capability.missingRequirements()).doesNotContain("merchantId", "apiV3Key")
                .contains("enabled", "privateKey", "notifyUrl", "platformCertificate");
        // The capability is rendered verbatim in the browser, so it must never carry a value.
        assertThat(capability.toString()).doesNotContain("1900000109").doesNotContain(API_V3_KEY);
        assertThat(properties.toString()).doesNotContain("1900000109").doesNotContain(API_V3_KEY)
                .contains("<set>");
    }

    @Test
    void anUnconfiguredProviderRefusesToCreateAnOrderRatherThanFabricatingAQrCode() {
        assertThatThrownBy(() -> provider.createPrepay(new PaymentProvider.PrepayCommand(9001L,
                "R9001", 10_000L, "光禾AI智能体框架积分充值", OffsetDateTime.now(CLOCK))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }

    @Test
    void anUnconfiguredProviderRefusesToQueryAnOrder() {
        assertThatThrownBy(() -> provider.queryOrder("STUB-ORDER-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }

    @Test
    void evenAFullyConfiguredProviderKeepsTheLiveOrderPathDisabled() {
        fullyConfigure();

        PaymentCapability capability = provider.capability();
        assertThat(capability.configured()).isTrue();
        // Credentials alone are not permission to take money.
        assertThat(capability.payable()).isFalse();
        assertThat(capability.status()).isEqualTo(PaymentCapability.NOT_IMPLEMENTED);
        assertThat(capability.missingRequirements()).containsExactly("liveOrderCreation");

        assertThatThrownBy(() -> provider.createPrepay(new PaymentProvider.PrepayCommand(9001L,
                "R9001", 10_000L, "光禾AI智能体框架积分充值", OffsetDateTime.now(CLOCK))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        assertThatThrownBy(() -> provider.queryOrder("STUB-ORDER-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
    }

    // ---------------------------------------------------------------- callback authentication

    @Test
    void aCallbackMissingItsSignatureHeadersIsRefused() {
        assertThatThrownBy(() -> provider.verifyCallback(Map.of(), "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    @Test
    void aCallbackWithAStaleTimestampIsRefused() {
        String body = "{}";
        String stale = Long.toString(EPOCH_SECONDS - properties.getCallbackTolerance().toSeconds() - 1);
        Map<String, String> headers = headers(stale, CALLBACK_NONCE, "signature");

        assertThatThrownBy(() -> provider.verifyCallback(headers, body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    @Test
    void aCallbackThatFailsSignatureVerificationIsRefused() throws Exception {
        String body = callbackBody("SUCCESS", 10_000L);
        // Signed by a key the platform does not hold.
        String forged = signOtherKey(TIMESTAMP, CALLBACK_NONCE, body);

        assertThatThrownBy(() -> provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, forged), body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    @Test
    void aCorrectlySignedButAlteredCallbackIsRefused() throws Exception {
        String signed = callbackBody("SUCCESS", 10_000L);
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, signed);
        String altered = callbackBody("SUCCESS", 999_999L);

        assertThatThrownBy(() -> provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature), altered))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    @Test
    void aCallbackIsRefusedWhenVerificationIsImpossible() {
        // No platform certificate: an unverifiable callback must never be treated as authentic.
        WeChatNativePaymentProvider unverifiable = new WeChatNativePaymentProvider(properties,
                new WeChatPaySignatureVerifier(new WeChatPayProperties()), new ObjectMapper(), CLOCK);

        assertThatThrownBy(() -> unverifiable.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, "signature"), "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }

    @Test
    void aCallbackCannotBeDecryptedWithoutAnApiV3Key() throws Exception {
        String body = callbackBody("SUCCESS", 10_000L);
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, body);
        // Signature verifies, but there is no key to open the resource with.
        assertThatThrownBy(() -> provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature), body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void anAuthenticCallbackIsReducedToNonSensitiveFieldsOnly() throws Exception {
        properties.setApiV3Key(API_V3_KEY);
        String body = callbackBody("SUCCESS", 10_000L);
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, body);

        PaymentProvider.CallbackEnvelope envelope = provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature), body);

        assertThat(envelope.providerEventId()).isEqualTo("evt-1");
        assertThat(envelope.providerOrderNo()).isEqualTo("R9001");
        assertThat(envelope.amountCents()).isEqualTo(10_000L);
        assertThat(envelope.tradeState()).isEqualTo(PaymentProvider.TradeState.SUCCESS);
        assertThat(envelope.tradeState().isSettled()).isTrue();
        assertThat(envelope.paidAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-18T09:59:00+08:00"));
        assertThat(envelope.payloadSha256()).isEqualTo(sha256(body));
        // The buyer identifiers in the decrypted payload must not survive the reduction.
        assertThat(envelope.summaryJson()).doesNotContain("openid").doesNotContain("payer")
                .doesNotContain("4200001234");
        assertThat(envelope.toString()).doesNotContain(API_V3_KEY).doesNotContain("ciphertext");
    }

    @Test
    void signatureHeadersAreMatchedCaseInsensitively() throws Exception {
        properties.setApiV3Key(API_V3_KEY);
        String body = callbackBody("SUCCESS", 10_000L);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("WeChatPay-Timestamp", TIMESTAMP);
        headers.put("WeChatPay-Nonce", CALLBACK_NONCE);
        headers.put("WeChatPay-Signature", sign(TIMESTAMP, CALLBACK_NONCE, body));
        headers.put("WeChatPay-Serial", PLATFORM_SERIAL);

        assertThat(provider.verifyCallback(headers, body).providerEventId()).isEqualTo("evt-1");
    }

    /**
     * WeChat keeps signing with the previous platform certificate while it rotates. A message that
     * names a certificate this deployment does not hold is not ours to accept, however valid its
     * signature looks against the key we have.
     */
    @Test
    void aCallbackNamingAnotherPlatformCertificateIsRefused() throws Exception {
        properties.setApiV3Key(API_V3_KEY);
        String body = callbackBody("SUCCESS", 10_000L);
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, body);

        assertThatThrownBy(() -> provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature, "DEADBEEF"), body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
        assertThatThrownBy(() -> provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature, ""), body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    /**
     * A certificate that is present but unusable is not a configured provider: without it no
     * callback can ever be authenticated, so recharge must report itself unavailable rather than
     * merely not implemented.
     */
    @Test
    void anUnusablePlatformCertificateLeavesTheProviderNotConfigured() {
        fullyConfigure();
        WeChatPayProperties unusable = new WeChatPayProperties();
        unusable.setEnabled(true);
        unusable.setMerchantId(properties.getMerchantId());
        unusable.setAppId(properties.getAppId());
        unusable.setApiV3Key(properties.getApiV3Key());
        unusable.setMerchantSerialNumber(properties.getMerchantSerialNumber());
        unusable.setPrivateKey(properties.getPrivateKey());
        unusable.setNotifyUrl(properties.getNotifyUrl());
        unusable.setPlatformCertificate("-----BEGIN CERTIFICATE-----\nnot a certificate\n"
                + "-----END CERTIFICATE-----");

        WeChatNativePaymentProvider broken = new WeChatNativePaymentProvider(unusable,
                new WeChatPaySignatureVerifier(unusable), new ObjectMapper(), CLOCK);

        PaymentCapability capability = broken.capability();
        assertThat(capability.configured()).isFalse();
        assertThat(capability.payable()).isFalse();
        assertThat(capability.callbackVerifiable()).isFalse();
        assertThat(capability.status()).isEqualTo(PaymentCapability.NOT_CONFIGURED);
        assertThat(capability.missingRequirements()).containsExactly("platformCertificate");
    }

    @Test
    void aTerminalFailureStateIsMappedFaithfullySoTheOrderIsNotCredited() throws Exception {
        properties.setApiV3Key(API_V3_KEY);
        String body = callbackBody("CLOSED", 10_000L);
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, body);

        PaymentProvider.CallbackEnvelope envelope = provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature), body);

        assertThat(envelope.tradeState()).isEqualTo(PaymentProvider.TradeState.CLOSED);
        assertThat(envelope.tradeState().isSettled()).isFalse();
        assertThat(envelope.tradeState().isTerminal()).isTrue();
    }

    @Test
    void anUnrecognisedTradeStateIsNeverTreatedAsSuccess() throws Exception {
        properties.setApiV3Key(API_V3_KEY);
        String body = callbackBody("SOMETHING_NEW", 10_000L);
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, body);

        PaymentProvider.CallbackEnvelope envelope = provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature), body);

        assertThat(envelope.tradeState()).isEqualTo(PaymentProvider.TradeState.UNKNOWN);
        assertThat(envelope.tradeState().isSettled()).isFalse();
        assertThat(envelope.tradeState().isTerminal()).isFalse();
    }

    @Test
    void anAuthenticCallbackWhosePayloadIsIncompleteIsRefused() throws Exception {
        properties.setApiV3Key(API_V3_KEY);
        String plain = "{\"out_trade_no\":\"R9001\",\"amount\":{\"total\":10000}}";
        String body = envelope("evt-1", plain);
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, body);

        assertThatThrownBy(() -> provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature), body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    @Test
    void aCallbackWhoseCiphertextIsNotDecryptableIsRefused() throws Exception {
        properties.setApiV3Key(API_V3_KEY);
        String body = "{\"id\":\"evt-1\",\"resource\":{\"ciphertext\":\"AAAAAAAAAAAAAAAAAAAAAA==\","
                + "\"nonce\":\"" + RESOURCE_NONCE + "\",\"associated_data\":\"" + ASSOCIATED_DATA
                + "\"}}";
        String signature = sign(TIMESTAMP, CALLBACK_NONCE, body);

        assertThatThrownBy(() -> provider.verifyCallback(
                headers(TIMESTAMP, CALLBACK_NONCE, signature), body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
    }

    // ---------------------------------------------------------------- helpers

    private void fullyConfigure() {
        properties.setEnabled(true);
        properties.setMerchantId("1900000109");
        properties.setAppId("wx0123456789abcdef");
        properties.setApiV3Key(API_V3_KEY);
        properties.setMerchantSerialNumber("MERCHANT-SERIAL-1");
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----\nplaceholder\n-----END PRIVATE KEY-----");
        properties.setPlatformCertificate(
                "-----BEGIN CERTIFICATE-----\nplaceholder\n-----END CERTIFICATE-----");
        properties.setNotifyUrl("https://example.invalid/api/v1/callbacks/payment/notify");
    }

    private static Map<String, String> headers(String timestamp, String nonce, String signature,
                                               String serial) {
        return Map.of(
                "wechatpay-timestamp", timestamp,
                "wechatpay-nonce", nonce,
                "wechatpay-signature", signature,
                "wechatpay-serial", serial);
    }

    private static Map<String, String> headers(String timestamp, String nonce, String signature) {
        return headers(timestamp, nonce, signature, PLATFORM_SERIAL);
    }

    /** A signed callback whose resource carries the given transaction and amount. */
    private static String callbackBody(String tradeState, long amountCents) throws Exception {
        String plain = "{\"out_trade_no\":\"R9001\",\"transaction_id\":\"4200001234\","
                + "\"trade_state\":\"" + tradeState + "\",\"success_time\":"
                + "\"2026-09-18T09:59:00+08:00\",\"payer\":{\"openid\":\"oBuyerIdentifiers\"},"
                + "\"amount\":{\"total\":" + amountCents + "}}";
        return envelope("evt-1", plain);
    }

    private static String envelope(String eventId, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, RESOURCE_NONCE.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(ASSOCIATED_DATA.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder()
                .encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        return "{\"id\":\"" + eventId + "\",\"resource\":{\"ciphertext\":\"" + ciphertext
                + "\",\"nonce\":\"" + RESOURCE_NONCE + "\",\"associated_data\":\""
                + ASSOCIATED_DATA + "\"}}";
    }

    private static String sign(String timestamp, String nonce, String body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(platformKeyPair.getPrivate());
        signer.update((timestamp + '\n' + nonce + '\n' + body + '\n')
                .getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String signOtherKey(String timestamp, String nonce, String body) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(generator.generateKeyPair().getPrivate());
        signer.update((timestamp + '\n' + nonce + '\n' + body + '\n')
                .getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
