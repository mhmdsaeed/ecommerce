package com.unispark.ecommerce.tenant;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.RoundingRule;

/**
 * Store-wide settings other modules depend on. Reads are cached for a short time, since they happen on every
 * request (digits, language, theme) and every displayed price.
 */
public interface StoreSettings {

    /** The settings of the store in {@code TenantContext}. */
    Settings current();

    Optional<Settings> of(UUID storeId);

    /** Changes the pricing settings of the store in {@code TenantContext}. */
    Settings updatePricing(Currency baseCurrency, Currency displayCurrency, RoundingRule roundingRule,
            NumberDigits numberDigits);

    /** Replaces the theme of the store in {@code TenantContext}. */
    Settings updateTheme(StoreTheme theme);

    /**
     * {@code baseCurrency} is the default for new products; {@code displayCurrency} is what shoppers see prices in
     * (SPEC 7). {@code defaultLocale} applies to visitors who have not picked a language (SPEC 11).
     */
    record Settings(UUID storeId, LocalizedText name, Locale defaultLocale, Currency baseCurrency,
            Currency displayCurrency, RoundingRule roundingRule, NumberDigits numberDigits, StoreTheme theme) {
    }
}
