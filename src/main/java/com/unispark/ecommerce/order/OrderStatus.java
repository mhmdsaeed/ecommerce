package com.unispark.ecommerce.order;

/**
 * Where an order stands (SPEC 6.1). Which status may follow which is {@link OrderStateMachine}'s business. Names
 * come from the bundles ({@code order.status.<NAME>}).
 */
public enum OrderStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    PREPARING,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COMPLETED,
    CANCELLED,
    REFUSED,
    RETURNED;

    /** Still on its way to the customer: holds stock and counts towards a new customer's open orders. */
    public boolean isOpen() {
        return this == PENDING_CONFIRMATION || this == CONFIRMED || this == PREPARING || this == OUT_FOR_DELIVERY;
    }
}
