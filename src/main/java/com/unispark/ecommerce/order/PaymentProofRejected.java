package com.unispark.ecommerce.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when staff reject a shopper's payment proof (SPEC 6.2), so the shopper can be told to send another.
 * Carries the tenant, since listeners run without the request's {@code TenantContext}.
 *
 * @param note staff's reason for the shopper, or null
 */
public record PaymentProofRejected(UUID tenantId, UUID orderId, int number, String note, Instant at) {
}
