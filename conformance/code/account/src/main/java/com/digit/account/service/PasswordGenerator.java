package com.digit.account.service;

import java.security.SecureRandom;
import java.util.Base64;

/** Random password generation. Mirrors Go internal/service/password.go. */
public final class PasswordGenerator {
    private PasswordGenerator() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int LENGTH = 10;

    /**
     * Ten base64url characters, so roughly 60 bits of entropy. Short enough to be typed from an
     * email, and the alphabet ({@code A-Za-z0-9-_}) contains nothing an HTML template would escape.
     */
    public static String generate() {
        byte[] buf = new byte[8];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf).substring(0, LENGTH);
    }
}
