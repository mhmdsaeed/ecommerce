package com.unispark.ecommerce.payment;

/**
 * Ways to pay (SPEC 5, 6.2). Cash on delivery is collected by the driver; the others are transfers the shopper makes
 * to the merchant's account and proves with a transaction reference, which the merchant then checks. Names come
 * from the bundles ({@code payment.method.<NAME>}).
 */
public enum PaymentMethod {
    COD,
    SYRIATEL_CASH,
    SHAM_CASH,
    MTN_CASH,
    BANK_TRANSFER;

    /** Paid by transfer into the merchant's account, so it needs account details and a proof. */
    public boolean isTransfer() {
        return this != COD;
    }
}
