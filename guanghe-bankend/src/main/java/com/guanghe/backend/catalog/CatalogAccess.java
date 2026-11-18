package com.guanghe.backend.catalog;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;

public final class CatalogAccess {

    private CatalogAccess() {
    }

    public static long requireUser(AuthenticatedAccount account) {
        if (account == null || account.role() != AccountRole.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return account.id();
    }
}
