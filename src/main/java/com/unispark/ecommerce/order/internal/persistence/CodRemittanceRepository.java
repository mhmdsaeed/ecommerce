package com.unispark.ecommerce.order.internal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped. */
public interface CodRemittanceRepository extends JpaRepository<CodRemittance, UUID> {
}
