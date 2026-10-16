package com.unispark.ecommerce.shared;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;

/**
 * Request budgets per client (SPEC 10, M15), in memory per application instance: each {@link Limit} allows
 * {@code requests} per {@code window} for one key, usually the client address. Behind a reverse proxy the address
 * comes from its forwarded headers (prod profile). {@code app.rate-limits.enabled=false} lifts all limits (tests
 * that are not about them share one address).
 */
@Component
public class RateLimits {

    private final boolean enabled;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public RateLimits(@Value("${app.rate-limits.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Counts one request against {@code limit} for {@code key}. Empty when it is allowed; otherwise how long until
     * the next one would be.
     */
    public Optional<Duration> tryTake(Limit limit, String key) {
        if (!enabled) {
            return Optional.empty();
        }
        var bucket = buckets.get(limit.name() + ":" + key, ignored -> Bucket.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.requests())
                        .refillGreedy(limit.requests(), limit.window()))
                .build());
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    /** Seconds for a {@code Retry-After} header: at least one. */
    public static long retryAfterSeconds(Duration wait) {
        return Math.max(1, (wait.toMillis() + 999) / 1000);
    }

    /** @param name distinguishes the budgets of different limits for the same key */
    public record Limit(String name, int requests, Duration window) {
    }
}
