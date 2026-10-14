package com.unispark.ecommerce.identity.internal.security;

import org.springframework.security.core.AuthenticationException;

/** Too many login attempts for the account or from the client address. */
class LoginThrottledException extends AuthenticationException {

    LoginThrottledException() {
        super("Too many login attempts");
    }
}
