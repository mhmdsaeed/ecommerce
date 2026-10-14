package com.unispark.ecommerce.cart;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.unispark.ecommerce.catalog.Products.ImageView;
import com.unispark.ecommerce.catalog.StorefrontCatalog.Choice;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * Shopping carts of the current store (SPEC 5, 9.2). A cart is found by the token its shopper's browser holds; a
 * null or unknown token is an empty cart. The first item added creates the cart and returns its token.
 *
 * <p>Carts store variants and quantities only. Prices, names and availability are looked up on every read, so a
 * cart always shows what checkout would charge now. Stock is checked here but not reserved: that happens when an
 * order is placed (M8).
 */
public interface Carts {

    int MAX_QUANTITY = 99;
    int MAX_LINES = 50;
    /** A cart lives this long after its last change. */
    Duration LIFETIME = Duration.ofDays(30);

    CartView view(String token);

    /** The number of items (sum of quantities); 0 without a cart. */
    int count(String token);

    /** Adds to the line for this variant (or starts one), within stock and {@value #MAX_QUANTITY} per line. */
    AddOutcome add(String token, UUID variantId, int quantity);

    /** Sets a line's quantity, within stock; 0 removes it. Unknown lines are ignored. */
    Change update(String token, UUID itemId, int quantity);

    Change remove(String token, UUID itemId);

    /** Deletes the current store's carts unchanged for {@link #LIFETIME}; returns how many. */
    int deleteExpired();

    /**
     * @param totals one amount per currency: prices that could not be converted (no exchange rate yet) stay in
     *               their own currency
     */
    record CartView(List<Line> lines, List<Money> totals, int itemCount) {

        public static final CartView EMPTY = new CartView(List.of(), List.of(), 0);

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        public boolean hasProblems() {
            return lines.stream().anyMatch(line -> line.problem() != null);
        }
    }

    /**
     * Prices are in the display currency. An unavailable line has no prices and does not count towards the
     * totals. {@code available} is the stock left when it is tracked, else null.
     */
    record Line(UUID itemId, UUID variantId, LocalizedText productName, String productSlug, List<Choice> choices,
            ImageView image, int quantity, Money unitPrice, Money lineTotal, Integer available, Problem problem) {

        public int maxQuantity() {
            return available == null ? MAX_QUANTITY : Math.min(MAX_QUANTITY, Math.max(available, quantity));
        }
    }

    enum Problem {
        /** No longer sold: archived, hidden or deactivated. */
        UNAVAILABLE,
        /** More in the cart than is in stock. */
        NOT_ENOUGH_STOCK
    }

    /** What happened beyond the plain request. */
    enum Notice {
        /** Less than asked was added or kept: stock or the per-line maximum. */
        LIMITED,
        SOLD_OUT,
        UNAVAILABLE,
        CART_FULL
    }

    /**
     * @param token  the cart's token, new when this call created the cart; null when there is still no cart
     * @param notice null when everything asked for was added
     */
    record AddOutcome(String token, int itemCount, Notice notice) {
    }

    record Change(CartView cart, Notice notice) {
    }
}
