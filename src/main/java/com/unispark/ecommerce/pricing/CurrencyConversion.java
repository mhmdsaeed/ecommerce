package com.unispark.ecommerce.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.RoundingRule;

/**
 * Converts prices between the two store currencies (SPEC 7). Pure arithmetic, so orders can repeat a conversion
 * from their rate snapshot and get exactly the same result.
 *
 * <ul>
 *   <li>The rate is pounds per dollar ({@code sypPerUsd}), as merchants quote it.
 *   <li>To SYP: multiply, then apply the store's {@link RoundingRule}.
 *   <li>To USD: divide and round half up to cents.
 *   <li>A positive price never converts to zero: it becomes the smallest step (one cent, or one rounding step).
 *   <li>A price already in the target currency is returned unchanged; merchants' own prices are never rounded.
 * </ul>
 */
public final class CurrencyConversion {

    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    private CurrencyConversion() {
    }

    public static Money convert(Money price, Currency target, BigDecimal sypPerUsd, RoundingRule rounding) {
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(target, "target");
        if (price.currency() == target) {
            return price;
        }
        Objects.requireNonNull(sypPerUsd, "sypPerUsd");
        if (sypPerUsd.signum() <= 0) {
            throw new IllegalArgumentException("Exchange rate must be positive: " + sypPerUsd);
        }
        return switch (target) {
            case SYP -> Money.of(rounding.apply(price.amount().multiply(sypPerUsd)), Currency.SYP);
            case USD -> Money.of(toCents(price.amount().divide(sypPerUsd, 10, RoundingMode.HALF_UP),
                    price.amount().signum() > 0), Currency.USD);
        };
    }

    private static BigDecimal toCents(BigDecimal dollars, boolean positive) {
        var cents = dollars.setScale(Money.SCALE, RoundingMode.HALF_UP);
        return positive && cents.signum() == 0 ? ONE_CENT : cents;
    }
}
