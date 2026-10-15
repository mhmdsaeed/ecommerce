package com.unispark.ecommerce.billing;

/**
 * Where a store stands with its subscription (SPEC 5).
 * <ul>
 *   <li>TRIAL: the free first period; nothing billed yet.
 *   <li>ACTIVE: nothing owed.
 *   <li>PAST_DUE: an invoice is open; the store keeps working until the grace period ends.
 *   <li>SUSPENDED: grace ran out; billing suspended the store until it pays.
 *   <li>CANCELLED: no more invoices.
 * </ul>
 */
public enum SubscriptionStatus {
    TRIAL,
    ACTIVE,
    PAST_DUE,
    SUSPENDED,
    CANCELLED;

    /** Billed each month. */
    public boolean billed() {
        return this == TRIAL || this == ACTIVE || this == PAST_DUE;
    }
}
