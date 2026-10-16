package com.unispark.ecommerce.api.internal.security;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unispark.ecommerce.shared.RateLimits;
import com.unispark.ecommerce.shared.RateLimits.Limit;

/**
 * Per-address budget for the whole API (M15), checked before tokens are, so floods of bad tokens are limited
 * too. Generous for an app showing a catalog; a 429 problem detail with {@code Retry-After} otherwise.
 */
class ApiRateLimitFilter extends OncePerRequestFilter {

    static final Limit REQUESTS = new Limit("api", 300, Duration.ofMinutes(1));

    private final RateLimits limits;
    private final ObjectMapper json;

    ApiRateLimitFilter(RateLimits limits, ObjectMapper json) {
        this.limits = limits;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var wait = limits.tryTake(REQUESTS, request.getRemoteAddr());
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RateLimits.retryAfterSeconds(wait.get())));
            Problems.write(json, request, response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests from this address; try again later.");
            return;
        }
        chain.doFilter(request, response);
    }
}
