package com.unispark.ecommerce.billing.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.billing.InvoiceStatus;
import com.unispark.ecommerce.shared.BaseEntity;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

/** An invoice to a store. Platform entity. */
@Entity
@Table(name = "invoice")
public class Invoice extends BaseEntity {

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private long number;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(name = "plan_code", updatable = false)
    private String planCode;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @Column(name = "issued_on", nullable = false, updatable = false)
    private LocalDate issuedOn;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "period_start", updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", updatable = false)
    private LocalDate periodEnd;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason")
    private String voidReason;

    protected Invoice() {
    }

    /** A subscription month, billed in advance. */
    public static Invoice forPeriod(UUID storeId, long number, String description, String planCode, Money amount,
            LocalDate issuedOn, LocalDate periodStart, LocalDate periodEnd) {
        var invoice = manual(storeId, number, description, amount, issuedOn, periodStart);
        invoice.planCode = planCode;
        invoice.periodStart = periodStart;
        invoice.periodEnd = periodEnd;
        return invoice;
    }

    public static Invoice manual(UUID storeId, long number, String description, Money amount, LocalDate issuedOn,
            LocalDate dueDate) {
        var invoice = new Invoice();
        invoice.storeId = storeId;
        invoice.number = number;
        invoice.description = description;
        invoice.amount = amount.amount();
        invoice.currency = amount.currency();
        invoice.status = InvoiceStatus.OPEN;
        invoice.issuedOn = issuedOn;
        invoice.dueDate = dueDate;
        return invoice;
    }

    public void paid(Instant at) {
        status = InvoiceStatus.PAID;
        paidAt = at;
    }

    public void voided(Instant at, String reason) {
        status = InvoiceStatus.VOID;
        voidedAt = at;
        voidReason = reason;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public long getNumber() {
        return number;
    }

    public String getDescription() {
        return description;
    }

    public String getPlanCode() {
        return planCode;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public String getVoidReason() {
        return voidReason;
    }
}
