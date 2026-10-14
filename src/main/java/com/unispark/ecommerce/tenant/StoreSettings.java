package com.unispark.ecommerce.tenant;

import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.RoundingRule;

/**
 * Store-wide settings other modules depend on. Reads are cached for a short time, since they happen on every
 * request (digits) and every displayed price.
 */
public interface StoreSettings {

    /** The settings of the store in {@code TenantContext}. */
    Settings current();

    Optional<Settings> of(UUID storeId);

    /** Changes the pricing settings of the store in {@code TenantContext}. */
    Settings updatePricing(Currency baseCurrency, Currency displayCurrency, RoundingRule roundingRule,
            NumberDigits numberDigits);

    /**
     * {@code baseCurrency} is the default for new products; {@code displayCurrency} is what shoppers see prices in
     * (SPEC 7).
     */
    record Settings(UUID storeId, Currency baseCurrency, Currency displayCurrency, RoundingRule roundingRule,
            NumberDigits numberDigits) {
    }
}
