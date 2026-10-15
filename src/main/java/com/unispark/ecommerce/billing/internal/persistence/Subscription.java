package com.unispark.ecommerce.billing.internal.persistence;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.billing.SubscriptionStatus;
import com.unispark.ecommerce.shared.BaseEntity;

/** A store's subscription. Platform entity; periods are calendar days in Syrian time. */
@Entity
@Table(name = "subscription")
public class Subscription extends BaseEntity {

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "plan_code", nullable = false)
    private String planCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(name = "current_period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "current_period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "grace_until")
    private LocalDate graceUntil;

    protected Subscription() {
    }

    /** A trial from {@code today} until {@code trialEnd}, when the first invoice is issued. */
    public static Subscription trial(UUID storeId, String planCode, LocalDate today, LocalDate trialEnd) {
        var subscription = new Subscription();
        subscription.storeId = storeId;
        subscription.planCode = planCode;
        subscription.status = SubscriptionStatus.TRIAL;
        subscription.periodStart = today;
        subscription.periodEnd = trialEnd;
        return subscription;
    }

    /** The next month has been invoiced: it becomes the current period, and the store owes it. */
    public void billedNextPeriod(LocalDate graceEnd) {
        periodStart = periodEnd;
        periodEnd = periodEnd.plusMonths(1);
        status = SubscriptionStatus.PAST_DUE;
        if (graceUntil == null) {
            graceUntil = graceEnd;
        }
    }

    /**
     * The next month is on a free plan: it becomes the current period with nothing to pay. An unpaid invoice from
     * before still counts, so a subscription in its grace period stays past due.
     */
    public void startedFreePeriod() {
        periodStart = periodEnd;
        periodEnd = periodEnd.plusMonths(1);
        if (graceUntil == null) {
            status = SubscriptionStatus.ACTIVE;
        }
    }

    /**
     * The platform admin reopened a store billing had suspended, without the money arriving. Billing resumes with
     * a fresh month from today, and the store gets a new grace period for what it still owes.
     */
    public void reinstated(LocalDate today, LocalDate graceEnd) {
        periodStart = today;
        periodEnd = today.plusMonths(1);
        status = SubscriptionStatus.PAST_DUE;
        graceUntil = graceEnd;
    }

    public void suspended() {
        status = SubscriptionStatus.SUSPENDED;
    }

    /**
     * Nothing is owed any more. A suspended subscription starts a fresh month today, since the store was closed
     * for part of the one it paid for.
     */
    public void settled(LocalDate today) {
        if (status == SubscriptionStatus.SUSPENDED) {
            periodStart = today;
            periodEnd = today.plusMonths(1);
        }
        status = SubscriptionStatus.ACTIVE;
        graceUntil = null;
    }

    public void changePlan(String planCode) {
        this.planCode = planCode;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getGraceUntil() {
        return graceUntil;
    }
}
