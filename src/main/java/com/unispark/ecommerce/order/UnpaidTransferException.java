package com.unispark.ecommerce.order;

/**
 * An order paid by transfer cannot go ahead before the merchant approves its payment (SPEC 6.2): the status change is
 * allowed by {@link OrderStateMachine}, but not yet. Nothing was changed.
 */
public class UnpaidTransferException extends IllegalOrderTransitionException {

    public UnpaidTransferException(OrderStatus from, OrderStatus to) {
        super(from, to, "An order paid by transfer cannot go from " + from + " to " + to + " before it is paid");
    }
}
