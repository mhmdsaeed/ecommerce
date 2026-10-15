package com.unispark.ecommerce.order.internal;

import java.time.Clock;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.OrderStatusChanged;
import com.unispark.ecommerce.order.PaymentProofRejected;
import com.unispark.ecommerce.order.internal.persistence.OrderEvent;
import com.unispark.ecommerce.order.internal.persistence.OrderEventRepository;
import com.unispark.ecommerce.order.internal.persistence.ShopOrder;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Writes an order's events. Callers hold the order's row lock (or just created it), so positions never clash.
 * Status changes are also published as {@link OrderStatusChanged}, rejected payment proofs as
 * {@link PaymentProofRejected}.
 */
@Component
class OrderLog {

    static final String PLACED = "PLACED";
    static final String STATUS_CHANGED = "STATUS_CHANGED";
    static final String PROOF_SUBMITTED = "PROOF_SUBMITTED";
    static final String DRIVER_ASSIGNED = "DRIVER_ASSIGNED";
    static final String PAYMENT_APPROVED = "PAYMENT_APPROVED";
    static final String PAYMENT_REJECTED = "PAYMENT_REJECTED";

    private final OrderEventRepository events;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    OrderLog(OrderEventRepository events, ApplicationEventPublisher publisher, Clock clock) {
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
    }

    void statusChanged(ShopOrder order, String type, OrderStatus from, OrderStatus to, Actor actor,
            Map<String, Object> payload) {
        events.save(new OrderEvent(order.getId(), events.countByOrderId(order.getId()), type, from, to,
                actor.toString(), payload));
        publisher.publishEvent(new OrderStatusChanged(TenantContext.requireTenantId(), order.getId(),
                order.getNumber(), from, to, actor.toString(), clock.instant()));
    }

    void fact(ShopOrder order, String type, Actor actor, Map<String, Object> payload) {
        events.save(new OrderEvent(order.getId(), events.countByOrderId(order.getId()), type, null, null,
                actor.toString(), payload));
        if (PAYMENT_REJECTED.equals(type)) {
            publisher.publishEvent(new PaymentProofRejected(TenantContext.requireTenantId(), order.getId(),
                    order.getNumber(), payload == null ? null : (String) payload.get("note"), clock.instant()));
        }
    }
}
