package com.unispark.ecommerce.pricing;

import java.math.BigDecimal;

import com.unispark.ecommerce.shared.Money;

/** Prices as the current store's shoppers see them (SPEC 7). */
public interface Prices {

    /**
     * {@code price} in the store's display currency, converted at the current rate and rounded by the store's
     * rule. Without a rate it cannot be converted and is shown in its own currency.
     */
    DisplayPrice display(Money price);

    /**
     * @param rate the pounds-per-dollar rate used, or null when nothing was converted; orders keep it as their
     *             rate snapshot
     */
    record DisplayPrice(Money amount, Money original, BigDecimal rate) {

        public boolean converted() {
            return rate != null;
        }
    }
}
