package com.unispark.ecommerce.payment.internal;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods.MethodConfig;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.payment.Payments.Instructions;
import com.unispark.ecommerce.shared.Money;

/**
 * One kind of payment (SPEC 6.3). Takes amounts and settings rather than orders, so the payment module does not
 * depend on orders. Card payments join as another implementation.
 */
sealed interface PaymentProvider permits CodPaymentProvider, ManualPaymentProvider {

    boolean supports(PaymentMethod method);

    /** The payment status of a newly placed order. */
    PaymentStatus onCheckout();

    Instructions instructionsFor(MethodConfig config, Money amount);
}
