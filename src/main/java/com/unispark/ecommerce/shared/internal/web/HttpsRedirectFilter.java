package com.unispark.ecommerce.shared.internal.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends plain-http requests to https (prod profile, {@code app.https-only}). Behind a proxy that terminates TLS,
 * "secure" comes from its {@code X-Forwarded-Proto} (forwarded headers are trusted in prod). 308 keeps the method
 * and body, so a form posted over http is not silently turned into a GET. Actuator stays reachable over http for
 * the platform's health probes, which call the container directly. Spring Security adds HSTS to https responses.
 */
class HttpsRedirectFilter extends OncePerRequestFilter {

    static final int PERMANENT_REDIRECT = 308;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.isSecure()
                || request.getRequestURI().substring(request.getContextPath().length()).startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var query = request.getQueryString();
        var target = "https://" + request.getServerName() + request.getRequestURI() + (query == null ? "" : "?" + query);
        response.setStatus(PERMANENT_REDIRECT);
        response.setHeader(HttpHeaders.LOCATION, target);
    }
}
