package com.unispark.ecommerce.identity.internal;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;

/**
 * Rate limits for logins and signups (SPEC 10), in memory per application instance.
 * <ul>
 *   <li>per account: 5 attempts per 15 minutes, reset by a successful login, against password guessing;
 *   <li>per client address: 30 login attempts per 5 minutes, against trying many accounts;
 *   <li>per client address: 5 signups per hour.
 * </ul>
 * Behind a reverse proxy the client address must come from forwarded headers (production setup, M15).
 */
@Component
public class LoginThrottle {

    static final int ACCOUNT_ATTEMPTS = 5;
    static final Duration ACCOUNT_WINDOW = Duration.ofMinutes(15);
    static final int ADDRESS_ATTEMPTS = 30;
    static final Duration ADDRESS_WINDOW = Duration.ofMinutes(5);
    static final int SIGNUPS = 5;
    static final Duration SIGNUP_WINDOW = Duration.ofHours(1);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    /** Counts one login attempt; false when the address or the account is over its limit. */
    public boolean tryLogin(String clientAddress, String account) {
        if (!bucket("login-address:" + clientAddress, ADDRESS_ATTEMPTS, ADDRESS_WINDOW).tryConsume(1)) {
            return false;
        }
        return bucket("login-account:" + account, ACCOUNT_ATTEMPTS, ACCOUNT_WINDOW).tryConsume(1);
    }

    public void loginSucceeded(String account) {
        buckets.invalidate("login-account:" + account);
    }

    public boolean trySignup(String clientAddress) {
        return bucket("signup-address:" + clientAddress, SIGNUPS, SIGNUP_WINDOW).tryConsume(1);
    }

    private Bucket bucket(String key, int attempts, Duration window) {
        return buckets.get(key, ignored -> Bucket.builder()
                .addLimit(limit -> limit.capacity(attempts).refillIntervally(attempts, window))
                .build());
    }
}
