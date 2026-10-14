package com.unispark.ecommerce.pricing.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.pricing.internal.persistence.ExchangeRateRecord;
import com.unispark.ecommerce.pricing.internal.persistence.ExchangeRateRepository;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * USD to SYP rates, newest wins. The current rate is cached per store because every displayed price needs it;
 * setting a rate evicts this instance's entry, other instances follow within {@link #CACHE_TTL}.
 */
@Service
@Transactional(readOnly = true)
class ExchangeRateService implements ExchangeRates {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final ExchangeRateRepository rates;
    private final Clock clock;
    private final Cache<UUID, Optional<ExchangeRate>> current = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(CACHE_TTL)
            .build();

    ExchangeRateService(ExchangeRateRepository rates, Clock clock) {
        this.rates = rates;
        this.clock = clock;
    }

    @Override
    public Optional<ExchangeRate> current() {
        return current.get(TenantContext.requireTenantId(), tenant -> rates
                .findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc(Currency.USD, Currency.SYP)
                .map(ExchangeRateService::view));
    }

    @Override
    public List<ExchangeRate> history(int limit) {
        return rates.findByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc(Currency.USD, Currency.SYP,
                        PageRequest.of(0, Math.max(1, limit)))
                .stream().map(ExchangeRateService::view).toList();
    }

    @Override
    @Transactional
    public RateResult set(String input, UUID setBy) {
        var rate = Amounts.parse(input, RATE_DECIMALS);
        if (rate.isEmpty() || rate.get().signum() <= 0) {
            return new Invalid(new FieldProblem("rate", "invalid"));
        }
        if (rate.get().compareTo(MAX_RATE) > 0) {
            return new Invalid(new FieldProblem("rate", "too-high"));
        }
        var saved = rates.save(new ExchangeRateRecord(Currency.USD, Currency.SYP, rate.get().stripTrailingZeros(),
                clock.instant(), setBy));
        current.invalidate(TenantContext.requireTenantId());
        return new Saved(view(saved));
    }

    private static ExchangeRate view(ExchangeRateRecord record) {
        return new ExchangeRate(record.getId(), record.getRate().stripTrailingZeros(), record.getEffectiveAt(),
                record.getSetBy());
    }
}
