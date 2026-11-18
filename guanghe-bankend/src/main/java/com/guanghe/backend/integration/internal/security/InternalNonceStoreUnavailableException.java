package com.guanghe.backend.integration.internal.security;

public class InternalNonceStoreUnavailableException extends RuntimeException {

    public InternalNonceStoreUnavailableException(Throwable cause) {
        super("Internal nonce store is unavailable", cause);
    }

    public InternalNonceStoreUnavailableException() {
        super("Internal nonce store is unavailable");
    }
}
