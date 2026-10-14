package com.unispark.ecommerce.catalog.internal.persistence;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped. Variants are changed through their product; this is for store-wide checks and listings. */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    boolean existsBySkuAndIdNot(String sku, UUID id);

    /** Recently changed products first. {@code pattern} is a lowercase LIKE pattern, or null for everything. */
    @Query(value = """
            select v from ProductVariant v join fetch v.product p
            where :pattern is null or p.searchText like :pattern escape '\\' or lower(v.sku) like :pattern escape '\\'
            order by p.updatedAt desc, p.id, v.position
            """, countQuery = """
            select count(v) from ProductVariant v join v.product p
            where :pattern is null or p.searchText like :pattern escape '\\' or lower(v.sku) like :pattern escape '\\'
            """)
    Page<ProductVariant> search(@Param("pattern") String pattern, Pageable pageable);
}
