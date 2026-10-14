package com.unispark.ecommerce.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Bearer secrets handed to browsers (verification tokens, order tracking links): 256 random bits, URL-safe. Tables
 * keep only {@link #hash}, so reading the database does not reveal a usable token.
 */
public final class SecretTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private SecretTokens() {
    }

    public static String newToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A token as {@link #newToken} makes them; anything else (tampered, truncated) matches nothing. */
    public static boolean isWellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    /** Six random digits, for codes people type. */
    public static String newCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    /** SHA-256, hex. */
    public static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A token derived from a secret, so the same secret always gives the same token (base64url SHA-256). */
    public static String derive(String purpose, String secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((purpose + ":" + secret).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
