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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SPEC 6.1: exactly these transitions, nothing else. */
class OrderStateMachineTest {

    static final Map<OrderStatus, Set<OrderStatus>> SPEC = Map.of(
            PENDING_CONFIRMATION, EnumSet.of(CONFIRMED, CANCELLED),
            CONFIRMED, EnumSet.of(PREPARING, CANCELLED),
            PREPARING, EnumSet.of(OUT_FOR_DELIVERY, CANCELLED),
            OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, REFUSED),
            DELIVERED, EnumSet.of(COMPLETED),
            REFUSED, EnumSet.of(RETURNED),
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            RETURNED, EnumSet.noneOf(OrderStatus.class));

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void allowsExactlyTheSpecifiedTransitions(OrderStatus from) {
        assertThat(OrderStateMachine.next(from)).isEqualTo(SPEC.get(from));
        for (var to : OrderStatus.values()) {
            assertThat(OrderStateMachine.canTransition(from, to)).as("%s -> %s", from, to)
                    .isEqualTo(SPEC.get(from).contains(to));
        }
    }

    @Test
    void walksTheHappyPathToCompleted() {
        var path = new OrderStatus[] {PENDING_CONFIRMATION, CONFIRMED, PREPARING, OUT_FOR_DELIVERY, DELIVERED, COMPLETED};
        for (var step = 1; step < path.length; step++) {
            OrderStateMachine.check(path[step - 1], path[step]);
        }
    }

    @Test
    void rejectsTransitionFromDeliveredToCancelled() {
        assertThatThrownBy(() -> OrderStateMachine.check(DELIVERED, CANCELLED))
                .isInstanceOfSatisfying(IllegalOrderTransitionException.class, exception -> {
                    assertThat(exception.from()).isEqualTo(DELIVERED);
                    assertThat(exception.to()).isEqualTo(CANCELLED);
                });
    }

    @Test
    void rejectsSkippingStepsGoingBackAndStayingPut() {
        assertThatThrownBy(() -> OrderStateMachine.check(PENDING_CONFIRMATION, DELIVERED))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> OrderStateMachine.check(PREPARING, CONFIRMED))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> OrderStateMachine.check(CONFIRMED, CONFIRMED))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> OrderStateMachine.check(OUT_FOR_DELIVERY, CANCELLED))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void openOrdersAreTheOnesStillOnTheirWay() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isOpen))
                .containsExactly(PENDING_CONFIRMATION, CONFIRMED, PREPARING, OUT_FOR_DELIVERY);
    }

    @Test
    void namesActorsForTheEventLog() {
        var id = java.util.UUID.fromString("01a13bcb-6ed2-7c87-a484-d732819b7fc6");
        assertThat(Actor.customer(id)).hasToString("customer:" + id);
        assertThat(Actor.staff(id)).hasToString("staff:" + id);
        assertThat(Actor.system()).hasToString("system");
        assertThatThrownBy(() -> new Actor(Actor.Kind.STAFF, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
