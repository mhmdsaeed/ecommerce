package com.unispark.ecommerce.identity.internal.security;

import java.io.Serializable;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.AuthenticationDetailsSource;

/** The staff login form's store address, plus the client address for rate limiting. */
record StaffLoginDetails(String storeSlug, String remoteAddress) implements Serializable {

    static final String STORE_PARAMETER = "store";

    static final AuthenticationDetailsSource<HttpServletRequest, StaffLoginDetails> SOURCE =
            request -> new StaffLoginDetails(request.getParameter(STORE_PARAMETER), request.getRemoteAddr());
}
