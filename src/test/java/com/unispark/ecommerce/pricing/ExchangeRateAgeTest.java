package com.unispark.ecommerce.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.pricing.ExchangeRates.ExchangeRate;

class ExchangeRateAgeTest {

    static final Instant SET = Instant.parse("2026-10-01T09:00:00Z");
    static final ExchangeRate RATE = new ExchangeRate(UUID.randomUUID(), new BigDecimal("118.5"), SET, null);

    @Test
    void isFreshForAWeek() {
        assertThat(RATE.staleAt(SET.plus(Duration.ofDays(7)))).isFalse();
    }

    @Test
    void isStaleOnceAWeekHasPassed() {
        assertThat(RATE.staleAt(SET.plus(Duration.ofDays(7)).plusSeconds(1))).isTrue();
    }

    @Test
    void countsWholeDays() {
        assertThat(RATE.ageInDays(SET.plus(Duration.ofDays(12)).plus(Duration.ofHours(23)))).isEqualTo(12);
    }
}
