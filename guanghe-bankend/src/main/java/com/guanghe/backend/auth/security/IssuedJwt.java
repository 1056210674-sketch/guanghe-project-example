package com.guanghe.backend.auth.security;

import java.time.Instant;

public record IssuedJwt(String value, Instant expiresAt) {
    @Override
    public String toString() {
        return "IssuedJwt[value=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
