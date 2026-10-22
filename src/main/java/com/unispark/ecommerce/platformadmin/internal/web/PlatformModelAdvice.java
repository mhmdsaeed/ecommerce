package com.unispark.ecommerce.platformadmin.internal.web;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.unispark.ecommerce.identity.ColorSchemes;

/** Model values every platform admin page shares. */
@ControllerAdvice(basePackageClasses = PlatformModelAdvice.class)
class PlatformModelAdvice {

    private final ColorSchemes schemes;

    PlatformModelAdvice(ColorSchemes schemes) {
        this.schemes = schemes;
    }

    /**
     * Which part of the platform admin the request is in, for the navigation: the first path segment after
     * {@code /platform} ({@code invoices} for {@code /platform/invoices/…}), {@code dashboard} for {@code /platform}.
     */
    @ModelAttribute("platformSection")
    String platformSection(HttpServletRequest request) {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/platform")) {
            return null;
        }
        var segments = path.substring("/platform".length()).split("/");
        return segments.length < 2 || segments[1].isEmpty() ? "dashboard" : segments[1];
    }

    /**
     * The admin's light or dark choice, for the layout's data-scheme; full pages of a logged-in admin only, a form
     * sent back with mistakes (a POST that renders its page) included.
     */
    @ModelAttribute("colorScheme")
    String colorScheme(HttpServletRequest request, Principal principal) {
        if (principal == null || request.getHeader("HX-Request") != null) {
            return null;
        }
        return schemes.ofPlatformUser(principal.getName()).attribute();
    }
}
