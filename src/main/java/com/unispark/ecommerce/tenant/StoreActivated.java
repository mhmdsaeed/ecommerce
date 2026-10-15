package com.unispark.ecommerce.tenant;

import java.util.UUID;

/**
 * Published when the platform admin activates a store, so billing can pick up a subscription it had suspended
 * (SPEC 8, 9.4).
 */
public record StoreActivated(UUID storeId) {
}
