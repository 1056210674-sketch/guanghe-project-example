package com.guanghe.backend.billing.payment;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies WeChat Pay v3 callback signatures with the platform certificate.
 *
 * <p>WeChat signs {@code timestamp\nnonce\nbody\n} with RSA-SHA256. The certificate is parsed once at
 * construction; if it is absent or unreadable the verifier reports itself unavailable and every
 * verification fails closed, so an unverifiable callback can never be treated as authentic.
 *
 * <p>WeChat rotates its platform certificate. A signature only counts as authentic when the
 * {@code Wechatpay-Serial} header names the serial of the certificate this verifier actually holds,
 * so a message signed by a retired or sibling platform certificate — which the public key alone
 * would happily accept — is refused.
 *
 * <p>No credential, signature or payload is logged.
 */
public class WeChatPaySignatureVerifier implements PaymentCallbackSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WeChatPaySignatureVerifier.class);
    private static final String TRANSFORMATION = "SHA256withRSA";

    private final PublicKey platformKey;
    private final BigInteger platformSerialNumber;

    public WeChatPaySignatureVerifier(WeChatPayProperties properties) {
        X509Certificate certificate = parseCertificate(properties.getPlatformCertificate());
        this.platformKey = certificate == null ? null : certificate.getPublicKey();
        this.platformSerialNumber = certificate == null ? null : certificate.getSerialNumber();
    }

    /** Test seam: build a verifier around an already-parsed key pair. */
    WeChatPaySignatureVerifier(PublicKey platformKey) {
        this(platformKey, null);
    }

    /** Test seam for exercising the platform-certificate serial check. */
    WeChatPaySignatureVerifier(PublicKey platformKey, BigInteger platformSerialNumber) {
        this.platformKey = platformKey;
        this.platformSerialNumber = platformSerialNumber;
    }

    @Override
    public boolean available() {
        return platformKey != null;
    }

    @Override
    public boolean verify(String timestamp, String nonce, String rawBody, String signature,
                          String serialNumber) {
        if (platformKey == null) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        if (timestamp == null || nonce == null || rawBody == null || signature == null) {
            throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        }
        // Checked before the signature so a rotation mismatch is refused for the right reason and
        // never wastes an RSA verification on a certificate we do not hold.
        if (!matchesPlatformSerial(serialNumber)) return false;
        try {
            Signature verifier = Signature.getInstance(TRANSFORMATION);
            verifier.initVerify(platformKey);
            verifier.update((timestamp + '\n' + nonce + '\n' + rawBody + '\n')
                    .getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (IllegalArgumentException | SignatureException exception) {
            // Malformed base64, or a signature of the wrong length, is an authentication failure
            // rather than a server fault: the message simply is not authentic.
            return false;
        } catch (Exception exception) {
            log.warn("WeChat Pay callback signature verification could not be performed: {}",
                    exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        }
    }

    /** WeChat Pay sends the platform certificate serial as a hexadecimal string. */
    private boolean matchesPlatformSerial(String serialNumber) {
        if (platformSerialNumber == null || serialNumber == null || serialNumber.isBlank()) {
            return false;
        }
        try {
            return platformSerialNumber.equals(new BigInteger(serialNumber, 16));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static X509Certificate parseCertificate(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            // A malformed certificate must leave the provider unconfigured, never half-configured.
            log.error("Configured WeChat Pay platform certificate could not be parsed: {}",
                    exception.getClass().getSimpleName());
            return null;
        }
    }
}
