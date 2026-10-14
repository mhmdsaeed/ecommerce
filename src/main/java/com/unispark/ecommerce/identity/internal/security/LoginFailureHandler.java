package com.unispark.ecommerce.identity.internal.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.unispark.ecommerce.tenant.StoreSlugs;

/**
 * Back to the login page with the reason ({@code error}, {@code locked} or {@code disabled}). The store address is
 * kept so the merchant does not retype it; the email/phone is not echoed back.
 */
final class LoginFailureHandler implements AuthenticationFailureHandler {

    private final String loginUrl;

    LoginFailureHandler(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        var reason = switch (exception) {
            case LoginThrottledException throttled -> "locked";
            case DisabledException disabled -> "disabled";
            default -> "error";
        };
        var target = UriComponentsBuilder.fromPath(request.getContextPath() + loginUrl).queryParam(reason);
        var store = StoreSlugs.normalize(request.getParameter(StaffLoginDetails.STORE_PARAMETER));
        if (StoreSlugs.hasValidFormat(store)) {
            target.queryParam(StaffLoginDetails.STORE_PARAMETER, store);
        }
        response.sendRedirect(target.build().toUriString());
    }
}
