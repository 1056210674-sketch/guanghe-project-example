package com.guanghe.backend.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * First-version compatibility encoder. This deliberately implements the accepted
 * MD5(password + fixed salt) security debt and must not be described as a strong hash.
 */
@Component
public final class FixedSaltMd5PasswordEncoder implements PasswordEncoder {

    public static final String ALGORITHM_ID = "MD5_FIXED_SALT_V1";
    private static final String FIXED_SALT = "guanghe888";

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] encoded = digest.digest((rawPassword + FIXED_SALT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide MD5", exception);
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        byte[] actual = encode(rawPassword).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = encodedPassword.toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return false;
    }
}

