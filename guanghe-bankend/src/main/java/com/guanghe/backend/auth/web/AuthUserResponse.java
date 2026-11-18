package com.guanghe.backend.auth.web;

import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.security.AuthenticatedAccount;

public record AuthUserResponse(
        String id,
        String username,
        String displayName,
        String phoneMasked,
        String email,
        String role,
        String status
) {
    public static AuthUserResponse from(AccountRecord account) {
        return new AuthUserResponse(
                Long.toString(account.id()),
                account.username(),
                account.displayName(),
                maskPhone(account.phoneE164()),
                account.email(),
                account.role().name(),
                account.status().name()
        );
    }

    public static AuthUserResponse from(AuthenticatedAccount account) {
        return new AuthUserResponse(
                Long.toString(account.id()),
                account.username(),
                account.displayName(),
                maskPhone(account.phoneE164()),
                account.email(),
                account.role().name(),
                account.status().name()
        );
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return null;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
