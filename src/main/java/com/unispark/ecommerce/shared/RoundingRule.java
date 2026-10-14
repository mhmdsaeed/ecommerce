package com.unispark.ecommerce.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How converted Syrian pound prices are rounded for display (SPEC 7). Prices a merchant typed in the display
 * currency are never rounded; only conversions are. Rounding is to the nearest step, half up, but a positive price
 * never rounds down to zero: it becomes one step instead.
 */
public enum RoundingRule {
    NONE(null),
    NEAREST_5(new BigDecimal("5")),
    NEAREST_10(new BigDecimal("10")),
    NEAREST_100(new BigDecimal("100"));

    private final BigDecimal step;

    RoundingRule(BigDecimal step) {
        this.step = step;
    }

    /** Rounds a non-negative amount; the result always has {@link Money#SCALE} decimals. */
    public BigDecimal apply(BigDecimal amount) {
        if (step == null) {
            return amount.setScale(Money.SCALE, RoundingMode.HALF_UP);
        }
        var rounded = amount.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        if (rounded.signum() == 0 && amount.signum() > 0) {
            rounded = step;
        }
        return rounded.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
    }
}
