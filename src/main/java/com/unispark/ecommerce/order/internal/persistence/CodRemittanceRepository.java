package com.unispark.ecommerce.order.internal.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped. */
public interface CodRemittanceRepository extends JpaRepository<CodRemittance, UUID> {

    List<CodRemittance> findByDriverIdOrderByRemittedAtDesc(UUID driverId, Pageable page);
}
