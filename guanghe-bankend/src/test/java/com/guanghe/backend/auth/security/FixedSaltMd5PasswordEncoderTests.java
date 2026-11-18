package com.guanghe.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedSaltMd5PasswordEncoderTests {

    private final FixedSaltMd5PasswordEncoder encoder = new FixedSaltMd5PasswordEncoder();

    @Test
    void shouldEncodeDeterministicallyAndMatchOnlyTheSamePassword() {
        String encoded = encoder.encode("contract-test-password");

        assertThat(encoded).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(encoder.encode("contract-test-password")).isEqualTo(encoded);
        assertThat(encoder.matches("contract-test-password", encoded)).isTrue();
        assertThat(encoder.matches("different-password", encoded)).isFalse();
    }

    @Test
    void shouldExposeThePersistedAlgorithmIdentifier() {
        assertThat(FixedSaltMd5PasswordEncoder.ALGORITHM_ID).isEqualTo("MD5_FIXED_SALT_V1");
    }
}

