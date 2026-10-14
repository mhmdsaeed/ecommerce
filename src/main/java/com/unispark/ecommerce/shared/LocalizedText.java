package com.unispark.ecommerce.shared;

import java.util.Locale;

/**
 * Text entered in Arabic and/or English, stored as {@code jsonb {"ar": "...", "en": "..."}} (SPEC 11).
 * At least one language is required; {@link #in(Locale)} falls back to the other language.
 */
public record LocalizedText(String ar, String en) {

    public LocalizedText {
        ar = blankToNull(ar);
        en = blankToNull(en);
        if (ar == null && en == null) {
            throw new IllegalArgumentException("LocalizedText needs an Arabic or an English value");
        }
    }

    public static LocalizedText of(String ar, String en) {
        return new LocalizedText(ar, en);
    }

    public String in(Locale locale) {
        var preferEnglish = locale != null && "en".equals(locale.getLanguage());
        if (preferEnglish) {
            return en != null ? en : ar;
        }
        return ar != null ? ar : en;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
