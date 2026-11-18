package com.guanghe.backend.integration.internal.client;

import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import com.guanghe.backend.integration.internal.security.InternalHmacSigner;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "zhixiaohe.internal.enabled", havingValue = "true", matchIfMissing = true)
public class InternalHmacRequestSigner {

    private final InternalCommunicationProperties properties;
    private final Clock clock;

    public InternalHmacRequestSigner(
            InternalCommunicationProperties properties,
            @Qualifier("internalCommunicationClock") Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
    }

    public InternalHmacRequestHeaders sign(String method, String rawPathWithQuery, byte[] body) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        InternalHmacSigner.SignedContent signed = InternalHmacSigner.sign(
                timestamp,
                nonce,
                method,
                rawPathWithQuery,
                body,
                properties.secretBytes()
        );
        return new InternalHmacRequestHeaders(
                properties.getKeyId(),
                timestamp,
                nonce,
                signed.bodySha256(),
                signed.signature(),
                TraceIdHolder.outboundTraceparent()
        );
    }
}
