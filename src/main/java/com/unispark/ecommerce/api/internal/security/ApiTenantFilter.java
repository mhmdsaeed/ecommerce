package com.unispark.ecommerce.api.internal.security;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreStatus;

/**
 * The API's tenant (SPEC 4.1): the {@value #STORE_CLAIM} claim of a merchant token, or else the
 * {@value #STORE_HEADER} header the shopper app sends. A header naming another store than the token is refused.
 * Shoppers reach only stores open to them, as on the storefront: unknown, closed or not yet launched stores are 404,
 * suspended ones 503. A token's store only has to exist, so merchants can use the API before launch.
 * Requests without either run without a tenant; endpoints that need one say so.
 */
class ApiTenantFilter extends OncePerRequestFilter {

    static final String STORE_HEADER = "X-Store-Id";
    static final String STORE_CLAIM = "store_id";

    private final StoreLookup stores;
    private final ObjectMapper json;

    ApiTenantFilter(StoreLookup stores, ObjectMapper json) {
        this.stores = stores;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader(STORE_HEADER);
        var claim = tokenStore();
        if (header == null && claim.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<UUID> fromHeader = Optional.empty();
        if (header != null) {
            fromHeader = parse(header);
            if (fromHeader.isEmpty()) {
                Problems.write(json, request, response, HttpStatus.BAD_REQUEST, STORE_HEADER + " is not a store id.");
                return;
            }
        }
        if (claim.isPresent() && fromHeader.isPresent() && !claim.get().equals(fromHeader.get())) {
            Problems.write(json, request, response, HttpStatus.FORBIDDEN,
                    STORE_HEADER + " names another store than the access token.");
            return;
        }
        UUID storeId;
        if (claim.isPresent()) {
            storeId = claim.get();
            if (stores.findById(storeId).filter(store -> store.status().hasStorefront()).isEmpty()) {
                Problems.write(json, request, response, HttpStatus.FORBIDDEN, "The access token's store is closed.");
                return;
            }
        } else {
            storeId = fromHeader.get();
            var store = stores.findById(storeId).filter(found -> found.status().hasStorefront() && found.launched());
            if (store.isEmpty()) {
                Problems.write(json, request, response, HttpStatus.NOT_FOUND, "No such store.");
                return;
            }
            if (store.get().status() == StoreStatus.SUSPENDED) {
                Problems.write(json, request, response, HttpStatus.SERVICE_UNAVAILABLE,
                        "This store is temporarily unavailable.");
                return;
            }
        }
        try (var scope = TenantContext.enter(storeId)) {
            chain.doFilter(request, response);
        }
    }

    /** The store of a merchant token; a token without a valid claim names none (it is not a merchant's). */
    private static Optional<UUID> tokenStore() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            var claim = token.getToken().getClaimAsString(STORE_CLAIM);
            return claim == null ? Optional.empty() : parse(claim);
        }
        return Optional.empty();
    }

    private static Optional<UUID> parse(String value) {
        try {
            var id = UUID.fromString(value.strip());
            // UUID.fromString accepts short forms such as "1-1-1-1-1"; only the canonical form is an id.
            return id.toString().equalsIgnoreCase(value.strip()) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
