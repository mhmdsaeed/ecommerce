package com.unispark.ecommerce.order;

/** A status change {@link OrderStateMachine} does not allow. Nothing was changed. */
public class IllegalOrderTransitionException extends RuntimeException {

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("An order cannot go from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
