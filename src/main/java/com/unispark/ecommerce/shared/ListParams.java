package com.unispark.ecommerce.shared;

import java.util.Arrays;
import java.util.Optional;

/**
 * Query parameters of lists (filters, page numbers), in the admin and the storefront. They live in URLs people bookmark, share and edit, so a
 * value the list does not know is ignored rather than answered with an error page.
 */
public final class ListParams {

    private ListParams() {
    }

    /** The page number; 0 for a missing, malformed or negative value. */
    public static int page(String value) {
        try {
            return value == null ? 0 : Math.max(Integer.parseInt(value.strip()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The constant with this exact name; empty for a missing or unknown one. */
    public static <E extends Enum<E>> Optional<E> constant(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(type.getEnumConstants()).filter(constant -> constant.name().equals(value)).findFirst();
    }
}
