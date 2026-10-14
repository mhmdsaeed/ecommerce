package com.unispark.ecommerce.tenant;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for store slugs, which are the storefront subdomains: one lowercase DNS label of 3 to 32 characters
 * (letters, digits, inner hyphens), not reserved for the platform. The format matches the {@code store} table's
 * CHECK constraint.
 */
public final class StoreSlugs {

    /** Subdomains the platform uses or may use; never a store. */
    public static final Set<String> RESERVED = Set.of(
            "admin", "www", "api", "app", "mail", "smtp", "static", "cdn", "media", "assets", "help", "support",
            "status", "blog", "docs", "platform", "signup", "login", "account", "billing", "driver", "dashboard");

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]");

    private StoreSlugs() {
    }

    /** Lowercases and trims what a merchant typed; does not validate. */
    public static String normalize(String input) {
        return input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean hasValidFormat(String slug) {
        return slug != null && FORMAT.matcher(slug).matches();
    }

    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug);
    }
}
