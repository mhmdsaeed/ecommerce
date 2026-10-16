/**
 * REST API v1 (SPEC 13): JSON under {@code /api/v1} on the main host, for the mobile app. Stateless: shopper
 * requests name their store in {@code X-Store-Id}, merchant tokens (JWT) carry it as a claim. Reads go through the
 * domain modules' services; OpenAPI docs at {@code /api/docs}.
 */
@ApplicationModule(displayName = "REST API")
package com.unispark.ecommerce.api;

import org.springframework.modulith.ApplicationModule;
