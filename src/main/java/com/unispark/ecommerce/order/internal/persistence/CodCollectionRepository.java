package com.unispark.ecommerce.order.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped. */
public interface CodCollectionRepository extends JpaRepository<CodCollection, UUID> {

    boolean existsByOrderId(UUID orderId);

    Optional<CodCollection> findByOrderId(UUID orderId);
}
