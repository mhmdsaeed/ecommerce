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

/** A shopper's proof of a transfer (a reference and/or a private screenshot), and the merchant's decision. */
@Entity
@Table(name = "payment_proof")
public class PaymentProof extends TenantScopedEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(name = "transaction_ref", updatable = false)
    private String transactionRef;

    @Column(name = "image_key", updatable = false)
    private String imageKey;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    protected PaymentProof() {
    }

    public PaymentProof(UUID orderId, PaymentMethod method, String transactionRef, String imageKey,
            Instant submittedAt) {
        this.orderId = orderId;
        this.method = method;
        this.transactionRef = transactionRef;
        this.imageKey = imageKey;
        this.submittedAt = submittedAt;
        this.status = PENDING;
    }

    /** @param reviewer the staff member deciding, or null for the system */
    public void review(boolean approved, UUID reviewer, String note, Instant at) {
        this.status = approved ? APPROVED : REJECTED;
        this.reviewedBy = reviewer;
        this.reviewNote = note;
        this.reviewedAt = at;
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

    public String getImageKey() {
        return imageKey;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getReviewNote() {
        return reviewNote;
    }
}
