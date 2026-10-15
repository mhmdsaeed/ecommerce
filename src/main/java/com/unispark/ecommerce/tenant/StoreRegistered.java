package com.unispark.ecommerce.tenant;

import java.util.UUID;

/** Published when a store is created, so billing can start its trial (SPEC 8). */
public record StoreRegistered(UUID storeId) {
}
