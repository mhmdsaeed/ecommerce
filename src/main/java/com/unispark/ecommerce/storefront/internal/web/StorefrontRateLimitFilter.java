package com.unispark.ecommerce.storefront.internal.web;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.unispark.ecommerce.shared.RateLimits;
import com.unispark.ecommerce.shared.RateLimits.Limit;

/**
 * Per-address limits on what shoppers send (M15). Browsing is not limited here (that is for the proxy or CDN);
 * these guard what costs money or fills the merchant's lists: verification texts, orders, payment proofs, and
 * cart writes. Over the limit, browsers get the "too many requests" page (error/429.html) with {@code Retry-After};
 * HTMX requests get the page's error banner.
 * The per-phone code limits (PhoneVerification) still apply on top.
 */
class StorefrontRateLimitFilter extends OncePerRequestFilter {

    /** Each text costs money (SPEC 12): a few codes per hour from one address, across all phones. */
    static final Limit CODES = new Limit("store-codes", 10, Duration.ofHours(1));
    static final Limit CODE_CHECKS = new Limit("store-code-checks", 30, Duration.ofMinutes(15));
    static final Limit ORDERS = new Limit("store-orders", 20, Duration.ofHours(1));
    static final Limit PROOFS = new Limit("store-proofs", 20, Duration.ofHours(1));
    static final Limit CART = new Limit("store-cart", 120, Duration.ofMinutes(1));

    private static final PathPatternParser PATHS = PathPatternParser.defaultInstance;

    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.POST, PATHS.parse("/checkout/code"), CODES),
            new Rule(HttpMethod.POST, PATHS.parse("/checkout/verify"), CODE_CHECKS),
            new Rule(HttpMethod.POST, PATHS.parse("/checkout"), ORDERS),
            new Rule(HttpMethod.POST, PATHS.parse("/orders/{number}/proof"), PROOFS),
            new Rule(null, PATHS.parse("/cart/items/**"), CART));

    private final RateLimits limits;

    StorefrontRateLimitFilter(RateLimits limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var path = PathContainer.parsePath(
                request.getRequestURI().substring(request.getContextPath().length()));
        for (var rule : RULES) {
            if ((rule.method() == null || rule.method().matches(request.getMethod())) && rule.path().matches(path)) {
                var wait = limits.tryTake(rule.limit(), request.getRemoteAddr());
                if (wait.isPresent()) {
                    response.setHeader(HttpHeaders.RETRY_AFTER,
                            String.valueOf(RateLimits.retryAfterSeconds(wait.get())));
                    response.sendError(429);
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }

    /** {@code method} null: any method. */
    private record Rule(HttpMethod method, PathPattern path, Limit limit) {
    }
}
