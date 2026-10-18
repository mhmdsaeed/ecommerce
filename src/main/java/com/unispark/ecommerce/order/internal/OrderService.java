package com.unispark.ecommerce.order.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.identity.StaffDirectory;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.OrderStateMachine;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.order.internal.persistence.OrderEventRepository;
import com.unispark.ecommerce.order.internal.persistence.OrderItem;
import com.unispark.ecommerce.order.internal.persistence.PaymentProof;
import com.unispark.ecommerce.order.internal.persistence.PaymentProofRepository;
import com.unispark.ecommerce.order.internal.persistence.ShopOrder;
import com.unispark.ecommerce.order.internal.persistence.ShopOrderRepository;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.shared.Digits;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.PhoneNumbers;
import com.unispark.ecommerce.shared.SecretTokens;

/** Orders after placing. Changes lock the order row first, so they apply one after the other. */
@Service
@Transactional
class OrderService implements Orders {

    private final ShopOrderRepository orders;
    private final OrderEventRepository events;
    private final PaymentProofRepository proofs;
    private final OrderLog log;
    private final Inventory inventory;
    private final Customers customers;
    private final StaffDirectory staff;
    private final CodLedger ledger;
    private final Clock clock;

    OrderService(ShopOrderRepository orders, OrderEventRepository events, PaymentProofRepository proofs, OrderLog log,
            Inventory inventory, Customers customers, StaffDirectory staff, CodLedger ledger,
            Clock clock) {
        this.orders = orders;
        this.events = events;
        this.proofs = proofs;
        this.log = log;
        this.inventory = inventory;
        this.customers = customers;
        this.staff = staff;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderView> track(int number, String trackingToken) {
        if (!SecretTokens.isWellFormed(trackingToken)) {
            return Optional.empty();
        }
        var hash = SecretTokens.hash(trackingToken);
        return orders.findByNumber(number).filter(order -> order.getTrackingTokenHash().equals(hash)).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderView> findByNumber(int number) {
        return orders.findByNumber(number).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage list(OrderFilter filter, int page) {
        var request = PageRequest.of(Math.max(page, 0), PAGE_SIZE,
                Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("number")));
        var found = orders.findAll(matching(filter), request);
        var rows = found.getContent().stream()
                .map(order -> new OrderRow(order.getId(), order.getNumber(), order.getPlacedAt(),
                        order.getCustomerName(), order.getCustomerPhone(), money(order, order.getTotal()),
                        order.getStatus(), order.getPaymentStatus(), order.getPaymentMethod(), order.getDriverId()))
                .toList();
        return new OrderPage(rows, found.getNumber(), found.hasNext());
    }

    @Override
    @Transactional(readOnly = true)
    public Counts counts(Instant placedSince) {
        return new Counts(orders.countByPlacedAtGreaterThanEqual(placedSince),
                orders.countByStatus(OrderStatus.PENDING_CONFIRMATION),
                orders.countByPaymentStatus(PaymentStatus.AWAITING_VERIFICATION));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<OrderStatus, Long> countsByStatus() {
        var counts = new EnumMap<OrderStatus, Long>(OrderStatus.class);
        for (var status : OrderStatus.values()) {
            counts.put(status, 0L);
        }
        for (var row : orders.countGroupedByStatus()) {
            counts.put((OrderStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** Declared here, not as an interface default, so it runs in a transaction like every other method. */
    @Override
    public Optional<OrderView> changeStatus(UUID orderId, OrderStatus target, Actor actor) {
        return changeStatus(orderId, target, actor, null);
    }

    @Override
    public Optional<OrderView> changeStatus(UUID orderId, OrderStatus target, Actor actor, String note) {
        var found = orders.findForUpdate(orderId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var order = found.get();
        var from = order.getStatus();
        OrderStateMachine.check(from, target);
        switch (target) {
            case CANCELLED, RETURNED -> inventory.release(quantities(order));
            case DELIVERED -> {
                inventory.deduct(quantities(order));
                ledger.collect(order, money(order, order.getTotal()));
                customers.recordDelivered(order.getCustomerId());
            }
            case REFUSED -> customers.recordRefused(order.getCustomerId());
            default -> {
            }
        }
        order.moveTo(target, clock.instant());
        log.statusChanged(order, OrderLog.STATUS_CHANGED, from, target, actor, noted(note));
        return Optional.of(view(order));
    }

    @Override
    public AssignResult assignDriver(UUID orderId, UUID driverId, Actor actor) {
        var found = orders.findForUpdate(orderId);
        if (found.isEmpty()) {
            return new OrderNotFound();
        }
        var order = found.get();
        if (!order.getStatus().isOpen()) {
            return new OrderClosed();
        }
        if (driverId != null && staff.activeDrivers().stream().noneMatch(driver -> driver.id().equals(driverId))) {
            return new NotADriver();
        }
        if (!Objects.equals(order.getDriverId(), driverId)) {
            order.setDriverId(driverId);
            var payload = new HashMap<String, Object>();
            payload.put("driverId", driverId == null ? null : driverId.toString());
            log.fact(order, OrderLog.DRIVER_ASSIGNED, actor, payload);
        }
        return new DriverAssigned();
    }

    @Override
    public ReviewResult reviewProof(UUID orderId, boolean approve, String note, Actor actor) {
        var found = orders.findForUpdate(orderId);
        if (found.isEmpty()) {
            return new OrderNotFound();
        }
        var order = found.get();
        var pending = proofs.findByOrderIdOrderBySubmittedAt(orderId).stream()
                .filter(proof -> PaymentProof.PENDING.equals(proof.getStatus())).reduce((first, last) -> last);
        if (pending.isEmpty() || order.getPaymentStatus() != PaymentStatus.AWAITING_VERIFICATION) {
            return new NothingToReview();
        }
        var cleanNote = clean(note);
        pending.get().review(approve, actor.kind() == Actor.Kind.STAFF ? actor.id() : null, cleanNote,
                clock.instant());
        order.setPaymentStatus(approve ? PaymentStatus.PAID : PaymentStatus.REJECTED);
        log.fact(order, approve ? OrderLog.PAYMENT_APPROVED : OrderLog.PAYMENT_REJECTED, actor, noted(cleanNote));
        return new ProofReviewed();
    }

    @Override
    public ProofResult submitProof(int number, String trackingToken, String transactionReference, String imageKey) {
        if (!SecretTokens.isWellFormed(trackingToken)) {
            return new OrderNotFound();
        }
        var hash = SecretTokens.hash(trackingToken);
        var found = orders.findByNumberForUpdate(number).filter(order -> order.getTrackingTokenHash().equals(hash));
        if (found.isEmpty()) {
            return new OrderNotFound();
        }
        var order = found.get();
        if (!order.getPaymentMethod().isTransfer() || !order.getPaymentStatus().awaitsProof()
                || !order.getStatus().isOpen()) {
            return new ProofNotExpected();
        }
        var reference = clean(transactionReference);
        if (reference == null && imageKey == null
                || reference != null && reference.length() > MAX_REFERENCE_LENGTH) {
            return new InvalidReference();
        }
        proofs.save(new PaymentProof(order.getId(), order.getPaymentMethod(), reference, imageKey, clock.instant()));
        order.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        var payload = new HashMap<String, Object>();
        payload.put("transactionReference", reference);
        payload.put("screenshot", imageKey != null);
        log.fact(order, OrderLog.PROOF_SUBMITTED, Actor.customer(order.getCustomerId()), payload);
        return new ProofAccepted();
    }

    /** Number, phone (any spelling, or some of its digits) or name. */
    private static Specification<ShopOrder> matching(OrderFilter filter) {
        return (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (filter.statuses() != null && !filter.statuses().isEmpty()) {
                predicates.add(root.get("status").in(filter.statuses()));
            }
            if (filter.paymentStatus() != null) {
                predicates.add(builder.equal(root.get("paymentStatus"), filter.paymentStatus()));
            }
            var text = clean(filter.query());
            if (text != null) {
                var typed = Digits.toAscii(text).replace("#", "").strip();
                var matches = new ArrayList<Predicate>();
                if (typed.matches("\\d{1,9}")) {
                    matches.add(builder.equal(root.get("number"), Integer.parseInt(typed)));
                }
                PhoneNumbers.normalize(typed)
                        .ifPresent(phone -> matches.add(builder.equal(root.get("customerPhone"), phone)));
                var digits = typed.replaceAll("\\D", "");
                if (digits.length() >= 4) {
                    matches.add(builder.like(root.get("customerPhone"), "%" + digits + "%"));
                }
                matches.add(builder.like(builder.lower(root.get("customerName")),
                        "%" + text.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                                .replace("_", "\\_") + "%", '\\'));
                predicates.add(builder.or(matches.toArray(Predicate[]::new)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** What the order holds in stock; lines whose variant was deleted hold nothing any more. */
    private static Map<UUID, Integer> quantities(ShopOrder order) {
        var quantities = new LinkedHashMap<UUID, Integer>();
        order.getItems().stream().filter(item -> item.getVariantId() != null)
                .forEach(item -> quantities.merge(item.getVariantId(), item.getQuantity(), Integer::sum));
        return quantities;
    }

    private OrderView view(ShopOrder order) {
        var items = order.getItems().stream().map(item -> item(order, item)).toList();
        var history = events.findByOrderIdOrderByPosition(order.getId()).stream()
                .map(event -> new Event(event.getType(), event.getFromStatus(), event.getToStatus(), event.getActor(),
                        event.getCreatedAt(),
                        event.getPayload() == null ? null : (String) event.getPayload().get("note")))
                .toList();
        var submitted = proofs.findByOrderIdOrderBySubmittedAt(order.getId()).stream()
                .map(proof -> new Proof(proof.getId(), proof.getMethod(), proof.getTransactionRef(),
                        proof.getImageKey(), proof.getStatus(), proof.getSubmittedAt(), proof.getReviewNote()))
                .toList();
        return new OrderView(order.getId(), order.getNumber(), order.getPlacedAt(), order.getStatus(),
                order.getPaymentStatus(), order.getPaymentMethod(), money(order, order.getSubtotal()),
                money(order, order.getDeliveryFee()), money(order, order.getTotal()), order.getRateSnapshot(),
                order.getCustomerId(), order.getCustomerName(), order.getCustomerPhone(), order.getAddress(),
                order.getNotes(), order.getDriverId(), ledger.collected(order).orElse(null), items, history,
                submitted);
    }

    private static Item item(ShopOrder order, OrderItem item) {
        var product = item.getProduct();
        return new Item(item.getVariantId(), product.name(), product.sku(), product.options(),
                money(order, item.getUnitPrice()), item.getQuantity(), money(order, item.getLineTotal()));
    }

    private static Money money(ShopOrder order, BigDecimal amount) {
        return Money.of(amount, order.getCurrency());
    }

    /** The note as event payload; null without one. Notes are cut at {@link #MAX_NOTE_LENGTH}. */
    private static Map<String, Object> noted(String note) {
        var text = clean(note);
        return text == null ? null : Map.of("note", text);
    }

    private static String clean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var stripped = text.strip();
        return stripped.length() > MAX_NOTE_LENGTH ? stripped.substring(0, MAX_NOTE_LENGTH) : stripped;
    }
}
