package com.unispark.ecommerce.tenant.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    Optional<Store> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query("select s.id from Store s order by s.id")
    List<UUID> findAllIds();
}
