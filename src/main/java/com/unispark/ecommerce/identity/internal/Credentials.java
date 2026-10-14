package com.unispark.ecommerce.identity.internal;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.unispark.ecommerce.shared.PhoneNumbers;

/** Normalizes login identifiers and checks passwords, the same way for signup, staff management and login. */
public final class Credentials {

    public static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt ignores everything after 72 bytes; refuse longer passwords rather than silently truncate them. */
    public static final int MAX_PASSWORD_BYTES = 72;

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private Credentials() {
    }

    public static Optional<String> normalizeEmail(String input) {
        if (input == null) {
            return Optional.empty();
        }
        var email = input.strip().toLowerCase(Locale.ROOT);
        return email.length() <= 254 && EMAIL.matcher(email).matches() ? Optional.of(email) : Optional.empty();
    }

    /** A problem code for the password ({@code too-short}, {@code too-long}), or empty when it is acceptable. */
    public static Optional<String> passwordProblem(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD_LENGTH) {
            return Optional.of("too-short");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return Optional.of("too-long");
        }
        return Optional.empty();
    }

    /** What someone typed in the login form's "email or phone" field. */
    public sealed interface LoginIdentifier {

        /** Stable text for rate-limit keys, whatever the user typed. */
        String key();

        static LoginIdentifier parse(String input) {
            if (input != null && input.contains("@")) {
                return normalizeEmail(input).<LoginIdentifier>map(Email::new).orElseGet(() -> new Unusable(input));
            }
            return PhoneNumbers.normalize(input).<LoginIdentifier>map(Phone::new).orElseGet(() -> new Unusable(input));
        }
    }

    public record Email(String value) implements LoginIdentifier {

        @Override
        public String key() {
            return value;
        }
    }

    public record Phone(String value) implements LoginIdentifier {

        @Override
        public String key() {
            return value;
        }
    }

    public record Unusable(String raw) implements LoginIdentifier {

        @Override
        public String key() {
            return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        }
    }
}
