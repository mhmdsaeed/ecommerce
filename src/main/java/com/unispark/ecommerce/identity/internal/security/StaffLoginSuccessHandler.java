package com.unispark.ecommerce.identity.internal.security;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.util.UriComponentsBuilder;

import com.unispark.ecommerce.identity.StaffPrincipal;

/**
 * Sends staff to their own area whichever login page they used: drivers to {@code /driver}, everyone else to
 * {@code /admin}, or back to the page that asked them to log in if it is in that area. Remembers the store
 * address in a cookie so the login form is prefilled next time.
 */
final class StaffLoginSuccessHandler implements AuthenticationSuccessHandler {

    static final String STORE_COOKIE = "store";
    private static final String SAVED_REQUEST_MARKER = "continue";

    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final boolean secureCookie;

    StaffLoginSuccessHandler(boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        var staff = (StaffPrincipal) authentication.getPrincipal();
        var area = request.getContextPath() + (staff.role().usesMerchantAdmin() ? "/admin" : "/driver");
        var target = area;
        var saved = requestCache.getRequest(request, response);
        if (saved != null) {
            // Spring Security marks saved requests with a "continue" parameter; it means nothing to the page.
            var savedUri = UriComponentsBuilder.fromUriString(saved.getRedirectUrl())
                    .replaceQueryParam(SAVED_REQUEST_MARKER)
                    .build();
            var savedPath = savedUri.getPath();
            if (savedPath != null && (savedPath.equals(area) || savedPath.startsWith(area + "/"))) {
                target = savedPath + (savedUri.getQuery() == null ? "" : "?" + savedUri.getQuery());
            }
            requestCache.removeRequest(request, response);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(STORE_COOKIE, staff.storeSlug())
                .path("/")
                .maxAge(Duration.ofDays(365))
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .build()
                .toString());
        response.sendRedirect(target);
    }
}
