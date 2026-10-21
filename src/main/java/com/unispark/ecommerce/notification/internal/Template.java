package com.unispark.ecommerce.notification.internal;

import java.util.Locale;

/**
 * The messages there are (SPEC 12). Texts live in the message bundles under {@code notification.<code>}, so every
 * template exists in Arabic and English. {@link #NEW_ORDER} and {@link #PASSWORD_RESET} go to staff; the others
 * go to shoppers.
 * Public only because JobRunr stores it in job payloads.
 */
public enum Template {
    ORDER_PLACED,
    ORDER_CONFIRMED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    PAYMENT_PROOF_REJECTED,
    OTP,
    NEW_ORDER,
    PASSWORD_RESET,
    PASSWORD_CHANGED;

    /** As stored in {@code notification_log.template}, e.g. {@code order_confirmed}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
