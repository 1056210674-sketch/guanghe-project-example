package com.guanghe.backend.integration.internal.security;

public record InternalCallerIdentity(String keyId) {

    public static final String REQUEST_ATTRIBUTE = InternalCallerIdentity.class.getName();
}
