package com.unispark.ecommerce.delivery.internal;

import java.util.function.UnaryOperator;

import com.unispark.ecommerce.delivery.DeliveryQuotes.BelowMinimum;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Deliverable;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Quote;
import com.unispark.ecommerce.delivery.DeliveryZones.Zone;
import com.unispark.ecommerce.shared.Money;

/**
 * Turns a zone and a subtotal into a quote (SPEC 8). The zone's amounts are first brought to the subtotal's terms
 * by {@code toDisplay} (the store's conversion and rounding). A subtotal below the minimum is refused; one at or
 * above free-over delivers free. Thresholds that could not be converted into the subtotal's currency (no exchange
 * rate) are not applied, so a missing rate never blocks delivery. Pure, so it is unit-tested on its own.
 */
final class FeeRules {

    private FeeRules() {
    }

    static Quote quote(Zone zone, Money subtotal, UnaryOperator<Money> toDisplay) {
        var fee = toDisplay.apply(zone.fee());
        var minimum = zone.minOrder() == null ? null : toDisplay.apply(zone.minOrder());
        var freeOver = zone.freeOver() == null ? null : toDisplay.apply(zone.freeOver());
        if (subtotal == null) {
            return new Deliverable(zone, fee, null, null, zone.eta());
        }
        if (subtotal.sameCurrency(minimum) && subtotal.isLessThan(minimum)) {
            return new BelowMinimum(zone, minimum, minimum.minus(subtotal));
        }
        Money untilFree = null;
        if (fee.isPositive() && subtotal.sameCurrency(freeOver)) {
            if (subtotal.isLessThan(freeOver)) {
                untilFree = freeOver.minus(subtotal);
            } else {
                fee = Money.zero(fee.currency());
            }
        }
        var total = subtotal.sameCurrency(fee) ? subtotal.plus(fee) : null;
        return new Deliverable(zone, fee, total, untilFree, zone.eta());
    }
}
