package com.guanghe.backend.auth.security;

import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;

public record AuthenticatedAccount(
        long id,
        String username,
        AccountRole role,
        int tokenVersion,
        AccountStatus status,
        String phoneE164,
        String displayName,
        String email,
        String credentialState
) {
    public AuthenticatedAccount(long id, String username, AccountRole role, int tokenVersion,
                                AccountStatus status, String phoneE164, String displayName, String email) {
        this(id, username, role, tokenVersion, status, phoneE164, displayName, email, "ACTIVE");
    }
    public static AuthenticatedAccount from(AccountIdentity account) {
        return new AuthenticatedAccount(
                account.id(), account.username(), account.role(), account.tokenVersion(), account.status(),
                account.phoneE164(), account.displayName(), account.email(), account.credentialState()
        );
    }

    @Override
    public String toString() {
        return "AuthenticatedAccount[id=" + id + ", username=" + username
                + ", role=" + role + ", tokenVersion=" + tokenVersion + "]";
    }
}
