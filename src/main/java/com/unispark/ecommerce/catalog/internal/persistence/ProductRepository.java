package com.unispark.ecommerce.catalog.internal.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unispark.ecommerce.catalog.ProductStatus;

/** Tenant-scoped: only the current store's products. */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    long countByCategoryId(UUID categoryId);

    /** {@code pattern} is a lowercase LIKE pattern, or null for all products. */
    @Query("""
            select p from Product p
            where (:status is null or p.status = :status)
              and (:pattern is null or p.searchText like :pattern escape '\\')
            """)
    Page<Product> search(@Param("status") ProductStatus status, @Param("pattern") String pattern, Pageable pageable);

    @Query("select p.categoryId as categoryId, count(p) as products from Product p "
            + "where p.categoryId is not null group by p.categoryId")
    List<CategoryCount> countByCategory();

    interface CategoryCount {

        UUID getCategoryId();

        long getProducts();
    }
}
