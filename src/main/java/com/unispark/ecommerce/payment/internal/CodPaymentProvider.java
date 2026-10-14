package com.unispark.ecommerce.payment.internal;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods.MethodConfig;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.payment.Payments.Instructions;
import com.unispark.ecommerce.shared.Money;

/** Cash on delivery: unpaid until the driver collects (M10). */
@Component
final class CodPaymentProvider implements PaymentProvider {

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.COD;
    }

    @Override
    public PaymentStatus onCheckout() {
        return PaymentStatus.UNPAID;
    }

    @Override
    public Instructions instructionsFor(MethodConfig config, Money amount) {
        return new Instructions(PaymentMethod.COD, config.displayName(), amount, false, null, null,
                config.instructions());
    }
}
