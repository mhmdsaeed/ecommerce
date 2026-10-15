package com.unispark.ecommerce.billing.internal;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Billing settings (SPEC 8).
 *
 * @param trialDays   how long a new store's trial lasts before the first invoice
 * @param graceDays   how long an invoice may stay unpaid before the store is suspended
 * @param defaultPlan the plan trials start on
 * @param payTo       where merchants send the money, shown on their billing page ({@code BILLING_PAY_TO}), e.g. a
 *                    wallet number; blank shows a generic "contact us" instead
 */
@ConfigurationProperties("app.billing")
record BillingProperties(
        @DefaultValue("14") int trialDays,
        @DefaultValue("7") int graceDays,
        @DefaultValue("basic") String defaultPlan,
        String payTo) {

    /** Billing days are Syrian calendar days (SPEC 18). */
    static final ZoneId ZONE = ZoneId.of("Asia/Damascus");
}
