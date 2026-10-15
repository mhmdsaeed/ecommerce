package com.unispark.ecommerce.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One subscription per store (SPEC 5 {@code subscription}). */
public interface Subscriptions {

    Optional<SubscriptionView> forStore(UUID storeId);

    /** Ending soonest first; {@code status} null for all. */
    List<SubscriptionView> list(SubscriptionStatus status);

    /** The new plan applies from the next invoice. Only active plans can be chosen. */
    boolean changePlan(UUID storeId, String planCode);

    /**
     * @param periodEnd   the current period ends (and the next invoice is issued) on this day
     * @param graceUntil  with an open invoice, the store is suspended after this day; null otherwise
     */
    record SubscriptionView(UUID storeId, String planCode, SubscriptionStatus status, LocalDate periodStart,
            LocalDate periodEnd, LocalDate graceUntil) {
    }
}
