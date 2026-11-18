package com.guanghe.backend.billing;

import com.guanghe.backend.auth.access.AdminAccess;
import com.guanghe.backend.auth.security.AuthenticatedAccount;

/**
 * The commercial control plane's name for the shared operator gate.
 *
 * <p>The check itself now lives in {@link AdminAccess}, so the commercial console and the media
 * console cannot drift apart on who counts as an operator. This alias is kept because it is what the
 * billing services already call, and because "billing access" is the name that reads correctly at
 * those call sites.
 */
public final class BillingAccess {

    private BillingAccess() {
    }

    public static long requireAdmin(AuthenticatedAccount account) {
        return AdminAccess.requireAdmin(account);
    }
}
