package com.unispark.ecommerce.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Finds stores. Stores are platform data, so lookups work with or without a tenant in context. */
public interface StoreLookup {

    Optional<StoreSummary> findBySlug(String slug);

    Optional<StoreSummary> findById(UUID id);

    /** Every store id, for jobs that visit each tenant in turn (SPEC 14). */
    List<UUID> allIds();

    /**
     * Whether {@code token} is the store's preview secret, which lets its staff see the storefront before launch.
     * Compared in constant time.
     */
    boolean previewAllowed(UUID storeId, String token);
}
