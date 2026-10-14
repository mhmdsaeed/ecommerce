package com.unispark.ecommerce.identity.internal.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfException;

import com.unispark.ecommerce.shared.Htmx;

/**
 * Login redirects that also work for HTMX requests. A plain 302 would be followed by htmx and the login page
 * swapped into a fragment; instead HTMX requests get a status with {@code HX-Redirect}, and htmx navigates the
 * whole page (it reads that header before looking at the status).
 */
final class HtmxAware {

    private HtmxAware() {
    }

    static void redirect(HttpServletRequest request, HttpServletResponse response, int htmxStatus, String path)
            throws IOException {
        var target = request.getContextPath() + path;
        if (Htmx.isHtmx(request)) {
            response.setHeader(Htmx.REDIRECT, target);
            response.setStatus(htmxStatus);
        } else {
            response.sendRedirect(target);
        }
    }

    /** Not logged in: the login page, as a full-page navigation for HTMX requests. */
    static AuthenticationEntryPoint entryPoint(String loginUrl) {
        var pages = new LoginUrlAuthenticationEntryPoint(loginUrl);
        return (request, response, exception) -> {
            if (Htmx.isHtmx(request)) {
                redirect(request, response, HttpServletResponse.SC_UNAUTHORIZED, loginUrl + "?expired");
            } else {
                pages.commence(request, response, exception);
            }
        };
    }

    /**
     * A CSRF failure on an HTMX request almost always means the session expired (and its token with it), so it
     * goes to the login page. Everything else is a plain 403.
     */
    static AccessDeniedHandler accessDenied(String loginUrl) {
        var standard = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (Htmx.isHtmx(request) && exception instanceof CsrfException) {
                redirect(request, response, HttpServletResponse.SC_FORBIDDEN, loginUrl + "?expired");
            } else {
                standard.handle(request, response, exception);
            }
        };
    }
}
