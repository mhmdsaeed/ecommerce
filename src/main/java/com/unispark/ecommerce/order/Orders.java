package com.unispark.ecommerce.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * The current store's orders (SPEC 5, 6, 9.3): what shoppers and staff see, status changes through
 * {@link OrderStateMachine}, drivers, and payment proofs. Every change writes an order event.
 */
public interface Orders {

    int PAGE_SIZE = 25;
    int MAX_REFERENCE_LENGTH = 100;
    int MAX_NOTE_LENGTH = 500;

    /** For the shopper's order page: only with the order's tracking token. */
    Optional<OrderView> track(int number, String trackingToken);

    Optional<OrderView> findByNumber(int number);

    /** Newest first. */
    OrderPage list(OrderFilter filter, int page);

    /** What needs attention, for the dashboard. */
    Counts counts(Instant placedSince);

    /** How many orders are in each status: every status, zero for none (the orders list's tabs). */
    Map<OrderStatus, Long> countsByStatus();

    /** {@link #changeStatus(UUID, OrderStatus, Actor, String)} without a note. */
    Optional<OrderView> changeStatus(UUID orderId, OrderStatus target, Actor actor);

    /**
     * Moves the order to {@code target}. Stock follows the order: cancelling or returning releases what it
     * reserved, delivering takes it off the shelf. Delivered and refused orders count on the customer.
     *
     * @param note why, as staff wrote it (a cancellation reason), kept in the event; null for none
     * @throws IllegalOrderTransitionException when the state machine does not allow it; nothing changes
     */
    Optional<OrderView> changeStatus(UUID orderId, OrderStatus target, Actor actor, String note);

    /** Gives an open order to an active driver of the store, or takes it back ({@code driverId} null). */
    AssignResult assignDriver(UUID orderId, UUID driverId, Actor actor);

    /**
     * Staff decide on the proof awaiting verification: approved, the order is paid; rejected, the shopper may send
     * another proof.
     *
     * @param note a reason for the shopper, kept on the proof; null for none
     */
    ReviewResult reviewProof(UUID orderId, boolean approve, String note, Actor actor);

    /**
     * The shopper says they paid a transfer, with its reference and/or a screenshot (a private image key from the
     * media module) (SPEC 6.2). The payment then awaits the merchant's check.
     */
    ProofResult submitProof(int number, String trackingToken, String transactionReference, String imageKey);

    /** {@code statuses} empty means any; {@code query} matches the number, the phone (any spelling) or the name. */
    record OrderFilter(Set<OrderStatus> statuses, PaymentStatus paymentStatus, String query) {

        public static final OrderFilter ALL = new OrderFilter(Set.of(), null, null);
    }

    record OrderPage(List<OrderRow> rows, int page, boolean hasNext) {
    }

    record OrderRow(UUID id, int number, Instant placedAt, String customerName, String customerPhone, Money total,
            OrderStatus status, PaymentStatus paymentStatus, PaymentMethod paymentMethod, UUID driverId) {
    }

    /**
     * @param placedSince   orders placed since the given time (today, on the dashboard)
     * @param toConfirm     waiting for the merchant's confirmation
     * @param toVerify      payments waiting for the merchant's check
     */
    record Counts(long placedSince, long toConfirm, long toVerify) {
    }

    /**
     * @param rateSnapshot the pounds-per-dollar rate prices were converted at, or null when nothing was converted
     * @param driverId     the driver delivering it, or null
     * @param codCollected the cash taken at delivery, for cash on delivery; null before
     */
    record OrderView(UUID id, int number, Instant placedAt, OrderStatus status, PaymentStatus paymentStatus,
            PaymentMethod paymentMethod, Money subtotal, Money deliveryFee, Money total, BigDecimal rateSnapshot,
            UUID customerId, String customerName, String customerPhone, Address address, String notes,
            UUID driverId, Money codCollected, List<Item> items, List<Event> events, List<Proof> proofs) {

        /** The statuses staff can move it to now. */
        public Set<OrderStatus> nextStatuses() {
            return OrderStateMachine.next(status);
        }

        public boolean cancellable() {
            return OrderStateMachine.canTransition(status, OrderStatus.CANCELLED);
        }

        /** The proof the merchant has to decide on, if any. */
        public Optional<Proof> pendingProof() {
            return proofs.stream().filter(proof -> "PENDING".equals(proof.status())).reduce((first, last) -> last);
        }
    }

    record Address(Governorate governorate, String city, String area, String landmark, String details) {
    }

    /** @param variantId null if the variant was deleted since; the snapshot remains */
    record Item(UUID variantId, LocalizedText name, String sku, List<Option> options, Money unitPrice, int quantity,
            Money lineTotal) {
    }

    record Option(LocalizedText name, LocalizedText value) {
    }

    /**
     * @param type  PLACED, STATUS_CHANGED, DRIVER_ASSIGNED, PROOF_SUBMITTED, PAYMENT_APPROVED or PAYMENT_REJECTED
     * @param actor {@code customer:<id>}, {@code staff:<id>} or {@code system}
     * @param note  staff's reason, or null
     */
    record Event(String type, OrderStatus from, OrderStatus to, String actor, Instant at, String note) {
    }

    /**
     * @param imageKey a private screenshot, or null
     * @param status   PENDING, APPROVED or REJECTED
     */
    record Proof(UUID id, PaymentMethod method, String transactionReference, String imageKey, String status,
            Instant submittedAt, String reviewNote) {
    }

    sealed interface ProofResult {
    }

    record ProofAccepted() implements ProofResult {
    }

    /** Neither a reference nor a screenshot, or a reference that is too long. */
    record InvalidReference() implements ProofResult {
    }

    /** Cash on delivery, already awaiting a check, or paid. */
    record ProofNotExpected() implements ProofResult {
    }

    record OrderNotFound() implements ProofResult, AssignResult, ReviewResult {
    }

    sealed interface AssignResult {
    }

    record DriverAssigned() implements AssignResult {
    }

    /** Not an active driver of this store. */
    record NotADriver() implements AssignResult {
    }

    /** Delivered, cancelled or otherwise closed orders keep their driver. */
    record OrderClosed() implements AssignResult {
    }

    sealed interface ReviewResult {
    }

    record ProofReviewed() implements ReviewResult {
    }

    /** No proof is waiting for a decision (already decided, or none sent). */
    record NothingToReview() implements ReviewResult {
    }
}
