package com.unispark.ecommerce.catalog.internal;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes search text the same way as the generated {@code product.search_text} column (V7 migration): lower
 * case, Arabic diacritics and tatweel removed, hamza forms of alef to alef, alef maksura to ya, ta marbuta to ha.
 * So a shopper typing احمر finds أحمر, and مدرسه finds مدرسة. Keep both in sync.
 */
final class ProductSearch {

    private static final Pattern MARKS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u0640]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ProductSearch() {
    }

    static String normalize(String query) {
        if (query == null) {
            return "";
        }
        var text = MARKS.matcher(query.toLowerCase(Locale.ROOT)).replaceAll("");
        var result = new StringBuilder(text.length());
        for (var ch : text.toCharArray()) {
            result.append(switch (ch) {
                case 'أ', 'إ', 'آ', 'ٱ' -> 'ا';
                case 'ى' -> 'ي';
                case 'ة' -> 'ه';
                default -> ch;
            });
        }
        return SPACES.matcher(result.toString().strip()).replaceAll(" ");
    }

    /** A LIKE pattern matching the normalized query anywhere; {@code \} is the escape character. */
    static String containsPattern(String normalized) {
        return "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
