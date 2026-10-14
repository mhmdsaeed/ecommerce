package com.unispark.ecommerce.order;

import java.util.List;
import java.util.UUID;

import com.unispark.ecommerce.cart.Carts.CartView;
import com.unispark.ecommerce.delivery.DeliveryQuotes.BelowMinimum;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Deliverable;
import com.unispark.ecommerce.delivery.DeliveryQuotes.NotDelivered;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Quote;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Money;

/**
 * Turning a cart into an order (SPEC 6.4). Everything is recalculated on the server from the cart, the catalog, the
 * exchange rate and the delivery zones; nothing the browser sends about prices is trusted, except that an expected
 * total that no longer matches stops the order, so the shopper never pays an amount they have not seen.
 *
 * <p>Placing is idempotent: the checkout form carries a key, and submitting it again returns the order it created.
 */
public interface Checkout {

    /** New customers (no delivered order yet) may have this many open orders at most (SPEC 6.4). */
    int MAX_OPEN_ORDERS_FOR_NEW_CUSTOMERS = 2;
    int MAX_NAME_LENGTH = 100;
    int MAX_NOTES_LENGTH = 500;

    /**
     * What the checkout page shows: the cart, its subtotal, the delivery quote for the destination and the total,
     * calculated the way {@link #place} will.
     */
    Preview preview(String cartToken, Destination destination);

    PlaceResult place(Request request);

    /**
     * @param subtotal null when the cart has prices in two currencies (no exchange rate yet)
     * @param quote    null without a destination
     * @param total    subtotal plus delivery, when both are known and deliverable
     */
    record Preview(CartView cart, Money subtotal, Quote quote, Money total) {

        public Deliverable deliverable() {
            return quote instanceof Deliverable deliverable ? deliverable : null;
        }

        public BelowMinimum belowMinimum() {
            return quote instanceof BelowMinimum belowMinimum ? belowMinimum : null;
        }

        public boolean notDelivered() {
            return quote instanceof NotDelivered;
        }
    }

    /**
     * The checkout form as submitted.
     *
     * @param phoneVerification the token from verifying the phone by code; needed when the store requires it
     * @param expectedTotal     the total the page showed, as plain text; null skips the check
     */
    record Request(String cartToken, UUID idempotencyKey, String name, String phone, String phoneVerification,
            Governorate governorate, String city, String area, String landmark, String details,
            PaymentMethod paymentMethod, String notes, String expectedTotal) {
    }

    sealed interface PlaceResult {
    }

    /**
     * @param trackingToken opens the order page; the same for a repeated submit
     * @param repeated      this submit had already placed the order
     */
    record Placed(UUID orderId, int number, String trackingToken, boolean repeated) implements PlaceResult {
    }

    /**
     * Nothing was created. {@code problems} are field errors of the form; {@code refusal} is why a valid form
     * could not become an order.
     */
    record Rejected(List<FieldProblem> problems, Refusal refusal) implements PlaceResult {

        public static Rejected fields(List<FieldProblem> problems) {
            return new Rejected(List.copyOf(problems), null);
        }

        public static Rejected because(Reason reason) {
            return new Rejected(List.of(), new Refusal(reason, null));
        }

        public static Rejected because(Reason reason, Money amount) {
            return new Rejected(List.of(), new Refusal(reason, amount));
        }
    }

    /** @param amount for the reasons that name one: the minimum, the new-customer cap, or the new total */
    record Refusal(Reason reason, Money amount) {
    }

    enum Reason {
        CART_EMPTY,
        /** Something in the cart is no longer sold, or there is more of it than in stock. */
        CART_PROBLEMS,
        /** Prices in a currency the store cannot convert yet (no exchange rate). */
        PRICES_UNAVAILABLE,
        NOT_DELIVERED,
        BELOW_MINIMUM,
        PHONE_NOT_VERIFIED,
        METHOD_UNAVAILABLE,
        /** Deliberately vague for the shopper (SPEC 6.4): blocked customers learn nothing about why. */
        BLOCKED,
        NEW_CUSTOMER_TOTAL,
        NEW_CUSTOMER_OPEN_ORDERS,
        OUT_OF_STOCK,
        /** The total changed since the page was shown (price, rate or delivery fee). */
        TOTAL_CHANGED
    }
}
