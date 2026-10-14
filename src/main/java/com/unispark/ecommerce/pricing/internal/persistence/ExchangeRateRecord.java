package com.unispark.ecommerce.pricing.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** One rate as set by the merchant. Never changed afterwards: a new rate is a new row. */
@Entity
@Immutable
@Table(name = "exchange_rate")
public class ExchangeRateRecord extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "from_currency", nullable = false)
    private Currency fromCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_currency", nullable = false)
    private Currency toCurrency;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "set_by")
    private UUID setBy;

    protected ExchangeRateRecord() {
    }

    public ExchangeRateRecord(Currency fromCurrency, Currency toCurrency, BigDecimal rate, Instant effectiveAt,
            UUID setBy) {
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.rate = rate;
        this.effectiveAt = effectiveAt;
        this.setBy = setBy;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public UUID getSetBy() {
        return setBy;
    }
}
