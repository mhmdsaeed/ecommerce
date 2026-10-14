package com.unispark.ecommerce.shared;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTMX conventions (SPEC 9.1): HTMX requests get fragments, full requests get pages. Validation errors come back as
 * 200 with the re-rendered fragment, since htmx does not swap 4xx responses by default.
 */
public final class Htmx {

    public static final String REQUEST = "HX-Request";
    /** Response header: htmx navigates the whole page there instead of swapping a fragment. */
    public static final String REDIRECT = "HX-Redirect";

    private Htmx() {
    }

    public static boolean isHtmx(HttpServletRequest request) {
        return "true".equals(request.getHeader(REQUEST));
    }
}
