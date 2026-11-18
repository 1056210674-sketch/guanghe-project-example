package com.guanghe.backend.integration.internal.contract;

import java.util.regex.Pattern;

/**
 * Canonical format shared by the signed Python capability response, internal execution claims and
 * persisted execution evidence. Keeping one format makes every stored bundle version addressable
 * through the read-only administrator API without unsafe path encoding or lossy filtering.
 */
public final class AgentBundleVersionFormat {
    public static final String REGEX = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private AgentBundleVersionFormat() { }

    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }
}
