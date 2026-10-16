package com.unispark.ecommerce.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
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
     * Changes the checkout settings of the store in {@code TenantContext}.
     *
     * @param newCustomerMaxTotal in the display currency; null for no limit, otherwise positive
     */
    Settings updateCheckout(boolean otpRequired, BigDecimal newCustomerMaxTotal);

    /**
     * Changes the name and contact numbers of the store in {@code TenantContext}. The name needs at least one
     * language (the other falls back to it); the phones are optional Syrian or international numbers.
     */
    DetailsResult updateDetails(String nameAr, String nameEn, String contactPhone, String whatsapp);

    /** Opens the store in {@code TenantContext} to shoppers (SPEC 9.3, the wizard's last step). Idempotent. */
    void launch();

    /**
     * The secret that lets the store's own staff preview its storefront before launch. It is shown in the admin
     * only, and opens nothing once the store is live (its storefront is open anyway).
     */
    String previewToken();

    /**
     * {@code baseCurrency} is the default for new products; {@code displayCurrency} is what shoppers see prices in
     * (SPEC 7). {@code defaultLocale} applies to visitors who have not picked a language (SPEC 11).
     * {@code otpRequired} asks shoppers to confirm their phone with a code at checkout; {@code newCustomerMaxTotal}
     * caps the orders of customers without a completed order, in the display currency, null for no cap (SPEC 6.4).
     * {@code contactPhone} and {@code whatsapp} are in E.164 or null; {@code launchedAt} is null until launch.
     */
    record Settings(UUID storeId, LocalizedText name, Locale defaultLocale, Currency baseCurrency,
            Currency displayCurrency, RoundingRule roundingRule, NumberDigits numberDigits, StoreTheme theme,
            boolean otpRequired, BigDecimal newCustomerMaxTotal, String contactPhone, String whatsapp,
            Instant launchedAt) {

        public boolean launched() {
            return launchedAt != null;
        }
    }

    sealed interface DetailsResult {
    }

    record DetailsSaved(Settings settings) implements DetailsResult {
    }

    /** @param problems e.g. ("name", "required"), ("whatsapp", "invalid") */
    record DetailsInvalid(List<FieldProblem> problems) implements DetailsResult {
    }
}
