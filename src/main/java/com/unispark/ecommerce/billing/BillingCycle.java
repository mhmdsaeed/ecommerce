package com.unispark.ecommerce.billing;

import java.time.LocalDate;

/**
 * The daily billing run (SPEC 14), in platform scope. Safe to run again on the same day: a month is billed once
 * and a store is suspended once.
 * <ol>
 *   <li>Stores without a subscription get a trial.
 *   <li>Each billed subscription whose period has ended gets an invoice for the next month, due at once, and
 *       becomes PAST_DUE with a grace period; missed months are caught up one by one.
 *   <li>PAST_DUE subscriptions whose grace ended with an invoice still open are suspended, and so is the store.
 * </ol>
 */
public interface BillingCycle {

    CycleReport run(LocalDate today);

    record CycleReport(int trialsStarted, int invoicesIssued, int storesSuspended) {
    }
}
