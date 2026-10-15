package com.unispark.ecommerce.customer.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Tenant-scoped: only the current store's customers. */
public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    /** {@code phone} in E.164. */
    Optional<Customer> findByPhone(String phone);
}
