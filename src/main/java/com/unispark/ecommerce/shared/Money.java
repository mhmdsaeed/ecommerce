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

    public boolean sameCurrency(Money other) {
        return other != null && currency == other.currency;
    }

    /** Both amounts must be in the same currency; converting is the pricing module's job. */
    public Money plus(Money other) {
        return new Money(amount.add(requireSameCurrency(other).amount), currency);
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(requireSameCurrency(other).amount), currency);
    }

    public boolean isLessThan(Money other) {
        return amount.compareTo(requireSameCurrency(other).amount) < 0;
    }

    private Money requireSameCurrency(Money other) {
        if (!sameCurrency(other)) {
            throw new IllegalArgumentException("Different currencies: " + this + " and " + other);
        }
        return other;
    }
}
