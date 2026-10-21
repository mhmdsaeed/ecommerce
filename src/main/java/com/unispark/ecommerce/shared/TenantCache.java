package com.unispark.ecommerce.shared;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * One value per store, for data storefront pages read on every view but merchants rarely change (delivery coverage,
 * payment methods). Keyed by the store in {@link TenantContext}, so one store never sees another's.
 *
 * <p>{@link #evict()} after a change: it drops the entry at once and again when the transaction commits, so a page
 * read between the two cannot keep the old value. Other instances catch up within {@link #TTL}.
 */
public final class TenantCache<V> {

    public static final Duration TTL = Duration.ofSeconds(60);

    private final Cache<UUID, V> cache = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(TTL).build();

    public V get(Supplier<V> load) {
        return cache.get(TenantContext.requireTenantId(), storeId -> load.get());
    }

    public void evict() {
        var storeId = TenantContext.requireTenantId();
        cache.invalidate(storeId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate(storeId);
                }
            });
        }
    }
}
