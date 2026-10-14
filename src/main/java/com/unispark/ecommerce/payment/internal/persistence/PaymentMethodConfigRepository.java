package com.unispark.ecommerce.payment.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.unispark.ecommerce.payment.PaymentMethod;

/** Tenant-scoped: only the current store's settings. */
public interface PaymentMethodConfigRepository extends JpaRepository<PaymentMethodConfig, UUID> {

    Optional<PaymentMethodConfig> findByMethod(PaymentMethod method);
}
