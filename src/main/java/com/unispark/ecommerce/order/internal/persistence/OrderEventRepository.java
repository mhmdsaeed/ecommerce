package com.unispark.ecommerce.order.internal.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped. Append-only: only {@code save} of new events is ever used. */
public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {

    List<OrderEvent> findByOrderIdOrderByPosition(UUID orderId);

    int countByOrderId(UUID orderId);
}
