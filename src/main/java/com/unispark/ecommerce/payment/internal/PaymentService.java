package com.unispark.ecommerce.payment.internal;

import java.util.List;

import org.springframework.stereotype.Service;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodConfig;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.payment.Payments;
import com.unispark.ecommerce.shared.Money;

@Service
class PaymentService implements Payments {

    private final List<PaymentProvider> providers;
    private final PaymentMethods methods;

    PaymentService(List<PaymentProvider> providers, PaymentMethods methods) {
        this.providers = providers;
        this.methods = methods;
    }

    @Override
    public PaymentStatus initialStatus(PaymentMethod method) {
        return provider(method).onCheckout();
    }

    @Override
    public Instructions instructions(PaymentMethod method, Money amount) {
        // The account as configured now: an order placed earlier still shows where to pay today.
        var config = methods.all().stream().filter(candidate -> candidate.method() == method).findFirst()
                .orElseGet(() -> new MethodConfig(method, false, null, null, null, null));
        return provider(method).instructionsFor(config, amount);
    }

    private PaymentProvider provider(PaymentMethod method) {
        return providers.stream().filter(provider -> provider.supports(method)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No payment provider for " + method));
    }
}
