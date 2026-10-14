package com.unispark.ecommerce.shared;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses amounts the way merchants type them: {@code 150000}, {@code 150,000}, {@code 150 000},
 * {@code ١٥٠٬٠٠٠}, {@code 12.50} or {@code ١٢٫٥}. Never negative, at most two decimals.
 *
 * <p>Commas, spaces and the Arabic thousands separator only count as grouping when they group exactly three digits,
 * so the European {@code 12,50} is rejected instead of silently read as 1250. The decimal mark is the dot or the
 * Arabic decimal separator.
 */
public final class Amounts {

    private static final String GROUP = "[,\\s\\u066C\\u00A0\\u202F]";
    private static final Pattern PLAIN = Pattern.compile("\\d{1,13}(\\.\\d{1,2})?");
    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}(" + GROUP + "\\d{3}){1,4}(\\.\\d{1,2})?");

    private Amounts() {
    }

    public static Optional<BigDecimal> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        var normalized = Digits.toAscii(input.strip()).replace('٫', '.');
        if (PLAIN.matcher(normalized).matches()) {
            return Optional.of(new BigDecimal(normalized).setScale(Money.SCALE));
        }
        if (GROUPED.matcher(normalized).matches()) {
            return Optional.of(new BigDecimal(normalized.replaceAll(GROUP, "")).setScale(Money.SCALE));
        }
        return Optional.empty();
    }
}
