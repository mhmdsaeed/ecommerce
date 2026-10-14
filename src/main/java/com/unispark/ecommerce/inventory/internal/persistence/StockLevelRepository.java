package com.unispark.ecommerce.inventory.internal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped. Read-only by convention: writes go through {@code InventoryService}. */
public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    List<StockLevel> findByVariantIdIn(Collection<UUID> variantIds);
}
