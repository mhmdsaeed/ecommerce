package com.unispark.ecommerce.identity;

import java.util.Locale;

/** Light or dark admin screens, each person's choice. DEVICE follows the phone or computer's setting. */
public enum ColorScheme {
    DEVICE,
    LIGHT,
    DARK;

    /** As the layouts write it in {@code data-scheme}: device, light or dark. */
    public String attribute() {
        return name().toLowerCase(Locale.ROOT);
    }
}
