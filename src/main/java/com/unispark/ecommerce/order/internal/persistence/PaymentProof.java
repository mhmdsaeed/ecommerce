package com.unispark.ecommerce.order.internal.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/**
 * A shopper's proof of a transfer. M8 takes the transaction reference; screenshots ({@code image_key}) and the
 * merchant's review ({@code reviewed_by}, {@code review_note}) are mapped with M9.
 */
@Entity
@Table(name = "payment_proof")
public class PaymentProof extends TenantScopedEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(name = "transaction_ref", updatable = false)
    private String transactionRef;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(nullable = false)
    private String status;

    protected PaymentProof() {
    }

    public PaymentProof(UUID orderId, PaymentMethod method, String transactionRef, Instant submittedAt) {
        this.orderId = orderId;
        this.method = method;
        this.transactionRef = transactionRef;
        this.submittedAt = submittedAt;
        this.status = "PENDING";
    }

    public UUID getOrderId() {
        return orderId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getStatus() {
        return status;
    }
}
