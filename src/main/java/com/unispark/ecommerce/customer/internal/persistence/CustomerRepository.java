package com.unispark.ecommerce.customer.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped: only the current store's customers. */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** {@code phone} in E.164. */
    Optional<Customer> findByPhone(String phone);
}
