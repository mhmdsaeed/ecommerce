package com.unispark.ecommerce.order.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.DriverDeliveries;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.order.Orders.OrderView;
import com.unispark.ecommerce.order.internal.persistence.ShopOrder;
import com.unispark.ecommerce.order.internal.persistence.ShopOrderRepository;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.Money;

/**
 * The driver's steps. Each locks the order and checks it is the driver's and at the right step before moving it,
 * so a double tap or a merchant acting at the same time cannot move it twice.
 */
@Service
@Transactional
class DriverDeliveryService implements DriverDeliveries {

    private final ShopOrderRepository orders;
    private final Orders orderService;
    private final CodLedger ledger;

    DriverDeliveryService(ShopOrderRepository orders, Orders orderService, CodLedger ledger) {
        this.orders = orders;
        this.orderService = orderService;
        this.ledger = ledger;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> openFor(UUID driverId) {
        return orders.findByDriverIdAndStatusInOrderByPlacedAtAscNumberAsc(driverId, CheckoutService.OPEN).stream()
                .map(order -> orderService.findByNumber(order.getNumber()).orElseThrow())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderView> find(UUID driverId, int number) {
        return orderService.findByNumber(number).filter(order -> driverId.equals(order.driverId()));
    }

    @Override
    public DriverResult startDelivery(UUID driverId, int number) {
        return step(driverId, number, OrderStatus.PREPARING, order -> {
            orderService.changeStatus(order.getId(), OrderStatus.OUT_FOR_DELIVERY, Actor.staff(driverId));
            return new Done();
        });
    }

    @Override
    public DriverResult deliver(UUID driverId, int number, String collected) {
        return step(driverId, number, OrderStatus.OUT_FOR_DELIVERY, order -> {
            if (!order.getPaymentMethod().isTransfer()) {
                var amount = Amounts.parse(collected).filter(value -> value.signum() > 0);
                if (amount.isEmpty()) {
                    return new InvalidAmount();
                }
                ledger.collect(order, Money.of(amount.get(), order.getCurrency()));
            }
            orderService.changeStatus(order.getId(), OrderStatus.DELIVERED, Actor.staff(driverId));
            return new Done();
        });
    }

    @Override
    public DriverResult refuse(UUID driverId, int number, String reason) {
        return step(driverId, number, OrderStatus.OUT_FOR_DELIVERY, order -> {
            orderService.changeStatus(order.getId(), OrderStatus.REFUSED, Actor.staff(driverId), reason);
            return new Done();
        });
    }

    private DriverResult step(UUID driverId, int number, OrderStatus from, Function<ShopOrder, DriverResult> action) {
        var found = orders.findByNumberForUpdate(number)
                .filter(order -> Objects.equals(order.getDriverId(), driverId));
        if (found.isEmpty()) {
            return new NotYours();
        }
        if (found.get().getStatus() != from) {
            return new WrongStep();
        }
        return action.apply(found.get());
    }
}
