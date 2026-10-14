package com.unispark.ecommerce.cart.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Cart tokens: 256 random bits, URL-safe, kept in the shopper's cookie. The database stores only their SHA-256, so
 * reading the table does not let anyone open a cart.
 */
final class CartTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private CartTokens() {
    }

    static String newToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Anything else in the cookie (tampered, truncated) is no cart at all. */
    static boolean isWellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    static String sessionKey(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
