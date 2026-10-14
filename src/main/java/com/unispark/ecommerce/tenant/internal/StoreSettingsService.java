package com.unispark.ecommerce.tenant.internal;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocaleAdjuster;
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.RoundingRule;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.internal.persistence.StoreRepository;

/**
 * Store settings with a short-lived cache: the locale adjuster reads them on every request. Updates evict this
 * instance's entry at once; other instances catch up within {@link #CACHE_TTL}.
 *
 * <p>Also the {@link LocaleAdjuster} that applies the store's digits to the request locale. Pages without a store
 * (signup, platform admin) use Western digits.
 */
@Service
@Transactional(readOnly = true)
class StoreSettingsService implements StoreSettings, LocaleAdjuster {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final NumberDigits PLATFORM_DIGITS = NumberDigits.WESTERN;

    private final StoreRepository stores;
    private final Cache<UUID, Optional<Settings>> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(CACHE_TTL)
            .build();

    StoreSettingsService(StoreRepository stores) {
        this.stores = stores;
    }

    @Override
    public Settings current() {
        var storeId = TenantContext.requireTenantId();
        return of(storeId).orElseThrow(() -> new IllegalStateException("No store " + storeId));
    }

    @Override
    public Optional<Settings> of(UUID storeId) {
        return cache.get(storeId, id -> stores.findById(id).map(store -> store.toSettings()));
    }

    @Override
    @Transactional
    public Settings updatePricing(Currency baseCurrency, Currency displayCurrency, RoundingRule roundingRule,
            NumberDigits numberDigits) {
        var storeId = TenantContext.requireTenantId();
        var store = stores.findById(storeId).orElseThrow(() -> new IllegalStateException("No store " + storeId));
        store.updatePricing(Objects.requireNonNull(baseCurrency), Objects.requireNonNull(displayCurrency),
                Objects.requireNonNull(roundingRule), Objects.requireNonNull(numberDigits));
        cache.invalidate(storeId);
        return store.toSettings();
    }

    @Override
    public Locale adjust(Locale locale) {
        var digits = TenantContext.currentTenantId()
                .flatMap(this::of)
                .map(Settings::numberDigits)
                .orElse(PLATFORM_DIGITS);
        return digits.apply(locale);
    }
}
