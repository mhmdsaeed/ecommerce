package com.unispark.ecommerce.catalog.internal;

import com.unispark.ecommerce.shared.LocalizedText;

/** Small helpers for the Arabic/English text pairs typed into catalog forms. */
final class Texts {

    private Texts() {
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Null when both languages are blank. */
    static LocalizedText localizedOrNull(String ar, String en) {
        return isBlank(ar) && isBlank(en) ? null : LocalizedText.of(ar, en);
    }

    /** The text a slug is generated from: Arabic first, as the default store language. */
    static String slugSource(LocalizedText name) {
        return name.ar() != null ? name.ar() : name.en();
    }
}
