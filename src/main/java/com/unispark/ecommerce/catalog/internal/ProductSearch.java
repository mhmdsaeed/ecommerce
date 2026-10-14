package com.unispark.ecommerce.catalog.internal;

import com.unispark.ecommerce.shared.ArabicText;

/**
 * Normalizes search text the same way as the generated {@code product.search_text} column (V7 migration), which
 * mirrors {@link ArabicText#fold}: lower case, Arabic diacritics and tatweel removed, hamza forms of alef to alef,
 * alef maksura to ya, ta marbuta to ha. So a shopper typing احمر finds أحمر, and مدرسه finds مدرسة. Keep both in
 * sync.
 */
final class ProductSearch {

    private ProductSearch() {
    }

    static String normalize(String query) {
        return ArabicText.fold(query);
    }

    /** A LIKE pattern matching the normalized query anywhere; {@code \} is the escape character. */
    static String containsPattern(String normalized) {
        return "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
