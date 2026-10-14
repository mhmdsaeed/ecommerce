package com.unispark.ecommerce.tenant;

import java.util.Optional;
import java.util.UUID;

/** Finds stores. Stores are platform data, so lookups work with or without a tenant in context. */
public interface StoreLookup {

    Optional<StoreSummary> findBySlug(String slug);

    Optional<StoreSummary> findById(UUID id);
}
