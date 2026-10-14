package com.unispark.ecommerce.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after every status change, placing included ({@code from} null) (SPEC 6.1). Carries the tenant, since
 * listeners run without the request's {@code TenantContext}. Notifications (M11) listen for it.
 */
public record OrderStatusChanged(UUID tenantId, UUID orderId, int number, OrderStatus from, OrderStatus to,
        String actor, Instant at) {
}
