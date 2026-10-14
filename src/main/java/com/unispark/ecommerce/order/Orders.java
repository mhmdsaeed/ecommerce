package com.unispark.ecommerce.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * The current store's orders (SPEC 5, 6): what shoppers and staff see, status changes through
 * {@link OrderStateMachine}, and payment proofs. Every change writes an order event.
 */
public interface Orders {

    int PAGE_SIZE = 25;
    int MAX_REFERENCE_LENGTH = 100;

    /** For the shopper's order page: only with the order's tracking token. */
    Optional<OrderView> track(int number, String trackingToken);

    Optional<OrderView> findByNumber(int number);

    /** Newest first. Filters arrive with order management (M9). */
    OrderPage list(int page);

    /**
     * Moves the order to {@code target}. Stock follows the order: cancelling or returning releases what it
     * reserved, delivering takes it off the shelf. Delivered and refused orders count on the customer.
     *
     * @throws IllegalOrderTransitionException when the state machine does not allow it; nothing changes
     */
    Optional<OrderView> changeStatus(UUID orderId, OrderStatus target, Actor actor);

    /**
     * The shopper says they paid a transfer, giving its reference (SPEC 6.2). The payment then awaits the
     * merchant's check.
     */
    ProofResult submitProof(int number, String trackingToken, String transactionReference);

    record OrderPage(List<OrderRow> rows, int page, boolean hasNext) {
    }

    record OrderRow(UUID id, int number, Instant placedAt, String customerName, String customerPhone, Money total,
            OrderStatus status, PaymentStatus paymentStatus, PaymentMethod paymentMethod) {
    }

    /**
     * @param rateSnapshot the pounds-per-dollar rate prices were converted at, or null when nothing was converted
     */
    record OrderView(UUID id, int number, Instant placedAt, OrderStatus status, PaymentStatus paymentStatus,
            PaymentMethod paymentMethod, Money subtotal, Money deliveryFee, Money total, BigDecimal rateSnapshot,
            UUID customerId, String customerName, String customerPhone, Address address, String notes,
            List<Item> items, List<Event> events, List<Proof> proofs) {
    }

    record Address(Governorate governorate, String city, String area, String landmark, String details) {
    }

    /** @param variantId null if the variant was deleted since; the snapshot remains */
    record Item(UUID variantId, LocalizedText name, String sku, List<Option> options, Money unitPrice, int quantity,
            Money lineTotal) {
    }

    record Option(LocalizedText name, LocalizedText value) {
    }

    /** @param type PLACED, STATUS_CHANGED or PROOF_SUBMITTED */
    record Event(String type, OrderStatus from, OrderStatus to, String actor, Instant at) {
    }

    record Proof(PaymentMethod method, String transactionReference, String status, Instant submittedAt) {
    }

    sealed interface ProofResult {
    }

    record ProofAccepted() implements ProofResult {
    }

    /** Blank or too long. */
    record InvalidReference() implements ProofResult {
    }

    /** Cash on delivery, already awaiting a check, or paid. */
    record ProofNotExpected() implements ProofResult {
    }

    record OrderNotFound() implements ProofResult {
    }
}
