package com.unispark.ecommerce.tenant.internal.web;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import com.unispark.ecommerce.tenant.Site;
import com.unispark.ecommerce.tenant.Sites;
import com.unispark.ecommerce.tenant.StoreSlugs;

/**
 * Classifies a request host against the base domain (SPEC 4.1):
 * <ul>
 *   <li>{@code <base>}, {@code www.<base>} and hosts outside the base domain (e.g. {@code localhost}) are the
 *       main site;
 *   <li>{@code admin.<base>} is the platform admin;
 *   <li>{@code <slug>.<base>} is a storefront;
 *   <li>anything else under the base domain (several labels, a malformed or reserved slug) cannot be a store.
 * </ul>
 * Ports are ignored on both sides, so {@code APP_BASE_DOMAIN=lvh.me:8080} matches {@code demo1.lvh.me}.
 */
final class StoreHosts implements Sites {

    static final String PLATFORM_ADMIN_SUBDOMAIN = "admin";
    static final String MAIN_ALIAS_SUBDOMAIN = "www";

    sealed interface Match {
    }

    record MainHost() implements Match {
    }

    record PlatformAdminHost() implements Match {
    }

    record StoreHost(String slug) implements Match {
    }

    record InvalidStoreHost() implements Match {
    }

    private final String baseDomain;
    private final String baseHost;

    StoreHosts(String baseDomain) {
        this.baseDomain = baseDomain == null ? "" : baseDomain.strip().toLowerCase(Locale.ROOT);
        this.baseHost = normalize(baseDomain);
        if (baseHost.isEmpty()) {
            throw new IllegalArgumentException("app.base-domain must not be empty");
        }
    }

    Match match(String host) {
        var normalized = normalize(host);
        var suffix = "." + baseHost;
        if (normalized.equals(baseHost) || !normalized.endsWith(suffix)) {
            return new MainHost();
        }
        var label = normalized.substring(0, normalized.length() - suffix.length());
        if (label.equals(PLATFORM_ADMIN_SUBDOMAIN)) {
            return new PlatformAdminHost();
        }
        if (label.equals(MAIN_ALIAS_SUBDOMAIN)) {
            return new MainHost();
        }
        return StoreSlugs.hasValidFormat(label) && !StoreSlugs.isReserved(label)
                ? new StoreHost(label)
                : new InvalidStoreHost();
    }

    @Override
    public Site siteOf(HttpServletRequest request) {
        return switch (match(request.getServerName())) {
            case MainHost main -> Site.MAIN;
            case PlatformAdminHost admin -> Site.PLATFORM_ADMIN;
            case StoreHost store -> Site.STOREFRONT;
            case InvalidStoreHost invalid -> Site.STOREFRONT;
        };
    }

    @Override
    public String storeHost(String slug) {
        return slug + "." + baseDomain;
    }

    @Override
    public String baseHost() {
        return baseHost;
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
