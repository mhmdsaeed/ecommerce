package com.unispark.ecommerce.catalog.internal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped: only the current store's categories. */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    int countByParentId(UUID parentId);
}
