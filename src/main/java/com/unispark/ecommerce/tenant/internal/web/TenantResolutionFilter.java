package com.unispark.ecommerce.tenant.internal.web;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreStatus;

/**
 * Resolves the storefront tenant from the host (SPEC 4.1) and makes it current for the rest of the request.
 * Unknown, closed or malformed store hosts get a 404 page, suspended stores a 503 "temporarily unavailable" page,
 * and stores not launched yet a 503 "opening soon" page (except to their staff's preview).
 * The main and platform admin hosts pass through without a tenant; the merchant admin and driver areas take their
 * tenant from the logged-in staff member.
 */
class TenantResolutionFilter extends OncePerRequestFilter {

    /** Static files and actuator need no tenant; skipping them saves a store lookup per asset. */
    private static final String[] TENANT_FREE_PATHS = {"/css/", "/js/", "/images/", "/favicon.ico", "/actuator/"};

    /** Request attributes the storefront layout and error page read. */
    static final String OPENING_SOON = "storeOpeningSoon";
    static final String PREVIEW = "storePreview";
    static final String PREVIEW_PARAMETER = "preview";
    static final String PREVIEW_COOKIE = "store_preview";

    private final StoreHosts hosts;
    private final StoreLookup stores;

    TenantResolutionFilter(StoreHosts hosts, StoreLookup stores) {
        this.hosts = hosts;
        this.stores = stores;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        for (var prefix : TENANT_FREE_PATHS) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Before launch, the store's staff preview it through the link on their setup page ({@code ?preview=}), which
     * leaves a cookie for the rest of the visit. Preview pages are marked for the layout and kept out of search
     * engines.
     */
    private boolean previewing(UUID storeId, HttpServletRequest request, HttpServletResponse response) {
        var fromLink = "GET".equals(request.getMethod()) ? request.getParameter(PREVIEW_PARAMETER) : null;
        if (fromLink != null && stores.previewAllowed(storeId, fromLink)) {
            var cookie = new Cookie(PREVIEW_COOKIE, fromLink);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setSecure(request.isSecure());
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        } else if (!stores.previewAllowed(storeId, cookie(request))) {
            return false;
        }
        request.setAttribute(PREVIEW, true);
        response.setHeader("X-Robots-Tag", "noindex");
        return true;
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> PREVIEW_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (hosts.match(request.getServerName())) {
            case StoreHosts.MainHost main -> chain.doFilter(request, response);
            case StoreHosts.PlatformAdminHost platformAdmin -> chain.doFilter(request, response);
            case StoreHosts.InvalidStoreHost invalid -> response.sendError(HttpServletResponse.SC_NOT_FOUND);
            case StoreHosts.StoreHost(var slug) -> {
                var store = stores.findBySlug(slug).filter(found -> found.status().hasStorefront());
                if (store.isEmpty()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                if (store.get().status() == StoreStatus.SUSPENDED) {
                    // error/503.html: "temporarily unavailable", without the store's catalog or cart.
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                if (!store.get().launched() && !previewing(store.get().id(), request, response)) {
                    // error/503.html shows "opening soon" for this attribute: the store's name, on its closed sign.
                    request.setAttribute(OPENING_SOON, store.get().name());
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                try (var scope = TenantContext.enter(store.get().id())) {
                    chain.doFilter(request, response);
                }
            }
        }
    }
}
