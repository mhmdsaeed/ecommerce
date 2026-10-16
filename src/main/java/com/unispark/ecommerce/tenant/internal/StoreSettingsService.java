package com.unispark.ecommerce.tenant.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.DefaultLocaleSource;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocaleAdjuster;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.PhoneNumbers;
import com.unispark.ecommerce.shared.RoundingRule;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreTheme;
import com.unispark.ecommerce.tenant.internal.persistence.Store;
import com.unispark.ecommerce.tenant.internal.persistence.StoreRepository;

/**
 * Store settings with a short-lived cache: the locale resolver reads them on every request. Updates evict this
 * instance's entry at once; other instances catch up within {@link #CACHE_TTL}.
 *
 * <p>Also applies the current store to the request locale: its language when the visitor has not picked one
 * ({@link DefaultLocaleSource}) and its digits ({@link LocaleAdjuster}). Pages without a store (signup, platform
 * admin) use Western digits.
 */
@Service
@Transactional(readOnly = true)
class StoreSettingsService implements StoreSettings, LocaleAdjuster, DefaultLocaleSource {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final NumberDigits PLATFORM_DIGITS = NumberDigits.WESTERN;
    static final int MAX_NAME_LENGTH = 100;

    private final StoreRepository stores;
    private final Clock clock;
    private final Cache<UUID, Optional<Settings>> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(CACHE_TTL)
            .build();

    StoreSettingsService(StoreRepository stores, Clock clock) {
        this.stores = stores;
        this.clock = clock;
    }

    @Override
    public Settings current() {
        var storeId = TenantContext.requireTenantId();
        return of(storeId).orElseThrow(() -> new IllegalStateException("No store " + storeId));
    }

    @Override
    public Optional<Settings> of(UUID storeId) {
        return cache.get(storeId, id -> stores.findById(id).map(Store::toSettings));
    }

    @Override
    @Transactional
    public Settings updatePricing(Currency baseCurrency, Currency displayCurrency, RoundingRule roundingRule,
            NumberDigits numberDigits) {
        var store = currentStore();
        store.updatePricing(Objects.requireNonNull(baseCurrency), Objects.requireNonNull(displayCurrency),
                Objects.requireNonNull(roundingRule), Objects.requireNonNull(numberDigits));
        cache.invalidate(store.getId());
        return store.toSettings();
    }

    @Override
    @Transactional
    public Settings updateTheme(StoreTheme theme) {
        var store = currentStore();
        store.updateTheme(theme);
        cache.invalidate(store.getId());
        return store.toSettings();
    }

    @Override
    @Transactional
    public Settings updateCheckout(boolean otpRequired, BigDecimal newCustomerMaxTotal) {
        if (newCustomerMaxTotal != null && newCustomerMaxTotal.signum() <= 0) {
            throw new IllegalArgumentException("The new-customer limit must be positive: " + newCustomerMaxTotal);
        }
        var store = currentStore();
        store.updateCheckout(otpRequired, newCustomerMaxTotal);
        cache.invalidate(store.getId());
        return store.toSettings();
    }

    @Override
    @Transactional
    public DetailsResult updateDetails(String nameAr, String nameEn, String contactPhone, String whatsapp) {
        var problems = new ArrayList<FieldProblem>();
        var ar = clean(nameAr);
        var en = clean(nameEn);
        if (ar == null && en == null) {
            problems.add(new FieldProblem("name", "required"));
        } else if (ar != null && ar.length() > MAX_NAME_LENGTH || en != null && en.length() > MAX_NAME_LENGTH) {
            problems.add(new FieldProblem("name", "too-long"));
        }
        var phone = phone(contactPhone, "contactPhone", problems);
        var whatsappNumber = phone(whatsapp, "whatsapp", problems);
        if (!problems.isEmpty()) {
            return new DetailsInvalid(problems);
        }
        var store = currentStore();
        store.updateDetails(LocalizedText.of(ar, en), phone, whatsappNumber);
        cache.invalidate(store.getId());
        return new DetailsSaved(store.toSettings());
    }

    @Override
    @Transactional
    public void launch() {
        var store = currentStore();
        store.launch(clock.instant());
        cache.invalidate(store.getId());
    }

    @Override
    public String previewToken() {
        return currentStore().getPreviewToken();
    }

    /** Blank is no number; anything else must be a valid phone, stored in E.164. */
    private static String phone(String typed, String field, List<FieldProblem> problems) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        var normalized = PhoneNumbers.normalize(typed);
        if (normalized.isEmpty()) {
            problems.add(new FieldProblem(field, "invalid"));
            return null;
        }
        return normalized.get();
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    @Override
    public Locale adjust(Locale locale) {
        var digits = currentSettings().map(Settings::numberDigits).orElse(PLATFORM_DIGITS);
        return digits.apply(locale);
    }

    @Override
    public Optional<Locale> defaultLocale() {
        return currentSettings().map(Settings::defaultLocale);
    }

    private Optional<Settings> currentSettings() {
        return TenantContext.currentTenantId().flatMap(this::of);
    }

    private Store currentStore() {
        var storeId = TenantContext.requireTenantId();
        return stores.findById(storeId).orElseThrow(() -> new IllegalStateException("No store " + storeId));
    }
}
