package com.unispark.ecommerce.delivery;

import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * What delivering an order to a destination costs (SPEC 8): the fee of the matching zone, its minimum order and
 * free-delivery threshold. Amounts are in the store's display currency, converted and rounded like prices, so a
 * quote adds up with the cart it is shown under.
 */
public interface DeliveryQuotes {

    /**
     * @param subtotal the order's subtotal in the display currency; null when it has none (prices in two currencies
     *                 and no exchange rate yet), in which case the minimum and free delivery cannot be checked and
     *                 the fee is charged as is
     */
    Quote quote(Destination destination, Money subtotal);

    sealed interface Quote {
    }

    /**
     * @param fee       0 when delivery is free (a free zone, or the subtotal reached free-over)
     * @param total     subtotal plus fee; null when they are in different currencies or the subtotal is unknown
     * @param untilFree how much more would make delivery free; null when it is free or has no threshold
     * @param eta       the zone's delivery time, or null
     */
    record Deliverable(DeliveryZones.Zone zone, Money fee, Money total, Money untilFree, LocalizedText eta)
            implements Quote {

        public boolean free() {
            return !fee.isPositive();
        }
    }

    /** The zone delivers, but only orders of at least {@code minimum}. */
    record BelowMinimum(DeliveryZones.Zone zone, Money minimum, Money shortBy) implements Quote {
    }

    /** No active zone covers the destination. */
    record NotDelivered() implements Quote {
    }
}
