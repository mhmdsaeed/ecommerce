package com.unispark.ecommerce.billing;

/** How a store paid an invoice (SPEC 8): a wallet transfer, a bank transfer or cash in hand. */
public enum InvoicePaymentMethod {
    WALLET,
    BANK,
    CASH
}
