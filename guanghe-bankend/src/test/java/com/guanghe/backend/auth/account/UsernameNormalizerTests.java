package com.guanghe.backend.auth.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsernameNormalizerTests {

    private final UsernameNormalizer normalizer = new UsernameNormalizer();

    @Test
    void trimsAndNormalizesCaseWithLocaleIndependentRules() {
        assertThat(normalizer.normalize("  Guanghe888  ")).isEqualTo("guanghe888");
        assertThat(normalizer.normalize("TEST001")).isEqualTo("test001");
    }
}
