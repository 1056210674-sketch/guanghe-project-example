package com.guanghe.backend.integration.internal.security;

import java.time.Duration;

public interface InternalNonceStore {

    boolean claim(String keyId, String nonce, Duration ttl);
}
