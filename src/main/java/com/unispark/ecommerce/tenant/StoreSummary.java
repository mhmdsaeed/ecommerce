package com.unispark.ecommerce.tenant;

import java.util.Locale;
import java.util.UUID;

import com.unispark.ecommerce.shared.LocalizedText;

/** What other modules need to know about a store. {@code id} is the tenant id. */
public record StoreSummary(UUID id, String slug, LocalizedText name, StoreStatus status, Locale defaultLocale) {
}
