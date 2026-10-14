package com.unispark.ecommerce.order;

import static com.unispark.ecommerce.order.OrderStatus.CANCELLED;
import static com.unispark.ecommerce.order.OrderStatus.COMPLETED;
import static com.unispark.ecommerce.order.OrderStatus.CONFIRMED;
import static com.unispark.ecommerce.order.OrderStatus.DELIVERED;
import static com.unispark.ecommerce.order.OrderStatus.OUT_FOR_DELIVERY;
import static com.unispark.ecommerce.order.OrderStatus.PENDING_CONFIRMATION;
import static com.unispark.ecommerce.order.OrderStatus.PREPARING;
import static com.unispark.ecommerce.order.OrderStatus.REFUSED;
import static com.unispark.ecommerce.order.OrderStatus.RETURNED;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The allowed order status changes (SPEC 6.1), and nothing else:
 *
 * <pre>
 * PENDING_CONFIRMATION -> CONFIRMED -> PREPARING -> OUT_FOR_DELIVERY -> DELIVERED -> COMPLETED
 * PENDING_CONFIRMATION, CONFIRMED, PREPARING -> CANCELLED
 * OUT_FOR_DELIVERY -> REFUSED (at the door) -> RETURNED
 * </pre>
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(PENDING_CONFIRMATION, EnumSet.of(CONFIRMED, CANCELLED));
        ALLOWED.put(CONFIRMED, EnumSet.of(PREPARING, CANCELLED));
        ALLOWED.put(PREPARING, EnumSet.of(OUT_FOR_DELIVERY, CANCELLED));
        ALLOWED.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, REFUSED));
        ALLOWED.put(DELIVERED, EnumSet.of(COMPLETED));
        ALLOWED.put(REFUSED, EnumSet.of(RETURNED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(RETURNED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    /** The statuses that may follow {@code from}, in declaration order; empty for final statuses. */
    public static Set<OrderStatus> next(OrderStatus from) {
        return EnumSet.copyOf(ALLOWED.get(from));
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** @throws IllegalOrderTransitionException for any change not listed above */
    public static void check(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalOrderTransitionException(from, to);
        }
    }
}
