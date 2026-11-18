package com.guanghe.backend.auth.account;

public record AccountIdentity(
        long id,
        String username,
        String phoneE164,
        int tokenVersion,
        AccountRole role,
        AccountStatus status,
        String displayName,
        String email,
        String credentialState
) {
    public AccountIdentity(long id, String username, String phoneE164, int tokenVersion,
                           AccountRole role, AccountStatus status, String displayName, String email) {
        this(id, username, phoneE164, tokenVersion, role, status, displayName, email, "ACTIVE");
    }
}
