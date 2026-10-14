package com.unispark.ecommerce.identity.internal.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.internal.StaffAccounts;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Runs merchant admin and driver requests in the logged-in staff member's store (SPEC 4.1: tenant from the
 * session principal). Also re-checks the account on every request, so disabling a staff member, changing their
 * role or closing the store ends their session immediately rather than when it expires.
 */
final class StaffSessionFilter extends OncePerRequestFilter {

    private final StaffAccounts accounts;
    private final StoreLookup stores;
    private final String loginUrl;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    StaffSessionFilter(StaffAccounts accounts, StoreLookup stores, String loginUrl) {
        this.accounts = accounts;
        this.stores = stores;
        this.loginUrl = loginUrl;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal staff)) {
            chain.doFilter(request, response);
            return;
        }
        try (var scope = TenantContext.enter(staff.tenantId())) {
            if (!stillValid(staff)) {
                logout.logout(request, response, authentication);
                HtmxAware.redirect(request, response, HttpServletResponse.SC_UNAUTHORIZED, loginUrl + "?expired");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    private boolean stillValid(StaffPrincipal staff) {
        var storeOpen = stores.findById(staff.tenantId()).filter(store -> store.status().hasStorefront()).isPresent();
        return storeOpen && accounts.currentState(staff.staffId())
                .filter(state -> state.active() && state.role() == staff.role())
                .isPresent();
    }
}
