package com.unispark.ecommerce.order.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** Cash taken for one order; {@code driverId} null when the store collected it itself. */
@Entity
@Table(name = "cod_collection")
public class CodCollection extends TenantScopedEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "driver_id", updatable = false)
    private UUID driverId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Currency currency;

    @Column(name = "collected_at", nullable = false, updatable = false)
    private Instant collectedAt;

    protected CodCollection() {
    }

    public CodCollection(UUID orderId, UUID driverId, BigDecimal amount, Currency currency, Instant collectedAt) {
        this.orderId = orderId;
        this.driverId = driverId;
        this.amount = amount;
        this.currency = currency;
        this.collectedAt = collectedAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }
}
