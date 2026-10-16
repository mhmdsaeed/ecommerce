package com.unispark.ecommerce.tenant;

import java.util.Locale;
import java.util.UUID;

import com.unispark.ecommerce.shared.LocalizedText;

/**
 * What other modules need to know about a store. {@code id} is the tenant id.
 *
 * @param launched the owner has opened it to shoppers (the last step of the setup wizard); before that its
 *                 storefront says "opening soon", except through the owner's preview link
 */
public record StoreSummary(UUID id, String slug, LocalizedText name, StoreStatus status, Locale defaultLocale,
        boolean launched) {
}
