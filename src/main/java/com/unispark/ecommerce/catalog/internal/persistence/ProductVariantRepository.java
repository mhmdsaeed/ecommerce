package com.unispark.ecommerce.catalog.internal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped. Variants are changed through their product; this is for store-wide SKU checks. */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    boolean existsBySkuAndIdNot(String sku, UUID id);
}
