package com.unispark.ecommerce.platformadmin.internal.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Model values every platform admin page shares. */
@ControllerAdvice(basePackageClasses = PlatformModelAdvice.class)
class PlatformModelAdvice {

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
}
