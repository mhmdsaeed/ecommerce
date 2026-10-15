package com.unispark.ecommerce.payment.internal;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods.MethodConfig;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.payment.Payments.Instructions;
import com.unispark.ecommerce.shared.Money;

/**
 * Wallets (Syriatel Cash, Sham Cash, MTN Cash) and bank transfers: the shopper pays into the merchant's account,
 * sends the transaction reference, and the merchant confirms it (M9).
 */
@Component
final class ManualPaymentProvider implements PaymentProvider {

    @Override
    public boolean supports(PaymentMethod method) {
        return method.isTransfer();
    }

    @Override
    public PaymentStatus onCheckout() {
        return PaymentStatus.UNPAID;
    }

    @Override
    public Instructions instructionsFor(MethodConfig config, Money amount) {
        return new Instructions(config.method(), config.displayName(), amount, true, config.accountId(),
                config.accountName(), config.instructions(), config.qrImageKey());
    }
}
