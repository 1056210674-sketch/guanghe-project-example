package com.guanghe.backend.auth.account;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class UsernameNormalizer {

    public String normalize(String username) {
        if (username == null) {
            return "";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
