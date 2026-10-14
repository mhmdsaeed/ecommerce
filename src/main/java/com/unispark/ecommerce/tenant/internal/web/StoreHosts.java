package com.unispark.ecommerce.tenant.internal.web;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies a request host against the base domain (SPEC 4.1):
 * <ul>
 *   <li>{@code <base>}, {@code admin.<base>}, {@code www.<base>} and hosts outside the base domain (e.g.
 *       {@code localhost}) are platform hosts: no tenant from the host;
 *   <li>{@code <slug>.<base>} is a storefront;
 *   <li>anything else under the base domain (several labels, invalid characters) cannot be a store.
 * </ul>
 * Ports are ignored on both sides, so {@code APP_BASE_DOMAIN=lvh.me:8080} matches {@code demo1.lvh.me}.
 */
final class StoreHosts {

    /** Subdomains that are never stores. Store signup (M2) must reject these slugs too. */
    static final Set<String> RESERVED_SUBDOMAINS = Set.of("admin", "www");

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]");

    sealed interface Match {
    }

    record PlatformHost() implements Match {
    }

    record StoreHost(String slug) implements Match {
    }

    record InvalidStoreHost() implements Match {
    }

    private final String baseHost;

    StoreHosts(String baseDomain) {
        this.baseHost = normalize(baseDomain);
        if (baseHost.isEmpty()) {
            throw new IllegalArgumentException("app.base-domain must not be empty");
        }
    }

    Match match(String host) {
        var normalized = normalize(host);
        var suffix = "." + baseHost;
        if (normalized.equals(baseHost) || !normalized.endsWith(suffix)) {
            return new PlatformHost();
        }
        var label = normalized.substring(0, normalized.length() - suffix.length());
        if (RESERVED_SUBDOMAINS.contains(label)) {
            return new PlatformHost();
        }
        return SLUG.matcher(label).matches() ? new StoreHost(label) : new InvalidStoreHost();
    }

    private static String normalize(String host) {
        if (host == null) {
            return "";
        }
        var value = host.strip().toLowerCase(Locale.ROOT);
        if (!value.startsWith("[")) {
            var colon = value.lastIndexOf(':');
            if (colon >= 0) {
                value = value.substring(0, colon);
            }
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
