package com.unispark.ecommerce.pricing.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.unispark.ecommerce.shared.Currency;

/** Tenant-scoped: only the current store's rates. */
public interface ExchangeRateRepository extends JpaRepository<ExchangeRateRecord, UUID> {

    Optional<ExchangeRateRecord> findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc(Currency from, Currency to);

    List<ExchangeRateRecord> findByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc(Currency from, Currency to,
            Pageable page);
}
