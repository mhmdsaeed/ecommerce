package com.unispark.ecommerce.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stock levels of the current store's variants (SPEC 5, 6.4). A variant is tracked once the merchant sets its stock;
 * untracked variants can always be bought.
 *
 * <p>Reservations hold stock for open orders: placing an order reserves, cancelling or returning releases, and
 * delivery deducts from on hand. Every change is a single conditional update, so concurrent orders can never take
 * the available quantity below zero.
 */
public interface Inventory {

    int MAX_ON_HAND = 1_000_000;

    StockLevels levels(Collection<UUID> variantIds);

    /**
     * Sets the quantity on hand; null stops tracking. Refused when it would leave less on hand than is reserved,
     * or stop tracking a variant with reservations.
     */
    StockResult setOnHand(UUID variantId, Integer onHand);

    /** All or nothing: either every tracked variant gets its quantity reserved, or nothing changes. */
    Reservation reserve(Map<UUID, Integer> quantities);

    /** Gives reserved units back. Untracked variants are ignored. */
    void release(Map<UUID, Integer> quantities);

    /** Delivered: the reserved units leave the shelf. Untracked variants are ignored. */
    void deduct(Map<UUID, Integer> quantities);

    sealed interface StockResult {
    }

    /** {@code level} is null when the variant is not tracked (any more). */
    record Updated(StockLevels.Level level) implements StockResult {
    }

    /** codes: invalid (out of range), below-reserved, reserved (cannot stop tracking). */
    record Rejected(String code, int reserved) implements StockResult {
    }

    record UnknownVariant() implements StockResult {
    }

    sealed interface Reservation {
    }

    record Reserved() implements Reservation {
    }

    record Insufficient(List<Shortage> shortages) implements Reservation {
    }

    record Shortage(UUID variantId, int requested, int available) {
    }
}
