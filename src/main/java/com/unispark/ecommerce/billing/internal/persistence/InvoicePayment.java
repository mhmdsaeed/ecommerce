package com.unispark.ecommerce.billing.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.billing.InvoicePaymentMethod;
import com.unispark.ecommerce.shared.BaseEntity;

/** Money received for an invoice, in the invoice's currency. Platform entity; never changed once recorded. */
@Entity
@Table(name = "invoice_payment")
public class InvoicePayment extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private InvoicePaymentMethod method;

    @Column(updatable = false)
    private String reference;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected InvoicePayment() {
    }

    public InvoicePayment(UUID invoiceId, InvoicePaymentMethod method, String reference, BigDecimal amount,
            String recordedBy, Instant recordedAt) {
        this.invoiceId = invoiceId;
        this.method = method;
        this.reference = reference;
        this.amount = amount;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public InvoicePaymentMethod getMethod() {
        return method;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
