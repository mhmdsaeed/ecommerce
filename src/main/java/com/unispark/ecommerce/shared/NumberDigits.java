package com.unispark.ecommerce.shared;

import java.util.Locale;

/**
 * Which digits a store shows numbers in (SPEC 7): Western (0-9) or Arabic-Indic (٠-٩). Applied through the Unicode
 * locale extension {@code nu}, so every {@code NumberFormat} and {@code MessageFormat} on the page follows it while
 * the language (and with it the message bundle and text direction) stays the same.
 */
public enum NumberDigits {
    WESTERN("latn"),
    ARABIC_INDIC("arab");

    private final String numberingSystem;

    NumberDigits(String numberingSystem) {
        this.numberingSystem = numberingSystem;
    }

    public Locale apply(Locale locale) {
        return new Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", numberingSystem).build();
    }
}
