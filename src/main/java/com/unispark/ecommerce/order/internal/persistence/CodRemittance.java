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

/** Cash a driver handed over to the store. */
@Entity
@Table(name = "cod_remittance")
public class CodRemittance extends TenantScopedEntity {

    @Column(name = "driver_id", nullable = false, updatable = false)
    private UUID driverId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Currency currency;

    @Column(name = "remitted_at", nullable = false, updatable = false)
    private Instant remittedAt;

    @Column(name = "received_by", nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(updatable = false)
    private String note;

    protected CodRemittance() {
    }

    public CodRemittance(UUID driverId, BigDecimal amount, Currency currency, Instant remittedAt, UUID receivedBy,
            String note) {
        this.driverId = driverId;
        this.amount = amount;
        this.currency = currency;
        this.remittedAt = remittedAt;
        this.receivedBy = receivedBy;
        this.note = note;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Instant getRemittedAt() {
        return remittedAt;
    }

    public UUID getReceivedBy() {
        return receivedBy;
    }

    public String getNote() {
        return note;
    }
}
