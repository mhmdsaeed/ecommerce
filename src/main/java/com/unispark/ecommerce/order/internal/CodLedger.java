package com.unispark.ecommerce.order.internal;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.order.internal.persistence.CodCollection;
import com.unispark.ecommerce.order.internal.persistence.CodCollectionRepository;
import com.unispark.ecommerce.order.internal.persistence.ShopOrder;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.shared.Money;

/**
 * Records the cash of cash-on-delivery orders (SPEC 6.2): once per order, held by the order's driver (or by the
 * store when it has none), and the payment becomes PAID. Callers hold the order's row lock.
 */
@Component
class CodLedger {

    private final CodCollectionRepository collections;
    private final Clock clock;

    CodLedger(CodCollectionRepository collections, Clock clock) {
        this.collections = collections;
        this.clock = clock;
    }

    /** Does nothing for transfers, or when the cash was already recorded (the driver typed the amount). */
    void collect(ShopOrder order, Money amount) {
        if (order.getPaymentMethod().isTransfer() || collections.existsByOrderId(order.getId())) {
            return;
        }
        collections.save(new CodCollection(order.getId(), order.getDriverId(), amount.amount(), amount.currency(),
                clock.instant()));
        order.setPaymentStatus(PaymentStatus.PAID);
    }

    Optional<Money> collected(ShopOrder order) {
        return collections.findByOrderId(order.getId())
                .map(collection -> Money.of(collection.getAmount(), collection.getCurrency()));
    }
}
