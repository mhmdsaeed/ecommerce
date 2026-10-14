package com.unispark.ecommerce.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.FieldProblem;

/**
 * The current store's USD to SYP rate (SPEC 7). Setting a rate adds a row; earlier rates stay in the history,
 * so orders can refer to the rate they used.
 */
public interface ExchangeRates {

    /** Highest sensible rate; anything above is surely a typo. */
    BigDecimal MAX_RATE = new BigDecimal("1000000");
    int RATE_DECIMALS = 6;

    Optional<ExchangeRate> current();

    /** Newest first. */
    List<ExchangeRate> history(int limit);

    /** {@code rate} as typed: pounds per dollar, Western or Arabic digits, up to six decimals. */
    RateResult set(String rate, UUID setBy);

    record ExchangeRate(UUID id, BigDecimal sypPerUsd, Instant effectiveAt, UUID setBy) {
    }

    sealed interface RateResult {
    }

    record Saved(ExchangeRate rate) implements RateResult {
    }

    record Invalid(FieldProblem problem) implements RateResult {
    }
}
