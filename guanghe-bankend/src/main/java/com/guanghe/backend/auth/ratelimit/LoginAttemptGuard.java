package com.guanghe.backend.auth.ratelimit;

public interface LoginAttemptGuard {

    void checkAllowed(String usernameNormalized, String sourceIp);

    boolean recordFailure(String usernameNormalized, String sourceIp);

    void clear(String usernameNormalized, String sourceIp);
}
