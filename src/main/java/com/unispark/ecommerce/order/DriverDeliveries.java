package com.unispark.ecommerce.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.order.Orders.OrderView;

/**
 * What a driver can see and do (SPEC 8): only the orders given to them, and only the delivery steps. Each step goes
 * through {@link OrderStateMachine} with the driver as actor; delivering a cash-on-delivery order records the cash
 * the driver now holds, which the merchant settles later ({@link CodSettlement}).
 */
public interface DriverDeliveries {

    /** Open orders given to the driver, oldest first. */
    List<OrderView> openFor(UUID driverId);

    /** The order, if it is given to this driver. */
    Optional<OrderView> find(UUID driverId, int number);

    /** Picked up: PREPARING to OUT_FOR_DELIVERY. */
    DriverResult startDelivery(UUID driverId, int number);

    /**
     * Handed over: OUT_FOR_DELIVERY to DELIVERED. For cash on delivery, {@code collected} is the cash taken (as
     * typed, Arabic digits accepted); the payment becomes PAID. Ignored for orders paid by transfer.
     */
    DriverResult deliver(UUID driverId, int number, String collected);

    /** The customer would not take it: OUT_FOR_DELIVERY to REFUSED, with the driver's reason. */
    DriverResult refuse(UUID driverId, int number, String reason);

    sealed interface DriverResult {
    }

    record Done() implements DriverResult {
    }

    /** No such order, or it is not given to this driver. */
    record NotYours() implements DriverResult {
    }

    /** The order is not at the step this action belongs to (already moved, or not ready yet). */
    record WrongStep() implements DriverResult {
    }

    /** Cash on delivery needs the amount collected, above zero. */
    record InvalidAmount() implements DriverResult {
    }
}
