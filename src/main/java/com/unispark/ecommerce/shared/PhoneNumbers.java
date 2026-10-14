package com.unispark.ecommerce.shared;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalizes phone numbers to E.164 ({@code +963933123456}) so one number always has one spelling, for login and
 * per-store uniqueness.
 *
 * <p>Syria is the default country: {@code 0933 123 456}, {@code 00963 933 123 456}, {@code 963933123456} and
 * {@code +963 933-123-456} are the same number. Numbers from other countries must start with {@code +} or
 * {@code 00}. Arabic-Indic and Persian digits are accepted, since many Syrian keyboards type them.
 */
public final class PhoneNumbers {

    private static final String SYRIA = "963";
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().\\u200e\\u200f]");
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");
    /** Syrian national significant numbers: 9 digits for mobiles (9xx), 8 to 9 for landlines. */
    private static final Pattern SYRIAN_NATIONAL = Pattern.compile("[1-9][0-9]{7,8}");

    private PhoneNumbers() {
    }

    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        var digits = SEPARATORS.matcher(Digits.toAscii(input.strip())).replaceAll("");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        String candidate;
        if (digits.startsWith("+")) {
            candidate = digits;
        } else if (digits.startsWith("0")) {
            candidate = "+" + SYRIA + digits.substring(1);
        } else if (digits.startsWith(SYRIA)) {
            candidate = "+" + digits;
        } else {
            return Optional.empty();
        }
        if (!E164.matcher(candidate).matches()) {
            return Optional.empty();
        }
        if (candidate.startsWith("+" + SYRIA)
                && !SYRIAN_NATIONAL.matcher(candidate.substring(1 + SYRIA.length())).matches()) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
