package com.unispark.ecommerce.shared;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An amount in a currency (SPEC 5, 7). Always two decimal places, like the {@code numeric(19,2)} columns it is
 * stored in. Conversion between currencies and the store's rounding rules arrive with pricing (M4).
 */
public record Money(BigDecimal amount, Currency currency) {

    public static final int SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("Money has at most " + SCALE + " decimal places: " + amount);
        }
        amount = amount.setScale(SCALE);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }
}
