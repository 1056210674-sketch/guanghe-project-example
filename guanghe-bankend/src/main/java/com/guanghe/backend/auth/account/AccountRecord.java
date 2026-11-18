package com.guanghe.backend.auth.account;

import java.time.OffsetDateTime;

public record AccountRecord(
        long id,
        String username,
        String usernameNormalized,
        String phoneE164,
        String passwordHash,
        String passwordAlgorithm,
        int tokenVersion,
        AccountRole role,
        AccountStatus status,
        String displayName,
        String email,
        String credentialState,
        OffsetDateTime createdAt
) {
    public AccountRecord(long id, String username, String usernameNormalized, String phoneE164,
                         String passwordHash, String passwordAlgorithm, int tokenVersion,
                         AccountRole role, AccountStatus status, String displayName, String email,
                         String credentialState) {
        this(id, username, usernameNormalized, phoneE164, passwordHash, passwordAlgorithm,
                tokenVersion, role, status, displayName, email, credentialState, null);
    }

    public AccountRecord(long id, String username, String usernameNormalized, String phoneE164,
                         String passwordHash, String passwordAlgorithm, int tokenVersion,
                         AccountRole role, AccountStatus status, String displayName, String email) {
        this(id, username, usernameNormalized, phoneE164, passwordHash, passwordAlgorithm,
                tokenVersion, role, status, displayName, email, "ACTIVE", null);
    }
    @Override
    public String toString() {
        return "AccountRecord[id=" + id + ", username=" + username
                + ", role=" + role + ", status=" + status + ", tokenVersion=" + tokenVersion + "]";
    }
}
