package com.unispark.ecommerce.order.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.customer.Customers;
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
import com.unispark.ecommerce.shared.Money;
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
    private final Clock clock;

    OrderService(ShopOrderRepository orders, OrderEventRepository events, PaymentProofRepository proofs, OrderLog log,
            Inventory inventory, Customers customers, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.proofs = proofs;
        this.log = log;
        this.inventory = inventory;
        this.customers = customers;
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
    public OrderPage list(int page) {
        var slice = orders.findAllByOrderByPlacedAtDescNumberDesc(PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        var rows = slice.getContent().stream()
                .map(order -> new OrderRow(order.getId(), order.getNumber(), order.getPlacedAt(),
                        order.getCustomerName(), order.getCustomerPhone(), money(order, order.getTotal()),
                        order.getStatus(), order.getPaymentStatus(), order.getPaymentMethod()))
                .toList();
        return new OrderPage(rows, slice.getNumber(), slice.hasNext());
    }

    @Override
    public Optional<OrderView> changeStatus(UUID orderId, OrderStatus target, Actor actor) {
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
                customers.recordDelivered(order.getCustomerId());
            }
            case REFUSED -> customers.recordRefused(order.getCustomerId());
            default -> {
            }
        }
        order.moveTo(target, clock.instant());
        log.statusChanged(order, OrderLog.STATUS_CHANGED, from, target, actor, null);
        return Optional.of(view(order));
    }

    @Override
    public ProofResult submitProof(int number, String trackingToken, String transactionReference) {
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
        var reference = transactionReference == null ? "" : transactionReference.strip();
        if (reference.isEmpty() || reference.length() > MAX_REFERENCE_LENGTH) {
            return new InvalidReference();
        }
        proofs.save(new PaymentProof(order.getId(), order.getPaymentMethod(), reference, clock.instant()));
        order.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        log.fact(order, OrderLog.PROOF_SUBMITTED, Actor.customer(order.getCustomerId()),
                Map.of("transactionReference", reference));
        return new ProofAccepted();
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
                        event.getCreatedAt()))
                .toList();
        var submitted = proofs.findByOrderIdOrderBySubmittedAt(order.getId()).stream()
                .map(proof -> new Proof(proof.getMethod(), proof.getTransactionRef(), proof.getStatus(),
                        proof.getSubmittedAt()))
                .toList();
        return new OrderView(order.getId(), order.getNumber(), order.getPlacedAt(), order.getStatus(),
                order.getPaymentStatus(), order.getPaymentMethod(), money(order, order.getSubtotal()),
                money(order, order.getDeliveryFee()), money(order, order.getTotal()), order.getRateSnapshot(),
                order.getCustomerId(), order.getCustomerName(), order.getCustomerPhone(), order.getAddress(),
                order.getNotes(), items, history, submitted);
    }

    private static Item item(ShopOrder order, OrderItem item) {
        var product = item.getProduct();
        return new Item(item.getVariantId(), product.name(), product.sku(), product.options(),
                money(order, item.getUnitPrice()), item.getQuantity(), money(order, item.getLineTotal()));
    }

    private static Money money(ShopOrder order, BigDecimal amount) {
        return Money.of(amount, order.getCurrency());
    }
}
