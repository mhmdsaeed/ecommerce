package com.unispark.ecommerce.tenant.internal.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreSummary;

/**
 * Resolves the storefront tenant from the host (SPEC 4.1) and makes it current for the rest of the request.
 * Unknown, closed or malformed store hosts get a 404 page. Platform hosts pass through without a tenant; the
 * merchant admin and driver areas take their tenant from the logged-in user (M2).
 */
class TenantResolutionFilter extends OncePerRequestFilter {

    /** Static files and actuator need no tenant; skipping them saves a store lookup per asset. */
    private static final String[] TENANT_FREE_PATHS = {"/css/", "/js/", "/images/", "/favicon.ico", "/actuator/"};

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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (hosts.match(request.getServerName())) {
            case StoreHosts.PlatformHost platform -> chain.doFilter(request, response);
            case StoreHosts.InvalidStoreHost invalid -> response.sendError(HttpServletResponse.SC_NOT_FOUND);
            case StoreHosts.StoreHost(var slug) -> {
                var store = stores.findBySlug(slug)
                        .filter(found -> found.status().hasStorefront())
                        .map(StoreSummary::id);
                if (store.isEmpty()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                try (var scope = TenantContext.enter(store.get())) {
                    chain.doFilter(request, response);
                }
            }
        }
    }
}
