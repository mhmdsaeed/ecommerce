package com.unispark.ecommerce.shared;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses amounts the way merchants type them: {@code 150000}, {@code 150,000}, {@code 150 000},
 * {@code ١٥٠٬٠٠٠}, {@code 12.50} or {@code ١٢٫٥}. Never negative.
 *
 * <p>Commas, spaces and the Arabic thousands separator only count as grouping when they group exactly three digits,
 * so the European {@code 12,50} is rejected instead of silently read as 1250. The decimal mark is the dot or the
 * Arabic decimal separator.
 */
public final class Amounts {

    private static final String GROUP = "[,\\s\\u066C\\u00A0\\u202F]";

    private Amounts() {
    }

    /** Money amounts: at most two decimals, scale {@link Money#SCALE}. */
    public static Optional<BigDecimal> parse(String input) {
        return parse(input, Money.SCALE).map(amount -> amount.setScale(Money.SCALE));
    }

    /** At most {@code maxDecimals} decimals (e.g. 6 for exchange rates), keeping the scale as typed. */
    public static Optional<BigDecimal> parse(String input, int maxDecimals) {
        if (input == null) {
            return Optional.empty();
        }
        var decimals = maxDecimals > 0 ? "(\\.\\d{1," + maxDecimals + "})?" : "";
        var plain = Pattern.compile("\\d{1,13}" + decimals);
        var grouped = Pattern.compile("\\d{1,3}(" + GROUP + "\\d{3}){1,4}" + decimals);
        var normalized = Digits.toAscii(input.strip()).replace('٫', '.');
        if (plain.matcher(normalized).matches()) {
            return Optional.of(new BigDecimal(normalized));
        }
        if (grouped.matcher(normalized).matches()) {
            return Optional.of(new BigDecimal(normalized.replaceAll(GROUP, "")));
        }
        return Optional.empty();
    }
}
