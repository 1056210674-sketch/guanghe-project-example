package com.guanghe.backend.auth.access;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;

/**
 * Role gate for every operator-facing surface.
 *
 * <p>{@code SecurityConfiguration} already restricts {@code /api/v1/admin/**} to {@code ROLE_ADMIN},
 * but an operator endpoint reads across every account and, once writes arrive, moves other people's
 * balances and submissions. So the check is repeated in the service layer: a controller that loses
 * its mapping, or a future route that never had one, must not be able to reach an operator action.
 *
 * <p>It lives in the account package rather than in any one business domain. The commercial console
 * and the media console answer the same question — "is the caller an operator?" — and a second copy
 * of that answer is a second chance to disagree about who may act.
 */
public final class AdminAccess {

    private AdminAccess() {
    }

    /** Returns the operator's account id, or fails with {@code FORBIDDEN}. */
    public static long requireAdmin(AuthenticatedAccount account) {
        if (account == null || account.role() != AccountRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return account.id();
    }
}
