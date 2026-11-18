package com.guanghe.backend.billing.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WeChat Pay Native provider.
 *
 * <p>This build implements the <em>control plane</em> only. It never contacts WeChat Pay: with no
 * merchant id, certificate, key or callback address every operation fails closed, and even when
 * fully configured the live order-creation call stays disabled behind an explicit switch. It
 * therefore never returns a synthesised QR code, a synthesised provider order number or a
 * synthesised success.
 *
 * <p>What <em>is</em> implemented for real is callback authentication: header, timestamp and nonce
 * checks, RSA-SHA256 signature verification against the platform certificate, and AES-256-GCM
 * decryption of the callback resource. Those paths are exercised by tests with locally generated
 * keys, so they do not depend on live merchant credentials.
 *
 * <p>No key, signature, token or raw callback body is ever logged or persisted.
 */
public class WeChatNativePaymentProvider implements PaymentProvider {

    public static final String CODE = "WECHAT_NATIVE";
    private static final Logger log = LoggerFactory.getLogger(WeChatNativePaymentProvider.class);

    private static final String HEADER_TIMESTAMP = "wechatpay-timestamp";
    private static final String HEADER_NONCE = "wechatpay-nonce";
    private static final String HEADER_SIGNATURE = "wechatpay-signature";
    private static final String HEADER_SERIAL = "wechatpay-serial";
    private static final int GCM_TAG_BITS = 128;

    private final WeChatPayProperties properties;
    private final PaymentCallbackSignatureVerifier signatures;
    private final ObjectMapper json;
    private final Clock clock;

    public WeChatNativePaymentProvider(WeChatPayProperties properties,
                                       PaymentCallbackSignatureVerifier signatures,
                                       ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.signatures = signatures;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public PaymentCapability capability() {
        List<String> missing = properties.missingRequirements();
        if (!missing.isEmpty()) return PaymentCapability.notConfigured(CODE, missing);
        if (!signatures.available()) {
            // The certificate is present but unusable. Without it no callback can be verified, so
            // the provider is still not configured — a payment must never be taken on the strength
            // of credentials we cannot authenticate a result with.
            return PaymentCapability.notConfigured(CODE, List.of("platformCertificate"));
        }
        // Credentials are present, but live order creation is deliberately not enabled in this
        // build. Reporting READY here would be a lie, so the reason is named explicitly.
        return new PaymentCapability(CODE, true, false, true, PaymentCapability.NOT_IMPLEMENTED,
                List.of("liveOrderCreation"));
    }

    @Override
    public PrepayTicket createPrepay(PrepayCommand command) {
        requireConfigured();
        throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE,
                "微信支付下单能力尚未启用，请联系管理员", Map.of("provider", CODE));
    }

    @Override
    public ProviderOrderSnapshot queryOrder(String providerOrderNo) {
        requireConfigured();
        throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE,
                "微信支付查单能力尚未启用，请联系管理员", Map.of("provider", CODE));
    }

    @Override
    public CallbackEnvelope verifyCallback(Map<String, String> headers, String rawBody) {
        String timestamp = header(headers, HEADER_TIMESTAMP);
        String nonce = header(headers, HEADER_NONCE);
        String signature = header(headers, HEADER_SIGNATURE);
        String serial = header(headers, HEADER_SERIAL);
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature) || isBlank(rawBody)) {
            throw invalid("缺少必需的签名请求头");
        }
        requireFreshTimestamp(timestamp);
        if (!signatures.available()) {
            // An unverifiable callback must never be accepted as authentic.
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                    "支付回调验签能力尚未配置", Map.of("provider", CODE));
        }
        if (!signatures.verify(timestamp, nonce, rawBody, signature, serial)) {
            throw invalid("回调签名校验失败");
        }
        return decrypt(rawBody);
    }

    /** Rejects callbacks outside the configured clock-skew window. */
    private void requireFreshTimestamp(String timestamp) {
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            throw invalid("回调时间戳格式非法");
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(seconds), clock.instant()).abs();
        if (skew.compareTo(properties.getCallbackTolerance()) > 0) throw invalid("回调时间戳超出允许范围");
    }

    /**
     * Decrypts the AES-256-GCM {@code resource} envelope. Only a summary and a hash leave this
     * method; the decrypted payload is never stored.
     */
    private CallbackEnvelope decrypt(String rawBody) {
        if (properties.getApiV3Key().isBlank()) {
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                    "支付回调解密能力尚未配置", Map.of("provider", CODE));
        }
        try {
            JsonNode root = json.readTree(rawBody);
            JsonNode resource = root.path("resource");
            String ciphertext = text(resource, "ciphertext");
            String nonce = text(resource, "nonce");
            String associatedData = text(resource, "associated_data");
            if (isBlank(ciphertext) || isBlank(nonce)) throw invalid("回调 resource 不完整");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(properties.getApiV3Key().getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            String plain = new String(
                    cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
            return reduce(text(root, "id"), plain, rawBody);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("WeChat Pay callback could not be decrypted: {}",
                    exception.getClass().getSimpleName());
            throw invalid("回调解密失败");
        }
    }

    /** Reduces a decrypted transaction to non-sensitive fields. */
    private CallbackEnvelope reduce(String eventId, String plain, String rawBody) {
        try {
            JsonNode transaction = json.readTree(plain);
            String providerOrderNo = text(transaction, "out_trade_no");
            String tradeState = text(transaction, "trade_state");
            String transactionId = text(transaction, "transaction_id");
            String successTime = text(transaction, "success_time");
            JsonNode amountNode = transaction.path("amount").path("total");
            if (isBlank(providerOrderNo) || isBlank(tradeState) || !amountNode.isNumber()) {
                throw invalid("回调缺少订单号、交易状态或金额");
            }
            long amountCents = amountNode.asLong();
            OffsetDateTime paidAt = successTime == null ? null : OffsetDateTime.parse(successTime);
            String summary = "{\"tradeState\":\"" + tradeState.replace("\"", "")
                    + "\",\"amountCents\":" + amountCents + ",\"providerOrderNo\":\""
                    + providerOrderNo.replace("\"", "") + "\"}";
            String providerEventId = isBlank(eventId) ? transactionId : eventId;
            return new CallbackEnvelope(providerEventId, providerOrderNo, amountCents,
                    mapTradeState(tradeState), paidAt, sha256(rawBody), summary);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("WeChat Pay callback payload could not be interpreted: {}",
                    exception.getClass().getSimpleName());
            throw invalid("回调内容格式非法");
        }
    }

    private static TradeState mapTradeState(String state) {
        return switch (state) {
            case "SUCCESS" -> TradeState.SUCCESS;
            case "NOTPAY" -> TradeState.NOTPAY;
            case "USERPAYING" -> TradeState.USERPAYING;
            case "CLOSED" -> TradeState.CLOSED;
            case "REVOKED" -> TradeState.REVOKED;
            case "PAYERROR" -> TradeState.PAYERROR;
            case "REFUND" -> TradeState.REFUND;
            default -> TradeState.UNKNOWN;
        };
    }

    private void requireConfigured() {
        List<String> missing = properties.missingRequirements();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                    "支付能力尚未配置", Map.of("provider", CODE, "missing", missing));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID, message, Map.of());
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        String direct = headers.get(name);
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
